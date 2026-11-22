package io.github.usernamealreadytakensht.cameras

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MapLayerTest {
    @Test
    fun styleWithoutLocalFiles() {
        val style = JSONObject(MapLayer.PLAN.styleJson())
        assertEquals(listOf("background", "base"), layers(style))
    }

    @Test
    fun localFilesAreDrawnUnderTheOnlineTiles() {
        val files = listOf(File("/data/maps/plan-FR.pmtiles"), File("/data/maps/plan-53.pmtiles"))
        val style = JSONObject(MapLayer.PLAN.styleJson(files))
        assertEquals(listOf("background", "local0", "local1", "base"), layers(style))
        val source = style.getJSONObject("sources").getJSONObject("local0")
        assertEquals("raster", source.getString("type"))
        // On Windows the test path gains a drive letter: only the end is compared.
        assertEquals(true, source.getString("url").startsWith("pmtiles://file://"))
        assertEquals(true, source.getString("url").endsWith("/data/maps/plan-FR.pmtiles"))
    }

    private fun layers(style: JSONObject): List<String> {
        val layers = style.getJSONArray("layers")
        return (0 until layers.length()).map { layers.getJSONObject(it).getString("id") }
    }
}
