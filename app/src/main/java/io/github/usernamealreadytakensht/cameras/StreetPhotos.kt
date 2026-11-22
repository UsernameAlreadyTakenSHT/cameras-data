package io.github.usernamealreadytakensht.cameras

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.StringRes
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/** Street-level photo services. Both are off until the user turns them on: no request before that. */
enum class PhotoProvider(val shortName: String, @StringRes val label: Int) {
    /** French open commons (IGN, OpenStreetMap France): no key. */
    PANORAMAX("Panoramax", R.string.photos_panoramax),

    /** Run by Meta: needs the access token of a Mapillary developer account, typed in by the user. */
    MAPILLARY("Mapillary", R.string.photos_mapillary),
}

/**
 * @param date day the photo was taken, "2025-10-10", or empty.
 * @param license short name of the licence, such as "etalab-2.0" or "CC BY-SA 4.0".
 * @param pageUrl page of the photo on the service's own site.
 */
data class StreetPhoto(
    val provider: PhotoProvider,
    val imageUrl: String,
    val lat: Double,
    val lon: Double,
    val date: String,
    val author: String,
    val license: String,
    val pageUrl: String,
)

object StreetPhotos {
    /** Photos are looked for within this distance of the point pressed. */
    const val RADIUS_M = 50

    private const val PANORAMAX_API = "https://api.panoramax.xyz/api/search"
    private const val MAPILLARY_API = "https://graph.mapillary.com/images"

    /** Square of [meters] around [point]. */
    fun box(point: GeoPoint, meters: Int): GeoBox {
        val dLat = meters / 111_320.0
        val dLon = dLat / cos(Math.toRadians(point.lat)).coerceAtLeast(0.01)
        return GeoBox(point.lat - dLat, point.lon - dLon, point.lat + dLat, point.lon + dLon)
    }

    /** Answer of the Panoramax search (a STAC list of items). */
    fun parsePanoramax(json: String): List<StreetPhoto> {
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
                provider = PhotoProvider.PANORAMAX,
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

    /** Answer of the Mapillary image search (Graph API). */
    fun parseMapillary(json: String): List<StreetPhoto> {
        val data = JSONObject(json).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { index ->
            val item = data.getJSONObject(index)
            val coordinates = (item.optJSONObject("computed_geometry") ?: item.optJSONObject("geometry"))
                ?.optJSONArray("coordinates") ?: return@mapNotNull null
            val image = item.optString("thumb_1024_url")
            if (!image.startsWith("https://")) return@mapNotNull null
            val taken = item.optLong("captured_at")
            StreetPhoto(
                provider = PhotoProvider.MAPILLARY,
                imageUrl = image,
                lat = coordinates.optDouble(1),
                lon = coordinates.optDouble(0),
                date = if (taken > 0) SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(taken)) else "",
                author = item.optJSONObject("creator")?.optString("username").orEmpty(),
                license = "CC BY-SA 4.0",
                pageUrl = "https://www.mapillary.com/app/?pKey=${item.optString("id")}",
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
     * Closest photo to [point] among the services turned on.
     *
     * @param mapillaryToken null to leave Mapillary out.
     * @throws IOException when every service asked failed.
     */
    suspend fun find(point: GeoPoint, panoramax: Boolean, mapillaryToken: String?): StreetPhoto? =
        withContext(Dispatchers.IO) {
            val box = box(point, RADIUS_M)
            val area = String.format(Locale.ROOT, "%.6f,%.6f,%.6f,%.6f", box.west, box.south, box.east, box.north)
            val found = ArrayList<StreetPhoto>()
            var failure: IOException? = null
            var answered = false
            if (panoramax) {
                try {
                    found += parsePanoramax(get("$PANORAMAX_API?bbox=$area&limit=30", null))
                    answered = true
                } catch (e: IOException) {
                    failure = e
                }
            }
            if (mapillaryToken != null) {
                try {
                    val fields = "id,thumb_1024_url,captured_at,computed_geometry,creator"
                    found += parseMapillary(get("$MAPILLARY_API?fields=$fields&bbox=$area&limit=30", mapillaryToken))
                    answered = true
                } catch (e: IOException) {
                    failure = e
                }
            }
            if (!answered && failure != null) throw failure
            nearest(found, point)
        }

    suspend fun image(url: String): Bitmap = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        val bytes = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body?.bytes() ?: throw IOException("empty answer")
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IOException("unreadable image")
    }

    private fun get(url: String, token: String?): String {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        // The token travels in a header, not in the address: addresses end up in the disk cache index.
        if (token != null) request.header("Authorization", "OAuth $token")
        return httpClient.newCall(request.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val text = response.body?.string() ?: throw IOException("empty answer")
            try {
                JSONObject(text)
            } catch (e: org.json.JSONException) {
                throw IOException("unexpected answer")
            }
            text
        }
    }
}
