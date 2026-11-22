package io.github.usernamealreadytakensht.cameras

import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * One line of the search results.
 *
 * @param zoom map zoom to show it at: the more precise the result, the closer.
 */
data class SearchResult(val title: String, val subtitle: String, val point: GeoPoint, val zoom: Double)

/** Map zooms (MapLibre scale: one less than the tile zoom) for each kind of result. */
object ResultZoom {
    const val HOUSE = 17.0
    const val STREET = 15.5
    const val LOCALITY = 14.0
    const val TOWN = 12.0
    const val POINT = 15.0
}

/**
 * Client of every request but the map tiles, which MapLibre handles itself. Created by
 * [CamerasApp] with [httpCache].
 */
lateinit var httpClient: OkHttpClient

/**
 * Disk cache of the online answers: street photos, photo searches and address searches. An answer
 * kept here is shown again without a request, and without a connection, until it expires.
 */
lateinit var httpCache: Cache

/** Largest size of [httpCache]: the oldest answers are dropped beyond it. */
const val HTTP_CACHE_BYTES = 300L * 1024 * 1024

/**
 * How long an answer may be reused, by host. The servers themselves mostly forbid caching or say
 * nothing: this decides for them, for the kinds of answers that do not change by the minute.
 */
fun cacheSeconds(host: String, path: String): Int? = when {
    // Photos never change once published.
    path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") || path.endsWith(".webp") -> 90 * DAY_S
    host == "data.geopf.fr" && path.startsWith("/geocodage") -> 7 * DAY_S
    host == "api.panoramax.xyz" -> DAY_S
    else -> null
}

private const val DAY_S = 24 * 3600

/** Names the app to the services it calls. */
const val USER_AGENT = "Cameras (Android; io.github.usernamealreadytakensht.cameras)"

/**
 * Address and place search of the IGN Géoplateforme (https://data.geopf.fr/geocodage), built on
 * the Base Adresse Nationale. No key; partial words are completed by the service.
 */
object IgnGeocoder {
    private const val ENDPOINT = "https://data.geopf.fr/geocodage/search"

    /** The service refuses queries outside this length. */
    val queryLength = 3..200

    /** @throws IOException when the service cannot be reached or answers with an error. */
    suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val url = "$ENDPOINT?q=${URLEncoder.encode(query, "UTF-8")}&limit=8&index=address,poi"
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        val text = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body?.string() ?: throw IOException("empty answer")
        }
        parse(text)
    }

    fun parse(json: String): List<SearchResult> {
        val features = JSONObject(json).optJSONArray("features") ?: return emptyList()
        return (0 until features.length()).mapNotNull { index ->
            val feature = features.getJSONObject(index)
            val coordinates = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
            val point = GeoPoint(coordinates.getDouble(1), coordinates.getDouble(0))
            val p = feature.getJSONObject("properties")
            if (p.optString("_type") == "poi") {
                // A place: its fields are lists (names, towns, categories).
                val title = p.optString("toponym").ifEmpty { p.first("name") }
                if (title.isEmpty()) return@mapNotNull null
                val subtitle = listOf(p.first("category"), "${p.first("postcode")} ${p.first("city")}".trim())
                    .filter { it.isNotEmpty() }.joinToString(" · ")
                SearchResult(title, subtitle, point, ResultZoom.POINT)
            } else {
                val title = p.optString("name").ifEmpty { p.optString("label") }
                if (title.isEmpty()) return@mapNotNull null
                val zoom = when (p.optString("type")) {
                    "housenumber" -> ResultZoom.HOUSE
                    "street" -> ResultZoom.STREET
                    "locality" -> ResultZoom.LOCALITY
                    else -> ResultZoom.TOWN
                }
                SearchResult(title, "${p.optString("postcode")} ${p.optString("city")}".trim(), point, zoom)
            }
        }
    }

    /** Value of a field that is either a string or a list of strings. */
    private fun JSONObject.first(name: String): String = when (val value = opt(name)) {
        is JSONArray -> value.optString(0)
        is String -> value
        else -> ""
    }
}
