package io.github.usernamealreadytakensht.cameras

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One fixed speed or red-light camera of the official list.
 *
 * @param type code of the list: ETF, ETD, ETT, ETU, ETVM, ETFR, ETPN.
 * @param since day it came into service, as written in the list ("31/10/2011").
 * @param speedLimit in km/h; null for the cameras that do not check speed.
 */
data class Radar(
    val id: String,
    val type: String,
    val since: String,
    val speedLimit: Int?,
    val lat: Double,
    val lon: Double,
)

/** Wording of the dataset for each type of camera. */
@StringRes
fun radarTypeLabel(type: String): Int = when (type.uppercase()) {
    "ETF" -> R.string.radar_type_etf
    "ETD" -> R.string.radar_type_etd
    "ETT" -> R.string.radar_type_ett
    "ETU" -> R.string.radar_type_etu
    "ETVM" -> R.string.radar_type_etvm
    "ETFR" -> R.string.radar_type_etfr
    "ETPN" -> R.string.radar_type_etpn
    else -> R.string.radar_type_other
}

/** Reading of the list's CSV file, free of any Android class. */
object RadarCsv {
    /**
     * The ministry's file is announced as UTF-8 but written in Windows-1252: UTF-8 is tried
     * strictly, then Windows-1252.
     */
    fun decode(bytes: ByteArray): String {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
        return text.removePrefix("﻿")
    }

    /**
     * Columns are found by name, whatever their order, spacing or case: "Numéro de radar",
     * "Type de radar", "Date de mise en service", "VMA", "Latitude", "Longitude". Lines without
     * a position are skipped.
     *
     * @throws IOException when the position columns are missing.
     */
    fun parse(text: String): List<Radar> {
        val lines = text.lineSequence().filter { it.isNotBlank() }.iterator()
        if (!lines.hasNext()) throw IOException("empty file")
        val header = AddressCodec.splitCsv(lines.next()).map { it.trim().lowercase() }
        fun column(word: String) = header.indexOfFirst { word in it }
        val iLat = column("latitude")
        val iLon = column("longitude")
        if (iLat < 0 || iLon < 0) throw IOException("position columns missing")
        val iId = column("num")
        val iType = column("type")
        val iDate = column("date")
        val iSpeed = column("vma")

        val radars = ArrayList<Radar>()
        for (line in lines) {
            val fields = AddressCodec.splitCsv(line).map { it.trim() }
            val lat = fields.getOrNull(iLat)?.toDoubleOrNull() ?: continue
            val lon = fields.getOrNull(iLon)?.toDoubleOrNull() ?: continue
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
            radars.add(
                Radar(
                    id = fields.getOrNull(iId).orEmpty(),
                    type = fields.getOrNull(iType).orEmpty(),
                    // "31/10/2011 00:00": the time of day is of no use.
                    since = fields.getOrNull(iDate).orEmpty().substringBefore(' '),
                    speedLimit = fields.getOrNull(iSpeed)?.toIntOrNull(),
                    lat = lat,
                    lon = lon,
                ),
            )
        }
        return radars
    }
}

/**
 * The official list of fixed cameras ("Liste des radars fixes en France", ministère de
 * l'Intérieur, Licence Ouverte 2.0): bundled in the app, renewed by the monthly update.
 */
class RadarStore(private val files: DataFiles) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Empty for the instant the list takes to be read. */
    var radars by mutableStateOf<List<Radar>>(emptyList())
        private set

    /** Day the list shown was published, as "30/12/2025". */
    var date by mutableStateOf("")
        private set

    init {
        reload()
    }

    /** Reads the list in use again: called once the monthly update has brought a newer one. */
    fun reload() {
        scope.launch {
            val list = try {
                RadarCsv.parse(RadarCsv.decode(files.open(DataKind.RADARS).use { it.readBytes() }))
            } catch (_: IOException) {
                emptyList()
            }
            val published = files.version(DataKind.RADARS).displayDate
            withContext(Dispatchers.Main) {
                radars = list
                date = published
            }
        }
    }
}
