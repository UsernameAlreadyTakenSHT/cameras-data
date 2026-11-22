package io.github.usernamealreadytakensht.cameras

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.maplibre.geojson.Geometry
import org.maplibre.geojson.MultiPolygon
import org.maplibre.geojson.Polygon

/**
 * An area that can be downloaded whole: metropolitan France or one region.
 *
 * @param tilesPerZoom number of tiles covering [geometry] at each zoom, indexed by zoom.
 */
class Region(
    val code: String,
    val name: String,
    val geometry: Geometry,
    private val tilesPerZoom: IntArray,
) {
}

object Regions {
    /**
     * Reads `assets/regions.geojson`: outlines from IGN ADMIN EXPRESS, simplified and slightly
     * enlarged, with their tile counts (see `tools/build_regions.py`).
     */
    suspend fun load(context: Context): List<Region> = withContext(Dispatchers.IO) {
        val text = context.assets.open("regions.geojson").use { it.readBytes().toString(Charsets.UTF_8) }
        val features = JSONObject(text).getJSONArray("features")
        List(features.length()) { index ->
            val feature = features.getJSONObject(index)
            val properties = feature.getJSONObject("properties")
            val geometry = feature.getJSONObject("geometry")
            val tiles = properties.getJSONArray("tiles")
            Region(
                code = properties.getString("code"),
                name = properties.getString("name"),
                geometry = if (geometry.getString("type") == "MultiPolygon") {
                    MultiPolygon.fromJson(geometry.toString())
                } else {
                    Polygon.fromJson(geometry.toString())
                },
                tilesPerZoom = IntArray(tiles.length()) { tiles.getInt(it) },
            )
        }
    }
}
