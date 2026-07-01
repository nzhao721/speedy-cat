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
import org.junit.Test

class ReadinessLogicTest {
    private fun practiceAttempt(
        id: String,
        correct: Boolean,
        time: Int,
        selected: String = if (correct) "A" else "B",
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
        assertThat(pillar.detail.any { it.contains("2 / 4 correct") }, equalTo(true))
    }
}
