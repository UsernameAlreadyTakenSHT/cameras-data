package io.github.usernamealreadytakensht.cameras

import android.content.Context
import android.os.StatFs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * One published map file: a base map over the whole country (zooms 0 to 13) or over one region
 * (zoom 14), cut into parts when it is larger than GitHub accepts for one file.
 *
 * @param key "<layer>-<area>", such as "plan-FR" or "photos-53".
 * @param date day the tiles were fetched, "2026-11-23": such dates sort as text.
 */
data class MapFile(val key: String, val date: String, val size: Long, val parts: List<MapPart>)

data class MapPart(val name: String, val size: Long)

/** Reading of `maps.json`, the list of the files of the "maps" release. */
object MapCatalog {
    const val COUNTRY = "FR"

    fun key(layer: MapLayer, area: String) = "${layer.id}-$area"

    fun parse(json: String): Map<String, MapFile> {
        val files = JSONObject(json).optJSONObject("files") ?: return emptyMap()
        return files.keys().asSequence().mapNotNull { key ->
            val file = files.getJSONObject(key)
            val parts = file.optJSONArray("parts") ?: return@mapNotNull null
            val list = (0 until parts.length()).map {
                val part = parts.getJSONObject(it)
                MapPart(part.getString("name"), part.getLong("size"))
            }
            if (list.isEmpty()) null else key to MapFile(key, file.optString("date"), list.sumOf { it.size }, list)
        }.toMap()
    }
}

/**
 * The offline maps: files built every month on GitHub from the IGN tiles, downloaded on demand,
 * then renewed by the monthly update. The phones never fetch tiles from IGN for offline use.
 *
 * An object, because the style served to the map ([CamerasApp]) lists the files installed.
 */
object MapFiles {
    private const val RELEASE = "https://github.com/UsernameAlreadyTakenSHT/cameras-data/releases/download/maps"

    private lateinit var directory: File
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Files published, null until `maps.json` has been read. */
    var catalog by mutableStateOf<Map<String, MapFile>?>(null)
        private set

    /** Files on the phone, by key, with the date of their tiles. */
    var installed by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** Bumped whenever a file is installed or deleted: the map then loads its style again. */
    var version by mutableIntStateOf(0)
        private set

    /** @param done bytes received out of [total]. */
    data class Progress(val key: String, val done: Long, val total: Long)

    var progress by mutableStateOf<Progress?>(null)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        directory = File(appContext.filesDir, "maps").apply { mkdirs() }
        val prefs = appContext.getSharedPreferences("maps", Context.MODE_PRIVATE)
        installed = directory.listFiles { f -> f.name.endsWith(".pmtiles") }.orEmpty()
            .associate { it.name.removeSuffix(".pmtiles") to prefs.getString(it.name.removeSuffix(".pmtiles"), "").orEmpty() }
    }

    private fun file(key: String) = File(directory, "$key.pmtiles")

    /** Room taken on the phone by the installed file [key]. */
    fun installedSize(key: String): Long = file(key).length()

    /** Files to draw for [layer], the country first, then the regions over it. */
    fun installedFiles(layer: MapLayer): List<File> =
        installed.keys.filter { it.startsWith("${layer.id}-") }
            .sortedBy { if (it.endsWith("-${MapCatalog.COUNTRY}")) "" else it }
            .map(::file)
            .filter { it.isFile }

    /** Reads the list of published files; does nothing once read, unless [force]. */
    fun refresh(force: Boolean = false) {
        if (catalog != null && !force) return
        scope.launch {
            try {
                val text = get("$RELEASE/maps.json", 0).use { it.body!!.string() }
                val read = MapCatalog.parse(text)
                withContext(Dispatchers.Main) {
                    catalog = read
                    error = null
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { error = e.message ?: e.javaClass.simpleName }
            }
        }
    }

    /**
     * Downloads [key] into a ".download" file beside the one in use, part after part. An
     * interrupted download goes on from where it stopped, as long as the published file is the same.
     */
    fun download(key: String) {
        if (job?.isActive == true) return
        val published = catalog?.get(key) ?: return
        error = null
        progress = Progress(key, 0, published.size)
        job = scope.launch {
            val partial = File(directory, "$key.download")
            val marker = File(directory, "$key.download.date")
            try {
                // The bytes received are only reused for the very same file: same date, same parts.
                val identity = published.date + "|" + published.parts.joinToString(",") { "${it.name}:${it.size}" }
                if (!marker.isFile || marker.readText() != identity || partial.length() > published.size) {
                    partial.delete()
                    marker.writeText(identity)
                }
                val free = StatFs(directory.path).availableBytes
                if (published.size - partial.length() > free) {
                    throw IOException(appContext.getString(R.string.maps_no_space))
                }
                var start = 0L
                for (part in published.parts) {
                    val end = start + part.size
                    if (partial.length() < end) {
                        val offset = partial.length() - start
                        get("$RELEASE/${part.name}", offset).use { response ->
                            val body = response.body ?: throw IOException("empty answer")
                            // A server ignoring the range sends the whole part again: start it over.
                            val from = if (offset > 0 && response.code != 206) 0L else offset
                            RandomAccessFile(partial, "rw").use { out ->
                                out.setLength(start + from)
                                out.seek(start + from)
                                val buffer = ByteArray(1 shl 16)
                                val input = body.byteStream()
                                var written = start + from
                                var lastReport = 0L
                                while (true) {
                                    ensureActive()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    out.write(buffer, 0, read)
                                    written += read
                                    if (written - lastReport > 1_000_000) {
                                        lastReport = written
                                        val shown = Progress(key, written, published.size)
                                        withContext(Dispatchers.Main) { progress = shown }
                                    }
                                }
                            }
                        }
                    }
                    if (partial.length() != end) {
                        // Not what was announced: start this file over next time.
                        partial.delete()
                        marker.delete()
                        throw IOException("incomplete part ${part.name}")
                    }
                    start = end
                }
                // Once received whole, the file is installed even if Stop is pressed now.
                withContext(NonCancellable) {
                    install(key, partial, published.date)
                    marker.delete()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { error = e.message ?: e.javaClass.simpleName }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { progress = null }
            }
        }
    }

    private suspend fun install(key: String, downloaded: File, date: String) {
        // Renamed over the file in use: if the rename fails, that file is still there.
        if (!downloaded.renameTo(file(key))) throw IOException("cannot install the map")
        appContext.getSharedPreferences("maps", Context.MODE_PRIVATE).edit().putString(key, date).apply()
        withContext(Dispatchers.Main) {
            installed = installed + (key to date)
            version++
        }
    }

    /** Stops the download; what it received is kept and the next download goes on from there. */
    fun stop() {
        job?.cancel()
    }

    fun delete(key: String) {
        scope.launch {
            file(key).delete()
            File(directory, "$key.download").delete()
            File(directory, "$key.download.date").delete()
            appContext.getSharedPreferences("maps", Context.MODE_PRIVATE).edit().remove(key).apply()
            withContext(Dispatchers.Main) {
                installed = installed - key
                version++
            }
        }
    }

    /** Keys of the installed files for which a newer file is published. */
    fun outdated(): List<String> {
        val published = catalog ?: return emptyList()
        return installed.filter { (key, date) -> (published[key]?.date ?: "") > date }.keys.toList()
    }

    /**
     * Monthly update, on an unmetered connection: reads the list again and downloads the newer
     * files one after the other. Returns once they are all in place, or one has failed.
     */
    suspend fun updateInstalled(): Boolean {
        val read = MapCatalog.parse(get("$RELEASE/maps.json", 0).use { it.body!!.string() })
        withContext(Dispatchers.Main) { catalog = read }
        for (key in outdated()) {
            withContext(Dispatchers.Main) { download(key) }
            job?.join()
            if (installed[key] != read[key]?.date) return false
        }
        return true
    }

    private fun get(url: String, from: Long): okhttp3.Response {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT)
            // The map files are large and the list must be fresh: neither goes through the disk cache.
            .header("Cache-Control", "no-store")
        if (from > 0) request.header("Range", "bytes=$from-")
        val response = httpClient.newCall(request.build()).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException(if (response.code == 404) appContext.getString(R.string.maps_not_published) else "HTTP ${response.code}")
        }
        return response
    }
}
