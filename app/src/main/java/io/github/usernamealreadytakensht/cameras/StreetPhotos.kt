package io.github.usernamealreadytakensht.cameras

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.StringRes
import java.io.IOException
import java.util.Locale
import kotlin.math.cos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject

/**
 * A street photo of Panoramax, the French open commons of street-level imagery.
 *
 * @param date day the photo was taken, "2025-10-10", or empty.
 * @param license short name of the licence, such as "etalab-2.0".
 * @param pageUrl page of the photo on the Panoramax site.
 */
data class StreetPhoto(
    val imageUrl: String,
    val lat: Double,
    val lon: Double,
    val date: String,
    val author: String,
    val license: String,
    val pageUrl: String,
)

/**
 * Other services where a place can be looked at. The app sends them nothing: each is a plain
 * link, opened in the browser or in the service's own app.
 */
enum class ExternalMap(@StringRes val label: Int) {
    MAPILLARY(R.string.see_in_mapillary),
    GOOGLE_STREET_VIEW(R.string.see_in_street_view),
    GOOGLE_MAPS(R.string.see_in_google_maps),
    APPLE_MAPS(R.string.see_in_apple_maps);

    fun url(point: GeoPoint): String {
        val lat = String.format(Locale.ROOT, "%.6f", point.lat)
        val lon = String.format(Locale.ROOT, "%.6f", point.lon)
        return when (this) {
            MAPILLARY -> "https://www.mapillary.com/app/?lat=$lat&lng=$lon&z=17"
            GOOGLE_STREET_VIEW -> "https://www.google.com/maps/@?api=1&map_action=pano&viewpoint=$lat,$lon"
            GOOGLE_MAPS -> "https://www.google.com/maps/search/?api=1&query=$lat,$lon"
            APPLE_MAPS -> "https://maps.apple.com/?ll=$lat,$lon&q=$lat,$lon"
        }
    }
}

/** Panoramax photos: off until the user turns them on, and no request before that. */
object StreetPhotos {
    /** Photos are looked for within this distance of the point pressed. */
    const val RADIUS_M = 50

    private const val API = "https://api.panoramax.xyz/api/search"

    /** Square of [meters] around [point]. */
    fun box(point: GeoPoint, meters: Int): GeoBox {
        val dLat = meters / 111_320.0
        val dLon = dLat / cos(Math.toRadians(point.lat)).coerceAtLeast(0.01)
        return GeoBox(point.lat - dLat, point.lon - dLon, point.lat + dLat, point.lon + dLon)
    }

    /** Answer of the Panoramax search (a STAC list of items). */
    fun parse(json: String): List<StreetPhoto> {
        val features = JSONObject(json).optJSONArray("features") ?: return emptyList()
        return (0 until features.length()).mapNotNull { index ->
            val feature = features.getJSONObject(index)
            val coordinates = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
            val assets = feature.optJSONObject("assets") ?: return@mapNotNull null
            // "sd" is about 2000 px wide: enough for a phone, a tenth of the weight of "hd".
            val image = (assets.optJSONObject("sd") ?: assets.optJSONObject("hd"))?.optString("href").orEmpty()
            if (!image.startsWith("https://")) return@mapNotNull null
            val properties = feature.optJSONObject("properties") ?: JSONObject()
            StreetPhoto(
                imageUrl = image,
                lat = coordinates.optDouble(1),
                lon = coordinates.optDouble(0),
                date = properties.optString("datetime").take(10),
                author = feature.optJSONArray("providers")?.optJSONObject(0)?.optString("name").orEmpty(),
                license = properties.optString("license"),
                pageUrl = "https://api.panoramax.xyz/#focus=pic&pic=${feature.optString("id")}",
            )
        }
    }

    /** The photo of [photos] taken closest to [point]. */
    fun nearest(photos: List<StreetPhoto>, point: GeoPoint): StreetPhoto? {
        val scale = cos(Math.toRadians(point.lat))
        return photos.minByOrNull {
            val dLat = it.lat - point.lat
            val dLon = (it.lon - point.lon) * scale
            dLat * dLat + dLon * dLon
        }
    }

    /**
     * Closest photo within [RADIUS_M] of [point], or null when there is none.
     *
     * @throws IOException when the service cannot be reached or answers unexpectedly.
     */
    suspend fun find(point: GeoPoint): StreetPhoto? = withContext(Dispatchers.IO) {
        val box = box(point, RADIUS_M)
        val area = String.format(Locale.ROOT, "%.6f,%.6f,%.6f,%.6f", box.west, box.south, box.east, box.north)
        val text = get("$API?bbox=$area&limit=30").toString(Charsets.UTF_8)
        try {
            nearest(parse(text), point)
        } catch (_: JSONException) {
            throw IOException("unexpected answer")
        }
    }

    suspend fun image(url: String): Bitmap = withContext(Dispatchers.IO) {
        val bytes = get(url)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IOException("unreadable image")
    }

    private fun get(url: String): ByteArray {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body?.bytes() ?: throw IOException("empty answer")
        }
    }
}
