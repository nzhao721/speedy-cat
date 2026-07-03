// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors
//
// Pure, framework-free logic for the SpeedyCAT Readiness metric, computed
// natively on mobile:
//
//   * Memory      = mean FSRS retrievability over reviewed MCAT cards
//   * Performance = practice-question accuracy (+ time) from the local store
//
// Each pillar is reported as a value PLUS an explicit range (a 95% confidence
// interval, ±1.96·SE) PLUS an explicit give-up / insufficient-data rule, and a
// named, deterministic source. No AI is involved. This mirrors the desktop
// readiness computation so both apps report the metric the same way, and is
// unit-tested directly.

package com.ichi2.anki.practice

import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Minimum reviewed cards before a Memory estimate is trustworthy. */
const val MIN_REVIEWED_CARDS = 30

/**
 * Named source for the full-length scaled-score ESTIMATE. The scaled scores are
 * computed on the SpeedyCAT desktop app by a deterministic representative
 * raw→scaled conversion (anchored to AAMC's published scoring examples) and
 * arrive here read-only via the synced results file. Mirrors the desktop
 * `SCALED_SCORE_SOURCE` so both apps name the same source.
 */
const val SCALED_SCORE_SOURCE = "AAMC published scoring examples (students-residents.aamc.org)"

/** Minimum answered practice questions before a Performance estimate is shown. */
const val MIN_PRACTICE_ANSWERS = 30

/** The z value for a two-sided 95% confidence interval. */
private const val Z_95 = 1.96

/** A closed numeric interval, both ends in the metric's own units. */
data class ConfidenceInterval(
    val low: Double,
    val high: Double,
)

// ---- Statistics helpers ---------------------------------------------------

private fun mean(xs: List<Double>): Double = if (xs.isEmpty()) 0.0 else xs.sum() / xs.size

/** Sample standard deviation (Bessel-corrected); 0 for fewer than two points. */
private fun sampleSd(xs: List<Double>): Double {
    if (xs.size < 2) return 0.0
    val m = mean(xs)
    val sumSq = xs.sumOf { (it - m) * (it - m) }
    return sqrt(sumSq / (xs.size - 1))
}

/** 95% CI of a mean of values already bounded to 0..1 (clamped to 0..1). */
private fun meanCi01(xs: List<Double>): ConfidenceInterval {
    val m = mean(xs)
    val se = if (xs.isEmpty()) 0.0 else sampleSd(xs) / sqrt(xs.size.toDouble())
    return ConfidenceInterval((m - Z_95 * se).coerceIn(0.0, 1.0), (m + Z_95 * se).coerceIn(0.0, 1.0))
}

/** 95% Wald CI of a proportion [p] estimated from [n] trials (clamped to 0..1). */
private fun proportionCi(
    p: Double,
    n: Int,
): ConfidenceInterval {
    if (n <= 0) return ConfidenceInterval(0.0, 0.0)
    val se = sqrt(p * (1 - p) / n)
    return ConfidenceInterval((p - Z_95 * se).coerceIn(0.0, 1.0), (p + Z_95 * se).coerceIn(0.0, 1.0))
}

// ---- Formatting -----------------------------------------------------------

fun formatPercent(fraction: Double): String = "${(fraction * 100).roundToInt()}%"

fun formatPercentRange(ci: ConfidenceInterval): String = "${formatPercent(ci.low)}\u2013${formatPercent(ci.high)}"

/**
 * "Based on N reviewed card(s)" — the sample a pillar's number was computed
 * from, with the noun singularised when [count] is 1.
 */
fun sampleLine(
    count: Int,
    singular: String,
    plural: String,
): String = "Based on $count ${if (count == 1) singular else plural}"

// ---- Pillar 1: Memory (FSRS retrievability) -------------------------------

data class MemoryResult(
    val sufficient: Boolean,
    val fsrsEnabled: Boolean,
    val reviewedCards: Int,
    val meanRetrievability: Double,
    val ci: ConfidenceInterval,
    val insufficientReason: String,
)

/**
 * Memory = mean FSRS retrievability over the reviewed MCAT-deck cards. Gives up
 * (refuses to score) when FSRS is off or fewer than [minReviewed] reviewed cards
 * exist. [retrievabilities] are per-card values in 0..1 from the Anki backend.
 */
fun computeMemory(
    fsrsEnabled: Boolean,
    retrievabilities: List<Double>,
    minReviewed: Int = MIN_REVIEWED_CARDS,
): MemoryResult {
    if (!fsrsEnabled) {
        return MemoryResult(
            sufficient = false,
            fsrsEnabled = false,
            reviewedCards = retrievabilities.size,
            meanRetrievability = 0.0,
            ci = ConfidenceInterval(0.0, 0.0),
            insufficientReason =
                "The FSRS scheduler is off, so card retrievability can't be estimated. " +
                    "Enable FSRS in the scheduling settings to see a Memory score.",
        )
    }
    if (retrievabilities.size < minReviewed) {
        return MemoryResult(
            sufficient = false,
            fsrsEnabled = true,
            reviewedCards = retrievabilities.size,
            meanRetrievability = 0.0,
            ci = ConfidenceInterval(0.0, 0.0),
            insufficientReason =
                "Not enough reviewed cards yet (${retrievabilities.size} of $minReviewed needed). " +
                    "Review more MCAT cards to build a reliable estimate.",
        )
    }
    return MemoryResult(
        sufficient = true,
        fsrsEnabled = true,
        reviewedCards = retrievabilities.size,
        meanRetrievability = mean(retrievabilities),
        ci = meanCi01(retrievabilities),
        insufficientReason = "",
    )
}

// ---- Pillar 2: Performance (practice accuracy + time) ---------------------

data class PerformanceResult(
    val sufficient: Boolean,
    val answered: Int,
    val correct: Int,
    val accuracy: Double,
    val ci: ConfidenceInterval,
    val avgTimeSeconds: Double,
    val insufficientReason: String,
    /** Answered attempts that were assisted-correct (hint ladder reached level 3)
     * and therefore NOT credited as correct — the anti-gaming penalty. */
    val assistedAnswered: Int = 0,
)

/**
 * Performance = accuracy over answered Practice-Question attempts (a blank
 * selection is a skip and is excluded from accuracy), plus average time per
 * answered question. Give up when fewer than [minAnswered] answered attempts
 * exist. Pass the practice-session attempts recorded on this device.
 *
 * SpeedyCAT anti-gaming penalty: an assisted-correct attempt (the learner
 * climbed to level 3 of the hint ladder, [Attempt.assisted]) does NOT count as
 * correct — it still counts toward the denominator but earns no credit, so a
 * student cannot inflate Performance by leaning on the tutor. Unassisted correct
 * answers keep full credit. Mirrors the desktop `practice_accuracy_totals`.
 */
fun computePerformance(
    practiceAttempts: List<Attempt>,
    minAnswered: Int = MIN_PRACTICE_ANSWERS,
): PerformanceResult {
    val answered = practiceAttempts.filter { it.selectedAnswer.isNotEmpty() }
    // Only UNASSISTED correct answers earn credit.
    val credited = answered.count { it.correct && !it.assisted }
    val assistedAnswered = answered.count { it.assisted }
    if (answered.size < minAnswered) {
        return PerformanceResult(
            sufficient = false,
            answered = answered.size,
            correct = credited,
            accuracy = 0.0,
            ci = ConfidenceInterval(0.0, 0.0),
            avgTimeSeconds = 0.0,
            insufficientReason =
                "Not enough answered practice questions yet (${answered.size} of $minAnswered needed). " +
                    "Do more practice to build a reliable estimate.",
            assistedAnswered = assistedAnswered,
        )
    }
    val accuracy = credited.toDouble() / answered.size
    val avgTime = answered.sumOf { it.timeSeconds }.toDouble() / answered.size
    return PerformanceResult(
        sufficient = true,
        answered = answered.size,
        correct = credited,
        accuracy = accuracy,
        ci = proportionCi(accuracy, answered.size),
        avgTimeSeconds = avgTime,
        insufficientReason = "",
        assistedAnswered = assistedAnswered,
    )
}

// ---- Display model --------------------------------------------------------

/**
 * A fully-formatted pillar ready to render: a value, an explicit range, the
 * named source, and — when there isn't enough data — the give-up message
 * ([insufficientReason]). The UI never shows a bare number: when [sufficient]
 * it shows value + range; otherwise it shows the give-up message.
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
)

private const val CI_CAPTION = "95% confidence interval (\u00b11.96\u00b7SE)"

fun memoryPillar(result: MemoryResult): ReadinessPillar =
    ReadinessPillar(
        name = "Memory",
        sufficient = result.sufficient,
        value = if (result.sufficient) formatPercent(result.meanRetrievability) else "\u2014",
        range = if (result.sufficient) formatPercentRange(result.ci) else "",
        rangeCaption = CI_CAPTION,
        source = "FSRS retrievability (Anki scheduler) over your reviewed MCAT cards",
        detail =
            if (result.sufficient) {
                listOf(sampleLine(result.reviewedCards, "reviewed card", "reviewed cards"))
            } else {
                emptyList()
            },
        insufficientReason = result.insufficientReason,
    )

fun performancePillar(result: PerformanceResult): ReadinessPillar =
    ReadinessPillar(
        name = "Performance",
        sufficient = result.sufficient,
        value = if (result.sufficient) formatPercent(result.accuracy) else "\u2014",
        range = if (result.sufficient) formatPercentRange(result.ci) else "",
        rangeCaption = CI_CAPTION,
        source = "Your Practice Question attempts (stored on this device)",
        detail =
            if (result.sufficient) {
                buildList {
                    add("${result.correct} / ${result.answered} unassisted correct")
                    if (result.assistedAnswered > 0) {
                        add(
                            "${result.assistedAnswered} answered with hints (level 3) — " +
                                "counted as incorrect (anti-gaming)",
                        )
                    }
                    add("${result.avgTimeSeconds.roundToInt()}s average per question")
                }
            } else {
                emptyList()
            },
        insufficientReason = result.insufficientReason,
    )

// ---- Pillar 3: Readiness (full-length raw score, desktop-created) ----------

data class ReadinessResult(
    val sufficient: Boolean,
    val completedTests: Int,
    val totalCorrect: Int,
    val totalQuestions: Int,
    val accuracy: Double,
    val ci: ConfidenceInterval,
    val insufficientReason: String,
    /**
     * Mean of the completed tests' overall scaled ESTIMATES (472–528), or null
     * when no synced summary carried one. [scaledLow]/[scaledHigh] are the
     * lowest/highest per-test estimates (the spread across completed tests).
     */
    val scaledEstimate: Int? = null,
    val scaledLow: Int? = null,
    val scaledHigh: Int? = null,
)

/**
 * Readiness = raw score (correct / total) over COMPLETED full-length tests, with
 * a 95% interval. Full-length exams are taken on the SpeedyCAT desktop app and
 * arrive here read-only via the synced media results file; this mirrors the
 * desktop Readiness pillar. Gives up (refuses to score) when no completed
 * full-length summary has synced yet. [summaries] are the desktop-published
 * per-test summaries (already deduped).
 */
fun computeReadiness(summaries: List<FullLengthSummary>): ReadinessResult {
    val completed = summaries.filter { it.totalQuestions > 0 }
    if (completed.isEmpty()) {
        return ReadinessResult(
            sufficient = false,
            completedTests = 0,
            totalCorrect = 0,
            totalQuestions = 0,
            accuracy = 0.0,
            ci = ConfidenceInterval(0.0, 0.0),
            insufficientReason =
                "No full-length results yet. Full-length exams are taken on the SpeedyCAT " +
                    "desktop app; finish one and sync, and your Readiness score appears here.",
        )
    }
    val totalCorrect = completed.sumOf { it.totalCorrect }
    val totalQuestions = completed.sumOf { it.totalQuestions }
    val accuracy = if (totalQuestions > 0) totalCorrect.toDouble() / totalQuestions else 0.0
    // Averaged MCAT-scale estimate across the completed tests that carried one
    // (desktop-computed; may be absent on older synced summaries).
    val scaled = completed.mapNotNull { it.overallScaledScore }
    return ReadinessResult(
        sufficient = true,
        completedTests = completed.size,
        totalCorrect = totalCorrect,
        totalQuestions = totalQuestions,
        accuracy = accuracy,
        ci = proportionCi(accuracy, totalQuestions),
        insufficientReason = "",
        scaledEstimate = if (scaled.isNotEmpty()) scaled.average().roundToInt() else null,
        scaledLow = scaled.minOrNull(),
        scaledHigh = scaled.maxOrNull(),
    )
}

fun readinessPillar(result: ReadinessResult): ReadinessPillar =
    ReadinessPillar(
        name = "Readiness",
        sufficient = result.sufficient,
        value = if (result.sufficient) formatPercent(result.accuracy) else "\u2014",
        range = if (result.sufficient) formatPercentRange(result.ci) else "",
        rangeCaption = CI_CAPTION,
        source = "SpeedyCAT full-length tests (taken on desktop, synced read-only)",
        detail =
            if (result.sufficient) {
                buildList {
                    add("${result.totalCorrect} / ${result.totalQuestions} correct")
                    if (result.scaledEstimate != null) {
                        add("Est. MCAT score \u2248 ${result.scaledEstimate}${scaledRangeSuffix(result)} (472\u2013528)")
                        add("Scaled score is an averaged raw\u2192scaled estimate ($SCALED_SCORE_SOURCE), not an official score")
                    }
                    add(sampleLine(result.completedTests, "completed full-length test", "completed full-length tests"))
                }
            } else {
                emptyList()
            },
        insufficientReason = result.insufficientReason,
    )

/** " (range low–high)" across completed tests, or "" when there's a single test
 * (or no spread) so we never print a degenerate range. */
private fun scaledRangeSuffix(result: ReadinessResult): String {
    val low = result.scaledLow
    val high = result.scaledHigh
    return if (low != null && high != null && low != high) " (range $low\u2013$high)" else ""
}
