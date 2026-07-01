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
 * Apply a [QuestionFilter] to an in-memory [bank]. Structural filters and the
 * id ordering mirror the desktop SQL (`get_questions_filtered`), topic matching
 * (ANY, case-insensitive), passage-set grouping and the limit mirror
 * `matching_questions` + `assemble_serving_order`.
 *
 * @param missedIds ids the user previously answered incorrectly (any attempt
 *   with `correct = false`), used only when [QuestionFilter.missedOnly] is set.
 */
fun matchingQuestions(
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
                    (filter.includeFullLength || q.testId == null) &&
                    (!filter.missedOnly || q.id in missedIds)
            }.sortedBy { it.id }

    val topicFiltered =
        if (filter.topics.isEmpty()) {
            structural
        } else {
            val wanted = filter.topics.map { it.lowercase() }
            structural.filter { q ->
                q.topicTags.any { tag -> wanted.any { it == tag.lowercase() } }
            }
        }

    return assembleServingOrder(topicFiltered, filter.limit)
}

/**
 * Assemble the serving order: passage-linked questions are grouped into an
 * atomic unit (first-seen order) and the [limit] is applied at group
 * granularity so a CARS passage set is never split and at least one group is
 * always served. Mirrors the Rust `assemble_serving_order`.
 */
fun assembleServingOrder(
    questions: List<PracticeQuestion>,
    limit: Int,
): List<PracticeQuestion> {
    val order = mutableListOf<String>()
    val groups = HashMap<String, MutableList<PracticeQuestion>>()
    questions.forEachIndexed { idx, q ->
        val pid = q.passageId
        val key = if (!pid.isNullOrEmpty()) "p:$pid" else "q:$idx"
        if (!groups.containsKey(key)) order.add(key)
        groups.getOrPut(key) { mutableListOf() }.add(q)
    }
    val result = mutableListOf<PracticeQuestion>()
    for (key in order) {
        val group = groups.getValue(key)
        if (limit > 0 && result.isNotEmpty() && result.size + group.size > limit) break
        result.addAll(group)
    }
    return result
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
        AttemptSource.FULL_LENGTH -> attempt.fullLengthAttemptId != null
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

/**
 * Build a full-length report from the answers recorded for one attempt,
 * grouping per section (ordered by canonical code). Scaled scores require
 * licensed AAMC data and are always null for the proof-of-concept forms.
 * Mirrors the desktop `submit_full_length_attempt`.
 */
fun fullLengthReport(
    testId: String,
    attempts: List<Attempt>,
): FullLengthReport {
    val bySection = sortedMapOf<String, MutableList<Attempt>>()
    for (a in attempts) {
        bySection.getOrPut(a.section?.dbCode ?: "") { mutableListOf() }.add(a)
    }
    val results =
        bySection.map { (code, group) ->
            SectionResult(
                section = McatSection.fromDb(code),
                correct = group.count { it.correct },
                total = group.size,
                timeSeconds = group.sumOf { it.timeSeconds },
                scaledScore = null,
            )
        }
    return FullLengthReport(
        testId = testId,
        sectionResults = results,
        overallScaledScore = null,
        totalCorrect = results.sumOf { it.correct },
        totalQuestions = results.sumOf { it.total },
    )
}
