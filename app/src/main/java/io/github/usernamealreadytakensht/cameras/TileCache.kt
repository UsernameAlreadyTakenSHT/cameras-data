package io.github.usernamealreadytakensht.cameras

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
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

    fun clear() {
        OfflineManager.getInstance(appContext).clearAmbientCache(Ignore)
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
