/*
 * SpeedyCAT: display-only formatting for topic labels (canonical topic_tags in
 * JSON/DB stay as-authored).
 */
package com.ichi2.anki.practice

private val TOPIC_ACRONYMS =
    setOf(
        "MCAT",
        "DNA",
        "RNA",
        "mRNA",
        "tRNA",
        "rRNA",
        "ATP",
        "ADP",
        "NAD",
        "FAD",
        "GDP",
        "GTP",
        "PCR",
        "AAMC",
        "pH",
        "pKa",
        "pKb",
        "UV",
        "IR",
        "NMR",
        "HIV",
        "AIDS",
        "CNS",
        "PNS",
    )

private fun formatTopicWord(word: String): String {
    if (word.isEmpty()) return ""
    val upper = word.uppercase()
    for (acr in TOPIC_ACRONYMS) {
        if (upper == acr.uppercase()) return acr
    }
    return word.lowercase().replaceFirstChar { it.titlecase() }
}

/**
 * Title-case a topic label for UI display. Splits on whitespace, `-`, and `_`.
 */
fun formatTopicLabel(raw: String): String =
    raw
        .trim()
        .split(Regex("""[\s_-]+"""))
        .filter { it.isNotEmpty() }
        .joinToString(" ") { formatTopicWord(it) }
