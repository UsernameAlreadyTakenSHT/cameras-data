package io.github.usernamealreadytakensht.cameras

import androidx.annotation.StringRes
import java.io.File

/** Host of the generated map styles: never resolved, see [LocalStyleInterceptor]. */
const val STYLE_HOST = "style.cameras.invalid"

/**
 * The base maps: raster tiles served without a key by the IGN Géoplateforme (WMTS, Web Mercator
 * pyramid), both under the open licence (Licence Ouverte 2.0).
 *
 * @param maxTileZoom deepest zoom the server holds; the map overzooms beyond it.
 */
enum class MapLayer(
    val id: String,
    @StringRes val fullName: Int,
    val infoUrl: String,
    private val wmtsLayer: String,
    private val format: String,
    val maxTileZoom: Int,
) {
    PLAN(
        id = "plan",
        fullName = R.string.layer_plan_full,
        infoUrl = "https://www.data.gouv.fr/datasets/plan-ign",
        wmtsLayer = "GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2",
        format = "image/png",
        maxTileZoom = 19,
    ),
    PHOTOS(
        id = "photos",
        fullName = R.string.layer_photos_full,
        infoUrl = "https://cartes.gouv.fr/cgu/",
        wmtsLayer = "ORTHOIMAGERY.ORTHOPHOTOS",
        format = "image/jpeg",
        maxTileZoom = 19,
    );

    /**
     * Address of this layer's style on the map. [version] changes whenever a map file of the
     * layer is downloaded or deleted, so that the map loads the style again.
     */
    fun styleUrl(version: Int = 0): String = "https://$STYLE_HOST/$id.json?v=$version"

    /** Address of the style the region downloads use: the online tiles alone. */
    val downloadStyleUrl: String
        get() = "https://$STYLE_HOST/$id-online.json"

    private val tileUrl: String
        get() = "https://data.geopf.fr/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=$wmtsLayer" +
            "&STYLE=normal&FORMAT=$format&TILEMATRIXSET=PM&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}"

    /**
     * MapLibre style showing this layer. [local], the map files downloaded (the country first, then
     * regions), are drawn under the online tiles: they fill in wherever those do not load.
     */
    fun styleJson(local: List<File> = emptyList()): String {
        val localSources = local.withIndex().joinToString("") { (i, file) ->
            // Forward slashes whatever the system: a backslash would be an escape in the JSON.
            val path = file.absolutePath.replace('\\', '/')
            """
            "local$i": { "type": "raster", "url": "pmtiles://file://$path", "tileSize": 256 },"""
        }
        val localLayers = local.indices.joinToString("") { i ->
            """{ "id": "local$i", "type": "raster", "source": "local$i" },"""
        }
        return """
        {
          "version": 8,
          "name": "$id",
          "glyphs": "https://$STYLE_HOST/fonts/{fontstack}/{range}.pbf",
          "sources": {$localSources
            "base": {
              "type": "raster",
              "tiles": ["$tileUrl"],
              "tileSize": 256,
              "minzoom": 0,
              "maxzoom": $maxTileZoom,
              "attribution": "© IGN"
            }
          },
          "layers": [
            { "id": "background", "type": "background", "paint": { "background-color": "#dde3e8" } },
            $localLayers
            { "id": "base", "type": "raster", "source": "base" }
          ]
        }
        """.trimIndent()
    }

    /**
     * Typical size of one tile at [zoom], for the estimates shown before downloading. Measured on
     * a sample of tiles across France.
     */
    fun averageTileBytes(zoom: Int): Long = when (this) {
        PLAN -> when {
            zoom <= 11 -> 60_000L
            zoom == 12 -> 55_000L
            zoom == 13 -> 48_000L
            zoom == 14 -> 38_000L
            zoom == 15 -> 29_000L
            zoom == 16 -> 20_000L
            // Built-up areas weigh far more than fields here: a whole island measured 20 kB a tile.
            else -> 16_000L
        }
        PHOTOS -> when {
            zoom <= 11 -> 20_000L
            zoom <= 13 -> 14_000L
            else -> 11_000L
        }
    }

    companion object {
        fun byId(id: String?): MapLayer? = entries.firstOrNull { it.id == id }
    }
}
