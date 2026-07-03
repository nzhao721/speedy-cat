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
    )

    private fun attempt(
        questionId: String,
        correct: Boolean,
        time: Int,
        section: McatSection?,
        topic: String,
        selected: String = if (correct) "A" else "B",
        sessionId: String? = "s1",
    ) = Attempt(
        id = questionId,
        sessionId = sessionId,
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
    fun `filters for difficulty and missed narrow the set`() {
        val bank =
            listOf(
                q("cpbs-a", McatSection.CPBS, difficulty = Difficulty.EASY),
                q("cpbs-b", McatSection.CPBS, difficulty = Difficulty.MEDIUM),
                q("cpbs-c", McatSection.CPBS, difficulty = Difficulty.HARD),
            )
        // A plain section filter serves the whole section, id-ordered.
        assertThat(
            ids(bank, QuestionFilter(sections = listOf(McatSection.CPBS))),
            equalTo(listOf("cpbs-a", "cpbs-b", "cpbs-c")),
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
                ),
            )
        val all = topicStats(attempts, AttemptSource.ALL)
        val acids = all.first { it.topic == "acids and bases" }
        assertThat(acids.attempts, equalTo(2))
        assertThat(acids.correct, equalTo(1))
        assertThat(acids.totalTimeSeconds, equalTo(80))
        assertThat(acids.accuracy, closeTo(0.5, 1e-9))
        assertThat(acids.avgTimeSeconds, closeTo(40.0, 1e-9))

        // ALL counts every attempt, including the non-session "kinetics" miss.
        val allKinetics = all.first { it.topic == "kinetics" }
        assertThat(allKinetics.attempts, equalTo(2))
        assertThat(allKinetics.correct, equalTo(1))

        // Practice-session source excludes the non-session "kinetics" miss.
        val practiceKinetics = topicStats(attempts, AttemptSource.PRACTICE_SESSION).first { it.topic == "kinetics" }
        assertThat(practiceKinetics.attempts, equalTo(1))
        assertThat(practiceKinetics.correct, equalTo(1))
    }

    // ---- Randomization: seeded selection + answer-choice shuffle ----------
    // These mirror the desktop Rust tests for `assemble_serving_order`,
    // `shuffle_question_choices` and `seed_from_str` in
    // `anki/rslib/src/practice/service.rs` + `mod.rs`.

    private fun discrete(id: String) = q(id, McatSection.CPBS)

    private fun passageQ(
        id: String,
        passageId: String,
    ) = q(id, McatSection.CARS, passageId = passageId)

    private fun mcq(
        correct: String,
        id: String = "q1",
    ) = PracticeQuestion(
        id = id,
        section = McatSection.CPBS,
        passageId = null,
        stem = "stem",
        choices = listOf(AnswerChoice("A", "w"), AnswerChoice("B", "x"), AnswerChoice("C", "y"), AnswerChoice("D", "z")),
        correctAnswer = correct,
        explanation = "e",
        questionType = null,
        topicTags = emptyList(),
        difficulty = null,
        sourceName = "src",
        sourceLicense = "lic",
        sourceUrl = null,
        answerProvenance = null,
        notes = null,
    )

    @Test
    fun `assembleServingOrder unseeded keeps the deterministic first-seen order`() {
        val pool = (0 until 20).map { discrete("q%02d".format(it)) }
        assertThat(assembleServingOrder(pool, 0).map { it.id }, equalTo(pool.map { it.id }))
    }

    @Test
    fun `seeded selection shuffle is a permutation that varies across seeds and honours the limit`() {
        val pool = (0 until 20).map { discrete("q%02d".format(it)) }
        val sortedIds = pool.map { it.id }.sorted()
        val orders =
            (1L..5L).map { seed ->
                val shuffled = assembleServingOrder(pool, 0, seed)
                // A shuffle is a permutation of the whole pool.
                assertThat(shuffled.map { it.id }.sorted(), equalTo(sortedIds))
                shuffled.map { it.id }
            }
        // At least one seed reorders vs first-seen, and the seeds disagree.
        assertThat(orders.any { it != pool.map { q -> q.id } }, equalTo(true))
        assertThat(orders.toSet().size > 1, equalTo(true))
        // The count limit still applies after shuffling (random subset of 5).
        assertThat(assembleServingOrder(pool, 5, 1L).size, equalTo(5))
    }

    @Test
    fun `seeded shuffle keeps passage sets contiguous and internally ordered`() {
        val pool =
            listOf(
                passageQ("a1", "pA"), passageQ("a2", "pA"), passageQ("a3", "pA"),
                discrete("d1"),
                passageQ("b1", "pB"), passageQ("b2", "pB"),
                discrete("d2"),
                passageQ("c1", "pC"), passageQ("c2", "pC"),
            )
        val sets = mapOf("pA" to listOf("a1", "a2", "a3"), "pB" to listOf("b1", "b2"), "pC" to listOf("c1", "c2"))
        for (seed in 0L until 8L) {
            val out = assembleServingOrder(pool, 0, seed)
            assertThat(out.size, equalTo(pool.size))
            for ((pid, members) in sets) {
                val positions = out.withIndex().filter { it.value.passageId == pid }.map { it.index }
                assertThat(positions.size, equalTo(members.size))
                assertThat(positions.zipWithNext().all { (a, b) -> b == a + 1 }, equalTo(true))
                assertThat(positions.map { out[it].id }, equalTo(members))
            }
        }
    }

    @Test
    fun `shuffleQuestionChoices keeps labels positional remaps correct and is stable per session`() {
        // correct answer "B" => the correct text is "x".
        val q1 = shuffleQuestionChoices(mcq("B"), "sess-1")
        assertThat(q1.choices.map { it.label }, equalTo(listOf("A", "B", "C", "D")))
        assertThat(q1.choices.first { it.label == q1.correctAnswer }.text, equalTo("x"))
        assertThat(q1.choices.map { it.text }.sorted(), equalTo(listOf("w", "x", "y", "z")))
        // Stable: same session + question reproduces the same order.
        val q1b = shuffleQuestionChoices(mcq("B"), "sess-1")
        assertThat(q1b.choices.map { it.text }, equalTo(q1.choices.map { it.text }))
        // Varies across sessions, and the remap always stays correct.
        val orders =
            (0 until 6).map { s ->
                val shuffled = shuffleQuestionChoices(mcq("B"), "sess-$s")
                assertThat(shuffled.choices.first { it.label == shuffled.correctAnswer }.text, equalTo("x"))
                shuffled.choices.map { it.text }
            }
        assertThat(orders.toSet().size > 1, equalTo(true))
    }

    @Test
    fun `shuffleQuestionChoices leaves a single-choice question untouched`() {
        val single = mcq("A").copy(choices = listOf(AnswerChoice("A", "only")))
        val out = shuffleQuestionChoices(single, "sess-1")
        assertThat(out.choices.map { it.text }, equalTo(listOf("only")))
        assertThat(out.correctAnswer, equalTo("A"))
    }

    @Test
    fun `seedFromStr is deterministic and starts from the FNV-1a offset basis`() {
        assertThat(seedFromStr("abc"), equalTo(seedFromStr("abc")))
        // Empty input hashes to the FNV-1a 64-bit offset basis (no bytes mixed in).
        assertThat(seedFromStr("") == 0xcbf29ce484222325uL.toLong(), equalTo(true))
        assertThat(seedFromStr("session-a") != seedFromStr("session-b"), equalTo(true))
    }

    @Test
    fun `sessionQuestions randomizes selection and answer choices per session id`() {
        val bank = (0 until 12).map { mcq("B", id = "q%02d".format(it)) }
        val filter = QuestionFilter(sections = listOf(McatSection.CPBS), limit = 6)
        val s1 = sessionQuestions(bank, filter, emptySet(), "ps-1")
        val s2 = sessionQuestions(bank, filter, emptySet(), "ps-2")
        assertThat(s1.size, equalTo(6))
        assertThat(s2.size, equalTo(6))
        // Every served question still exposes a valid, remapped correct answer.
        for (question in s1 + s2) {
            assertThat(question.choices.first { it.label == question.correctAnswer }.text, equalTo("x"))
        }
        // Two sessions differ in either the selection or the choice order.
        val sameSelection = s1.map { it.id } == s2.map { it.id }
        val sameChoiceOrder =
            s1.map { q -> q.choices.map { it.text } } == s2.map { q -> q.choices.map { it.text } }
        assertThat(sameSelection && sameChoiceOrder, equalTo(false))
    }

    // ---- Graduated hint ladder --------------------------------------------

    private fun hint(
        level: Int,
        correct: String = "A",
    ) = HintSubquestion(
        level = level,
        prompt = "prompt L$level",
        choices =
            listOf(
                HintChoice("A", "a"),
                HintChoice("B", "b"),
                HintChoice("C", "c"),
                HintChoice("D", "d"),
            ),
        correctAnswer = correct,
        rationale = "because",
    )

    private val ladder = listOf(hint(1), hint(2), hint(3))

    @Test
    fun `hintLevelReached tracks the highest tier and clamps to 0 through 3`() {
        assertThat(hintLevelReached(ladder, 0), equalTo(0))
        assertThat(hintLevelReached(ladder, 1), equalTo(1))
        assertThat(hintLevelReached(ladder, 2), equalTo(2))
        assertThat(hintLevelReached(ladder, 3), equalTo(3))
        // Falls back to 1-based position when a level is missing/out of range.
        val noLevels = listOf(hint(0), hint(0), hint(0))
        assertThat(hintLevelReached(noLevels, 3), equalTo(3))
    }

    @Test
    fun `assisted is true only once level 3 is reached`() {
        assertThat(isAssisted(hintLevelReached(ladder, 1)), equalTo(false))
        assertThat(isAssisted(hintLevelReached(ladder, 2)), equalTo(false))
        assertThat(isAssisted(hintLevelReached(ladder, 3)), equalTo(true))
    }

    @Test
    fun `no-skip cannot reveal the next hint until the current one is answered`() {
        val pending = HintProgress(revealed = 1, picks = emptyMap())
        assertThat(pendingHintIndex(pending), equalTo(0))
        assertThat(canRevealNextHint(ladder, pending), equalTo(false))

        val answered = HintProgress(revealed = 1, picks = mapOf(0 to "A"))
        assertThat(pendingHintIndex(answered), equalTo(-1))
        assertThat(canRevealNextHint(ladder, answered), equalTo(true))

        val allDone = HintProgress(revealed = 3, picks = mapOf(0 to "A", 1 to "B", 2 to "C"))
        assertThat(canRevealNextHint(ladder, allDone), equalTo(false))
    }

    @Test
    fun `no-skip cannot submit the main question while a hint is unanswered`() {
        val pending = HintProgress(revealed = 2, picks = mapOf(0 to "A"))
        assertThat(canSubmitMain("C", pending), equalTo(false))
        val cleared = HintProgress(revealed = 2, picks = mapOf(0 to "A", 1 to "B"))
        assertThat(canSubmitMain("C", cleared), equalTo(true))
        assertThat(canSubmitMain("", cleared), equalTo(false))
        // No ladder engaged: a selection submits immediately.
        assertThat(canSubmitMain("C", HintProgress()), equalTo(true))
    }
}
