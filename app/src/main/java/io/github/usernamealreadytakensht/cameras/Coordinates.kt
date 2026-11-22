package io.github.usernamealreadytakensht.cameras

/** A position in decimal degrees (WGS 84). */
data class GeoPoint(val lat: Double, val lon: Double)

/** Recognises a position typed by hand: coordinates in the usual notations, or a geohash. */
object CoordinateParser {
    private const val DOT_NUMBER = """\d+(?:\.\d+)?"""
    private const val ANY_NUMBER = """\d+(?:[.,]\d+)?"""

    /** One coordinate: degrees, optional minutes and seconds, a hemisphere letter before or after. */
    private fun component(number: String) =
        """([NSEWO])?\s*([+-]?$number)\s*°?\s*(?:($number)\s*['′’]\s*(?:($number)\s*(?:"|″|”|'')?\s*)?)?([NSEWO])?"""

    private fun pair(number: String) =
        Regex("""^\s*${component(number)}\s*[,;\s]\s*${component(number)}\s*$""", RegexOption.IGNORE_CASE)

    // "48.8566,2.3522" needs the comma as a separator, "48,8566 2,3522" as a decimal mark: the
    // dot-only reading is tried first.
    private val dotPair = pair(DOT_NUMBER)
    private val anyPair = pair(ANY_NUMBER)

    /**
     * Reads "48.8566, 2.3522", "48,8566 2,3522", "48.8566N 2.3522E", "48°51'24"N 2°21'08"E",
     * "48°51.4' 2°21.1'"… Latitude comes first unless the hemisphere letters (N S E W, O for
     * "ouest") say otherwise. Two bare integers are not taken for coordinates.
     */
    fun parse(text: String): GeoPoint? {
        val match = dotPair.matchEntire(text) ?: anyPair.matchEntire(text) ?: return null
        val g = match.groupValues
        val first = Part(g[1].ifEmpty { g[5] }, g[2], g[3], g[4])
        val second = Part(g[6].ifEmpty { g[10] }, g[7], g[8], g[9])
        if (!first.explicit && !second.explicit) return null
        val a = first.value() ?: return null
        val b = second.value() ?: return null
        val latitudeFirst = when {
            first.hemisphere in "NS" && first.hemisphere.isNotEmpty() -> true
            first.hemisphere.isNotEmpty() -> false
            second.hemisphere in "NS" && second.hemisphere.isNotEmpty() -> false
            else -> true
        }
        val point = if (latitudeFirst) GeoPoint(a, b) else GeoPoint(b, a)
        return point.takeIf { it.lat in -90.0..90.0 && it.lon in -180.0..180.0 }
    }

    private class Part(hemisphere: String, val degrees: String, val minutes: String, val seconds: String) {
        val hemisphere = hemisphere.uppercase()

        /** True when the text cannot be a mere integer, such as a house number. */
        val explicit: Boolean
            get() = hemisphere.isNotEmpty() || minutes.isNotEmpty() || '.' in degrees || ',' in degrees

        fun value(): Double? {
            val d = number(degrees)
            val m = if (minutes.isEmpty()) 0.0 else number(minutes)
            val s = if (seconds.isEmpty()) 0.0 else number(seconds)
            if (m >= 60 || s >= 60) return null
            val magnitude = kotlin.math.abs(d) + m / 60 + s / 3600
            val negative = degrees.startsWith("-") || hemisphere in "SWO" && hemisphere.isNotEmpty()
            return if (negative) -magnitude else magnitude
        }

        private fun number(text: String) = text.replace(',', '.').toDouble()
    }

    private const val GEOHASH_ALPHABET = "0123456789bcdefghjkmnpqrstuvwxyz"

    /**
     * Centre of the cell named by a geohash such as "u09tvw0f6" (https://en.wikipedia.org/wiki/Geohash).
     * Place names made only of geohash letters ("brest") and postcodes are left alone: a geohash is
     * accepted with 5 to 12 characters, at least one letter, and either a digit or 8 characters.
     */
    fun geohash(text: String): GeoPoint? {
        val hash = text.trim().lowercase()
        if (hash.length !in 5..12 || hash.any { it !in GEOHASH_ALPHABET }) return null
        if (hash.none(Char::isLetter) || hash.none(Char::isDigit) && hash.length < 8) return null
        var south = -90.0
        var north = 90.0
        var west = -180.0
        var east = 180.0
        var longitudeBit = true
        for (char in hash) {
            val value = GEOHASH_ALPHABET.indexOf(char)
            for (bit in 4 downTo 0) {
                val upper = value shr bit and 1 == 1
                if (longitudeBit) {
                    val middle = (west + east) / 2
                    if (upper) west = middle else east = middle
                } else {
                    val middle = (south + north) / 2
                    if (upper) south = middle else north = middle
                }
                longitudeBit = !longitudeBit
            }
        }
        return GeoPoint((south + north) / 2, (west + east) / 2)
    }
}
