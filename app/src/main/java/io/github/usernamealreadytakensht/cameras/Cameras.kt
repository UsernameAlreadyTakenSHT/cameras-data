package io.github.usernamealreadytakensht.cameras

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject

/**
 * Kinds of surveillance mapped in OpenStreetMap, as told apart by "Surveillance under
 * Surveillance" (https://sunders.uber.space).
 *
 * @param code value written in the bundled file and given to the map layer.
 * @param color of the dot on the map, `#rrggbb`.
 */
enum class CameraKind(val code: String, @StringRes val label: Int, val color: String) {
    UNKNOWN("unknown", R.string.camera_unknown, "#868E96"),
    FIXED("fixed", R.string.camera_fixed, "#1971C2"),
    PANNING("panning", R.string.camera_panning, "#7048E8"),
    DOME("dome", R.string.camera_dome, "#0CA678"),
    GUARD("guard", R.string.camera_guard, "#5C940D"),
    ALPR("alpr", R.string.camera_alpr, "#C2255C");

    companion object {
        fun byCode(code: String): CameraKind = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/**
 * One object tagged `man_made=surveillance` in OpenStreetMap.
 *
 * @param id OSM type and number, such as "n218117881".
 * @param zone `surveillance` tag: "public", "outdoor", "indoor", or empty.
 * @param direction `camera:direction` tag, in degrees or as a compass point, or empty.
 */
data class Camera(
    val id: String,
    val lat: Double,
    val lon: Double,
    val kind: CameraKind,
    val zone: String,
    val direction: String,
    val operator: String,
)

/** A rectangle of the map, in degrees. */
data class GeoBox(val south: Double, val west: Double, val north: Double, val east: Double) {
    operator fun contains(camera: Camera) = camera.lat in south..north && camera.lon in west..east
    fun contains(other: GeoBox) =
        other.south >= south && other.north <= north && other.west >= west && other.east <= east
}

/** Reading of the camera data: the bundled file and the answers of the Overpass servers. */
object CameraData {
    /** One camera of the bundled file: a tab-separated line. */
    fun decode(line: String): Camera? {
        val fields = line.split('\t')
        if (fields.size < 7) return null
        return Camera(
            id = fields[0],
            lat = fields[1].toDoubleOrNull() ?: return null,
            lon = fields[2].toDoubleOrNull() ?: return null,
            kind = CameraKind.byCode(fields[3]),
            zone = fields[4],
            direction = fields[5],
            operator = fields[6],
        )
    }

    /** Kind of a surveillance object, from its OSM tags. Same rule as `tools/build_data.py`. */
    fun kind(surveillanceType: String, cameraType: String): CameraKind = when (surveillanceType.lowercase()) {
        "guard" -> CameraKind.GUARD
        "alpr" -> CameraKind.ALPR
        else -> when (cameraType.lowercase()) {
            "fixed" -> CameraKind.FIXED
            "panning" -> CameraKind.PANNING
            "dome" -> CameraKind.DOME
            else -> CameraKind.UNKNOWN
        }
    }

    /** Elements of an Overpass answer asked with `out center tags`: ways and relations carry a centre. */
    fun parseOverpass(json: String): List<Camera> {
        val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        return (0 until elements.length()).mapNotNull { index ->
            val element = elements.getJSONObject(index)
            val position = if (element.has("lat")) element else element.optJSONObject("center") ?: return@mapNotNull null
            val tags = element.optJSONObject("tags") ?: JSONObject()
            Camera(
                id = element.optString("type").take(1) + element.optLong("id"),
                lat = position.optDouble("lat"),
                lon = position.optDouble("lon"),
                kind = kind(tags.optString("surveillance:type"), tags.optString("camera:type")),
                zone = tags.optString("surveillance"),
                direction = tags.optString("camera:direction").ifEmpty { tags.optString("direction") },
                operator = tags.optString("operator"),
            ).takeIf { !it.lat.isNaN() && !it.lon.isNaN() }
        }
    }

    /** Overpass query for the surveillance objects inside [box]. */
    fun query(box: GeoBox): String = String.format(
        Locale.ROOT,
        "[out:json][timeout:%d];nwr[\"man_made\"=\"surveillance\"](%.5f,%.5f,%.5f,%.5f);out center tags;",
        SERVER_TIMEOUT_S, box.south, box.west, box.north, box.east,
    )

    /**
     * The cameras to show once [fresh] has been fetched for [box]: inside the box the fresh ones
     * replace everything (a camera removed from OpenStreetMap disappears), outside nothing moves.
     */
    fun merge(current: List<Camera>, box: GeoBox, fresh: List<Camera>): List<Camera> {
        val ids = fresh.mapTo(HashSet()) { it.id }
        return current.filter { it !in box && it.id !in ids } + fresh
    }

    const val SERVER_TIMEOUT_S = 25
}

/**
 * Surveillance cameras of OpenStreetMap (ODbL).
 *
 * The whole of France comes bundled in the app (`tools/build_data.py`) and is renewed by the
 * monthly update, so the layer works with no connection. With a connection, [refresh] brings the
 * area on screen up to date for the session, through the public Overpass servers.
 */
class CameraStore(private val files: DataFiles) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Areas brought up to date since the app started. */
    private val fresh = ArrayList<GeoBox>()
    private var nextServer = 0

    private val client = httpClient.newBuilder()
        .readTimeout(CameraData.SERVER_TIMEOUT_S + 10L, TimeUnit.SECONDS)
        .build()

    /** Empty for the instant the bundled file takes to be read. */
    var cameras by mutableStateOf<List<Camera>>(emptyList())
        private set

    /** True once some area has been brought up to date online. */
    var updatedOnline by mutableStateOf(false)
        private set

    /** Day the cameras of the whole country date from, as "22/11/2026". */
    var date by mutableStateOf("")
        private set

    init {
        reload()
    }

    /** Reads the file in use again: called once the monthly update has brought a newer one. */
    fun reload() {
        scope.launch {
            val list = try {
                files.open(DataKind.CAMERAS).bufferedReader().useLines { lines ->
                    lines.mapNotNull(CameraData::decode).toList()
                }
            } catch (_: IOException) {
                emptyList()
            }
            val built = files.version(DataKind.CAMERAS).displayDate
            withContext(Dispatchers.Main) {
                cameras = list
                date = built
                // The areas refreshed online were merged into the previous list: they are due again.
                fresh.clear()
                updatedOnline = false
            }
        }
    }

    /**
     * Brings [box], the area on screen, up to date. Does nothing when it was already done this
     * session, while another request is running, or before the bundled cameras are read; a
     * failure (no connection, busy server) leaves the bundled cameras in place.
     */
    fun refresh(box: GeoBox) {
        if (job?.isActive == true || cameras.isEmpty() || fresh.any { it.contains(box) }) return
        job = scope.launch {
            val server = SERVERS[nextServer % SERVERS.size]
            val list = try {
                val request = Request.Builder()
                    .url(server)
                    .header("User-Agent", USER_AGENT)
                    .post(FormBody.Builder().add("data", CameraData.query(box)).build())
                    .build()
                val text = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    response.body?.string() ?: throw IOException("empty answer")
                }
                // A server short of time answers 200 with a "remark" and no data: not an empty area.
                if ("\"remark\"" in text) throw IOException("incomplete answer")
                CameraData.parseOverpass(text)
            } catch (_: IOException) {
                nextServer++
                return@launch
            } catch (_: JSONException) {
                nextServer++
                return@launch
            }
            withContext(Dispatchers.Main) {
                cameras = CameraData.merge(cameras, box, list)
                fresh.add(box)
                updatedOnline = true
            }
        }
    }

    private companion object {
        /** Public Overpass instances; the next one is tried after a failure. */
        val SERVERS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
        )
    }
}
