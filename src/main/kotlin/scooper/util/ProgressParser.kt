package scooper.util

/**
 * Parses download progress from Scoop output.
 *
 * Primarily supports aria2 output format:
 * ```
 * [#a1b2c3 1.2MiB/5.4MiB(22%) CN:1 DL:800KiB ETA:3s]
 * ```
 *
 * Also supports scoop download output format:
 * ```
 * Downloading: xxx (1.2 MB / 5.4 MB)...
 * ```
 */
object ProgressParser {

    /**
     * aria2 progress format:
     *   [#a1b2c3 1.2MiB/5.4MiB(22%) CN:1 DL:800KiB ETA:3s]
     *   [#a1b2c3 0B/5.4MiB(0%) CN:1 DL:0B]
     *   [#a1b2c3 5.4MiB/5.4MiB(98%) CN:1 DL:0B]
     */
    private val aria2Pattern = Regex("""\[#\w+\s+\S+/.+?\((\d+)%\)""")

    /**
     * Attempts to parse download progress percentage from a line of output.
     * @return progress value 0..100, or null if not parseable
     */
    fun parseProgress(line: String): Int? {
        return parseAria2Progress(line)
    }

    private fun parseAria2Progress(line: String): Int? {
        return aria2Pattern.find(line)?.groupValues?.get(1)?.toIntOrNull()
    }
}
