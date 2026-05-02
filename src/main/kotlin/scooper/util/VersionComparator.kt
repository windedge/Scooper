package scooper.util

/**
 * Natural version string comparator for Scoop manifest versions.
 *
 * Splits version strings into alternating digit / non-digit segments,
 * then compares digit segments numerically and non-digit segments lexicographically.
 *
 * Examples:
 * - `10.0.0` > `9.16.3`  (10 > 9 numerically)
 * - `1.2.10` > `1.2.9`
 * - `2.0.0` > `2.0.0-beta`  (release > pre-release)
 */
object VersionComparator : Comparator<String> {

    private val SEGMENT_REGEX = """(\d+|\D+)""".toRegex()

    override fun compare(a: String, b: String): Int {
        val segsA = SEGMENT_REGEX.findAll(a).map { it.value }.toList()
        val segsB = SEGMENT_REGEX.findAll(b).map { it.value }.toList()

        val maxLen = maxOf(segsA.size, segsB.size)
        for (i in 0 until maxLen) {
            val sa = segsA.getOrNull(i)
            val sb = segsB.getOrNull(i)

            // One string ran out of segments
            if (sa == null && sb == null) return 0
            if (sa == null) {
                // a is a prefix of b, e.g. a="2.0.0" vs b="2.0.0-beta".
                // b's remaining segments might be just dots (equal) or extra content.
                // Extra numeric content (".1") means b > a.
                // Extra non-numeric content ("-beta") means a > b (release > pre-release).
                val remaining = segsB.subList(i, segsB.size)
                val firstNonDot = remaining.firstOrNull { it.any { c -> c != '.' } }
                return when {
                    firstNonDot == null -> 0
                    firstNonDot.all { it.isDigit() } -> -1  // b has extra numeric segment → b > a
                    else -> 1  // b has pre-release suffix → a > b
                }
            }
            if (sb == null) {
                val remaining = segsA.subList(i, segsA.size)
                val firstNonDot = remaining.firstOrNull { it.any { c -> c != '.' } }
                return when {
                    firstNonDot == null -> 0
                    firstNonDot.all { it.isDigit() } -> 1   // a has extra numeric segment → a > b
                    else -> -1  // a has pre-release suffix → b > a
                }
            }

            val aIsNum = sa.all { it.isDigit() }
            val bIsNum = sb.all { it.isDigit() }

            val cmp = when {
                aIsNum && bIsNum -> {
                    sa.toLong().compareTo(sb.toLong())
                }
                aIsNum -> 1   // digit > non-digit (release > pre-release suffix)
                bIsNum -> -1
                else -> sa.compareTo(sb, ignoreCase = true)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }
}

/** Sort a list of version strings in natural descending order. */
fun List<String>.sortedByVersionDesc(): List<String> =
    sortedWith(Comparator { a, b -> VersionComparator.compare(b, a) })
