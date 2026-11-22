package io.github.usernamealreadytakensht.cameras

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadPlanTest {
    // Tiles covering an area at zooms 0..6: each zoom has four times the tiles of the previous one.
    private val tiles = intArrayOf(1, 4, 16, 64, 256, 1024, 4096)

    @Test
    fun stopsAtTheDeepestZoomFittingTheBudget() {
        // Zooms 1..4 hold 4 + 16 + 64 + 256 = 340 tiles; zoom 5 would bring the total to 1364.
        val plan = planDownload(tiles, deepestZoom = 19, budget = 1000) { 10 }
        assertEquals(DownloadPlan(maxTileZoom = 4, tiles = 340, bytes = 3400), plan)
    }

    @Test
    fun neverGoesDeeperThanTheLayer() {
        val plan = planDownload(tiles, deepestZoom = 2, budget = 1_000_000) { 10 }
        assertEquals(DownloadPlan(maxTileZoom = 2, tiles = 20, bytes = 200), plan)
    }

    @Test
    fun stopsWhereTheCountsStop() {
        val plan = planDownload(tiles, deepestZoom = 19, budget = 1_000_000) { 1 }
        assertEquals(6, plan.maxTileZoom)
        assertEquals(5460L, plan.tiles)
    }

    @Test
    fun weighsEachZoomWithItsOwnTileSize() {
        val plan = planDownload(tiles, deepestZoom = 2, budget = 1_000_000) { zoom -> zoom * 100L }
        // 4 tiles of 100 bytes at zoom 1, 16 tiles of 200 bytes at zoom 2.
        assertEquals(3600L, plan.bytes)
    }

    @Test
    fun alwaysKeepsTheFirstZoom() {
        val plan = planDownload(tiles, deepestZoom = 19, budget = 1) { 10 }
        assertEquals(DownloadPlan(maxTileZoom = 1, tiles = 4, bytes = 40), plan)
    }
}
