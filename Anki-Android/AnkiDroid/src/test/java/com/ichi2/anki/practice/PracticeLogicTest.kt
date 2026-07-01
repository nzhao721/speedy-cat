// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors
//
// Unit tests for the pure MCAT study-mode logic. These mirror the finalized
// desktop tests in `anki/ts/routes/practice/lib.test.ts` and the Rust service
// tests in `anki/rslib/src/practice/service.rs`, so the mobile behavior is
// pinned to the same contract: setup-input parsing, CARS whole-passage-set
// serving, multi-select filtering, session summary and per-topic aggregation.

package com.ichi2.anki.practice

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.closeTo
import org.hamcrest.Matchers.equalTo
import org.junit.Test

class PracticeLogicTest {
    private fun q(
        id: String,
        section: McatSection?,
        passageId: String? = null,
        topicTags: List<String> = emptyList(),
        difficulty: Difficulty? = null,
        testId: String? = null,
        correctAnswer: String = "A",
    ) = PracticeQuestion(
        id = id,
        section = section,
        passageId = passageId,
        stem = "stem",
        choices = listOf(AnswerChoice("A", "x"), AnswerChoice("B", "y")),
        correctAnswer = correctAnswer,
        explanation = "e",
        questionType = null,
        topicTags = topicTags,
        difficulty = difficulty,
        sourceName = "src",
        sourceLicense = "lic",
        sourceUrl = null,
        answerProvenance = null,
        notes = null,
        testId = testId,
    )

    private fun attempt(
        questionId: String,
        correct: Boolean,
        time: Int,
        section: McatSection?,
        topic: String,
        selected: String = if (correct) "A" else "B",
        sessionId: String? = "s1",
        fullLengthAttemptId: String? = null,
    ) = Attempt(
        id = questionId,
        sessionId = sessionId,
        fullLengthAttemptId = fullLengthAttemptId,
        questionId = questionId,
        selectedAnswer = selected,
        correct = correct,
        timeSeconds = time,
        section = section,
        topic = topic,
        answeredAt = 0,
    )

    // ---- Labels & formatting ----------------------------------------------

    @Test
    fun `section and difficulty labels fall back for unknown values`() {
        assertThat(sectionShort(McatSection.CPBS), equalTo("Chem/Phys"))
        assertThat(sectionShort(McatSection.CARS), equalTo("CARS"))
        assertThat(
            sectionLong(McatSection.PSBB),
            equalTo("Psychological, Social & Biological Foundations of Behavior"),
        )
        assertThat(sectionShort(null), equalTo("—"))
        assertThat(difficultyLabel(Difficulty.HARD), equalTo("Hard"))
        assertThat(difficultyLabel(null), equalTo(""))
    }

    @Test
    fun `formatClock shows hours only when needed and clamps negatives`() {
        assertThat(formatClock(0), equalTo("0:00"))
        assertThat(formatClock(65), equalTo("1:05"))
        assertThat(formatClock(3661), equalTo("1:01:01"))
        assertThat(formatClock(-30), equalTo("0:00"))
    }

    @Test
    fun `formatDurationLong drops trailing units sensibly`() {
        assertThat(formatDurationLong(0), equalTo("0s"))
        assertThat(formatDurationLong(45), equalTo("45s"))
        assertThat(formatDurationLong(65), equalTo("1m 5s"))
        assertThat(formatDurationLong(60), equalTo("1m"))
        assertThat(formatDurationLong(3665), equalTo("1h 1m"))
        assertThat(formatDurationLong(3600), equalTo("1h"))
    }

    // ---- Setup input parsing ----------------------------------------------

    @Test
    fun `parseQuestionCount treats blank max all as everything and validates positives`() {
        for (raw in listOf("", "   ", "max", "MAX", "all", "All")) {
            assertThat(parseQuestionCount(raw), equalTo(QuestionCountInput(limit = 0, valid = true, all = true)))
        }
        assertThat(parseQuestionCount("15"), equalTo(QuestionCountInput(limit = 15, valid = true, all = false)))
        assertThat(parseQuestionCount("15", 40).limit, equalTo(15))
        assertThat(parseQuestionCount("100", 40).limit, equalTo(40))
        assertThat(parseQuestionCount("15", 0).limit, equalTo(15))
        for (bad in listOf("0", "-3", "3.5", "abc", "1a", "1 2")) {
            val p = parseQuestionCount(bad)
            assertThat(p.valid, equalTo(false))
            assertThat(p.limit, equalTo(0))
            assertThat(p.all, equalTo(false))
        }
    }

    @Test
    fun `parseTimerMinutes accepts positive whole minutes only`() {
        assertThat(parseTimerMinutes("20"), equalTo(TimerInput(seconds = 1200, valid = true)))
        assertThat(parseTimerMinutes(" 5 "), equalTo(TimerInput(seconds = 300, valid = true)))
        for (bad in listOf("", "0", "-1", "1.5", "abc", "10m")) {
            assertThat(parseTimerMinutes(bad), equalTo(TimerInput(seconds = 0, valid = false)))
        }
    }

    @Test
    fun `primaryTopic prefers the first tag else the section label`() {
        assertThat(primaryTopic(q("q1", McatSection.CPBS, topicTags = listOf("kinetics", "acids"))), equalTo("kinetics"))
        assertThat(primaryTopic(q("q2", McatSection.CARS)), equalTo("CARS"))
    }

    // ---- Run-item grouping ------------------------------------------------

    @Test
    fun `groupIntoRunItems keeps questions sharing a passage adjacent`() {
        val items =
            groupIntoRunItems(
                listOf(
                    q("d1", McatSection.CPBS),
                    q("p1a", McatSection.CARS, passageId = "p1"),
                    q("d2", McatSection.BBLS),
                    q("p1b", McatSection.CARS, passageId = "p1"),
                    q("p2a", McatSection.CARS, passageId = "p2"),
                ),
            )
        assertThat(items.size, equalTo(4))
        assertThat(items[0].passageId, equalTo(null))
        assertThat(items[0].questions.map { it.id }, equalTo(listOf("d1")))
        assertThat(items[1].passageId, equalTo("p1"))
        assertThat(items[1].questions.map { it.id }, equalTo(listOf("p1a", "p1b")))
        assertThat(items[2].questions.map { it.id }, equalTo(listOf("d2")))
        assertThat(items[3].passageId, equalTo("p2"))
        assertThat(items[3].questions.map { it.id }, equalTo(listOf("p2a")))
    }

    // ---- Filtering & CARS whole-passage-set serving -----------------------

    private fun carsBank() =
        listOf(
            q("cars-a1", McatSection.CARS, passageId = "pA", topicTags = listOf("ethics")),
            q("cars-a2", McatSection.CARS, passageId = "pA", topicTags = listOf("ethics")),
            q("cars-a3", McatSection.CARS, passageId = "pA", topicTags = listOf("ethics")),
            q("cars-b1", McatSection.CARS, passageId = "pB", topicTags = listOf("ethics")),
            q("cars-b2", McatSection.CARS, passageId = "pB", topicTags = listOf("ethics")),
        )

    private fun ids(
        bank: List<PracticeQuestion>,
        filter: QuestionFilter,
        missed: Set<String> = emptySet(),
    ) = matchingQuestions(bank, filter, missed).map { it.id }

    @Test
    fun `CARS practice serves whole passage sets rounding the limit to complete sets`() {
        val bank = carsBank()
        // limit smaller than the first set still serves the whole first set.
        assertThat(
            ids(bank, QuestionFilter(sections = listOf(McatSection.CARS), limit = 2)),
            equalTo(listOf("cars-a1", "cars-a2", "cars-a3")),
        )
        // limit=4 cannot fit both sets (3+2) without splitting: only the first.
        assertThat(
            ids(bank, QuestionFilter(sections = listOf(McatSection.CARS), limit = 4)),
            equalTo(listOf("cars-a1", "cars-a2", "cars-a3")),
        )
        // limit=5 fits both whole sets.
        assertThat(
            ids(bank, QuestionFilter(sections = listOf(McatSection.CARS), limit = 5)),
            equalTo(listOf("cars-a1", "cars-a2", "cars-a3", "cars-b1", "cars-b2")),
        )
        // no limit: every question, grouped by passage.
        assertThat(ids(bank, QuestionFilter(sections = listOf(McatSection.CARS))).size, equalTo(5))
    }

    @Test
    fun `multi-section filter matches any and empty means all`() {
        val bank =
            carsBank() +
                listOf(
                    q("cpbs-1", McatSection.CPBS, topicTags = listOf("kinetics")),
                    q("bbls-1", McatSection.BBLS, topicTags = listOf("genetics")),
                    q("psbb-1", McatSection.PSBB, topicTags = listOf("memory")),
                )
        // Two sections -> only those (CARS excluded), id-ordered.
        assertThat(
            ids(bank, QuestionFilter(sections = listOf(McatSection.CPBS, McatSection.BBLS))),
            equalTo(listOf("bbls-1", "cpbs-1")),
        )
        // Empty list == all sections.
        assertThat(ids(bank, QuestionFilter()).size, equalTo(8))
    }

    @Test
    fun `topic filter matches any tag case-insensitively`() {
        val bank =
            listOf(
                q("a", McatSection.CPBS, topicTags = listOf("Kinetics")),
                q("b", McatSection.CPBS, topicTags = listOf("acids and bases")),
            )
        assertThat(ids(bank, QuestionFilter(topics = listOf("kinetics"))), equalTo(listOf("a")))
        assertThat(ids(bank, QuestionFilter(topics = listOf("ACIDS AND BASES"))), equalTo(listOf("b")))
    }

    @Test
    fun `filters for difficulty full-length and missed narrow the set`() {
        val bank =
            listOf(
                q("cpbs-a", McatSection.CPBS, difficulty = Difficulty.EASY),
                q("cpbs-b", McatSection.CPBS, difficulty = Difficulty.MEDIUM),
                q("cpbs-c", McatSection.CPBS, difficulty = Difficulty.HARD),
                q("fl-q1", McatSection.CPBS, difficulty = Difficulty.HARD, testId = "fl-1"),
            )
        // Free-standing only excludes the full-length item by default.
        assertThat(
            ids(bank, QuestionFilter(sections = listOf(McatSection.CPBS))),
            equalTo(listOf("cpbs-a", "cpbs-b", "cpbs-c")),
        )
        // Including full-length adds it.
        assertThat(
            ids(bank, QuestionFilter(sections = listOf(McatSection.CPBS), includeFullLength = true)).size,
            equalTo(4),
        )
        // Difficulty selects a single item.
        assertThat(
            ids(bank, QuestionFilter(sections = listOf(McatSection.CPBS), difficulty = Difficulty.HARD)),
            equalTo(listOf("cpbs-c")),
        )
        // Limit truncates (id-ordered).
        assertThat(
            ids(bank, QuestionFilter(sections = listOf(McatSection.CPBS), limit = 2)),
            equalTo(listOf("cpbs-a", "cpbs-b")),
        )
        // missed_only restricts to the previously-missed set.
        assertThat(
            ids(bank, QuestionFilter(sections = listOf(McatSection.CPBS), missedOnly = true), missed = setOf("cpbs-b")),
            equalTo(listOf("cpbs-b")),
        )
    }

    // ---- Session summary & tracking ---------------------------------------

    @Test
    fun `summarizePracticeSession tallies correct incorrect unanswered and time`() {
        val attempts =
            listOf(
                attempt("q1", correct = true, time = 30, section = McatSection.CPBS, topic = "kinetics"),
                attempt("q2", correct = false, time = 40, section = McatSection.CPBS, topic = "kinetics"),
                attempt("q3", correct = false, time = 10, section = McatSection.CPBS, topic = "kinetics", selected = ""),
            )
        val summary = summarizePracticeSession(attempts)
        assertThat(summary.total, equalTo(3))
        assertThat(summary.correct, equalTo(1))
        assertThat(summary.incorrect, equalTo(1))
        assertThat(summary.unanswered, equalTo(1))
        assertThat(summary.totalTimeSeconds, equalTo(80))
        assertThat(summary.sectionBreakdown.size, equalTo(1))
        assertThat(summary.sectionBreakdown[0].section, equalTo(McatSection.CPBS))
        assertThat(summary.sectionBreakdown[0].correct, equalTo(1))
        assertThat(summary.sectionBreakdown[0].total, equalTo(3))
    }

    @Test
    fun `topicStats computes accuracy time and honours the source filter`() {
        val attempts =
            listOf(
                attempt("q1", correct = true, time = 30, section = McatSection.CPBS, topic = "acids and bases"),
                attempt("q2", correct = false, time = 50, section = McatSection.CPBS, topic = "acids and bases"),
                attempt("q3", correct = true, time = 20, section = McatSection.CPBS, topic = "kinetics"),
                attempt(
                    "q4",
                    correct = false,
                    time = 100,
                    section = McatSection.CPBS,
                    topic = "kinetics",
                    sessionId = null,
                    fullLengthAttemptId = "fla1",
                ),
            )
        val all = topicStats(attempts, AttemptSource.ALL)
        val acids = all.first { it.topic == "acids and bases" }
        assertThat(acids.attempts, equalTo(2))
        assertThat(acids.correct, equalTo(1))
        assertThat(acids.totalTimeSeconds, equalTo(80))
        assertThat(acids.accuracy, closeTo(0.5, 1e-9))
        assertThat(acids.avgTimeSeconds, closeTo(40.0, 1e-9))

        // Practice-session source excludes the full-length "kinetics" miss.
        val practiceKinetics = topicStats(attempts, AttemptSource.PRACTICE_SESSION).first { it.topic == "kinetics" }
        assertThat(practiceKinetics.attempts, equalTo(1))
        assertThat(practiceKinetics.correct, equalTo(1))

        val flKinetics = topicStats(attempts, AttemptSource.FULL_LENGTH).first { it.topic == "kinetics" }
        assertThat(flKinetics.attempts, equalTo(1))
        assertThat(flKinetics.correct, equalTo(0))
    }

    @Test
    fun `fullLengthReport aggregates per section`() {
        val attempts =
            listOf(
                attempt(
                    "fl-c1",
                    correct = true,
                    time = 90,
                    section = McatSection.CPBS,
                    topic = "electrochemistry",
                    sessionId = null,
                    fullLengthAttemptId = "fla1",
                ),
                attempt(
                    "fl-r1",
                    correct = false,
                    time = 120,
                    section = McatSection.CARS,
                    topic = "ethics",
                    sessionId = null,
                    fullLengthAttemptId = "fla1",
                ),
            )
        val report = fullLengthReport("fl-test", attempts)
        assertThat(report.totalQuestions, equalTo(2))
        assertThat(report.totalCorrect, equalTo(1))
        assertThat(report.sectionResults.size, equalTo(2))
        val cpbs = report.sectionResults.first { it.section == McatSection.CPBS }
        assertThat(cpbs.correct, equalTo(1))
        assertThat(cpbs.total, equalTo(1))
        assertThat(cpbs.timeSeconds, equalTo(90))
        assertThat(cpbs.scaledScore, equalTo(null))
    }
}
