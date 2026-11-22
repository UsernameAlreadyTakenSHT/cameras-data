package io.github.usernamealreadytakensht.cameras

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreetPhotosTest {
    @Test
    fun readsAPanoramaxAnswer() {
        // Shape of https://api.panoramax.xyz/api/search?bbox=…, trimmed.
        val json = """
            {"features": [
              {"id": "afd9aa9b-6737", "geometry": {"type": "Point", "coordinates": [2.294545, 48.858318]},
               "properties": {"datetime": "2025-10-10T08:47:23.022000+00:00", "license": "etalab-2.0"},
               "providers": [{"name": "someone", "roles": ["producer"]}],
               "assets": {"hd": {"href": "https://storage.example/hd.jpg"},
                          "sd": {"href": "https://storage.example/sd.jpg"},
                          "thumb": {"href": "https://storage.example/thumb.jpg"}}},
              {"id": "no-image", "geometry": {"type": "Point", "coordinates": [2.29, 48.85]}, "assets": {}}
            ], "links": []}
        """.trimIndent()
        assertEquals(
            listOf(
                StreetPhoto(
                    "https://storage.example/sd.jpg", 48.858318, 2.294545, "2025-10-10",
                    "someone", "etalab-2.0", "https://api.panoramax.xyz/#focus=pic&pic=afd9aa9b-6737",
                ),
            ),
            StreetPhotos.parse(json),
        )
        assertEquals(emptyList<StreetPhoto>(), StreetPhotos.parse("""{"features": []}"""))
    }

    @Test
    fun picksTheClosestPhoto() {
        fun photo(lat: Double, lon: Double) = StreetPhoto("u", lat, lon, "", "", "", "")
        val point = GeoPoint(48.8584, 2.2945)
        val near = photo(48.85842, 2.29452)
        assertEquals(near, StreetPhotos.nearest(listOf(photo(48.8590, 2.2945), near, photo(48.8584, 2.2955)), point))
        assertNull(StreetPhotos.nearest(emptyList(), point))
    }

    @Test
    fun searchesASquareAroundThePoint() {
        val box = StreetPhotos.box(GeoPoint(48.8584, 2.2945), 50)
        // 50 m is 0.00045° of latitude, and 0.00068° of longitude at this latitude.
        assertEquals(0.00045, 48.8584 - box.south, 0.00001)
        assertEquals(0.00045, box.north - 48.8584, 0.00001)
        assertEquals(0.00068, box.east - 2.2945, 0.00001)
        assertEquals(0.00068, 2.2945 - box.west, 0.00001)
    }

    @Test
    fun linksToTheSamePlaceInOtherServices() {
        // The decimal point stays a point whatever the phone's language.
        val point = GeoPoint(48.8584, -2.2945)
        assertEquals("https://www.mapillary.com/app/?lat=48.858400&lng=-2.294500&z=17", ExternalMap.MAPILLARY.url(point))
        assertEquals(
            "https://www.google.com/maps/@?api=1&map_action=pano&viewpoint=48.858400,-2.294500",
            ExternalMap.GOOGLE_STREET_VIEW.url(point),
        )
        assertEquals(
            "https://www.google.com/maps/search/?api=1&query=48.858400,-2.294500",
            ExternalMap.GOOGLE_MAPS.url(point),
        )
        assertEquals("https://maps.apple.com/?ll=48.858400,-2.294500&q=48.858400,-2.294500", ExternalMap.APPLE_MAPS.url(point))
    }
}
