package io.github.usernamealreadytakensht.cameras

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.maplibre.android.offline.OfflineManager

/**
 * The map tiles seen online, kept by MapLibre so that the places already visited show without a
 * connection. Limited in size: once full, the tiles seen longest ago are dropped. IGN says its
 * tiles stay valid 21 days, after which they are fetched again when online.
 */
object TileCache {
    /** Sizes the user can choose, in bytes. */
    val choices = listOf(50L, 100L, 200L, 500L, 1000L, 2000L).map { it * 1_000_000 }
    private val DEFAULT = 500L * 1_000_000

    private lateinit var appContext: Context

    var limit by mutableLongStateOf(DEFAULT)
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        limit = prefs().getLong("tile_cache", DEFAULT).takeIf { it in choices } ?: DEFAULT
        apply()
    }

    fun choose(bytes: Long) {
        limit = bytes
        prefs().edit().putLong("tile_cache", bytes).apply()
        apply()
    }

    /** Room taken on the phone by everything kept automatically: map tiles, photos, searches. */
    fun bytes(): Long = mapDatabase().sumOf { it.length() } + httpCache.size()

    /**
     * Empties everything kept automatically: the map tiles seen online, the street photos and
     * the online searches. The map files downloaded on purpose are left alone.
     */
    suspend fun clearAll() {
        httpCache.evictAll()
        val manager = OfflineManager.getInstance(appContext)
        await { manager.clearAmbientCache(it) }
        // Clearing frees the rows; packing gives the room back to the phone.
        await { manager.packDatabase(it) }
    }

    /** MapLibre's database of the tiles seen, with its journal files. */
    private fun mapDatabase(): List<File> =
        appContext.filesDir.listFiles { f -> f.name.startsWith("mbgl-offline.db") }.orEmpty().toList()

    private suspend fun await(call: (OfflineManager.FileSourceCallback) -> Unit) =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                call(object : OfflineManager.FileSourceCallback {
                    override fun onSuccess() = continuation.resume(Unit)
                    override fun onError(message: String) = continuation.resume(Unit)
                })
            }
        }

    private fun apply() {
        OfflineManager.getInstance(appContext).setMaximumAmbientCacheSize(limit, Ignore)
    }

    private fun prefs() = appContext.getSharedPreferences("map", Context.MODE_PRIVATE)

    private object Ignore : OfflineManager.FileSourceCallback {
        override fun onSuccess() = Unit
        override fun onError(message: String) = Unit
    }
}
