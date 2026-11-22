package io.github.usernamealreadytakensht.cameras

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONObject

/** The data sets that come with the app and are renewed once a month. */
enum class DataKind(val key: String, val file: String) {
    RADARS("radars", "radars.csv"),
    CAMERAS("cameras", "cameras.tsv"),
    ADDRESSES("addresses", "addresses.db"),
}

/**
 * Date and size of one build of a data set, as written in `data.json` by `tools/build_data.py`.
 *
 * @param date day the data dates from, "2026-11-22"; such dates sort as text.
 */
data class DataVersion(val date: String, val count: Long) {
    /** "22/11/2026". */
    val displayDate: String
        get() = date.split('-').let { if (it.size == 3) "${it[2]}/${it[1]}/${it[0]}" else date }

    override fun toString() = "$date|$count"

    companion object {
        fun of(json: JSONObject?, kind: DataKind): DataVersion? =
            json?.optJSONObject(kind.key)?.let { DataVersion(it.optString("date"), it.optLong("count")) }

        fun parse(text: String?): DataVersion? {
            val parts = text?.split('|') ?: return null
            return if (parts.size == 2) DataVersion(parts[0], parts[1].toLongOrNull() ?: return null) else null
        }
    }
}

/**
 * Tells, for each data set, whether the copy bundled in the app or one downloaded since is in use.
 *
 * Every set comes bundled (`assets/`), so the app works from the first launch with no connection.
 * A newer one downloaded by [DataUpdater] lies in the app's storage and wins until an update of
 * the app brings a bundled copy that is newer still.
 */
class DataFiles(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("data", Context.MODE_PRIVATE)
    private val bundled: JSONObject = try {
        JSONObject(appContext.assets.open("data.json").use { it.readBytes().toString(Charsets.UTF_8) })
    } catch (_: Exception) {
        JSONObject()
    }

    fun file(kind: DataKind) = File(appContext.filesDir, kind.file)

    private fun bundledVersion(kind: DataKind) = DataVersion.of(bundled, kind) ?: DataVersion("", 0)

    /** Version of the downloaded copy, when there is one and the bundled copy is not newer. */
    private fun downloaded(kind: DataKind): DataVersion? =
        DataVersion.parse(prefs.getString(kind.key, null))
            ?.takeIf { file(kind).isFile && it.date >= bundledVersion(kind).date }

    /** Version of the copy in use. */
    fun version(kind: DataKind): DataVersion = downloaded(kind) ?: bundledVersion(kind)

    /** The copy in use, for the sets read as a stream (not the address database). */
    fun open(kind: DataKind): InputStream =
        if (downloaded(kind) != null) file(kind).inputStream() else appContext.assets.open(kind.file)

    /** The bundled copy, whatever is in use. */
    fun openBundled(kind: DataKind): InputStream = appContext.assets.open(kind.file)

    /** Records that [file] now holds [version]. */
    fun installed(kind: DataKind, version: DataVersion) {
        prefs.edit().putString(kind.key, version.toString()).apply()
    }

    /** True when [file] holds the copy in use: false means the bundled one must be (re)installed. */
    fun fileIsCurrent(kind: DataKind): Boolean = downloaded(kind) != null

    var lastUpdateCheck: Long
        get() = prefs.getLong("last_update_check", 0)
        set(value) = prefs.edit().putLong("last_update_check", value).apply()
}

/**
 * Renews the data once a month, when the app is opened with a connection.
 *
 * The files are rebuilt on the 1st of each month in the cameras-data repository and published
 * with `data.json`, which gives the date of each. The app reads that small file and downloads
 * only the sets that changed: phones never query the source servers for whole data sets. The
 * heavy parts — the address database (about 190 MB) and the re-check of the downloaded map
 * areas — wait for an unmetered connection (Wi-Fi).
 */
class DataUpdater(
    context: Context,
    private val files: DataFiles,
    private val radars: RadarStore,
    private val cameras: CameraStore,
    private val addresses: AddressStore,
    private val maps: OfflineMaps,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    /** Does nothing when the last complete update is less than a month old. */
    fun runIfDue() {
        if (started || System.currentTimeMillis() - files.lastUpdateCheck < EVERY_MS) return
        started = true
        scope.launch {
            try {
                if (update()) files.lastUpdateCheck = System.currentTimeMillis()
            } catch (_: Exception) {
                // No connection, or an unexpected answer: the data in place stays, the next launch tries again.
            }
        }
    }

    /** @return true when nothing is left to do this month. */
    private suspend fun update(): Boolean {
        val remote = JSONObject(String(get("$BASE_URL/data.json").use { it.readBytes() }, Charsets.UTF_8))
        val unmetered = isUnmetered()

        newer(remote, DataKind.RADARS)?.let { version ->
            val temporary = download(DataKind.RADARS, gzip = false)
            val count = RadarCsv.parse(RadarCsv.decode(temporary.readBytes())).size
            // A sound list holds thousands of entries: anything tiny is not the right file.
            if (count < MIN_RADARS) throw IOException("unexpected radars file")
            replace(DataKind.RADARS, temporary, version)
            radars.reload()
        }
        newer(remote, DataKind.CAMERAS)?.let { version ->
            val temporary = download(DataKind.CAMERAS, gzip = false)
            val count = temporary.useLines { lines -> lines.count { CameraData.decode(it) != null } }
            if (count < MIN_CAMERAS) throw IOException("unexpected cameras file")
            replace(DataKind.CAMERAS, temporary, version)
            cameras.reload()
        }

        var complete = true
        val newAddresses = newer(remote, DataKind.ADDRESSES)
        if (newAddresses != null) {
            if (unmetered) {
                // The first launch may still be installing the bundled database.
                repeat(60) { if (!addresses.ready) delay(2000) }
                val temporary = download(DataKind.ADDRESSES, gzip = true)
                val places = SQLiteDatabase.openDatabase(temporary.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("SELECT count(*) FROM place", null).use { if (it.moveToFirst()) it.getLong(0) else 0 }
                }
                if (places < MIN_PLACES) throw IOException("unexpected address database")
                addresses.replaceWith(temporary, newAddresses)
            } else {
                complete = false
            }
        }

        if (unmetered) maps.revalidateAll() else complete = false
        return complete
    }

    /** The published version of [kind], when it differs from the one in use and is not older. */
    private fun newer(remote: JSONObject, kind: DataKind): DataVersion? {
        val published = DataVersion.of(remote, kind) ?: return null
        val current = files.version(kind)
        return published.takeIf { it != current && it.date >= current.date }
    }

    private fun get(url: String): InputStream {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("HTTP ${response.code}")
        }
        return response.body?.byteStream() ?: throw IOException("empty answer")
    }

    /** Downloads the published file of [kind] next to the one in use, which is left untouched. */
    private fun download(kind: DataKind, gzip: Boolean): File {
        val temporary = File(appContext.filesDir, "${kind.file}.new")
        get("$BASE_URL/${kind.file}${if (gzip) ".gz" else ""}").use { raw ->
            val input = if (gzip) GZIPInputStream(raw, 1 shl 16) else raw
            temporary.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
        }
        return temporary
    }

    private fun replace(kind: DataKind, temporary: File, version: DataVersion) {
        val target = files.file(kind)
        target.delete()
        if (!temporary.renameTo(target)) throw IOException("cannot install ${kind.file}")
        files.installed(kind, version)
    }

    private fun isUnmetered(): Boolean {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private companion object {
        /** Assets of the "data" release of the cameras-data repository. */
        const val BASE_URL = "https://github.com/UsernameAlreadyTakenSHT/cameras-data/releases/download/data"
        const val EVERY_MS = 30L * 24 * 3600 * 1000
        const val MIN_RADARS = 100
        const val MIN_CAMERAS = 1000
        const val MIN_PLACES = 100_000L
    }
}
