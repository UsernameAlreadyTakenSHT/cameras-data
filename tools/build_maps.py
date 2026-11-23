"""Builds the offline map of the whole of France as one PMTiles file per base map.

Usage: python tools/build_maps.py plan|photos AREA OUT.pmtiles
Needs the shapely and pmtiles packages.

AREA is "FR", the whole country (metropolitan France and the overseas regions) from zoom 0 to
13, or a region code of app/src/main/assets/regions.geojson ("53"), from zoom 0 to 14: a region
file shows on its own at every scale, down to the streets. The tiles come from the IGN Géoplateforme (WMTS,
Licence Ouverte 2.0): the app downloads these files instead of every phone asking IGN for tiles.
"""
import concurrent.futures
import json
import math
import sys
import time
import urllib.error
import urllib.request

from pmtiles.tile import Compression, TileType, zxy_to_tileid
from pmtiles.writer import Writer
from shapely.geometry import box, shape
from shapely.ops import unary_union
from shapely.prepared import prep

LAYERS = {
    "plan": ("GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2", "image/png", TileType.PNG, "Plan IGN"),
    "photos": ("ORTHOIMAGERY.ORTHOPHOTOS", "image/jpeg", TileType.JPEG, "Photographies aériennes"),
}
REGIONS = "app/src/main/assets/regions.geojson"
USER_AGENT = "Cameras-map-build (Android app io.github.usernamealreadytakensht.cameras)"
WORKERS = 8
COUNTRY_MAX_ZOOM = 13
REGION_ZOOM = 14
# Share of the tiles that must exist: the areas cover land, where IGN has tiles nearly everywhere.
MIN_SHARE = 0.5


def tx(lon, z):
    return min((1 << z) - 1, max(0, int(math.floor((lon + 180.0) / 360.0 * (1 << z)))))


def ty(lat, z):
    r = math.radians(max(-85.05112878, min(85.05112878, lat)))
    return min((1 << z) - 1, max(0, int(math.floor((1.0 - math.log(math.tan(r) + 1.0 / math.cos(r)) / math.pi) / 2.0 * (1 << z)))))


def lon_of(x, z):
    return x / (1 << z) * 360.0 - 180.0


def lat_of(y, z):
    return math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * y / (1 << z)))))


def tiles_over(geom, max_zoom):
    """Every z/x/y tile intersecting geom, zoom 0 to max_zoom: quadtree descent from the zoom above."""
    p = prep(geom)
    w, s, e, n = geom.bounds
    current = None
    for z in range(0, max_zoom + 1):
        if current is None:
            cand = [(x, y) for x in range(tx(w, z), tx(e, z) + 1) for y in range(ty(n, z), ty(s, z) + 1)]
        else:
            cand = [(2 * x + dx, 2 * y + dy) for (x, y) in current for dx in (0, 1) for dy in (0, 1)]
        current = [(x, y) for (x, y) in cand
                   if p.intersects(box(lon_of(x, z), lat_of(y + 1, z), lon_of(x + 1, z), lat_of(y, z)))]
        for x, y in current:
            yield z, x, y


def fetch(layer, fmt, z, x, y):
    """The tile's bytes, or None where IGN has no tile. Retries what fails for other reasons."""
    url = (f"https://data.geopf.fr/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER={layer}"
           f"&STYLE=normal&FORMAT={fmt}&TILEMATRIXSET=PM&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}")
    for attempt in range(6):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(request, timeout=60) as response:
                # An error page sent with status 200 is not a tile.
                if not response.headers.get("Content-Type", "").startswith("image/"):
                    raise SystemExit(f"{z}/{x}/{y}: not an image ({response.headers.get('Content-Type')})")
                return response.read()
        except urllib.error.HTTPError as error:
            if error.code in (400, 404):
                return None
            failure = error
        except Exception as error:  # timeout, reset connection
            failure = error
        time.sleep(2 * (attempt + 1))
    raise SystemExit(f"{z}/{x}/{y}: {failure}")


def main():
    name, area, out = sys.argv[1], sys.argv[2], sys.argv[3]
    min_zoom, max_zoom = 0, COUNTRY_MAX_ZOOM if area == "FR" else REGION_ZOOM
    layer, fmt, tile_type, title = LAYERS[name]

    features = json.load(open(REGIONS, encoding="utf-8"))["features"]
    # The whole country is metropolitan France ("FR", the union of its regions) and the overseas regions.
    overseas = {"01", "02", "03", "04", "06"}
    wanted = {"FR"} | overseas if area == "FR" else {area}
    if not any(f["properties"]["code"] in wanted for f in features):
        raise SystemExit(f"unknown area {area}")
    geom = unary_union([shape(f["geometry"]) for f in features if f["properties"]["code"] in wanted])

    tiles = sorted({t for t in tiles_over(geom, max_zoom) if t[0] >= min_zoom}, key=lambda t: zxy_to_tileid(*t))
    print(f"{name}-{area}: {len(tiles)} tiles, zoom {min_zoom}-{max_zoom}", flush=True)

    started = time.time()
    written = size = 0
    with open(out, "wb") as f:
        writer = Writer(f)
        with concurrent.futures.ThreadPoolExecutor(WORKERS) as pool:
            # Fetched in parallel, written in tile id order: the file is clustered.
            for start in range(0, len(tiles), 2000):
                chunk = tiles[start:start + 2000]
                for (z, x, y), data in zip(chunk, pool.map(lambda t: fetch(layer, fmt, *t), chunk)):
                    if data:
                        writer.write_tile(zxy_to_tileid(z, x, y), data)
                        written += 1
                        size += len(data)
                print(f"  {start + len(chunk)}/{len(tiles)} tiles, {size / 1e6:.0f} MB, "
                      f"{time.time() - started:.0f}s", flush=True)
        # Almost nothing received means the service changed (a renamed layer answers 400 to
        # everything): failing keeps last month's file instead of publishing a blank map.
        if written < len(tiles) * MIN_SHARE:
            raise SystemExit(f"only {written} of {len(tiles)} tiles received")
        w, s, e, n = geom.bounds
        writer.finalize(
            {
                "tile_type": tile_type,
                "tile_compression": Compression.NONE,
                "min_lon_e7": int(w * 1e7), "min_lat_e7": int(s * 1e7),
                "max_lon_e7": int(e * 1e7), "max_lat_e7": int(n * 1e7),
                "center_zoom": min_zoom, "center_lon_e7": int((w + e) / 2 * 1e7),
                "center_lat_e7": int((s + n) / 2 * 1e7),
            },
            {"name": title, "attribution": "© IGN", "format": "png" if tile_type == TileType.PNG else "jpg"},
        )
    print(f"{out}: {written} tiles, {size / 1e6:.0f} MB of tiles", flush=True)


if __name__ == "__main__":
    main()
