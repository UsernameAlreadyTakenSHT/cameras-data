package io.github.usernamealreadytakensht.cameras

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Offline address search over the whole of France (Base Adresse Nationale, Licence Ouverte 2.0;
 * database built by `tools/build_data.py`).
 *
 * The database holds one row per street, with its house numbers packed as [AddressCodec] reads
 * them, one row per town, and a full-text index of their names. It comes bundled in the app, but
 * SQLite cannot read it inside the APK: it is copied to the app's storage on first launch. The
 * monthly update then replaces that copy ([replaceWith]).
 */
class AddressStore(context: Context, private val files: DataFiles) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var database: SQLiteDatabase? = null

    /** Completed once the database is installed and open, or could not be. */
    private val installedSignal = kotlinx.coroutines.CompletableDeferred<Unit>()

    /** Install and replacement of the database file, never at the same time. */
    private val fileLock = kotlinx.coroutines.sync.Mutex()

    /** False while the database is being copied, on the first launch after an install or update. */
    var ready by mutableStateOf(false)
        private set

    init {
        scope.launch {
            val opened = try {
                fileLock.withLock { install() }
            } catch (_: Exception) {
                null
            }
            // A replacement may have come first: it then holds the database to use.
            synchronized(this@AddressStore) { if (database == null) database = opened else opened?.close() }
            installedSignal.complete(Unit)
            withContext(Dispatchers.Main) { ready = database != null }
        }
    }

    private fun install(): SQLiteDatabase {
        val file = files.file(DataKind.ADDRESSES)
        if (!files.fileIsCurrent(DataKind.ADDRESSES)) {
            // Per-region databases downloaded by earlier versions of the app.
            File(appContext.filesDir, "addresses").deleteRecursively()
            val temporary = File(appContext.filesDir, "${file.name}.tmp")
            files.openBundled(DataKind.ADDRESSES).use { input ->
                temporary.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
            }
            if (!temporary.renameTo(file)) throw IOException("cannot install the addresses")
            files.installedBundled(DataKind.ADDRESSES)
        }
        return open(file)
    }

    private fun open(file: File) = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)

    /** Waits until the first install is over, done or failed. */
    suspend fun awaitInstalled() = installedSignal.await()

    /** Puts the downloaded database [newer] in place of the one in use. */
    suspend fun replaceWith(newer: File, version: DataVersion) = fileLock.withLock {
        val file = files.file(DataKind.ADDRESSES)
        synchronized(this) {
            database?.close()
            database = null
            // Renamed over the file in use: if the rename fails, that file is still there.
            val moved = newer.renameTo(file)
            if (moved) files.installed(DataKind.ADDRESSES, version)
            database = open(file)
            if (!moved) throw IOException("cannot install the addresses")
        }
        withContext(Dispatchers.Main) { ready = true }
    }

    /** Towns come first, then the streets where the number asked exists, then the shortest names. */
    suspend fun search(text: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val query = AddressCodec.query(text) ?: return@withContext emptyList()
        synchronized(this@AddressStore) {
            val db = database ?: return@synchronized emptyList()
            val results = ArrayList<Pair<Int, SearchResult>>()
            db.rawQuery(
                "SELECT p.kind, p.name, p.city, p.postcode, p.lat, p.lon, p.numbers FROM place_fts f " +
                    "JOIN place p ON p.id = f.docid WHERE f.text MATCH ? ORDER BY p.kind, length(p.name) LIMIT $CANDIDATES",
                arrayOf(query.match),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val kind = cursor.getLong(0)
                    val name = cursor.getString(1)
                    val subtitle = "${cursor.getString(3)} ${cursor.getString(2)}".trim()
                    val lat = cursor.getDouble(4)
                    val lon = cursor.getDouble(5)
                    val house = if (kind == KIND_STREET && query.number != null && !cursor.isNull(6)) {
                        AddressCodec.find(cursor.getBlob(6), lat, lon, query.number, query.suffix)
                    } else {
                        null
                    }
                    val result = when {
                        kind == KIND_TOWN -> SearchResult(name, cursor.getString(3), GeoPoint(lat, lon), ResultZoom.TOWN)
                        house != null -> SearchResult(
                            listOf(query.number.toString(), query.suffix, name).filter { it.isNotEmpty() }.joinToString(" "),
                            subtitle, house, ResultZoom.HOUSE,
                        )
                        else -> SearchResult(name, subtitle, GeoPoint(lat, lon), ResultZoom.STREET)
                    }
                    results.add((kind.toInt() * 2 + if (house != null) 0 else 1) * 1000 + name.length to result)
                }
            }
            results.sortedBy { it.first }.take(LIMIT).map { it.second }
        }
    }

    private companion object {
        const val KIND_TOWN = 0L
        const val KIND_STREET = 1L
        const val LIMIT = 12

        /** Rows read before sorting: a street name found in many towns must not hide the right one. */
        const val CANDIDATES = 60
    }
}
