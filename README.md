# cameras-data

Data files of the Cameras Android app (map of surveillance cameras in France), rebuilt on the 1st
of each month by a GitHub Actions workflow and published as assets of the
[`data` release](../../releases/tag/data). The app checks `data.json` once a month and downloads
only what changed, so that phones never query the source servers themselves.

| File | Content | Source and licence |
|---|---|---|
| `addresses.db.gz` | SQLite database of every street and house number of France, with a full-text index | [Base Adresse Nationale](https://adresse.data.gouv.fr/), Licence Ouverte 2.0 |
| `cameras.tsv` | Surveillance cameras (`man_made=surveillance`) | © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors, ODbL |
| `radars.csv` | Fixed speed and red-light cameras | [Ministère de l'Intérieur](https://www.data.gouv.fr/datasets/liste-des-radars-fixes-en-france), Licence Ouverte 2.0 |
| `data.json` | Date and count of each file | |

`tools/build_data.py` builds them (plain Python 3, no package needed):

```
CAMERAS_DATA_OUT=dist python tools/build_data.py radars
CAMERAS_DATA_OUT=dist python tools/build_data.py cameras
CAMERAS_DATA_OUT=dist python tools/build_data.py addresses ban-cache
```

The workflow can also be started by hand from the Actions tab.
