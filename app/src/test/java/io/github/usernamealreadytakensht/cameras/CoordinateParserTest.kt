package io.github.usernamealreadytakensht.cameras

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoordinateParserTest {
    private fun assertPoint(lat: Double, lon: Double, text: String) {
        val point = CoordinateParser.parse(text) ?: throw AssertionError("not recognised: $text")
        assertEquals(text, lat, point.lat, 1e-4)
        assertEquals(text, lon, point.lon, 1e-4)
    }

    @Test
    fun decimalDegrees() {
        assertPoint(48.8566, 2.3522, "48.8566, 2.3522")
        assertPoint(48.8566, 2.3522, "48.8566,2.3522")
        assertPoint(48.8566, 2.3522, "  48.8566 2.3522 ")
        assertPoint(48.8566, 2.3522, "48.8566;2.3522")
        assertPoint(-12.7806, 45.2278, "-12.7806, 45.2278")
    }

    @Test
    fun decimalCommas() {
        assertPoint(48.8566, 2.3522, "48,8566 2,3522")
        assertPoint(48.8566, 2.3522, "48,8566, 2,3522")
        assertPoint(48.8566, 2.3522, "48,8566;2,3522")
    }

    @Test
    fun hemisphereLetters() {
        assertPoint(48.8566, 2.3522, "48.8566N 2.3522E")
        assertPoint(48.8566, 2.3522, "N48.8566 E2.3522")
        assertPoint(48.8566, 2.3522, "48.8566° N, 2.3522° E")
        assertPoint(-12.7806, 45.2278, "12.7806 S 45.2278 E")
        // "O" for "ouest", and the longitude given first.
        assertPoint(48.3904, -4.4861, "4.4861 O 48.3904 N")
        assertPoint(48.3904, -4.4861, "48.3904 N 4.4861 W")
    }

    @Test
    fun degreesMinutesSeconds() {
        assertPoint(48.8567, 2.3522, "48°51'24.1\"N 2°21'07.9\"E")
        assertPoint(48.8567, 2.3522, "48° 51' 24.1\" N, 2° 21' 7.9\" E")
        assertPoint(48.8567, 2.3518, "48°51.4' 2°21.11'")
        assertPoint(-21.1151, 55.5364, "21°06'54.4\"S 55°32'11\"E")
    }

    @Test
    fun whatIsNotAPosition() {
        assertNull(CoordinateParser.parse("12 34"))
        assertNull(CoordinateParser.parse("12,34"))
        assertNull(CoordinateParser.parse("12 rue de la Paix"))
        assertNull(CoordinateParser.parse("75011 Paris"))
        assertNull(CoordinateParser.parse("48.8566"))
        assertNull(CoordinateParser.parse("95.5, 2.35"))
        assertNull(CoordinateParser.parse("48°75' 2°21'"))
        assertNull(CoordinateParser.parse(""))
    }

    @Test
    fun geohash() {
        // Example of https://en.wikipedia.org/wiki/Geohash: "u4pruydqqvj" is 57.64911, 10.40744.
        val point = CoordinateParser.geohash("u4pruydqqvj")!!
        assertEquals(57.64911, point.lat, 1e-5)
        assertEquals(10.40744, point.lon, 1e-5)
        // Paris, upper case and surrounding spaces accepted.
        val paris = CoordinateParser.geohash(" U09TVW0 ")!!
        assertEquals(48.857, paris.lat, 0.005)
        assertEquals(2.352, paris.lon, 0.005)
    }

    @Test
    fun whatIsNotAGeohash() {
        assertNull(CoordinateParser.geohash("brest")) // only letters, too short to be sure
        assertNull(CoordinateParser.geohash("75011")) // a postcode
        assertNull(CoordinateParser.geohash("u09")) // too short
        assertNull(CoordinateParser.geohash("paris1")) // "a" and "i" are not geohash letters
        assertNull(CoordinateParser.geohash("u09tv u09tv"))
    }
}
