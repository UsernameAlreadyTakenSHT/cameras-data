"""Builds the app's regions asset from IGN ADMIN EXPRESS region outlines.

Usage: python build_regions.py regions_raw.geojson out.geojson   (needs the shapely package)

regions_raw.geojson is the "region" layer of ADMIN EXPRESS, fetched from the Géoplateforme WFS:
https://data.geopf.fr/wfs/ows?SERVICE=WFS&VERSION=2.0.0&REQUEST=GetFeature&TYPENAMES=ADMINEXPRESS-COG-CARTO.LATEST:region&OUTPUTFORMAT=application/json&SRSNAME=EPSG:4326
Each output feature carries the exact number of z/x/y tiles intersecting it, per zoom.
"""
import json, math, sys, unicodedata
from shapely.geometry import shape, mapping, box
from shapely.ops import unary_union
from shapely.prepared import prep

SRC, OUT = sys.argv[1], sys.argv[2]
METRO = {"11", "24", "27", "28", "32", "44", "52", "53", "75", "76", "84", "93", "94"}
MAX_ZOOM = 17
STOP_AFTER = 400_000  # no need for exact counts far beyond any download budget

def tx(lon, z): return int(math.floor((lon + 180.0) / 360.0 * (1 << z)))
def ty(lat, z):
    r = math.radians(lat)
    return int(math.floor((1.0 - math.log(math.tan(r) + 1.0 / math.cos(r)) / math.pi) / 2.0 * (1 << z)))
def lon_of(x, z): return x / (1 << z) * 360.0 - 180.0
def lat_of(y, z): return math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * y / (1 << z)))))

def tile_counts(geom):
    """Tiles intersecting geom for zooms 0..MAX_ZOOM; quadtree descent from the tiles of the zoom above."""
    p = prep(geom)
    w, s, e, n = geom.bounds
    counts = []
    current = None
    for z in range(0, MAX_ZOOM + 1):
        if current is None:
            cand = [(x, y) for x in range(tx(w, z), tx(e, z) + 1) for y in range(ty(n, z), ty(s, z) + 1)]
        else:
            cand = [(2 * x + dx, 2 * y + dy) for (x, y) in current for dx in (0, 1) for dy in (0, 1)]
        current = [(x, y) for (x, y) in cand
                   if p.intersects(box(lon_of(x, z), lat_of(y + 1, z), lon_of(x + 1, z), lat_of(y, z)))]
        counts.append(len(current))
        if len(current) > STOP_AFTER:
            break
    return counts

def tidy(geom):
    g = geom.buffer(0).simplify(0.003).buffer(0.01).simplify(0.003)
    return g

def rounded(geom):
    m = mapping(geom)
    def r(c):
        if isinstance(c[0], (int, float)): return [round(c[0], 4), round(c[1], 4)]
        return [r(x) for x in c]
    return {"type": m["type"], "coordinates": r(m["coordinates"])}

raw = json.load(open(SRC, encoding="utf-8"))
feats = []
metro_parts = []
for f in raw["features"]:
    code = f["properties"]["code_insee"]
    g = tidy(shape(f["geometry"]))
    if code in METRO: metro_parts.append(g)
    feats.append((code, f["properties"]["nom_officiel"], g))

def plain(name):
    """Sort key ignoring accents, so that Île-de-France comes after Hauts-de-France."""
    return unicodedata.normalize("NFD", name).encode("ascii", "ignore").decode().lower()

feats.sort(key=lambda t: (t[0] not in METRO, plain(t[1])))
out = [("FR", "France métropolitaine", unary_union(metro_parts).simplify(0.003))] + feats
features = []
for code, name, g in out:
    c = tile_counts(g)
    pts = sum(len(r) for poly in (rounded(g)["coordinates"] if g.geom_type == "MultiPolygon" else [rounded(g)["coordinates"]]) for r in poly)
    cum = 0; row = []
    for z, n in enumerate(c):
        cum += n; row.append(cum)
    print(f"{code:>2} {name:28s} {g.geom_type:12s} pts={pts:5d} cumulative tiles z12..17:", row[12:18])
    features.append({"type": "Feature", "properties": {"code": code, "name": name, "tiles": c}, "geometry": rounded(g)})
json.dump({"type": "FeatureCollection", "features": features}, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
