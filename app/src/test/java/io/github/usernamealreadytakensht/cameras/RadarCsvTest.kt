package io.github.usernamealreadytakensht.cameras

import java.io.IOException
import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RadarCsvTest {
    // First lines of the December 2025 file, with its stray spaces in the header.
    private val sample = """
        Numéro de radar;Type de radar;Date de mise en service;VMA ;Latitude; Longitude
        12001;ETD;31/10/2011 00:00;70;+45.361;+4.2526
        40123;ETFR;02/06/2015 00:00;NA;+48.8566;+2.3522
        60004;ETU;12/11/2023 14:00;50;-21.370958;+55.8006
    """.trimIndent().replace("\n", "\r\n")

    @Test
    fun readsTheOfficialLayout() {
        val radars = RadarCsv.parse(sample)
        assertEquals(3, radars.size)
        assertEquals(Radar("12001", "ETD", "31/10/2011", 70, 45.361, 4.2526), radars[0])
        // "NA": a red-light camera has no speed limit.
        assertEquals(Radar("40123", "ETFR", "02/06/2015", null, 48.8566, 2.3522), radars[1])
        assertEquals(Radar("60004", "ETU", "12/11/2023", 50, -21.370958, 55.8006), radars[2])
    }

    @Test
    fun decodesWindows1252AsWellAsUtf8() {
        val windows = sample.toByteArray(Charset.forName("windows-1252"))
        val utf8 = ("﻿" + sample).toByteArray(Charsets.UTF_8)
        assertEquals(sample, RadarCsv.decode(windows))
        assertEquals(sample, RadarCsv.decode(utf8))
        assertEquals(3, RadarCsv.parse(RadarCsv.decode(windows)).size)
    }

    @Test
    fun findsColumnsInAnyOrder() {
        val radars = RadarCsv.parse("LONGITUDE;LATITUDE;type\n2.35;48.85;ETF\n")
        assertEquals(listOf(Radar("", "ETF", "", null, 48.85, 2.35)), radars)
    }

    @Test
    fun skipsLinesWithoutAPosition() {
        val radars = RadarCsv.parse("Numéro;Latitude;Longitude\n1;;\n2;abc;2.0\n3;95.0;2.0\n4;48.0;2.0\n\n")
        assertEquals(listOf("4"), radars.map { it.id })
    }

    @Test
    fun refusesAFileWithoutPositions() {
        assertThrows(IOException::class.java) { RadarCsv.parse("a;b;c\n1;2;3\n") }
        assertThrows(IOException::class.java) { RadarCsv.parse("") }
    }
}
