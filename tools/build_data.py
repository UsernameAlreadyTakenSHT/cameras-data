"""Builds the data bundled in the app: addresses, fixed speed cameras, surveillance cameras.

Usage (from the project root, plain Python 3, no package needed):
    python tools/build_data.py addresses CACHE_DIR [DEPARTMENT ...]
    python tools/build_data.py radars
    python tools/build_data.py cameras

Outputs go to app/src/main/assets/ (or to the folder named by the CAMERAS_DATA_OUT environment
variable): addresses.db, radars.csv, cameras.tsv, and data.json (dates and counts, shown by the
app and compared by its monthly update). CACHE_DIR keeps the downloaded address files between runs.

The same script runs every month in GitHub Actions (.github/workflows/data.yml), which publishes
the files the app downloads.

Sources:
  - addresses: Base Adresse Nationale, one CSV per département (Licence Ouverte 2.0)
  - radars: "Liste des radars fixes en France", ministère de l'Intérieur (Licence Ouverte 2.0)
  - cameras: OpenStreetMap objects tagged man_made=surveillance, through Overpass (ODbL)
"""
import csv
import datetime
import gzip
import io
import json
import os
import sqlite3
import sys
import time
import urllib.parse
import urllib.request

# Where the files are written: the app's assets, or the folder named by CAMERAS_DATA_OUT.
ASSETS = os.environ.get("CAMERAS_DATA_OUT") or os.path.join("app", "src", "main", "assets")
USER_AGENT = "Cameras-data-build (Android app io.github.usernamealreadytakensht.cameras)"

DEPARTMENTS = (
    [f"{n:02d}" for n in range(1, 96) if n != 20] + ["2A", "2B", "971", "972", "973", "974", "976"]
)
BAN_URL = "https://adresse.data.gouv.fr/data/ban/adresses/latest/csv/adresses-{}.csv.gz"

# OpenStreetMap boundary relation of each region (13 metropolitan, 5 overseas).
OSM_REGIONS = [
    8649, 8640, 3792878, 3793170, 4217435, 3792876, 8650, 102740, 3792880, 3792883, 3792877, 8654, 76910,
    2562137, 2473088, 2502058, 2470060, 1363069,
]
OVERPASS = [
    "https://overpass-api.de/api/interpreter",
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
]

KIND_TOWN, KIND_STREET = 0, 1


def fetch(url, data=None, timeout=300):
    request = urllib.request.Request(url, data=data, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read()


def note(key, value):
    """Records a date or a count in data.json, next to the data it describes."""
    path = os.path.join(ASSETS, "data.json")
    data = json.load(open(path, encoding="utf-8")) if os.path.isfile(path) else {}
    data[key] = value
    json.dump(data, open(path, "w", encoding="utf-8"), indent=2, sort_keys=True)


# --- addresses -------------------------------------------------------------------------------

def varint(value, out):
    while value & ~0x7F:
        out.append(value & 0x7F | 0x80)
        value >>= 7
    out.append(value)


def zigzag(value):
    return (value << 1) ^ (value >> 63)


def encode(numbers, origin_lat, origin_lon):
    """Same packing as AddressCodec.encode in the app: keep the two in step."""
    out = bytearray()
    previous = 0
    for number, suffix, lat, lon in sorted(numbers, key=lambda n: (n[0], n[1])):
        varint(number - previous, out)
        previous = number
        text = suffix.encode("utf-8")[:255]
        out.append(len(text))
        out += text
        varint(zigzag(round((lat - origin_lat) * 100_000)), out)
        varint(zigzag(round((lon - origin_lon) * 100_000)), out)
    return bytes(out)


def build_addresses(cache, departments):
    os.makedirs(cache, exist_ok=True)
    os.makedirs(ASSETS, exist_ok=True)
    target = os.path.join(ASSETS, "addresses.db")
    if os.path.exists(target):
        os.remove(target)
    db = sqlite3.connect(target)
    db.execute("PRAGMA page_size=4096")
    db.execute("PRAGMA journal_mode=OFF")
    db.execute("PRAGMA synchronous=OFF")
    db.execute(
        "CREATE TABLE place(id INTEGER PRIMARY KEY, kind INTEGER NOT NULL, name TEXT NOT NULL, "
        "city TEXT NOT NULL, postcode TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, numbers BLOB)"
    )
    db.execute('CREATE VIRTUAL TABLE place_fts USING fts4(content="", text, tokenize=unicode61)')
    next_id = 1
    addresses = 0
    started = time.time()

    for position, department in enumerate(departments, 1):
        path = os.path.join(cache, f"adresses-{department}.csv.gz")
        if not os.path.isfile(path):
            data = fetch(BAN_URL.format(department))
            with open(path + ".part", "wb") as f:
                f.write(data)
            os.replace(path + ".part", path)
        places, texts = [], []

        def flush(streets):
            nonlocal next_id
            if not streets:
                return
            count = lat_sum = lon_sum = 0
            postcodes = {}
            city = ""
            for (name, city, postcode), numbers in streets.items():
                lat = sum(n[2] for n in numbers) / len(numbers)
                lon = sum(n[3] for n in numbers) / len(numbers)
                places.append((next_id, KIND_STREET, name, city, postcode, round(lat, 6), round(lon, 6),
                               encode(numbers, lat, lon)))
                texts.append((next_id, f"{name} {city} {postcode}"))
                next_id += 1
                count += len(numbers)
                lat_sum += lat * len(numbers)
                lon_sum += lon * len(numbers)
                postcodes[postcode] = postcodes.get(postcode, 0) + len(numbers)
            postcode = max(postcodes, key=postcodes.get)
            places.append((next_id, KIND_TOWN, city, city, postcode, round(lat_sum / count, 6),
                           round(lon_sum / count, 6), None))
            texts.append((next_id, f"{city} {postcode}"))
            next_id += 1

        with gzip.open(path, "rt", encoding="utf-8", newline="") as f:
            reader = csv.reader(f, delimiter=";")
            header = next(reader)
            col = {name: header.index(name) for name in
                   ("numero", "rep", "nom_voie", "code_postal", "code_insee", "nom_commune", "lon", "lat")}
            town = None
            streets = {}
            for row in reader:
                try:
                    lat = float(row[col["lat"]])
                    lon = float(row[col["lon"]])
                except (ValueError, IndexError):
                    continue
                name = row[col["nom_voie"]]
                if not name:
                    continue
                if row[col["code_insee"]] != town:
                    flush(streets)
                    streets = {}
                    town = row[col["code_insee"]]
                number = row[col["numero"]]
                # Streets of one town sharing a name but not a postcode stay apart.
                key = (name, row[col["nom_commune"]], row[col["code_postal"]])
                streets.setdefault(key, []).append((int(number) if number.isdigit() else 0, row[col["rep"]], lat, lon))
                addresses += 1
            flush(streets)
        db.executemany("INSERT INTO place VALUES(?,?,?,?,?,?,?,?)", places)
        db.executemany("INSERT INTO place_fts(docid, text) VALUES(?,?)", texts)
        db.commit()
        print(f"{position}/{len(departments)} département {department}: {addresses} addresses so far, "
              f"{time.time() - started:.0f}s", flush=True)

    db.execute("INSERT INTO place_fts(place_fts) VALUES('optimize')")
    db.commit()
    db.execute("VACUUM")
    db.close()
    note("addresses", {"date": datetime.date.today().isoformat(), "count": addresses, "places": next_id - 1})
    print(f"addresses.db: {os.path.getsize(target) / 1e6:.1f} MB, {addresses} addresses, {next_id - 1} places")


# --- fixed speed cameras ---------------------------------------------------------------------

def build_radars():
    api = "https://www.data.gouv.fr/api/1/datasets/liste-des-radars-fixes-en-france/"
    resources = json.loads(fetch(api))["resources"]
    newest = max((r for r in resources if (r.get("format") or "").lower() == "csv"), key=lambda r: r["last_modified"])
    data = fetch(newest["url"])
    # Announced as UTF-8, written in Windows-1252: stored as real UTF-8 in the app.
    try:
        text = data.decode("utf-8-sig")
    except UnicodeDecodeError:
        text = data.decode("cp1252")
    lines = [line for line in text.splitlines() if line.strip()]
    os.makedirs(ASSETS, exist_ok=True)
    with open(os.path.join(ASSETS, "radars.csv"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    # The app refuses a list this small: better to fail here than to publish it.
    if len(lines) - 1 < 100:
        raise SystemExit(f"only {len(lines) - 1} radars: the source file changed")
    note("radars", {"date": newest["last_modified"][:10], "count": len(lines) - 1})
    print(f"radars.csv: {len(lines) - 1} cameras, file of {newest['last_modified'][:10]}")


# --- surveillance cameras --------------------------------------------------------------------

def kind(tags):
    """Same rule as CameraData.kind in the app."""
    surveillance = tags.get("surveillance:type", "").lower()
    if surveillance == "guard":
        return "guard"
    if surveillance == "alpr":
        return "alpr"
    camera = tags.get("camera:type", "").lower()
    return camera if camera in ("fixed", "panning", "dome") else "unknown"


def overpass(query):
    body = urllib.parse.urlencode({"data": query}).encode()
    failure = None
    for attempt in range(4):
        for server in OVERPASS:
            try:
                data = json.loads(fetch(server, body, timeout=400))
                # A remark means the server stopped early (timeout, memory): the answer may lack
                # part of the region, so it is never kept.
                if data.get("remark"):
                    raise IOError(data["remark"])
                return data
            except Exception as error:  # busy server, timeout, truncated answer: try the next one
                failure = error
                print(f"  {server}: {error}", flush=True)
        time.sleep(20 * (attempt + 1))
    raise SystemExit(f"Overpass unavailable: {failure}")


def build_cameras():
    clean = str.maketrans("\t\n\r", "   ")
    seen = {}
    cache = os.environ.get("CAMERAS_DATA_CACHE") or ".data-cache"
    os.makedirs(cache, exist_ok=True)
    for position, relation in enumerate(OSM_REGIONS, 1):
        # Each region fetched is kept for a day: the public servers are often busy, and a run that
        # stops half-way carries on from there.
        kept = os.path.join(cache, f"cameras-{relation}.json")
        if os.path.isfile(kept) and time.time() - os.path.getmtime(kept) < 24 * 3600:
            elements = json.load(open(kept, encoding="utf-8"))
        else:
            query = f'[out:json][timeout:300];nwr["man_made"="surveillance"](area:{3_600_000_000 + relation});out center tags;'
            elements = overpass(query)["elements"]
            json.dump(elements, open(kept, "w", encoding="utf-8"))
            time.sleep(5)
        for element in elements:
            where = element if "lat" in element else element.get("center")
            if not where:
                continue
            tags = element.get("tags", {})
            seen[element["type"][0] + str(element["id"])] = (
                where["lat"], where["lon"], kind(tags), tags.get("surveillance", ""),
                tags.get("camera:direction") or tags.get("direction", ""), tags.get("operator", ""),
            )
        print(f"{position}/{len(OSM_REGIONS)} region {relation}: {len(elements)} objects, {len(seen)} in all", flush=True)
    if len(seen) < 1000:
        raise SystemExit(f"only {len(seen)} cameras: Overpass answers are incomplete")
    os.makedirs(ASSETS, exist_ok=True)
    with open(os.path.join(ASSETS, "cameras.tsv"), "w", encoding="utf-8", newline="\n") as f:
        for key, (lat, lon, k, zone, direction, operator) in seen.items():
            f.write("\t".join([key, repr(lat), repr(lon), k] + [v.translate(clean) for v in (zone, direction, operator)]) + "\n")
    note("cameras", {"date": datetime.date.today().isoformat(), "count": len(seen)})
    print(f"cameras.tsv: {len(seen)} cameras")


if __name__ == "__main__":
    command = sys.argv[1] if len(sys.argv) > 1 else ""
    if command == "addresses" and len(sys.argv) > 2:
        build_addresses(sys.argv[2], sys.argv[3:] or DEPARTMENTS)
    elif command == "radars":
        build_radars()
    elif command == "cameras":
        build_cameras()
    else:
        sys.exit(__doc__)
