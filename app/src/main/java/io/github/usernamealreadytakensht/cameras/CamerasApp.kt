package io.github.usernamealreadytakensht.cameras

import android.app.Application
import java.io.File
import okhttp3.Cache
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil

class CamerasApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Must run before any MapView or offline manager is created.
        MapLibre.getInstance(this)
        httpCache = Cache(File(cacheDir, "http"), HTTP_CACHE_BYTES)
        httpClient = OkHttpClient.Builder()
            .cache(httpCache)
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val url = chain.request().url
                val seconds = cacheSeconds(url.host, url.encodedPath)
                if (seconds == null || !response.isSuccessful) {
                    response
                } else {
                    response.newBuilder()
                        .removeHeader("Pragma")
                        .removeHeader("Expires")
                        .removeHeader("Vary")
                        .header("Cache-Control", "public, max-age=$seconds")
                        .build()
                }
            }
            .build()
        TileHealth.init(this)
        HttpRequestUtil.setOkHttpClient(
            OkHttpClient.Builder()
                .addInterceptor(LocalStyleInterceptor())
                // Tells the map why it stays empty, when the tile server cannot be reached.
                .addInterceptor(TileHealth.interceptor)
                .dns(TileHealth.dns)
                // Few parallel requests, to stay a light client of the public tile server.
                .dispatcher(Dispatcher().apply { maxRequestsPerHost = 4 })
                .build(),
        )
    }
}

/**
 * Serves the style of each [MapLayer] at its [MapLayer.styleUrl] without touching the network.
 *
 * MapLibre's offline downloads fetch the style over HTTP only: with an `asset://` style the
 * download hangs on its first resource. The host is under `.invalid`, which never resolves, so
 * nothing can leak if this interceptor is ever missing.
 */
private class LocalStyleInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != STYLE_HOST) return chain.proceed(request)
        val layer = MapLayer.byId(request.url.pathSegments.lastOrNull()?.removeSuffix(".json"))
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        return if (layer != null) {
            response.code(200).message("OK")
                .body(layer.styleJson().toResponseBody("application/json".toMediaType()))
                .build()
        } else {
            response.code(404).message("Not Found").body("".toResponseBody(null)).build()
        }
    }
}
