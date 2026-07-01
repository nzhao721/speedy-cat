// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors
//
// SpeedyCAT (MCAT study app) is a brownfield fork of AnkiDroid.
// AnkiDroid is licensed GPL-3.0-or-later; Anki is created by Damien Elmes and
// the Anki contributors.
//
// Domain models for the two MCAT study modes that sit alongside flashcards:
// the Practice Question Bank and the Full-Length Practice Tests. These mirror
// the desktop `anki.practice` protobuf messages (see
// `anki/proto/anki/practice.proto`) so the two apps behave identically. On
// mobile the data/session logic is implemented natively in Kotlin (approach B);
// there is intentionally no dependency on the Rust `PracticeService`.

package com.ichi2.anki.practice

/**
 * The four MCAT sections, in test order. [dbCode] is the canonical storage
 * string shared with the desktop engine; [shortLabel]/[longLabel] mirror the
 * desktop `sectionShort`/`sectionLong` helpers.
 */
enum class McatSection(
    val dbCode: String,
    val shortLabel: String,
    val longLabel: String,
) {
    CPBS("CPBS", "Chem/Phys", "Chemical & Physical Foundations of Biological Systems"),
    CARS("CARS", "CARS", "Critical Analysis & Reasoning Skills"),
    BBLS("BBLS", "Bio/Biochem", "Biological & Biochemical Foundations of Living Systems"),
    PSBB("PSBB", "Psych/Soc", "Psychological, Social & Biological Foundations of Behavior"),
    ;

    companion object {
        /** MCAT sections in the fixed exam order (CPBS → CARS → BBLS → PSBB). */
        val TEST_ORDER: List<McatSection> = listOf(CPBS, CARS, BBLS, PSBB)

        /** The [McatSection] for a canonical DB code, or null when unrecognized. */
        fun fromDb(code: String?): McatSection? = entries.firstOrNull { it.dbCode == code }
    }
}

/** Question / passage difficulty. Null models the desktop `UNSPECIFIED` value. */
enum class Difficulty(
    val dbCode: String,
    val label: String,
) {
    EASY("easy", "Easy"),
    MEDIUM("medium", "Medium"),
    HARD("hard", "Hard"),
    ;

    companion object {
        fun fromDb(code: String?): Difficulty? = entries.firstOrNull { it.dbCode == code }
    }
}

/** Which recorded attempts to include when aggregating per-topic tracking. */
enum class AttemptSource {
    ALL,
    PRACTICE_SESSION,
    FULL_LENGTH,
}

/** A single answer option; [label] is "A".."E" and unique within a question. */
data class AnswerChoice(
    val label: String,
    val text: String,
)

/**
 * A multiple-choice item — discrete ([passageId] null) or passage-linked. When
 * it belongs to a [FullLengthTest] section, [testId] is set (such items are
 * excluded from the free-standing bank by default).
 */
data class PracticeQuestion(
    val id: String,
    val section: McatSection?,
    val passageId: String?,
    val stem: String,
    val choices: List<AnswerChoice>,
    val correctAnswer: String,
    val explanation: String,
    val questionType: String?,
    val topicTags: List<String>,
    val difficulty: Difficulty?,
    val sourceName: String,
    val sourceLicense: String,
    val sourceUrl: String?,
    val answerProvenance: String?,
    val notes: String?,
    val testId: String?,
)

/** A reading passage (CARS passage set, or a full-length section passage). */
data class Passage(
    val passageId: String,
    val section: McatSection?,
    val title: String,
    val passage: String,
    val discipline: String?,
    val wordCount: Int?,
    val topicTags: List<String>,
    val difficulty: Difficulty?,
    val sourceName: String?,
    val sourceLicense: String?,
    val testId: String?,
)

/** A passage grouped with all of the questions that hang off it. */
data class CarsPassageSet(
    val passage: Passage?,
    val questions: List<PracticeQuestion>,
)

/**
 * A scheduled break AFTER [afterSection] (1-based). Mirrors the real MCAT: the
 * break after section 2 is the mid-exam break.
 */
data class FullLengthBreak(
    val afterSection: Int,
    val durationSeconds: Int,
    val optional: Boolean,
    val label: String,
)

/** One timed section of a full-length exam. */
data class FullLengthSection(
    val section: McatSection?,
    val order: Int,
    val durationSeconds: Int,
    val questionCount: Int,
)

/** A full-length practice exam mirroring the AAMC four-section structure. */
data class FullLengthTest(
    val testId: String,
    val title: String,
    val source: String,
    val format: String,
    val disclaimer: String,
    val totalQuestions: Int,
    val totalTestingSeconds: Int,
    val sections: List<FullLengthSection>,
    val breaks: List<FullLengthBreak>,
    val totalBreakSeconds: Int,
)

/** A lightweight summary of a [FullLengthTest] for the picker list. */
data class FullLengthTestSummary(
    val testId: String,
    val title: String,
    val totalQuestions: Int,
    val totalTestingSeconds: Int,
    val totalBreakSeconds: Int,
)

/** correct/total for one section within a practice session summary. */
data class SectionCount(
    val section: McatSection?,
    val correct: Int,
    val total: Int,
)

/** Post-session summary for the Practice Question Bank. */
data class PracticeSessionSummary(
    val total: Int,
    val correct: Int,
    val incorrect: Int,
    val unanswered: Int,
    val totalTimeSeconds: Int,
    val sectionBreakdown: List<SectionCount>,
)

/** Per-section result for a submitted full-length attempt. */
data class SectionResult(
    val section: McatSection?,
    val correct: Int,
    val total: Int,
    val timeSeconds: Int,
    /** Scaled score (118-132); null for the AI-generated proof-of-concept forms. */
    val scaledScore: Int?,
)

/** Report produced when a full-length attempt is submitted. */
data class FullLengthReport(
    val testId: String,
    val sectionResults: List<SectionResult>,
    /** Overall scaled score (472-528); null when scoring data is unavailable. */
    val overallScaledScore: Int?,
    val totalCorrect: Int,
    val totalQuestions: Int,
)

/** Per-topic aggregate over recorded attempts. */
data class TopicStat(
    val topic: String,
    val section: McatSection?,
    val attempts: Int,
    val correct: Int,
    val totalTimeSeconds: Int,
    /** correct / attempts, 0..1. */
    val accuracy: Double,
    val avgTimeSeconds: Double,
)

/** Per-section aggregate over recorded attempts. */
data class SectionStat(
    val section: McatSection?,
    val attempts: Int,
    val correct: Int,
    val totalTimeSeconds: Int,
    val accuracy: Double,
    val avgTimeSeconds: Double,
)

/**
 * A unit of navigation in a runner: a discrete question is a single-question
 * item, while a CARS passage set is one passage shown with ALL its questions.
 */
data class RunItem(
    val passageId: String?,
    val questions: List<PracticeQuestion>,
)

/**
 * Filter over the question bank. All fields are ANDed; [sections] matches ANY
 * selected section (empty = all) and [topics] matches ANY tag, case-insensitive
 * (empty = all). Mirrors the desktop `QuestionFilter` message.
 */
data class QuestionFilter(
    val sections: List<McatSection> = emptyList(),
    val topics: List<String> = emptyList(),
    val difficulty: Difficulty? = null,
    val passageId: String? = null,
    val missedOnly: Boolean = false,
    val includeFullLength: Boolean = false,
    val limit: Int = 0,
)

/**
 * A recorded answer. Exactly one of [sessionId] / [fullLengthAttemptId] is set,
 * mirroring the desktop `practice_attempts` row. Persisted locally (never in the
 * synced collection).
 */
data class Attempt(
    val id: String,
    val sessionId: String?,
    val fullLengthAttemptId: String?,
    val questionId: String,
    val selectedAnswer: String,
    val correct: Boolean,
    val timeSeconds: Int,
    val section: McatSection?,
    val topic: String,
    val answeredAt: Long,
)
