# Caméras

Appli Android de cartographie des caméras de surveillance en France : fonds de carte de l'IGN,
caméras recensées par OpenStreetMap, radars fixes, recherche d'adresse, photos de rue en option.
Elle fonctionne aussi sans connexion.

Télécharger l'APK : [Releases](../../releases).

## Fonctions

- **Fonds de carte** : Plan IGN ou photographies aériennes, téléchargeables par France entière ou
  par région pour le hors-ligne.
- **Caméras de surveillance** : une couleur par type (fixe, orientable, dôme, gardien, lecture de
  plaques), regroupées en grappes aux petits zooms ; toucher un point affiche sa fiche.
- **Radars fixes** : type, vitesse limite, date de mise en service. Aucune alerte en conduite.
- **Recherche** : adresse ou lieu, coordonnées (`48.8566, 2.3522`, `48°51'24"N 2°21'08"E`…) ou
  geohash (`u09tunqu`).
- **Photos de rue** Panoramax et Mapillary, désactivées par défaut : aucune requête tant qu'une
  case n'est pas cochée. Ensuite, un appui long sur la carte affiche la photo la plus proche.
  Mapillary demande le jeton d'accès d'un compte développeur.

## En ligne et hors-ligne

Ce qui sert hors connexion est fourni avec l'appli ; avec du réseau, l'appli préfère les données
en ligne, plus récentes.

| Donnée | Hors connexion | Avec connexion |
|---|---|---|
| Fonds de carte | zones téléchargées et zones déjà parcourues | tuiles de l'IGN |
| Adresses | base nationale intégrée (26 millions d'adresses) | géocodage de l'IGN |
| Caméras | fichier intégré | la zone affichée est relue dans OpenStreetMap |
| Radars | liste intégrée | — |
| Photos de rue | celles déjà vues (cache) | Panoramax, Mapillary |

**Mise à jour mensuelle.** Le 1er de chaque mois, une tâche GitHub Actions
(`.github/workflows/data.yml`) refabrique les adresses, les caméras et les radars et les publie
dans la release `data`. Au lancement, au plus une fois par mois, l'appli ne télécharge que ce qui
a changé ; la base d'adresses (environ 190 Mo) et la revérification des cartes téléchargées
attendent le Wi-Fi. Les téléphones n'interrogent donc jamais les serveurs sources pour des jeux de
données entiers.

**Cache.** Photos et recherches en ligne sont gardées sur le téléphone (300 Mo au plus) et
réaffichées sans requête ; « Vider le cache » se trouve dans le menu des calques.

## Compiler

```
python tools/build_data.py addresses DOSSIER_DE_CACHE
gradlew.bat :app:assembleDebug
```

La première commande fabrique la base d'adresses (358 Mo, trop lourde pour le dépôt) dans
`app/src/main/assets/` ; `radars` et `cameras` refabriquent de même les deux autres fichiers.
L'APK sort dans `app/build/outputs/apk/debug/` (environ 290 Mo). Tests :
`gradlew.bat :app:testDebugUnitTest`.

## Sources et licences des données

| Donnée | Source | Licence |
|---|---|---|
| Fonds de carte | [Géoplateforme IGN](https://cartes.gouv.fr/) | Licence Ouverte 2.0 |
| Adresses | [Base Adresse Nationale](https://adresse.data.gouv.fr/) | Licence Ouverte 2.0 |
| Caméras | © contributeurs [OpenStreetMap](https://www.openstreetmap.org/copyright) | ODbL |
| Radars | [Ministère de l'Intérieur](https://www.data.gouv.fr/datasets/liste-des-radars-fixes-en-france) | Licence Ouverte 2.0 |
| Photos de rue | [Panoramax](https://panoramax.fr/), [Mapillary](https://www.mapillary.com/) | ouverte, CC BY-SA 4.0 |

La carte est affichée par [MapLibre Native](https://maplibre.org/), sans clé d'API.
