package io.github.usernamealreadytakensht.cameras

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.IOException
import java.net.InetAddress
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.Response

/** Why the map tiles do not load. */
enum class TileProblem {
    /** The server's name resolves to this very phone: a DNS filter, an ad blocker or a VPN blocks it. */
    BLOCKED,

    /** The network is up but the server does not answer. */
    UNREACHABLE,
}

/**
 * Watches the requests MapLibre sends to the tile server, so that the map can say why it stays
 * empty instead of showing nothing.
 *
 * Without any network there is no message: the downloaded areas and the cache are shown, and
 * the rest staying empty is expected.
 */
object TileHealth {
    const val TILE_HOST = "data.geopf.fr"

    private lateinit var appContext: Context

    /** Null while the tiles load, or before any failure. */
    var problem by mutableStateOf<TileProblem?>(null)
        private set

    /** The user closed the message: it stays closed until the tiles load again. */
    var dismissed by mutableStateOf(false)

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** True when every address is the phone itself or "nowhere", the answer of a DNS filter. */
    fun isBlockedAnswer(addresses: List<InetAddress>): Boolean =
        addresses.isNotEmpty() && addresses.all { it.isLoopbackAddress || it.isAnyLocalAddress }

    /** Name resolution of the tile client: notes a blocked answer, which then fails to connect anyway. */
    val dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = Dns.SYSTEM.lookup(hostname)
            if (hostname == TILE_HOST && isBlockedAnswer(addresses)) report(TileProblem.BLOCKED)
            return addresses
        }
    }

    /** Interceptor of the tile client: a tile received clears the message, a failure explains it. */
    val interceptor = Interceptor { chain ->
        val request = chain.request()
        if (request.url.host != TILE_HOST) return@Interceptor chain.proceed(request)
        val response: Response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            if (problem != TileProblem.BLOCKED && isOnline()) report(TileProblem.UNREACHABLE)
            throw e
        }
        if (response.isSuccessful) {
            if (problem != null) problem = null
            dismissed = false
        }
        response
    }

    private fun report(found: TileProblem) {
        if (problem != found) problem = found
    }

    private fun isOnline(): Boolean {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
