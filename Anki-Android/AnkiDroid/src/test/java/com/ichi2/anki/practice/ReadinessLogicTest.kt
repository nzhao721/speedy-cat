// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors

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
        mainWrongFirst: Boolean = false,
        firstTryNoHint: Int? = null,
        questionId: String = id,
        answeredAt: Long = 0,
    ) = Attempt(
        id = id,
        sessionId = "s1",
        questionId = questionId,
        selectedAnswer = selected,
        correct = correct,
        timeSeconds = time,
        section = McatSection.CPBS,
        topic = "kinetics",
        answeredAt = answeredAt,
        hintLevelUsed = hintLevelUsed,
        assisted = assisted,
        mainWrongFirst = mainWrongFirst,
        firstTryNoHint = firstTryNoHint,
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
        assertThat(result.ci.low <= result.meanRetrievability, equalTo(true))
        assertThat(result.meanRetrievability <= result.ci.high, equalTo(true))
        assertThat(result.ci.low >= 0.0 && result.ci.high <= 1.0, equalTo(true))
        val pillar = memoryPillar(result, totalCards = 100)
        assertThat(pillar.value, equalTo("90%"))
        assertThat(pillar.range, equalTo(formatPercentRange(result.ci)))
        assertThat(pillar.range.isNotEmpty(), equalTo(true))
        assertThat(pillar.detail.any { it.contains("3 out of 100 flashcards studied") }, equalTo(true))
        assertThat(pillar.source, containsString("FSRS"))
    }

    @Test
    fun `memoryStudiedLine formats studied out of total`() {
        assertThat(memoryStudiedLine(42, 10287), containsString("42 out of 10,287 flashcards studied"))
        assertThat(memoryStudiedLine(42, 10287, soFar = true), containsString("so far"))
    }

    @Test
    fun `enhanceMemoryPillar replaces backend sample line`() {
        val pillar =
            ReadinessPillar(
                name = "Memory",
                sufficient = true,
                value = "80%",
                range = "70%\u201390%",
                rangeCaption = CI_CAPTION,
                source = "test",
                detail = listOf("Based on 42 reviewed cards"),
                insufficientReason = "",
                sampleSize = 42,
            )
        val enhanced = enhanceMemoryPillar(pillar, totalCards = 10287)
        assertThat(enhanced.detail.single(), containsString("42 out of 10,287 flashcards studied"))
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
                practiceAttempt("q5", correct = false, time = 100, selected = ""),
            )
        val result = computePerformance(attempts, minAnswered = 4)
        assertThat(result.sufficient, equalTo(true))
        assertThat(result.answered, equalTo(4))
        assertThat(result.weightedCorrect, closeTo(2.0, 1e-9))
        assertThat(result.accuracy, closeTo(0.5, 1e-9))
        assertThat(result.avgTimeSeconds, closeTo(25.0, 1e-9))
    }

    @Test
    fun `performance applies progressive hint penalties`() {
        // 10 L0 + 10 L1 + 10 L3 -> 10 + 6 + 1 = 17 / 30
        val attempts =
            (1..10).map { practiceAttempt("l0$it", correct = true, time = 10) } +
                (1..10).map {
                    practiceAttempt("l1$it", correct = true, time = 10, hintLevelUsed = 1)
                } +
                (1..10).map {
                    practiceAttempt("l3$it", correct = true, time = 10, hintLevelUsed = 3, assisted = true)
                }
        val result = computePerformance(attempts)
        assertThat(result.sufficient, equalTo(true))
        assertThat(result.answered, equalTo(30))
        assertThat(result.weightedCorrect, closeTo(17.0, 1e-9))
        assertThat(result.accuracy, closeTo(17.0 / 30.0, 1e-9))
        assertThat(result.heavilyHinted, equalTo(10))
    }

    @Test
    fun `performance main wrong first earns zero credit`() {
        val attempts =
            (1..29).map { practiceAttempt("ok$it", correct = true, time = 10) } +
                listOf(
                    practiceAttempt(
                        "bad",
                        correct = true,
                        time = 10,
                        hintLevelUsed = 2,
                        mainWrongFirst = true,
                    ),
                )
        val result = computePerformance(attempts)
        assertThat(result.weightedCorrect, closeTo(29.0, 1e-9))
        assertThat(result.accuracy, closeTo(29.0 / 30.0, 1e-9))
    }

    @Test
    fun `performance time penalty scales slow pace`() {
        val attempts =
            (1..30).map { i ->
                practiceAttempt("q$i", correct = i <= 27, time = 147)
            }
        val result = computePerformance(attempts)
        assertThat(result.sufficient, equalTo(true))
        assertThat(result.rawAccuracy, closeTo(0.9, 1e-9))
        assertThat(result.avgTimeSeconds, closeTo(147.0, 1e-9))
        assertThat(result.timePenaltyApplied, equalTo(true))
        assertThat(result.accuracy, closeTo(0.6, 1e-9))
    }

    @Test
    fun `performance no time penalty at or under 98 seconds`() {
        val attempts =
            (1..30).map { i ->
                practiceAttempt("q$i", correct = i <= 27, time = if (i == 1) 98 else 97)
            }
        val result = computePerformance(attempts)
        assertThat(result.timePenaltyApplied, equalTo(false))
        assertThat(result.accuracy, closeTo(0.9, 1e-9))
    }

    @Test
    fun `firstTryNoHint marks first-ever no-hint attempts only`() {
        assertThat(
            firstTryNoHint(
                questionSeenBefore = false,
                replacing = false,
                priorFirstTry = null,
                hintLevelUsed = 0,
                selectedAnswer = "A",
                correct = true,
            ),
            equalTo(1),
        )
        assertThat(
            firstTryNoHint(
                questionSeenBefore = true,
                replacing = false,
                priorFirstTry = null,
                hintLevelUsed = 0,
                selectedAnswer = "A",
                correct = true,
            ),
            nullValue(),
        )
        assertThat(
            firstTryNoHint(
                questionSeenBefore = false,
                replacing = false,
                priorFirstTry = null,
                hintLevelUsed = 2,
                selectedAnswer = "A",
                correct = true,
            ),
            nullValue(),
        )
    }

    // ---- Readiness pillar (desktop-created full-length, read-only) --------

    private fun fullLength(
        id: String,
        correct: Int,
        total: Int,
        completedAt: Long = 1_700_000_000L,
    ) = FullLengthSummary(
        attemptId = id,
        title = "FL $id",
        completedAt = completedAt,
        totalCorrect = correct,
        totalQuestions = total,
        sections = listOf(FullLengthSectionSummary("CPBS", correct, total, 5100, null)),
    )

    @Test
    fun `readiness gives up with no full-length results`() {
        val result = computeReadiness(emptyList())
        assertThat(result.sufficient, equalTo(false))
        assertThat(result.insufficientReason, containsString("desktop"))
    }

    @Test
    fun `readiness aggregates with EWMA across completed full-length tests`() {
        val now = 1_700_000_000L
        val result =
            computeReadiness(
                listOf(fullLength("a", 40, 59, now), fullLength("b", 45, 53, now)),
                nowSecs = now,
            )
        assertThat(result.sufficient, equalTo(true))
        assertThat(result.completedTests, equalTo(2))
        assertThat(result.totalCorrect, equalTo(85))
        assertThat(result.totalQuestions, equalTo(112))
        val expected = (40.0 / 59.0 + 45.0 / 53.0) / 2.0
        assertThat(result.accuracy, closeTo(expected, 1e-9))
    }

    @Test
    fun `ewma weight halves at half-life`() {
        assertThat(ewmaWeightForAgeDays(0.0, 7.0), closeTo(1.0, 1e-12))
        assertThat(ewmaWeightForAgeDays(7.0, 7.0), closeTo(0.5, 1e-12))
        assertThat(ewmaWeightForAgeDays(30.0, 30.0), closeTo(0.5, 1e-12))
    }

    @Test
    fun `performance EWMA favors recent attempts at half-life`() {
        val now = 2_000_000_000L
        val ageSecs = (7.0 * 86_400.0).toLong()
        val recent = practiceAttempt("recent", correct = true, time = 10, answeredAt = now)
        val old =
            practiceAttempt("old", correct = false, time = 10, answeredAt = now - ageSecs)
                .copy(questionId = "old-q")
        val result = computePerformance(listOf(recent, old), minAnswered = 2, nowSecs = now)
        assertThat(result.accuracy, closeTo(2.0 / 3.0, 1e-9))
    }
}
