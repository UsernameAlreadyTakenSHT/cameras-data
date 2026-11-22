package io.github.usernamealreadytakensht.cameras

import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** One numbered address of a street. [suffix] is "bis", "ter", "a"… or empty. */
data class HouseNumber(val number: Int, val suffix: String, val lat: Double, val lon: Double)

/** A search typed by the user, split for the offline address index. */
data class AddressQuery(val match: String, val number: Int?, val suffix: String)

/** Storage format and query syntax of the offline address index, free of any Android class. */
object AddressCodec {
    /** Offsets are kept in hundred-thousandths of a degree: about one metre. */
    private const val UNIT = 100_000.0
    private val suffixes = setOf("bis", "ter", "quater", "a", "b", "c", "d")

    /**
     * Packs the numbers of one street, in rising order: for each, the difference with the number
     * before, its suffix and its offset from the street's own position. About 6 bytes an address.
     * The bundled database is packed the same way by `tools/build_data.py`: keep the two in step.
     */
    fun encode(numbers: List<HouseNumber>, originLat: Double, originLon: Double): ByteArray {
        val out = ByteArrayOutputStream(numbers.size * 8)
        var previous = 0
        for (n in numbers.sortedWith(compareBy({ it.number }, { it.suffix }))) {
            out.varint((n.number - previous).toLong())
            previous = n.number
            val suffix = n.suffix.toByteArray(Charsets.UTF_8)
            out.write(suffix.size.coerceAtMost(255))
            out.write(suffix, 0, suffix.size.coerceAtMost(255))
            out.varint(zigzag(((n.lat - originLat) * UNIT).roundToInt().toLong()))
            out.varint(zigzag(((n.lon - originLon) * UNIT).roundToInt().toLong()))
        }
        return out.toByteArray()
    }

    /** Position of [number] in a street packed by [encode]: the exact suffix if it exists, else the bare number. */
    fun find(blob: ByteArray, originLat: Double, originLon: Double, number: Int, suffix: String): GeoPoint? {
        var position = 0
        var fallback: GeoPoint? = null
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val byte = blob[position++].toInt()
                result = result or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
            }
        }
        var n = 0
        while (position < blob.size) {
            n += varint().toInt()
            val length = blob[position++].toInt() and 0xFF
            val s = String(blob, position, length, Charsets.UTF_8)
            position += length
            val lat = originLat + unzigzag(varint()) / UNIT
            val lon = originLon + unzigzag(varint()) / UNIT
            if (n == number) {
                if (s.equals(suffix, ignoreCase = true)) return GeoPoint(lat, lon)
                if (fallback == null) fallback = GeoPoint(lat, lon)
            } else if (n > number) {
                break
            }
        }
        return fallback
    }

    private fun ByteArrayOutputStream.varint(value: Long) {
        var rest = value
        while (rest and 0x7FL.inv() != 0L) {
            write((rest and 0x7F or 0x80).toInt())
            rest = rest ushr 7
        }
        write(rest.toInt())
    }

    private fun zigzag(value: Long) = value shl 1 xor (value shr 63)
    private fun unzigzag(value: Long) = value ushr 1 xor -(value and 1)

    /** Fields of one line of a `;`-separated CSV file, with double-quoted fields unwrapped. */
    fun splitCsv(line: String): List<String> {
        if ('"' !in line) return line.split(';')
        val fields = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    field.append('"')
                    i++
                }
                c == '"' -> quoted = !quoted
                c == ';' && !quoted -> {
                    fields.add(field.toString())
                    field.setLength(0)
                }
                else -> field.append(c)
            }
            i++
        }
        fields.add(field.toString())
        return fields
    }

    /**
     * Turns "12 bis rue de la Paix, Paris" into the full-text query `rue* de* la* paix* paris*`
     * and the house number 12 bis. A leading number is a house number only when words follow;
     * one-letter words ("l'", "d'") are dropped, they match nearly everything. Null when nothing
     * is left to search.
     */
    fun query(text: String): AddressQuery? {
        val words = text.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }.toMutableList()
        var number: Int? = null
        var suffix = ""
        if (words.size >= 2 && words[0].length <= 4 && words[0].all(Char::isDigit)) {
            number = words.removeAt(0).toInt()
            if (words.size >= 2 && words[0] in suffixes) suffix = words.removeAt(0)
        }
        val terms = words.filter { it.length >= 2 }
        if (terms.isEmpty()) return null
        // Lower case keeps the words clear of the AND / OR / NOT / NEAR operators.
        return AddressQuery(terms.joinToString(" ") { "$it*" }, number, suffix)
    }
}
