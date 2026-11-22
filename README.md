# Caméras

Appli Android (Kotlin, Jetpack Compose) de cartographie des caméras de surveillance en France :
fonds de carte de l'IGN, caméras recensées par OpenStreetMap, radars fixes, recherche d'adresse,
photos de rue en option. Elle fonctionne aussi sans connexion.

## Fonctions

- Carte plein écran (déplacement, zoom), position et réglages mémorisés entre deux lancements.
- Bouton « calques » :
  - fond de carte : Plan IGN ou Photographies aériennes ;
  - calques : caméras de surveillance, radars fixes ;
  - photos de rue Panoramax et Mapillary, décochées par défaut ;
  - « Vider le cache ».
- Barre de recherche : adresse, lieu, coordonnées ou geohash ; le résultat est marqué d'un repère.
- Bouton « Hors-ligne » : téléchargement du fond affiché sur la France métropolitaine ou sur une
  région (13 de métropole, 5 d'outre-mer), avec estimation, progression, arrêt et reprise.

## Compiler

```
gradlew.bat :app:assembleDebug
```

L'APK est produit dans `app/build/outputs/apk/debug/` (environ 290 Mo, dont la base d'adresses).
Les tests unitaires se lancent avec `gradlew.bat :app:testDebugUnitTest`.

Les données intégrées (`app/src/main/assets/`) ne sont pas dans le dépôt de code si elles sont
trop lourdes pour lui : elles se refabriquent avec `tools/build_data.py` (voir plus bas).

## En ligne, hors-ligne, mises à jour

Le principe : tout ce qui est nécessaire hors connexion est fourni avec l'appli ou téléchargé une
fois ; quand il y a du réseau, l'appli préfère les données en ligne, plus récentes.

| Donnée | Hors connexion | Avec connexion | Renouvellement |
|---|---|---|---|
| Fonds de carte | zones téléchargées par l'utilisateur, plus le cache des zones parcourues | tuiles de l'IGN | revérification mensuelle des zones téléchargées, en Wi-Fi |
| Adresses | base nationale intégrée à l'appli | géocodage de l'IGN | mensuel, en Wi-Fi (environ 190 Mo) |
| Caméras | France entière intégrée à l'appli | la zone affichée est relue dans OpenStreetMap | mensuel |
| Radars fixes | liste intégrée à l'appli | — | mensuel |
| Photos de rue | celles déjà vues (cache) | Panoramax, Mapillary | — |

### Mise à jour mensuelle

Les fichiers de données sont refabriqués le 1er de chaque mois par une tâche GitHub Actions du
dépôt [cameras-data](https://github.com/UsernameAlreadyTakenSHT/cameras-data), qui les publie
avec un petit fichier `data.json` donnant la date de chacun. Au lancement, au plus une fois par
mois, l'appli lit ce fichier et ne télécharge que ce qui a changé (`DataFiles.kt`). Les téléphones
n'interrogent donc jamais eux-mêmes les serveurs sources pour des jeux de données entiers. La base
d'adresses et la revérification des cartes attendent une connexion non limitée (Wi-Fi).

`tools/build_data.py` fabrique ces fichiers, pour l'appli comme pour le dépôt de données (les deux
copies du script doivent rester identiques) :

```
python tools/build_data.py radars
python tools/build_data.py cameras
python tools/build_data.py addresses DOSSIER_DE_CACHE
```

### Cache

Les réponses en ligne sont gardées sur le téléphone (300 Mo au plus, `Search.kt`) : photos de rue
90 jours, recherches d'adresses 7 jours, recherches de photos 1 jour. Une réponse en cache est
réaffichée sans requête, y compris sans connexion. « Vider le cache » l'efface.

## Fonds de carte

Les tuiles viennent de la Géoplateforme de l'IGN (`data.geopf.fr`, service WMTS), sans clé ni
compte. Les deux fonds sont définis dans `MapLayer.kt` :

| Fond | Couche WMTS | Zooms |
|---|---|---|
| Plan IGN | `GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2` | 0 à 19 |
| Photographies aériennes | `ORTHOIMAGERY.ORTHOPHOTOS` | 0 à 19 |

- Plan IGN est sous [Licence Ouverte 2.0](https://www.data.gouv.fr/datasets/plan-ign), qui permet
  la réutilisation et la copie locale avec mention de la source (affichée en bas de carte).
- Les conditions de la Géoplateforme indiquent que le service WMTS n'est pas soumis à une limite
  d'usage ; elles ne mentionnent ni n'interdisent le téléchargement pour un usage hors-ligne.
- Les Cartes IGN et la carte au 1/25 000 (SCAN 25) ne sont pas proposées : données à accès
  restreint, servies seulement avec une clé personnelle.
- Le serveur `tile.openstreetmap.org` a été écarté : sa
  [politique](https://operations.osmfoundation.org/policies/tiles/) interdit l'usage hors-ligne.

### Téléchargement par zone

Une zone est téléchargée entière, du zoom 1 jusqu'au zoom le plus fin qui tient dans
70 000 tuiles (`MAX_TILES_PER_DOWNLOAD` dans `DownloadPlan.kt`) : plus la zone est grande, moins
le détail est fin.

| Zone | Zoom maximal | Précision |
|---|---|---|
| France métropolitaine | 13 | environ 13 m par pixel |
| grandes régions (Auvergne-Rhône-Alpes, Nouvelle-Aquitaine, Occitanie…) | 14 | 6,5 m |
| Bretagne, Île-de-France, Normandie, Pays de la Loire, Provence-Alpes-Côte d'Azur | 15 | 3,3 m |
| Corse | 16 | 1,6 m |
| Guadeloupe, Martinique, La Réunion, Mayotte | 17 | 0,8 m |

`app/src/main/assets/regions.geojson` contient le contour de chaque zone et son nombre exact de
tuiles par zoom ; il est produit par `tools/build_regions.py` à partir d'ADMIN EXPRESS (IGN).
Mesure : Mayotte en Plan IGN fait 10 055 tuiles et 198 Mo, à environ 25 tuiles par seconde.

## Recherche

Ce qui est tapé est interprété dans cet ordre (`SearchBar.kt`) :

1. **Coordonnées** (`Coordinates.kt`), sans réseau : `48.8566, 2.3522`, `48,8566 2,3522`,
   `48.8566N 2.3522E`, `48°51'24"N 2°21'08"E`… La latitude vient en premier, sauf si les lettres
   N S E W (ou O pour ouest) disent le contraire.
2. **Geohash** (`u09tunqu`), sans réseau : 5 à 12 caractères, au moins une lettre, et soit un
   chiffre soit 8 caractères, pour ne pas prendre « brest » ou un code postal pour un geohash.
3. **Adresse ou lieu, en ligne** : géocodage de la Géoplateforme IGN, sans clé.
4. **Adresse, hors connexion** : la base intégrée, si le service ne répond pas.

La base d'adresses (`addresses.db`, 358 Mo) vient de la
[Base Adresse Nationale](https://adresse.data.gouv.fr/) (Licence Ouverte 2.0) : 26 millions
d'adresses, une ligne par rue avec ses numéros compactés (`AddressCodec.kt`), une ligne par
commune, et un index plein texte. SQLite ne peut pas la lire dans l'APK : elle est copiée dans le
stockage de l'appli au premier lancement (une quinzaine de secondes).

## Caméras de surveillance

- Objets `man_made=surveillance` d'[OpenStreetMap](https://www.openstreetmap.org/copyright)
  (licence ODbL), les mêmes données que
  [Surveillance under Surveillance](https://sunders.uber.space/). La mention « © contributeurs
  OpenStreetMap » s'affiche en bas de carte tant que le calque est visible.
- Une couleur par type : fixe, orientable, dôme, gardien, lecture de plaques, non précisé. En
  dessous du niveau rue, les caméras sont regroupées en grappes ; toucher une grappe zoome dessus,
  toucher un point affiche sa fiche (`Overlays.kt`).
- Avec une connexion et au niveau du quartier, la zone affichée est relue dans OpenStreetMap par
  les serveurs publics Overpass et remplace les caméras intégrées à cet endroit (`Cameras.kt`).
  Ce sont de petites requêtes, une par zone et par session.
- La couverture dépend des bénévoles : dense dans certaines villes, presque vide ailleurs.

## Radars fixes

- [Liste des radars fixes](https://www.data.gouv.fr/datasets/liste-des-radars-fixes-en-france)
  du ministère de l'Intérieur (Licence Ouverte 2.0, environ 3 300 radars).
- Toucher un point affiche le type du radar, la vitesse limite, la date de mise en service et son
  numéro. L'appli n'émet aucune alerte à l'approche d'un radar.

## Photos de rue

Deux services, désactivés par défaut : tant qu'une case n'est pas cochée, aucune requête ne part
vers le service. Une fois activé, un appui long sur la carte affiche la photo prise le plus près,
à 50 m au plus, avec sa date, son auteur et sa licence (`StreetPhotos.kt`).

- **[Panoramax](https://panoramax.fr/)** : commun numérique français, sans clé, photos sous
  licence ouverte.
- **[Mapillary](https://www.mapillary.com/)** : ne répond qu'avec le jeton d'accès d'un compte
  développeur, que l'utilisateur colle dans l'appli ; photos sous CC BY-SA 4.0. Écrit d'après la
  documentation, jamais essayé faute de jeton.

## Technique

- Rendu et stockage des cartes hors-ligne : [MapLibre Native](https://maplibre.org/) (libre, sans
  clé d'API).
- Le téléchargement hors-ligne de MapLibre ne lit le style de carte que par HTTP : `CamerasApp.kt`
  sert le style de chaque fond à une adresse fictive (`style.cameras.invalid`) par un intercepteur
  OkHttp, sans accès réseau.
