package io.github.usernamealreadytakensht.cameras

import android.app.Application
import java.io.File
import okhttp3.Cache
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
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
        MapFiles.init(this)
        TileCache.init(this)
        HttpRequestUtil.setOkHttpClient(
            OkHttpClient.Builder()
                // Tells the map why it stays empty, when the tile server cannot be reached.
                .addInterceptor(TileHealth.interceptor)
                .dns(TileHealth.dns)
                // Few parallel requests, to stay a light client of the public tile server.
                .dispatcher(Dispatcher().apply { maxRequestsPerHost = 4 })
                .build(),
        )
    }
}
