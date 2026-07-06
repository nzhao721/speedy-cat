// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors
//
// Pure logic for the MCAT study modes: setup-input parsing, time formatting,
// section/difficulty labels, question-bank filtering with CARS whole-passage-set
// grouping, run-item grouping, and per-topic/section aggregation. These mirror
// the finalized desktop behavior in `anki/ts/routes/practice/lib.ts` and the
// Rust `anki/rslib/src/practice` service/storage so the two apps score and serve
// content identically. Everything here is framework-free so it is unit-tested
// directly.

package com.ichi2.anki.practice

import kotlin.random.Random

// ---- Sections & difficulty labels -----------------------------------------

fun sectionShort(section: McatSection?): String = section?.shortLabel ?: "—"

fun sectionLong(section: McatSection?): String = section?.longLabel ?: "Unspecified"

fun difficultyLabel(difficulty: Difficulty?): String = difficulty?.label ?: ""

// ---- Time -----------------------------------------------------------------

/** "h:mm:ss" (hours only when needed) for a countdown/elapsed display. */
fun formatClock(totalSeconds: Int): String {
    val s = maxOf(0, totalSeconds)
    val hours = s / 3600
    val minutes = (s % 3600) / 60
    val seconds = s % 60
    val mm = minutes.toString().padStart(if (hours > 0) 2 else 1, '0')
    val ss = seconds.toString().padStart(2, '0')
    return if (hours > 0) "$hours:$mm:$ss" else "$mm:$ss"
}

/** Longer human-readable duration, e.g. "1h 5m" / "45s". */
fun formatDurationLong(totalSeconds: Int): String {
    val s = maxOf(0, totalSeconds)
    val hours = s / 3600
    val minutes = (s % 3600) / 60
    val seconds = s % 60
    val parts = mutableListOf<String>()
    if (hours > 0) parts.add("${hours}h")
    if (minutes > 0) parts.add("${minutes}m")
    if (hours == 0 && (seconds > 0 || parts.isEmpty())) parts.add("${seconds}s")
    return parts.joinToString(" ")
}

// ---- Setup input parsing --------------------------------------------------

/** Result of parsing the free-text "number of questions" field. */
data class QuestionCountInput(
    /** Limit to pass to the filter (0 = all available). */
    val limit: Int,
    /** Whether the raw text is acceptable to start a session. */
    val valid: Boolean,
    /** True when the input means "all available" (blank / "max" / "all"). */
    val all: Boolean,
)

/**
 * Parse the free-text "number of questions" field. Blank, "max" or "all" mean
 * all available (limit 0). Otherwise the text must be a positive integer,
 * clamped to [available] when known; anything else (0, negative, decimal,
 * non-numeric) is invalid. Mirrors the desktop `parseQuestionCount`.
 */
fun parseQuestionCount(
    raw: String,
    available: Int? = null,
): QuestionCountInput {
    val trimmed = raw.trim().lowercase()
    if (trimmed == "" || trimmed == "max" || trimmed == "all") {
        return QuestionCountInput(limit = 0, valid = true, all = true)
    }
    if (!DIGITS.matches(trimmed)) {
        return QuestionCountInput(limit = 0, valid = false, all = false)
    }
    val n = trimmed.toLongOrNull()
    if (n == null || n <= 0) {
        return QuestionCountInput(limit = 0, valid = false, all = false)
    }
    val limit =
        if (available != null && available > 0) {
            minOf(n, available.toLong())
        } else {
            n.coerceAtMost(Int.MAX_VALUE.toLong())
        }
    return QuestionCountInput(limit = limit.toInt(), valid = true, all = false)
}

/** Result of parsing the free-text timer field (whole minutes). */
data class TimerInput(
    /** Session time limit in seconds (0 when invalid). */
    val seconds: Int,
    /** Whether the raw text is a usable positive whole-minute count. */
    val valid: Boolean,
)

/**
 * Parse the free-text timer field (whole minutes). Must be a positive integer;
 * blank / non-numeric / non-positive is invalid. Consulted only when the
 * session is timed. Mirrors the desktop `parseTimerMinutes`.
 */
fun parseTimerMinutes(raw: String): TimerInput {
    val trimmed = raw.trim()
    if (!DIGITS.matches(trimmed)) {
        return TimerInput(seconds = 0, valid = false)
    }
    val mins = trimmed.toLongOrNull()
    if (mins == null || mins <= 0) {
        return TimerInput(seconds = 0, valid = false)
    }
    val seconds = (mins * 60).coerceAtMost(Int.MAX_VALUE.toLong())
    return TimerInput(seconds = seconds.toInt(), valid = true)
}

private val DIGITS = Regex("^\\d+$")

// ---- Filtering & serving order --------------------------------------------

/**
 * The pool of questions matching a [filter]'s structural filters plus topic
 * matching (ANY, case-insensitive), in the deterministic id order. No grouping,
 * shuffling or limit — those are applied by [assembleServingOrder]. Mirrors the
 * Rust `filtered_questions`.
 */
fun filteredQuestions(
    bank: List<PracticeQuestion>,
    filter: QuestionFilter,
): List<PracticeQuestion> {
    val structural =
        bank
            .filter { q ->
                (filter.sections.isEmpty() || q.section in filter.sections) &&
                    (filter.difficulty == null || q.difficulty == filter.difficulty) &&
                    (filter.passageId == null || q.passageId == filter.passageId)
            }.sortedBy { it.id }

    if (filter.topics.isEmpty()) return structural
    val wanted = filter.topics.map { it.lowercase() }
    return structural.filter { q ->
        q.topicTags.any { tag -> wanted.any { it == tag.lowercase() } }
    }
}

/**
 * Apply a [QuestionFilter] to an in-memory [bank] with the deterministic
 * first-seen order used by the stateless query (no shuffle). Mirrors the desktop
 * `matching_questions`.
 */
fun matchingQuestions(
    bank: List<PracticeQuestion>,
    filter: QuestionFilter,
): List<PracticeQuestion> = assembleServingOrder(filteredQuestions(bank, filter), filter.limit)

/**
 * Assemble the questions served for one practice **session**, mirroring the
 * desktop `start_practice_session`: the [sessionId] (which carries randomness)
 * seeds every shuffle, so the selection + answer-choice order are reproducible
 * within the session but differ across sessions. The pool is shuffled at
 * passage-set granularity *before* the count [QuestionFilter.limit] is applied,
 * then each served question's answer choices are shuffled with the correct
 * answer remapped.
 */
fun sessionQuestions(
    bank: List<PracticeQuestion>,
    filter: QuestionFilter,
    sessionId: String,
): List<PracticeQuestion> {
    val pool = filteredQuestions(bank, filter)
    val ordered = assembleServingOrder(pool, filter.limit, seedFromStr(sessionId))
    return ordered.map { shuffleQuestionChoices(it, sessionId) }
}

/**
 * Assemble the serving order: passage-linked questions are grouped into an
 * atomic unit (first-seen order) and the [limit] is applied at group
 * granularity so a CARS passage set is never split and at least one group is
 * always served. Mirrors the Rust `assemble_serving_order`.
 *
 * When [shuffleSeed] is non-null the *groups* are shuffled with a seeded RNG
 * before the limit is applied — so each session draws a random subset/order,
 * while CARS passage sets stay whole and contiguous (only the sets are
 * reordered, not the questions inside a set). With `null` the deterministic
 * first-seen order is preserved.
 */
fun assembleServingOrder(
    questions: List<PracticeQuestion>,
    limit: Int,
    shuffleSeed: Long? = null,
): List<PracticeQuestion> {
    val order = mutableListOf<String>()
    val groups = HashMap<String, MutableList<PracticeQuestion>>()
    questions.forEachIndexed { idx, q ->
        val pid = q.passageId
        val key = if (!pid.isNullOrEmpty()) "p:$pid" else "q:$idx"
        if (!groups.containsKey(key)) order.add(key)
        groups.getOrPut(key) { mutableListOf() }.add(q)
    }
    val groupList = order.map { groups.getValue(it) }.toMutableList()
    if (shuffleSeed != null) {
        groupList.shuffle(Random(shuffleSeed))
    }
    val result = mutableListOf<PracticeQuestion>()
    for (group in groupList) {
        if (limit > 0 && result.isNotEmpty() && result.size + group.size > limit) break
        result.addAll(group)
    }
    return result
}

/**
 * Derive a stable RNG seed from a string (FNV-1a). Deterministic and portable,
 * so a session's shuffles are reproducible within the session (same key -> same
 * seed) while differing across sessions (session ids are random). Mirrors the
 * Rust `seed_from_str` — the 64-bit FNV-1a hash is bit-identical; only the
 * downstream RNG stream differs from Rust's, which is fine because mobile keeps
 * its own local practice store.
 */
fun seedFromStr(s: String): Long {
    var hash = 0xcbf29ce484222325uL.toLong()
    for (b in s.toByteArray(Charsets.UTF_8)) {
        hash = hash xor (b.toLong() and 0xff)
        hash *= 0x100000001b3L
    }
    return hash
}

/**
 * Shuffle a question's answer choices for a session (stable per `sessionId` +
 * question). The positional labels (A, B, C, …) stay in order; only the choice
 * *texts* are reordered, and [PracticeQuestion.correctAnswer] is remapped to the
 * new label of the originally-correct choice — so the post-submit correct
 * reveal stays right. Mirrors the Rust `shuffle_question_choices`.
 */
fun shuffleQuestionChoices(
    question: PracticeQuestion,
    sessionId: String,
): PracticeQuestion {
    if (question.choices.size < 2) return question
    val rng = Random(seedFromStr("$sessionId:${question.id}"))
    val positionalLabels = question.choices.map { it.label }
    val order =
        question.choices.indices
            .toMutableList()
            .apply { shuffle(rng) }
    var newCorrect = question.correctAnswer
    val newChoices =
        order.mapIndexed { position, origIdx ->
            val newLabel = positionalLabels[position]
            val orig = question.choices[origIdx]
            if (orig.label == question.correctAnswer) newCorrect = newLabel
            AnswerChoice(label = newLabel, text = orig.text)
        }
    return question.copy(choices = newChoices, correctAnswer = newCorrect)
}

/**
 * Group discrete + passage-linked questions into run items, keeping every
 * question that shares a passage adjacent. Mirrors the desktop
 * `groupIntoRunItems`.
 */
fun groupIntoRunItems(questions: List<PracticeQuestion>): List<RunItem> {
    val builders = mutableListOf<Pair<String?, MutableList<PracticeQuestion>>>()
    val passageIndex = HashMap<String, Int>()
    for (q in questions) {
        val pid = q.passageId
        if (!pid.isNullOrEmpty()) {
            val idx = passageIndex[pid]
            if (idx == null) {
                passageIndex[pid] = builders.size
                builders.add(pid to mutableListOf(q))
            } else {
                builders[idx].second.add(q)
            }
        } else {
            builders.add(null to mutableListOf(q))
        }
    }
    return builders.map { RunItem(it.first, it.second) }
}

/** Primary topic attributed to an attempt (first tag, else the section label). */
fun primaryTopic(question: PracticeQuestion): String = question.topicTags.firstOrNull() ?: sectionShort(question.section)

// ---- Graduated hint ladder ------------------------------------------------

// SpeedyCAT: a question's [PracticeQuestion.hints] are an ordered ladder of
// scaffolding SUBQUESTIONS (each a 4-choice MCQ). The learner works through them
// ONE AT A TIME — they must answer the currently-revealed subquestion before
// revealing the next tier or submitting the main question (the NO-SKIP rule) —
// and the main answer stays locked until the ladder is worked through. The
// highest tier reached sets `hintLevelUsed`; reaching level 3 sets `assisted`
// (penalized in the Performance pillar). These pure helpers mirror the desktop
// `lib.ts` so both apps gate + track identically, and are unit-tested directly.

/** Per-question progress through the hint ladder: how many tiers are revealed,
 * and the chosen label per revealed subquestion index (0-based). */
data class HintProgress(
    val revealed: Int = 0,
    val picks: Map<Int, String> = emptyMap(),
)

/** Whether a question offers a (well-formed) hint ladder at all. */
fun hasHintLadder(question: PracticeQuestion): Boolean = question.hints.isNotEmpty()

/**
 * The highest hint tier reached so far (0 = none), clamped to 0..3. Drives
 * `hintLevelUsed`. Uses each subquestion's [HintSubquestion.level], falling back
 * to its 1-based position when the level is missing/out of range.
 */
fun hintLevelReached(
    hints: List<HintSubquestion>,
    revealed: Int,
): Int {
    if (revealed <= 0 || hints.isEmpty()) return 0
    val last = minOf(revealed, hints.size)
    var level = 0
    for (i in 0 until last) {
        val l = hints[i].level.takeIf { it in 1..3 } ?: (i + 1)
        level = maxOf(level, l)
    }
    return minOf(3, level)
}

/** A learner is "assisted" once they reach level 3 of the ladder. */
fun isAssisted(level: Int): Boolean = level >= 3

/**
 * Index of the currently-revealed subquestion still awaiting an answer, or -1
 * when the latest revealed subquestion is answered (or none is revealed). This
 * enforces the NO-SKIP rule.
 */
fun pendingHintIndex(progress: HintProgress): Int {
    if (progress.revealed <= 0) return -1
    val idx = progress.revealed - 1
    return if (progress.picks[idx] == null) idx else -1
}

/**
 * Indices of the revealed hint subquestions in DISPLAY order: the most-recently
 * revealed tier first. This ONLY affects how revealed tiers are rendered (newest
 * on top, directly under the main question); the ladder still PROGRESSES strictly
 * L1 -> L2 -> L3 with no skipping (see [pendingHintIndex] / [canRevealNextHint]).
 * e.g. after all three are revealed this returns [2, 1, 0]. Mirrors the desktop
 * `hintDisplayOrder`.
 */
fun hintDisplayOrder(revealed: Int): List<Int> = (maxOf(0, revealed) - 1 downTo 0).toList()

/** Can the learner reveal the NEXT hint? Only when one remains AND the current
 * revealed subquestion has been answered (no skipping ahead). */
fun canRevealNextHint(
    hints: List<HintSubquestion>,
    progress: HintProgress,
): Boolean = progress.revealed < hints.size && pendingHintIndex(progress) == -1

/** Can the learner submit the MAIN question? A choice must be selected AND there
 * must be no revealed-but-unanswered subquestion (they cannot skip the ladder). */
fun canSubmitMain(
    mainSelected: String,
    progress: HintProgress,
): Boolean = mainSelected.isNotEmpty() && pendingHintIndex(progress) == -1

/** Whether a subquestion answer is correct. */
fun hintAnswerCorrect(
    hint: HintSubquestion,
    label: String,
): Boolean = label.isNotEmpty() && label == hint.correctAnswer

/** Label + text for the correct choice on a hint subquestion (compact display). */
fun hintCorrectAnswerLine(hint: HintSubquestion): String {
    val choice = hint.choices.find { it.label == hint.correctAnswer }
    return choice?.let { "${it.label}. ${it.text}" } ?: hint.correctAnswer
}

// ---- Hint ladder UX timers -------------------------------------------------
//
// SpeedyCAT practice mode gates hint affordances so learners attempt retrieval
// before scaffolding. Mirrors the desktop `lib.ts` helpers.

/** Minimum wait before the first "I'm stuck" button (seconds). */
const val HINT_FIRST_MIN_SECONDS = 15

/** Maximum wait before the first "I'm stuck" button (seconds). */
const val HINT_FIRST_MAX_SECONDS = 30

/** Wait on the main question before "I'm still stuck" reveals the next tier. */
const val HINT_SUBSEQUENT_SECONDS = 6

/** Cooldown after a wrong hint subquestion submit before retry (seconds). */
const val HINT_WRONG_RETRY_SECONDS = 5

/** Assumed reading speed when gating hints behind passage read time (words/min). */
const val PASSAGE_READ_WPM = 300

/** Readable character count for a question stem + all answer choices. */
fun questionReadingChars(question: PracticeQuestion): Int = question.stem.length + question.choices.sumOf { it.text.length }

/** Word count from passage metadata or plain text (HTML stripped). */
fun passageWordCount(
    wordCount: Int?,
    passageText: String?,
): Int {
    if (wordCount != null && wordCount > 0) return wordCount
    val text = (passageText ?: "").replace(Regex("<[^>]+>"), " ").trim()
    if (text.isEmpty()) return 0
    return text.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
}

/**
 * Seconds to allow reading a passage at [PASSAGE_READ_WPM] before hints may
 * unlock (e.g. 600 words → 120 s).
 */
fun passageReadGateSeconds(wordCount: Int): Int {
    if (wordCount <= 0) return 0
    return kotlin.math.ceil(wordCount / PASSAGE_READ_WPM.toDouble() * 60).toInt()
}

/**
 * Passage read gate for one question within a run item. In a multi-question
 * CARS sidebar only the first question carries the shared passage allowance;
 * later questions use stem-only timing once the learner scrolls to them.
 */
fun passageHintWordCountForQuestion(
    questionCountInItem: Int,
    questionIndexInItem: Int,
    passageWordCount: Int,
): Int? {
    if (passageWordCount <= 0) return null
    if (questionCountInItem > 1 && questionIndexInItem > 0) return null
    return passageWordCount
}

/**
 * Delay before showing the first "I'm stuck" affordance. Starts at 15 s, adds
 * ~1 s per 100 characters of stem/choices (time to read + attempt retrieval),
 * capped at 30 s. When a passage is linked, uses whichever is longer: that
 * base delay or the time to read the passage at 300 WPM.
 */
fun firstHintDelaySeconds(
    question: PracticeQuestion,
    passageWordCount: Int? = null,
): Int {
    val extra = questionReadingChars(question) / 100
    val base = minOf(HINT_FIRST_MAX_SECONDS, maxOf(HINT_FIRST_MIN_SECONDS, HINT_FIRST_MIN_SECONDS + extra))
    if (passageWordCount == null || passageWordCount <= 0) return base
    return maxOf(base, passageReadGateSeconds(passageWordCount))
}

/**
 * Whether a per-question hint timer should advance this tick. Timers start on
 * first viewport visibility and pause when scrolled away without resetting.
 */
fun shouldTickQuestionHintTimer(
    everVisible: Boolean,
    currentlyVisible: Boolean,
): Boolean = everVisible && currentlyVisible

/** May the learner tap "I'm stuck" to reveal hint tier 1? */
fun canShowFirstHintButton(
    elapsedSeconds: Int,
    question: PracticeQuestion,
    progress: HintProgress,
    locked: Boolean,
    passageWordCount: Int? = null,
): Boolean =
    !locked &&
        progress.revealed == 0 &&
        elapsedSeconds >= firstHintDelaySeconds(question, passageWordCount)

/**
 * May the learner tap "I'm still stuck" on the main question to reveal the
 * next tier? Requires the current tier to be answered and a subsequent delay.
 */
fun canShowNextHintButton(
    elapsedSinceHintComplete: Int,
    hints: List<HintSubquestion>,
    progress: HintProgress,
    locked: Boolean,
): Boolean =
    !locked &&
        canRevealNextHint(hints, progress) &&
        elapsedSinceHintComplete >= HINT_SUBSEQUENT_SECONDS

/**
 * Whether the post-hint "I'm still stuck" delay should advance this second.
 * The timer starts only after the learner dismisses the hint popup (or returns
 * to the main question), not while answering a tier inside the overlay.
 */
fun shouldTickSubsequentHintTimer(
    hints: List<HintSubquestion>,
    progress: HintProgress,
    hintLadderDismissed: Boolean,
): Boolean =
    hintLadderDismissed &&
        pendingHintIndex(progress) == -1 &&
        progress.revealed > 0 &&
        canRevealNextHint(hints, progress)

/** May the learner submit a hint subquestion answer right now? */
fun canSubmitHintAnswer(
    choice: String,
    cooldownRemaining: Int,
): Boolean = choice.isNotEmpty() && cooldownRemaining <= 0

/** May the learner pick a choice on the active hint subquestion? */
fun hintChoicesEnabled(
    answered: Boolean,
    locked: Boolean,
    isCurrent: Boolean,
    cooldownRemaining: Int,
): Boolean = !answered && !locked && isCurrent && cooldownRemaining <= 0

/** Start the post-wrong hint retry cooldown for one question. */
fun startHintWrongCooldown(
    cooldowns: Map<String, Int>,
    questionId: String,
): Map<String, Int> = cooldowns + (questionId to HINT_WRONG_RETRY_SECONDS)

/** Advance all per-question hint wrong-answer cooldowns by one second. */
fun tickHintCooldowns(cooldowns: Map<String, Int>): Map<String, Int> {
    var changed = false
    val next = cooldowns.toMutableMap()
    for ((qid, remaining) in cooldowns) {
        if (remaining > 0) {
            next[qid] = remaining - 1
            changed = true
        }
    }
    return if (changed) next else cooldowns
}

/**
 * Show a duplicate main Submit next to the hint affordances (near the top of
 * the question) so learners need not scroll past the ladder after using hints.
 * Mirrors the desktop `shouldShowConvenientMainSubmit`.
 */
fun shouldShowConvenientMainSubmit(
    progress: HintProgress,
    locked: Boolean,
): Boolean = !locked && progress.revealed > 0

/**
 * Apply a hint subquestion answer. Wrong picks are rejected (progress unchanged)
 * so the learner must retry until correct — picks only store correct answers.
 */
fun applyHintAnswer(
    hint: HintSubquestion,
    index: Int,
    label: String,
    progress: HintProgress,
): Pair<HintProgress, Boolean> {
    if (!hintAnswerCorrect(hint, label)) {
        return progress to false
    }
    return progress.copy(picks = progress.picks + (index to label)) to true
}

// ---- Session summary & tracking aggregation -------------------------------

/**
 * Tally a practice session's attempts into a [PracticeSessionSummary]. Mirrors
 * the desktop `end_practice_session`: unanswered = an attempt with no selected
 * answer, and the per-section breakdown is ordered by canonical section code.
 */
fun summarizePracticeSession(attempts: List<Attempt>): PracticeSessionSummary {
    val total = attempts.size
    val correct = attempts.count { it.correct }
    val unanswered = attempts.count { !it.correct && it.selectedAnswer.isEmpty() }
    val incorrect = total - correct - unanswered
    val totalTime = attempts.sumOf { it.timeSeconds }

    val bySection = sortedMapOf<String, IntArray>()
    for (a in attempts) {
        val code = a.section?.dbCode ?: ""
        val entry = bySection.getOrPut(code) { intArrayOf(0, 0) }
        entry[1] += 1
        if (a.correct) entry[0] += 1
    }
    val sectionBreakdown =
        bySection.map { (code, cell) ->
            SectionCount(section = McatSection.fromDb(code), correct = cell[0], total = cell[1])
        }

    return PracticeSessionSummary(
        total = total,
        correct = correct,
        incorrect = incorrect,
        unanswered = unanswered,
        totalTimeSeconds = totalTime,
        sectionBreakdown = sectionBreakdown,
    )
}

private fun sourceMatches(
    attempt: Attempt,
    source: AttemptSource,
): Boolean =
    when (source) {
        AttemptSource.ALL -> true
        AttemptSource.PRACTICE_SESSION -> attempt.sessionId != null
    }

private fun ratio(
    numerator: Int,
    denominator: Int,
): Double = if (denominator > 0) numerator.toDouble() / denominator else 0.0

/**
 * Aggregate time-spent + accuracy per (section, topic), optionally restricted to
 * a [section] and an attempt [source]. Ordered by section then topic. Mirrors
 * the desktop `topic_stats`.
 */
fun topicStats(
    attempts: List<Attempt>,
    source: AttemptSource = AttemptSource.ALL,
    section: McatSection? = null,
    firstAttemptNoHintOnly: Boolean = false,
): List<TopicStat> =
    attempts
        .filter { sourceMatches(it, source) && (section == null || it.section == section) }
        .filter { !firstAttemptNoHintOnly || it.firstTryNoHint != null }
        .groupBy { (it.section?.dbCode ?: "") to it.topic }
        .map { (key, group) ->
            val n = group.size
            val correct =
                if (firstAttemptNoHintOnly) {
                    group.sumOf { it.firstTryNoHint ?: 0 }
                } else {
                    group.count { it.correct }
                }
            val totalTime = group.sumOf { it.timeSeconds }
            TopicStat(
                topic = key.second,
                section = McatSection.fromDb(key.first),
                attempts = n,
                correct = correct,
                totalTimeSeconds = totalTime,
                accuracy = ratio(correct, n),
                avgTimeSeconds = ratio(totalTime, n),
            )
        }.sortedWith(compareBy({ it.section?.dbCode ?: "" }, { it.topic }))

// ---- Explanation gate (correct-answer verification) -----------------------

const val EXPLANATION_GATE_MODULO = 5
const val EXPLANATION_GATE_SUFFIX = ":explanation-gate"

val GENERIC_EXPLANATION_FAIL_HINTS: List<String> =
    listOf(
        "Walk through your reasoning step by step. What concept does this question test, " +
            "and how does it support your conclusion?",
        "Be more specific about the underlying principle. How does it apply to the " +
            "scenario in the stem?",
        "Connect the stem's details to the science. Explain why your answer follows " +
            "from that principle — without just restating the question.",
    )

/** Per-question progress through the explanation gate after a correct MCQ answer. */
data class ExplanationProgress(
    val active: Boolean = false,
    val failCount: Int = 0,
    val lastFeedback: String = "",
    val passed: Boolean = false,
    val checking: Boolean = false,
)

/** Deterministic ~1-in-5 gate per session + question (stable on revisit). */
fun requiresExplanationGate(
    sessionId: String,
    questionId: String,
): Boolean {
    val key = "$sessionId:$questionId$EXPLANATION_GATE_SUFFIX"
    return seedFromStr(key) % EXPLANATION_GATE_MODULO == 0L
}

fun shouldUseExplanationGate(
    sessionId: String,
    questionId: String,
    aiOn: Boolean,
    aiAvailable: Boolean,
): Boolean {
    if (!aiOn || !aiAvailable) return false
    return requiresExplanationGate(sessionId, questionId)
}

fun correctAnswerText(question: PracticeQuestion): String {
    val match = question.choices.firstOrNull { it.label == question.correctAnswer }
    return match?.text?.trim()?.takeIf { it.isNotEmpty() } ?: question.correctAnswer
}

const val EXPLANATION_INSTRUCTION =
    "You answered correctly. Before moving on, explain your reasoning in a few " +
        "sentences."

fun buildUserVisibleExplanationPrompt(@Suppress("UNUSED_PARAMETER") stem: String): String =
    EXPLANATION_INSTRUCTION

fun explanationFailureHint(
    failCount: Int,
    hints: List<HintSubquestion> = emptyList(),
): String {
    val level = maxOf(1, failCount)
    if (level <= GENERIC_EXPLANATION_FAIL_HINTS.size) {
        return GENERIC_EXPLANATION_FAIL_HINTS[level - 1]
    }
    val hintIdx = level - GENERIC_EXPLANATION_FAIL_HINTS.size - 1
    val prompt = hints.getOrNull(hintIdx)?.prompt
    if (!prompt.isNullOrBlank()) return "Consider: $prompt"
    return GENERIC_EXPLANATION_FAIL_HINTS.last()
}

fun itemBlocksExplanationProgress(
    questions: List<PracticeQuestion>,
    progress: Map<String, ExplanationProgress>,
): Boolean =
    questions.any { q ->
        val p = progress[q.id]
        p?.active == true && p.passed != true
    }

/**
 * Aggregate time-spent + accuracy per section. Mirrors the desktop
 * `section_stats`.
 */
fun sectionStats(
    attempts: List<Attempt>,
    source: AttemptSource = AttemptSource.ALL,
    section: McatSection? = null,
): List<SectionStat> =
    attempts
        .filter { sourceMatches(it, source) && (section == null || it.section == section) }
        .groupBy { it.section?.dbCode ?: "" }
        .map { (code, group) ->
            val n = group.size
            val correct = group.count { it.correct }
            val totalTime = group.sumOf { it.timeSeconds }
            SectionStat(
                section = McatSection.fromDb(code),
                attempts = n,
                correct = correct,
                totalTimeSeconds = totalTime,
                accuracy = ratio(correct, n),
                avgTimeSeconds = ratio(totalTime, n),
            )
        }.sortedBy { it.section?.dbCode ?: "" }
