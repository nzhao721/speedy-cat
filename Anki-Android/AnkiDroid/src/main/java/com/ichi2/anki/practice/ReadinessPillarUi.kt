// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors
//
// PRESENTATION-ONLY model for the three readiness pillars (Memory / Performance
// / Readiness).
//
// Every pillar VALUE, RANGE, sample size, named source and give-up decision is
// computed by the shared Anki Rust engine (`PracticeService.GetReadiness` in
// anki/rslib) and read over protobuf via [ReadinessBackend]. This file holds
// ONLY the UI data class and pure formatting helpers — there are deliberately no
// readiness/statistics formulas here (they used to live in the deleted
// `ReadinessLogic.kt`, which duplicated rslib). Keep it that way: if you need a
// new number, add it to the Rust engine and surface it through the proto.

package com.ichi2.anki.practice

import kotlin.math.roundToInt

/** A closed numeric interval, both ends as fractions in [0, 1]. */
data class ConfidenceInterval(
    val low: Double,
    val high: Double,
)

/** Caption shown under every pillar's numeric range. */
internal const val CI_CAPTION = "95% confidence interval (\u00b11.96\u00b7SE)"

/** Format a [0, 1] fraction as a rounded percentage, e.g. 0.42 -> "42%". */
fun formatPercent(fraction: Double): String = "${(fraction * 100).roundToInt()}%"

/** Format a confidence interval as "low%–high%". */
fun formatPercentRange(ci: ConfidenceInterval): String = "${formatPercent(ci.low)}\u2013${formatPercent(ci.high)}"

/**
 * Memory pillar sample line when studied and collection totals are known, e.g.
 * "42 out of 10,287 flashcards studied". Pure formatting of lifetime studied
 * count + collection card count (not today's review count).
 */
fun memoryStudiedLine(
    studied: Int,
    total: Int,
    soFar: Boolean = false,
): String {
    val studiedText =
        java.text.NumberFormat
            .getIntegerInstance()
            .format(studied)
    val totalText =
        java.text.NumberFormat
            .getIntegerInstance()
            .format(total)
    val base = "$studiedText out of $totalText flashcards studied"
    return if (soFar) "$base so far" else base
}

private val FULL_LENGTH_LABEL_NUMBER =
    Regex("""(?i)(?:full[- ]?length|(?:^|[-_])fl)[-_ ]*(\d+)""")

/**
 * Short dashboard label for a completed full-length exam, e.g.
 * "speedycat-fl-1" → "Full-length 1". Falls back to title parsing, then
 * "Full-length test".
 */
fun formatFullLengthExamLabel(
    testId: String,
    title: String = "",
): String {
    FULL_LENGTH_LABEL_NUMBER.find(testId)?.groupValues?.getOrNull(1)?.let {
        return "Full-length $it"
    }
    FULL_LENGTH_LABEL_NUMBER.find(title)?.groupValues?.getOrNull(1)?.let {
        return "Full-length $it"
    }
    return "Full-length test"
}

/**
 * Per-exam accuracy lines for the Readiness pillar detail. These render the raw
 * per-test scores carried in the read-only full-length summaries synced from the
 * desktop app; the pillar's headline value/range still comes from the Rust
 * engine. Formatting only — no scoring decisions here.
 */
fun readinessExamLines(
    summaries: List<FullLengthSummary>,
    maxExams: Int = 3,
): List<String> =
    summaries
        .filter { it.totalQuestions > 0 }
        .sortedByDescending { it.completedAt }
        .take(maxExams)
        .map { summary ->
            val title = formatFullLengthExamLabel(summary.testId, summary.title)
            val accuracy = formatPercent(summary.totalCorrect.toDouble() / summary.totalQuestions)
            "$title: $accuracy"
        }

/** String resource for a pillar's one-sentence score explanation (UI copy only). */
fun pillarTooltipResId(name: String): Int? =
    when (name) {
        "Memory" -> com.ichi2.anki.R.string.speedycat_pillar_memory_tooltip
        "Performance" -> com.ichi2.anki.R.string.speedycat_pillar_performance_tooltip
        "Readiness" -> com.ichi2.anki.R.string.speedycat_pillar_readiness_tooltip
        else -> null
    }

/** Value shown in breakdown tables when the Rust engine gives up on a row. */
internal const val PILLAR_BREAKDOWN_INSUFFICIENT_VALUE = "N/A"

/** A section row in a pillar's expandable breakdown (display only). */
data class PillarBreakdownRow(
    val label: String,
    val value: String,
    val sufficient: Boolean,
)

/** Section + topic tables for one pillar dropdown (topics: Performance only). */
data class PillarBreakdowns(
    val sections: List<PillarBreakdownRow>,
    val topics: List<PillarBreakdownRow> = emptyList(),
)

/**
 * A fully-formatted pillar ready to render: a value, an explicit range, the
 * named source, detail lines, and — when there isn't enough data — the give-up
 * message ([insufficientReason]). Populated from the Rust `GetReadiness`
 * response in [ReadinessBackend]. The UI never shows a bare point estimate as
 * the headline: when [sufficient] it shows [range] prominently; otherwise it
 * shows the give-up message.
 */
data class ReadinessPillar(
    val name: String,
    val sufficient: Boolean,
    val value: String,
    val range: String,
    val rangeCaption: String,
    val source: String,
    val detail: List<String>,
    val insufficientReason: String,
    /** Number of observations the pillar's value was computed from (0 when unknown). */
    val sampleSize: Int = 0,
    val breakdown: PillarBreakdowns? = null,
)

/** Per-section line in the projected MCAT score card (display only). */
data class ProjectedSectionLine(
    val sectionLabel: String,
    val scaledRange: String,
    val rawLine: String,
    /** Point estimate on the 118–132 section scale (for the range-bar marker). */
    val scaled: Int? = null,
    val scaledLow: Int? = null,
    val scaledHigh: Int? = null,
)

/**
 * Dashboard projected MCAT total (472–528) with per-section breakdown. All
 * numbers come from the Rust `GetReadiness` response — formatting only here.
 */
data class ProjectedMcatScore(
    val sufficient: Boolean,
    val totalRange: String,
    /** Point estimate on the 472–528 scale (for the range-bar marker). */
    val total: Int? = null,
    /** Lower bound of the projected total interval. */
    val totalLow: Int? = null,
    /** Upper bound of the projected total interval. */
    val totalHigh: Int? = null,
    val sections: List<ProjectedSectionLine>,
    val insufficientReason: String,
    val source: String,
)

/** MCAT total score scale endpoints (AAMC composite). */
internal const val MCAT_TOTAL_MIN = 472
internal const val MCAT_TOTAL_MAX = 528

/** MCAT per-section scaled score endpoints (AAMC). */
internal const val MCAT_SECTION_MIN = 118
internal const val MCAT_SECTION_MAX = 132

/** Left offset / width (0..1) for a range bar segment on a fixed MCAT scale. */
internal fun mcatScaledSpan(
    scaleMin: Int,
    scaleMax: Int,
    low: Int,
    high: Int,
): Pair<Float, Float> {
    val span = (scaleMax - scaleMin).toFloat()
    val lo = low.coerceIn(scaleMin, scaleMax).toFloat()
    val hi = high.coerceIn(scaleMin, scaleMax).toFloat()
    return Pair((lo - scaleMin) / span, (hi - lo) / span)
}

/** Marker position (0..1) for a score on a fixed MCAT scale. */
internal fun mcatScaledMarker(
    scaleMin: Int,
    scaleMax: Int,
    value: Int,
): Float {
    val span = (scaleMax - scaleMin).toFloat()
    val clamped = value.coerceIn(scaleMin, scaleMax).toFloat()
    return (clamped - scaleMin) / span
}

/** Left offset / width (0..1) for a projected-total range bar segment. */
internal fun mcatTotalSpan(
    low: Int,
    high: Int,
): Pair<Float, Float> = mcatScaledSpan(MCAT_TOTAL_MIN, MCAT_TOTAL_MAX, low, high)

/** Marker position (0..1) for a projected MCAT total on the range bar. */
internal fun mcatTotalMarker(total: Int): Float =
    mcatScaledMarker(MCAT_TOTAL_MIN, MCAT_TOTAL_MAX, total)
