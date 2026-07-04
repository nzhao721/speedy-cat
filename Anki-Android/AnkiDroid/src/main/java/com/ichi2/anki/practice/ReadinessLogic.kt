// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors
//
// Pure, framework-free **fallback** logic for the SpeedyCAT Readiness metric on
// mobile when `PracticeService.GetReadiness` is unavailable (stock backend).
// Prefer [ReadinessBackend.tryFetchPillars] when the SpeedyCAT Rust backend is
// linked (`local_backend=true`); keep these formulas in sync with `rslib`.

package com.ichi2.anki.practice

import kotlin.math.ln
import kotlin.math.pow
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

/** Performance EWMA half-life: half the weight from data within 7 days. */
const val PERFORMANCE_HALF_LIFE_DAYS = 7.0

/** Readiness EWMA half-life: half the weight from data within 30 days. */
const val READINESS_HALF_LIFE_DAYS = 30.0

/** Decay constant λ = ln(2) / half-life (per day). */
fun ewmaDecayPerDay(halfLifeDays: Double): Double = ln(2.0) / halfLifeDays

/** Weight at age *t* days: `2^(-t/H)`. */
fun ewmaWeightForAgeDays(
    ageDays: Double,
    halfLifeDays: Double,
): Double = if (ageDays <= 0.0) 1.0 else 2.0.pow(-ageDays / halfLifeDays)

/** Age in days; missing timestamps (`≤ 0`) count as current. */
fun observationAgeDays(
    nowSecs: Long,
    timestampSecs: Long,
): Double =
    if (timestampSecs <= 0) {
        0.0
    } else {
        (nowSecs - timestampSecs).coerceAtLeast(0).toDouble() / 86_400.0
    }

data class EwmaAggregate(
    val mean: Double,
    val effectiveN: Double,
    val count: Int,
)

/** Weighted mean of `(value, timestampSecs)` pairs. */
fun ewmaAggregate(
    observations: List<Pair<Double, Long>>,
    halfLifeDays: Double,
    nowSecs: Long = System.currentTimeMillis() / 1000,
): EwmaAggregate {
    var weightedSum = 0.0
    var weightSum = 0.0
    var weightSqSum = 0.0
    for ((value, ts) in observations) {
        val w = ewmaWeightForAgeDays(observationAgeDays(nowSecs, ts), halfLifeDays)
        weightedSum += w * value
        weightSum += w
        weightSqSum += w * w
    }
    val mean = if (weightSum > 0.0) weightedSum / weightSum else 0.0
    val effectiveN = if (weightSqSum > 0.0) weightSum * weightSum / weightSqSum else 0.0
    return EwmaAggregate(mean = mean, effectiveN = effectiveN, count = observations.size)
}

/** Wilson 95% CI using Kish effective sample size. */
private fun proportionCiEffective(
    p: Double,
    effectiveN: Double,
): ConfidenceInterval {
    if (effectiveN <= 0.0) return ConfidenceInterval(0.0, 0.0)
    return proportionCi(p, effectiveN.roundToInt().coerceAtLeast(1))
}

/**
 * Target pace for the Performance pillar: 230 questions in 6h15m (22_500s / 230
 * ≈ 97.8s), rounded to 98s. Slower averages scale the accuracy score down.
 */
const val PERFORMANCE_TARGET_SECONDS = 98.0

/** Multiplier when average pace exceeds [PERFORMANCE_TARGET_SECONDS]. */
fun performanceTimePenaltyFactor(avgSeconds: Double): Double =
    if (avgSeconds <= PERFORMANCE_TARGET_SECONDS) {
        1.0
    } else {
        PERFORMANCE_TARGET_SECONDS / avgSeconds
    }

/** Scale accuracy and its CI by the pace penalty (mirrors rslib). */
fun applyPerformanceTimePenalty(
    accuracy: Double,
    ci: ConfidenceInterval,
    avgSeconds: Double,
): Pair<Double, ConfidenceInterval> {
    val factor = performanceTimePenaltyFactor(avgSeconds)
    return Pair(
        accuracy * factor,
        ConfidenceInterval(ci.low * factor, ci.high * factor),
    )
}

/** Full credit — correct with no hints (Performance pillar). */
const val HINT_CREDIT_L0 = 1.0

/** Slightly penalized — correct after hint tier 1. */
const val HINT_CREDIT_L1 = 0.60

/** More penalized — correct after hint tier 2. */
const val HINT_CREDIT_L2 = 0.30

/** Heavily penalized (~incorrect) — correct after hint tier 3. */
const val HINT_CREDIT_L3 = 0.10

/** Fractional Performance credit for one answered attempt (see PRD). */
fun performanceCredit(
    correct: Boolean,
    hintLevelUsed: Int,
    mainWrongFirst: Boolean,
): Double {
    if (mainWrongFirst || !correct) return 0.0
    return when (hintLevelUsed.coerceIn(0, 3)) {
        0 -> HINT_CREDIT_L0
        1 -> HINT_CREDIT_L1
        2 -> HINT_CREDIT_L2
        else -> HINT_CREDIT_L3
    }
}

/**
 * Dashboard first-try eligibility: the learner's first-ever attempt on a question
 * with no hint usage. Returns null for retries or hint-assisted first encounters.
 */
fun firstTryNoHint(
    questionSeenBefore: Boolean,
    replacing: Boolean,
    priorFirstTry: Int?,
    hintLevelUsed: Int,
    selectedAnswer: String,
    correct: Boolean,
): Int? {
    if (replacing) return priorFirstTry
    if (questionSeenBefore) return null
    if (hintLevelUsed > 0 || selectedAnswer.isEmpty()) return null
    return if (correct) 1 else 0
}

/** The z value for a two-sided 95% confidence interval. */
private const val Z_95 = 1.96

/** Caption shown under every pillar's numeric range. */
internal const val CI_CAPTION = "95% confidence interval (\u00b11.96\u00b7SE)"

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

/**
 * Memory pillar sample line when studied and collection totals are known, e.g.
 * "42 out of 10,287 flashcards studied".
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
    /** Sum of fractional hint-penalty credits (for display as "weighted correct"). */
    val weightedCorrect: Double = 0.0,
    /** Answered attempts that used hints on tier 3 (heavily penalized). */
    val heavilyHinted: Int = 0,
    /** Raw accuracy before the pace penalty (equals [accuracy] when no penalty). */
    val rawAccuracy: Double = 0.0,
    /** True when average pace exceeded [PERFORMANCE_TARGET_SECONDS]. */
    val timePenaltyApplied: Boolean = false,
)

/**
 * Performance = time-weighted accuracy (EWMA, 7-day half-life) over answered
 * Practice-Question attempts (a blank selection is a skip and is excluded from
 * accuracy), plus EWMA average time per answered question. Give up when fewer
 * than [minAnswered] answered attempts exist. Pass the practice-session attempts
 * recorded on this device.
 *
 * SpeedyCAT progressive hint penalties: L0=1.0, L1=0.60, L2=0.30, L3=0.10;
 * main-wrong-first or incorrect = 0. Every answered attempt is in the
 * denominator. Mirrors desktop `practice_performance_observations`.
 */
fun computePerformance(
    practiceAttempts: List<Attempt>,
    minAnswered: Int = MIN_PRACTICE_ANSWERS,
    nowSecs: Long = System.currentTimeMillis() / 1000,
): PerformanceResult {
    val answered = practiceAttempts.filter { it.selectedAnswer.isNotEmpty() }
    val weightedCorrect =
        answered.sumOf { performanceCredit(it.correct, it.hintLevelUsed, it.mainWrongFirst) }
    val heavilyHinted = answered.count { it.hintLevelUsed >= 3 && it.correct && !it.mainWrongFirst }
    if (answered.size < minAnswered) {
        return PerformanceResult(
            sufficient = false,
            answered = answered.size,
            correct = weightedCorrect.roundToInt(),
            accuracy = 0.0,
            ci = ConfidenceInterval(0.0, 0.0),
            avgTimeSeconds = 0.0,
            insufficientReason =
                "Not enough answered practice questions yet (${answered.size} of $minAnswered needed). " +
                    "Do more practice to build a reliable estimate.",
            weightedCorrect = weightedCorrect,
            heavilyHinted = heavilyHinted,
        )
    }
    val creditObs =
        answered.map {
            performanceCredit(it.correct, it.hintLevelUsed, it.mainWrongFirst) to it.answeredAt
        }
    val timeObs = answered.map { it.timeSeconds to it.answeredAt }
    val agg = ewmaAggregate(creditObs, PERFORMANCE_HALF_LIFE_DAYS, nowSecs)
    val rawAccuracy = agg.mean
    val avgTime =
        run {
            var weightedSum = 0.0
            var weightSum = 0.0
            for ((seconds, ts) in timeObs) {
                val w = ewmaWeightForAgeDays(observationAgeDays(nowSecs, ts), PERFORMANCE_HALF_LIFE_DAYS)
                weightedSum += w * seconds
                weightSum += w
            }
            if (weightSum > 0.0) weightedSum / weightSum else 0.0
        }
    val rawCi = proportionCiEffective(rawAccuracy, agg.effectiveN)
    val (accuracy, ci) = applyPerformanceTimePenalty(rawAccuracy, rawCi, avgTime)
    val timePenaltyApplied = performanceTimePenaltyFactor(avgTime) < 1.0
    return PerformanceResult(
        sufficient = true,
        answered = answered.size,
        correct = weightedCorrect.roundToInt(),
        accuracy = accuracy,
        ci = ci,
        avgTimeSeconds = avgTime,
        insufficientReason = "",
        weightedCorrect = weightedCorrect,
        heavilyHinted = heavilyHinted,
        rawAccuracy = rawAccuracy,
        timePenaltyApplied = timePenaltyApplied,
    )
}

// ---- Display model --------------------------------------------------------

/**
 * A fully-formatted pillar ready to render: a value, an explicit range, the
 * named source, and — when there isn't enough data — the give-up message
 * ([insufficientReason]). The UI never shows a bare point estimate as the
 * headline: when [sufficient] it shows [range] prominently with [value] as the
 * estimate; otherwise it shows the give-up message.
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
    /** Sample the pillar's value was computed from (0 when unknown). */
    val sampleSize: Int = 0,
)

fun memoryPillar(
    result: MemoryResult,
    totalCards: Int = 0,
): ReadinessPillar =
    ReadinessPillar(
        name = "Memory",
        sufficient = result.sufficient,
        value = if (result.sufficient) formatPercent(result.meanRetrievability) else "\u2014",
        range = if (result.sufficient) formatPercentRange(result.ci) else "",
        rangeCaption = CI_CAPTION,
        source = "FSRS retrievability (Anki scheduler) over your reviewed MCAT cards",
        detail =
            if (result.reviewedCards > 0) {
                listOf(
                    if (totalCards > 0) {
                        memoryStudiedLine(result.reviewedCards, totalCards, soFar = !result.sufficient)
                    } else {
                        "${result.reviewedCards} flashcards studied"
                    },
                )
            } else {
                emptyList()
            },
        insufficientReason = result.insufficientReason,
        sampleSize = result.reviewedCards,
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
                    add(
                        "${result.weightedCorrect.roundToInt()} correct out of ${result.answered} practice questions answered",
                    )
                    if (result.avgTimeSeconds > 0) {
                        add("Average time of ${result.avgTimeSeconds.roundToInt()} seconds")
                    }
                }
            } else {
                emptyList()
            },
        insufficientReason = result.insufficientReason,
        sampleSize = result.answered,
    )

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
 * Readiness = time-weighted raw score (EWMA, 30-day half-life) over COMPLETED
 * full-length tests, with a 95% interval on effective sample size. Full-length
 * exams are taken on the SpeedyCAT desktop app and arrive here read-only via
 * the synced media results file; this mirrors the desktop Readiness pillar.
 * Gives up (refuses to score) when no completed full-length summary has synced
 * yet. [summaries] are the desktop-published per-test summaries (already deduped).
 */
fun computeReadiness(
    summaries: List<FullLengthSummary>,
    nowSecs: Long = System.currentTimeMillis() / 1000,
): ReadinessResult {
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
    val obs =
        completed.map {
            (it.totalCorrect.toDouble() / it.totalQuestions) to it.completedAt
        }
    val agg = ewmaAggregate(obs, READINESS_HALF_LIFE_DAYS, nowSecs)
    val accuracy = agg.mean
    // Averaged MCAT-scale estimate across the completed tests that carried one
    // (desktop-computed; may be absent on older synced summaries).
    val scaled = completed.mapNotNull { it.overallScaledScore }
    return ReadinessResult(
        sufficient = true,
        completedTests = completed.size,
        totalCorrect = totalCorrect,
        totalQuestions = totalQuestions,
        accuracy = accuracy,
        ci = proportionCiEffective(accuracy, agg.effectiveN),
        insufficientReason = "",
        scaledEstimate = if (scaled.isNotEmpty()) scaled.average().roundToInt() else null,
        scaledLow = scaled.minOrNull(),
        scaledHigh = scaled.maxOrNull(),
    )
}

fun readinessExamLines(
    summaries: List<FullLengthSummary>,
    maxExams: Int = 3,
): List<String> =
    summaries
        .filter { it.totalQuestions > 0 }
        .sortedByDescending { it.completedAt }
        .take(maxExams)
        .map { summary ->
            val title = summary.title.ifEmpty { "Full-length test" }
            val accuracy = formatPercent(summary.totalCorrect.toDouble() / summary.totalQuestions)
            "$title: $accuracy"
        }

fun readinessPillar(
    result: ReadinessResult,
    summaries: List<FullLengthSummary> = emptyList(),
): ReadinessPillar =
    ReadinessPillar(
        name = "Readiness",
        sufficient = result.sufficient,
        value = if (result.sufficient) formatPercent(result.accuracy) else "\u2014",
        range = if (result.sufficient) formatPercentRange(result.ci) else "",
        rangeCaption = CI_CAPTION,
        source = "SpeedyCAT full-length tests (taken on desktop, synced read-only)",
        detail = if (result.sufficient) readinessExamLines(summaries) else emptyList(),
        insufficientReason = result.insufficientReason,
        sampleSize = result.totalQuestions,
    )

/** Replace the Memory pillar sample line when collection totals are known. */
fun enhanceMemoryPillar(
    pillar: ReadinessPillar,
    totalCards: Int,
): ReadinessPillar {
    if (pillar.name != "Memory" || totalCards <= 0 || pillar.sampleSize <= 0) return pillar
    val line = memoryStudiedLine(pillar.sampleSize, totalCards, soFar = !pillar.sufficient)
    return pillar.copy(detail = listOf(line))
}

/** Replace Performance pillar detail with minimal answered/time lines. */
fun enhancePerformancePillar(
    pillar: ReadinessPillar,
    result: PerformanceResult,
): ReadinessPillar {
    if (pillar.name != "Performance") return pillar
    return performancePillar(result).copy(
        sufficient = pillar.sufficient,
        value = pillar.value,
        range = pillar.range,
        source = pillar.source,
        insufficientReason = pillar.insufficientReason,
        sampleSize = pillar.sampleSize,
    )
}

/** Replace Readiness pillar detail with per-exam accuracy lines. */
fun enhanceReadinessPillar(
    pillar: ReadinessPillar,
    summaries: List<FullLengthSummary>,
): ReadinessPillar {
    if (pillar.name != "Readiness" || !pillar.sufficient) return pillar
    return pillar.copy(detail = readinessExamLines(summaries))
}
