// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors
//
// Unit tests for the pure Readiness metric logic: the two pillars each yield a
// value + explicit 95% range + explicit give-up rule, computed deterministically
// from named sources (FSRS retrievability, practice attempts). These pin the
// give-up thresholds and the CI math.

package com.ichi2.anki.practice

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.closeTo
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.nullValue
import org.junit.Test

class ReadinessLogicTest {
    private fun practiceAttempt(
        id: String,
        correct: Boolean,
        time: Int,
        selected: String = if (correct) "A" else "B",
        assisted: Boolean = false,
        hintLevelUsed: Int = if (assisted) 3 else 0,
    ) = Attempt(
        id = id,
        sessionId = "s1",
        questionId = id,
        selectedAnswer = selected,
        correct = correct,
        timeSeconds = time,
        section = McatSection.CPBS,
        topic = "kinetics",
        answeredAt = 0,
        hintLevelUsed = hintLevelUsed,
        assisted = assisted,
    )

    // ---- Formatting -------------------------------------------------------

    @Test
    fun `formatPercent and range render whole percentages`() {
        assertThat(formatPercent(0.5), equalTo("50%"))
        assertThat(formatPercent(0.9), equalTo("90%"))
        assertThat(formatPercentRange(ConfidenceInterval(0.83, 0.91)), equalTo("83%\u201391%"))
    }

    // ---- Memory pillar ----------------------------------------------------

    @Test
    fun `memory gives up when FSRS is off`() {
        val result = computeMemory(fsrsEnabled = false, retrievabilities = List(50) { 0.9 })
        assertThat(result.sufficient, equalTo(false))
        assertThat(result.insufficientReason, containsString("FSRS"))
        val pillar = memoryPillar(result)
        assertThat(pillar.value, equalTo("\u2014"))
        assertThat(pillar.range, equalTo(""))
        assertThat(pillar.sufficient, equalTo(false))
    }

    @Test
    fun `memory gives up with too few reviewed cards`() {
        val result = computeMemory(fsrsEnabled = true, retrievabilities = listOf(0.8, 0.9), minReviewed = 3)
        assertThat(result.sufficient, equalTo(false))
        assertThat(result.insufficientReason, containsString("2 of 3"))
    }

    @Test
    fun `memory computes mean and a bounded ordered CI`() {
        val values = listOf(0.8, 0.9, 1.0)
        val result = computeMemory(fsrsEnabled = true, retrievabilities = values, minReviewed = 3)
        assertThat(result.sufficient, equalTo(true))
        assertThat(result.reviewedCards, equalTo(3))
        assertThat(result.meanRetrievability, closeTo(0.9, 1e-9))
        // CI is ordered, brackets the mean, and stays within [0, 1].
        assertThat(result.ci.low <= result.meanRetrievability, equalTo(true))
        assertThat(result.meanRetrievability <= result.ci.high, equalTo(true))
        assertThat(result.ci.low >= 0.0 && result.ci.high <= 1.0, equalTo(true))
        val pillar = memoryPillar(result)
        assertThat(pillar.value, equalTo("90%"))
        assertThat(pillar.range.isNotEmpty(), equalTo(true))
        assertThat(pillar.detail.any { it.contains("3 reviewed cards") }, equalTo(true))
        assertThat(pillar.source, containsString("FSRS"))
    }

    // ---- Performance pillar ----------------------------------------------

    @Test
    fun `memory gives up at default threshold minus one`() {
        val result = computeMemory(fsrsEnabled = true, retrievabilities = List(29) { 0.9 })
        assertThat(result.sufficient, equalTo(false))
        assertThat(result.insufficientReason, containsString("29 of 30"))
    }

    @Test
    fun `performance gives up at default threshold minus one`() {
        val attempts = (1..29).map { i -> practiceAttempt("q$i", i % 2 == 0, 10) }
        val result = computePerformance(attempts)
        assertThat(result.sufficient, equalTo(false))
        assertThat(result.insufficientReason, containsString("29 of 30"))
    }

    @Test
    fun `performance gives up with too few answered attempts`() {
        val attempts = listOf(practiceAttempt("q1", true, 30), practiceAttempt("q2", false, 40))
        val result = computePerformance(attempts, minAnswered = 4)
        assertThat(result.sufficient, equalTo(false))
        assertThat(result.insufficientReason, containsString("2 of 4"))
    }

    @Test
    fun `performance excludes skips from accuracy and reports time`() {
        val attempts =
            listOf(
                practiceAttempt("q1", correct = true, time = 10),
                practiceAttempt("q2", correct = true, time = 20),
                practiceAttempt("q3", correct = false, time = 30),
                practiceAttempt("q4", correct = false, time = 40),
                // A skip (blank selection) is not an answered attempt.
                practiceAttempt("q5", correct = false, time = 100, selected = ""),
            )
        val result = computePerformance(attempts, minAnswered = 4)
        assertThat(result.sufficient, equalTo(true))
        assertThat(result.answered, equalTo(4))
        assertThat(result.correct, equalTo(2))
        assertThat(result.accuracy, closeTo(0.5, 1e-9))
        assertThat(result.avgTimeSeconds, closeTo(25.0, 1e-9))
        assertThat(result.ci.low <= 0.5 && 0.5 <= result.ci.high, equalTo(true))
        val pillar = performancePillar(result)
        assertThat(pillar.value, equalTo("50%"))
        assertThat(pillar.detail.any { it.contains("2 / 4 unassisted correct") }, equalTo(true))
    }

    @Test
    fun `performance penalizes assisted-correct answers (anti-gaming)`() {
        // 15 unassisted-correct (full credit) + 15 assisted-correct (reached L3,
        // penalized to incorrect). Raw accuracy would be 30/30; penalized is
        // 15/30 = 0.5, and every attempt still counts in the denominator.
        val attempts =
            (1..15).map { practiceAttempt("u$it", correct = true, time = 20) } +
                (1..15).map { practiceAttempt("a$it", correct = true, time = 20, assisted = true) }
        val result = computePerformance(attempts)
        assertThat(result.sufficient, equalTo(true))
        assertThat(result.answered, equalTo(30))
        assertThat(result.correct, equalTo(15))
        assertThat(result.assistedAnswered, equalTo(15))
        assertThat(result.accuracy, closeTo(0.5, 1e-9))
        val pillar = performancePillar(result)
        assertThat(pillar.value, equalTo("50%"))
        assertThat(pillar.detail.any { it.contains("15 answered with hints") }, equalTo(true))
    }

    @Test
    fun `performance credits level 1 and 2 assists but penalizes only level 3`() {
        // Level 1/2 assists are NOT assisted -> full credit; only L3 is penalized.
        val attempts =
            (1..28).map { practiceAttempt("ok$it", correct = true, time = 10) } +
                listOf(
                    practiceAttempt("l2", correct = true, time = 10, hintLevelUsed = 2),
                    practiceAttempt("l3", correct = true, time = 10, assisted = true),
                )
        val result = computePerformance(attempts)
        // 29 credited (28 + the level-2 assist), 1 penalized (the level-3 assist).
        assertThat(result.correct, equalTo(29))
        assertThat(result.answered, equalTo(30))
        assertThat(result.assistedAnswered, equalTo(1))
    }

    // ---- Readiness pillar (desktop-created full-length, read-only) --------

    private fun fullLength(
        id: String,
        correct: Int,
        total: Int,
    ) = FullLengthSummary(
        attemptId = id,
        title = "FL $id",
        completedAt = 1000,
        totalCorrect = correct,
        totalQuestions = total,
        sections = listOf(FullLengthSectionSummary("CPBS", correct, total, 5100, null)),
    )

    @Test
    fun `readiness gives up with no full-length results`() {
        val result = computeReadiness(emptyList())
        assertThat(result.sufficient, equalTo(false))
        assertThat(result.insufficientReason, containsString("desktop"))
        val pillar = readinessPillar(result)
        assertThat(pillar.value, equalTo("\u2014"))
        assertThat(pillar.range, equalTo(""))
        assertThat(pillar.source, containsString("full-length"))
    }

    @Test
    fun `readiness aggregates raw score across completed full-length tests`() {
        val result = computeReadiness(listOf(fullLength("a", 40, 59), fullLength("b", 45, 53)))
        assertThat(result.sufficient, equalTo(true))
        assertThat(result.completedTests, equalTo(2))
        assertThat(result.totalCorrect, equalTo(85))
        assertThat(result.totalQuestions, equalTo(112))
        assertThat(result.accuracy, closeTo(85.0 / 112.0, 1e-9))
        assertThat(result.ci.low <= result.accuracy && result.accuracy <= result.ci.high, equalTo(true))
        assertThat(result.ci.low >= 0.0 && result.ci.high <= 1.0, equalTo(true))
        val pillar = readinessPillar(result)
        assertThat(pillar.sufficient, equalTo(true))
        assertThat(pillar.detail.any { it.contains("85 / 112 correct") }, equalTo(true))
        assertThat(pillar.detail.any { it.contains("2 completed full-length tests") }, equalTo(true))
    }

    @Test
    fun `readiness ignores summaries without questions`() {
        val result = computeReadiness(listOf(fullLength("empty", 0, 0)))
        assertThat(result.sufficient, equalTo(false))
    }

    @Test
    fun `readiness surfaces averaged scaled estimate with range and named source`() {
        val a = fullLength("a", 40, 59).copy(overallScaledScore = 508)
        val b = fullLength("b", 45, 53).copy(overallScaledScore = 512)
        val result = computeReadiness(listOf(a, b))
        // Mean of the per-test overall scaled estimates, and the spread.
        assertThat(result.scaledEstimate, equalTo(510))
        assertThat(result.scaledLow, equalTo(508))
        assertThat(result.scaledHigh, equalTo(512))
        val pillar = readinessPillar(result)
        assertThat(
            pillar.detail.any { it.contains("Est. MCAT score") && it.contains("510") },
            equalTo(true),
        )
        assertThat(pillar.detail.any { it.contains("range 508\u2013512") }, equalTo(true))
        assertThat(pillar.detail.any { it.contains(SCALED_SCORE_SOURCE) }, equalTo(true))
    }

    @Test
    fun `readiness omits scaled estimate when no summary carries one`() {
        val result = computeReadiness(listOf(fullLength("a", 40, 59)))
        assertThat(result.scaledEstimate, nullValue())
        val pillar = readinessPillar(result)
        assertThat(pillar.detail.none { it.contains("Est. MCAT score") }, equalTo(true))
    }
}
