package scooper.util

/** Structured copy rendered via [render] at composition time, so a language switch
 *  re-renders the text already stored in state (Settings.kt rule). */
data class LocalizedText(
    val msgid: String? = null,
    val args: List<Pair<String, String>> = emptyList(),
    val raw: String? = null,
) {
    /** Resolved string; empty when neither a msgid nor raw text is set. */
    fun render(): String = msgid?.let { tr(it, *args.toTypedArray()) } ?: raw.orEmpty()
}
