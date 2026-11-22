package io.github.usernamealreadytakensht.cameras

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONException
import org.json.JSONObject
import org.maplibre.android.offline.OfflineGeometryRegionDefinition
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus

/**
 * Downloaded map areas, stored by MapLibre in its own offline database. Every callback of the
 * offline manager runs on the main thread, so the Compose state is written directly.
 */
class OfflineMaps(context: Context) {
    /**
     * @param layerId, regionCode null for an area saved by an earlier version of the app.
     * @param planned number of tiles the download was created for.
     */
    data class SavedArea(
        val region: OfflineRegion,
        val name: String,
        val layerId: String?,
        val regionCode: String?,
        val planned: Long,
        val tiles: Long,
        val bytes: Long,
        val complete: Boolean,
    )

    /** @param manual false for the monthly re-check, which runs unasked. */
    data class Progress(
        val name: String,
        val layerId: String?,
        val done: Long,
        val total: Long,
        val manual: Boolean = true,
    )

    private val manager = OfflineManager.getInstance(context).apply {
        // The areas are sized by MAX_TILES_PER_DOWNLOAD; MapLibre's own cap (6000) is for other servers.
        setOfflineMapboxTileCountLimit(Long.MAX_VALUE)
    }
    private val pixelRatio = context.resources.displayMetrics.density
    private var active: OfflineRegion? = null

    var areas by mutableStateOf<List<SavedArea>>(emptyList())
        private set

    /** Non-null while a download is running. */
    var progress by mutableStateOf<Progress?>(null)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    fun refresh() {
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                // The area being downloaded is shown by the progress bar, not in the list.
                val regions = offlineRegions.orEmpty().filter { it.id != active?.id }
                if (regions.isEmpty()) {
                    areas = emptyList()
                    return
                }
                val found = arrayOfNulls<SavedArea>(regions.size)
                var pending = regions.size
                fun oneDone() {
                    pending--
                    if (pending == 0) areas = found.filterNotNull()
                }
                regions.forEachIndexed { index, region ->
                    region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(status: OfflineRegionStatus?) {
                            if (status != null) found[index] = savedArea(region, status)
                            oneDone()
                        }

                        override fun onError(error: String?) = oneDone()
                    })
                }
            }

            override fun onError(error: String) {
                this@OfflineMaps.error = error
            }
        })
    }

    private fun savedArea(region: OfflineRegion, status: OfflineRegionStatus): SavedArea {
        val raw = String(region.metadata, Charsets.UTF_8)
        // Earlier versions stored the bare name.
        val meta = try {
            JSONObject(raw)
        } catch (_: JSONException) {
            JSONObject().put("name", raw)
        }
        return SavedArea(
            region = region,
            name = meta.optString("name"),
            layerId = meta.optString("layer").ifEmpty { null },
            regionCode = meta.optString("region").ifEmpty { null },
            planned = meta.optLong("tiles"),
            // The style itself is one of the stored resources.
            tiles = (status.completedResourceCount - 1).coerceAtLeast(0),
            bytes = status.completedResourceSize,
            complete = status.isComplete,
        )
    }

    /** Downloads [layer] over the whole of [region], down to the zoom chosen by [Region.plan]. */
    fun download(region: Region, layer: MapLayer) {
        if (progress != null) return
        val plan = region.plan(layer)
        error = null
        progress = Progress(region.name, layer.id, 0, plan.tiles)
        val metadata = JSONObject()
            .put("name", region.name)
            .put("layer", layer.id)
            .put("region", region.code)
            .put("tiles", plan.tiles)
        // MapLibre zooms count 512 px tiles: a 256 px raster tile of zoom z is shown at map zoom z - 1.
        val definition = OfflineGeometryRegionDefinition(
            layer.styleUrl,
            region.geometry,
            0.0,
            (plan.maxTileZoom - 1).toDouble(),
            pixelRatio,
        )
        manager.createOfflineRegion(
            definition,
            metadata.toString().toByteArray(Charsets.UTF_8),
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) =
                    run(offlineRegion, region.name, layer.id, plan.tiles)

                override fun onError(error: String) {
                    progress = null
                    this@OfflineMaps.error = error
                }
            },
        )
    }

    /** Carries on an area whose download was stopped, or cut by the app being closed. */
    fun resume(area: SavedArea) {
        if (progress != null) return
        error = null
        progress = Progress(area.name, area.layerId, area.tiles, area.planned)
        areas = areas - area
        run(area.region, area.name, area.layerId, area.planned)
    }

    private fun run(
        region: OfflineRegion,
        name: String,
        layerId: String?,
        planned: Long,
        manual: Boolean = true,
        onDone: () -> Unit = {},
    ) {
        active = region
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                if (active?.id != region.id) return
                if (status.isComplete) {
                    region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    region.setObserver(null)
                    active = null
                    progress = null
                    refresh()
                    onDone()
                } else {
                    // MapLibre refines its own count as it walks the area: the planned one steadies the bar.
                    progress = Progress(
                        name,
                        layerId,
                        status.completedResourceCount,
                        maxOf(status.requiredResourceCount, planned),
                        manual,
                    )
                }
            }

            override fun onError(error: OfflineRegionError) {
                this@OfflineMaps.error = error.message
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                this@OfflineMaps.error = "tile limit: $limit"
            }
        })
        region.setDownloadState(OfflineRegion.STATE_ACTIVE)
    }

    /**
     * Asks the server again for every tile of the complete areas, one area after the other: tiles
     * that changed are downloaded, the others are only confirmed. Skipped while a download runs.
     */
    fun revalidateAll() {
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                if (progress != null) return
                val queue = ArrayDeque(offlineRegions.orEmpty().toList())
                fun next() {
                    val region = queue.removeFirstOrNull() ?: return
                    if (progress != null) return
                    region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(status: OfflineRegionStatus?) {
                            // An area left incomplete is the user's to resume or delete.
                            if (status == null || !status.isComplete || progress != null) return next()
                            val area = savedArea(region, status)
                            region.invalidate(object : OfflineRegion.OfflineRegionInvalidateCallback {
                                override fun onInvalidate() {
                                    progress = Progress(area.name, area.layerId, 0, area.planned, manual = false)
                                    run(region, area.name, area.layerId, area.planned, manual = false, onDone = ::next)
                                }

                                override fun onError(error: String) = next()
                            })
                        }

                        override fun onError(error: String?) = next()
                    })
                }
                next()
            }

            override fun onError(error: String) = Unit
        })
    }

    /** Stops the running download. What it fetched is kept: the area can be resumed or deleted. */
    fun stop() {
        val region = active ?: return
        active = null
        progress = null
        region.setObserver(null)
        region.setDownloadState(OfflineRegion.STATE_INACTIVE)
        refresh()
    }

    fun delete(area: SavedArea) {
        area.region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() = refresh()

            override fun onError(error: String) {
                this@OfflineMaps.error = error
                refresh()
            }
        })
    }
}
