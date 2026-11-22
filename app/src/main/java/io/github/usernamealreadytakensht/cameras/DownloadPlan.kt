package io.github.usernamealreadytakensht.cameras

/** What one offline download fetches: every tile zoom from 1 to [maxTileZoom]. */
data class DownloadPlan(val maxTileZoom: Int, val tiles: Long, val bytes: Long)

/** Most tiles one download may fetch. It sets the detail reached: the larger the area, the coarser. */
const val MAX_TILES_PER_DOWNLOAD = 70_000L

/**
 * Picks the deepest zoom whose tiles, added to those of all shallower zooms, fit in [budget].
 *
 * @param tilesPerZoom number of tiles covering the area at each zoom, indexed by zoom. Zoom 0 is
 *   never downloaded (the offline regions start at tile zoom 1).
 * @param deepestZoom deepest zoom the layer holds.
 */
fun planDownload(
    tilesPerZoom: IntArray,
    deepestZoom: Int,
    budget: Long,
    bytesPerTile: (zoom: Int) -> Long,
): DownloadPlan {
    var zoom = 0
    var tiles = 0L
    var bytes = 0L
    for (z in 1..minOf(deepestZoom, tilesPerZoom.lastIndex)) {
        val count = tilesPerZoom[z].toLong()
        // The first zoom is always taken, whatever the budget: a plan is never empty.
        if (z > 1 && tiles + count > budget) break
        zoom = z
        tiles += count
        bytes += count * bytesPerTile(z)
    }
    return DownloadPlan(zoom, tiles, bytes)
}
