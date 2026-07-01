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
 *
 * @param missedIds ids the user previously answered incorrectly (any attempt
 *   with `correct = false`), used only when [QuestionFilter.missedOnly] is set.
 */
fun filteredQuestions(
    bank: List<PracticeQuestion>,
    filter: QuestionFilter,
    missedIds: Set<String> = emptySet(),
): List<PracticeQuestion> {
    val structural =
        bank
            .filter { q ->
                (filter.sections.isEmpty() || q.section in filter.sections) &&
                    (filter.difficulty == null || q.difficulty == filter.difficulty) &&
                    (filter.passageId == null || q.passageId == filter.passageId) &&
                    (!filter.missedOnly || q.id in missedIds)
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
    missedIds: Set<String> = emptySet(),
): List<PracticeQuestion> = assembleServingOrder(filteredQuestions(bank, filter, missedIds), filter.limit)

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
    missedIds: Set<String>,
    sessionId: String,
): List<PracticeQuestion> {
    val pool = filteredQuestions(bank, filter, missedIds)
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
    val order = question.choices.indices.toMutableList().apply { shuffle(rng) }
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
): List<TopicStat> =
    attempts
        .filter { sourceMatches(it, source) && (section == null || it.section == section) }
        .groupBy { (it.section?.dbCode ?: "") to it.topic }
        .map { (key, group) ->
            val n = group.size
            val correct = group.count { it.correct }
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
