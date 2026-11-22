package io.github.usernamealreadytakensht.cameras

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressCodecTest {
    private val originLat = 48.869
    private val originLon = 2.331
    private val street = listOf(
        HouseNumber(12, "bis", 48.86901, 2.33201),
        HouseNumber(1, "", 48.86800, 2.33100),
        HouseNumber(12, "", 48.86900, 2.33200),
        HouseNumber(250, "", 48.87100, 2.32900),
    )
    private val blob = AddressCodec.encode(street, originLat, originLon)

    private fun find(number: Int, suffix: String = "") = AddressCodec.find(blob, originLat, originLon, number, suffix)

    private fun assertPoint(lat: Double, lon: Double, point: GeoPoint?) {
        assertEquals(lat, point!!.lat, 1e-6)
        assertEquals(lon, point.lon, 1e-6)
    }

    @Test
    fun findsEachNumberBack() {
        assertPoint(48.86800, 2.33100, find(1))
        assertPoint(48.86900, 2.33200, find(12))
        assertPoint(48.87100, 2.32900, find(250))
    }

    @Test
    fun tellsSuffixesApart() {
        assertPoint(48.86901, 2.33201, find(12, "bis"))
        assertPoint(48.86901, 2.33201, find(12, "BIS"))
        // An unknown suffix falls back on the bare number.
        assertPoint(48.86900, 2.33200, find(12, "ter"))
    }

    @Test
    fun unknownNumber() {
        assertNull(find(7))
        assertNull(find(999))
        assertNull(AddressCodec.find(ByteArray(0), 0.0, 0.0, 1, ""))
    }

    @Test
    fun staysCompact() {
        // A difference of numbers, an empty suffix and two small offsets: under 7 bytes an address.
        val many = List(1000) { HouseNumber(it + 1, "", 48.86 + it * 1e-5, 2.33 + it * 1e-5) }
        val size = AddressCodec.encode(many, 48.865, 2.335).size
        assertTrue("too large: $size bytes", size < 7000)
    }

    @Test
    fun splitsCsvLines() {
        assertEquals(listOf("a", "", "c"), AddressCodec.splitCsv("a;;c"))
        assertEquals(listOf("a", "b;c", "d"), AddressCodec.splitCsv("a;\"b;c\";d"))
        assertEquals(listOf("say \"hi\"", ""), AddressCodec.splitCsv("\"say \"\"hi\"\"\";"))
    }

    @Test
    fun readsQueries() {
        assertEquals(
            AddressQuery("rue* de* la* paix* paris*", 12, "bis"),
            AddressCodec.query("12 bis rue de la Paix, Paris"),
        )
        assertEquals(AddressQuery("rue* de* rivoli*", 10, ""), AddressCodec.query("10 rue de Rivoli"))
        // One-letter words are dropped; a postcode stays a word; a lone number is not a house number.
        assertEquals(AddressQuery("place* de* église*", null, ""), AddressCodec.query("place de l'Église"))
        assertEquals(AddressQuery("75011*", null, ""), AddressCodec.query("75011"))
        assertEquals(AddressQuery("mamoudzou*", null, ""), AddressCodec.query("Mamoudzou"))
        // With nothing after it, "bis" is the word searched, not a suffix.
        assertEquals(AddressQuery("bis*", 3, ""), AddressCodec.query("3 bis"))
        assertNull(AddressCodec.query("  "))
        assertNull(AddressCodec.query("a b"))
    }
}
