// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors
//
// SpeedyCAT (MCAT study app) is a brownfield fork of AnkiDroid.
// AnkiDroid is licensed GPL-3.0-or-later; Anki is created by Damien Elmes and
// the Anki contributors.
//
// Domain models for the MCAT Practice Question Bank that sits alongside
// flashcards. These mirror the desktop `anki.practice` protobuf messages (see
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
}

/** A single answer option; [label] is "A".."E" and unique within a question. */
data class AnswerChoice(
    val label: String,
    val text: String,
)

/** One answer option of a hint subquestion; [label] is "A".."D". */
data class HintChoice(
    val label: String,
    val text: String,
)

/**
 * SpeedyCAT graduated hint ladder — one tier is a self-contained 4-choice
 * multiple-choice SUBQUESTION that scaffolds toward the parent question WITHOUT
 * revealing its final answer. A question carries an ordered ladder (levels 1→3).
 * Mirrors the desktop `HintSubquestion` proto message.
 */
data class HintSubquestion(
    val level: Int,
    val prompt: String,
    val choices: List<HintChoice>,
    val correctAnswer: String,
    val rationale: String,
)

/** A multiple-choice item — discrete ([passageId] null) or passage-linked. */
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
    /** Graduated hint ladder; empty when the question has no hints yet. */
    val hints: List<HintSubquestion> = emptyList(),
)

/** A reading passage (e.g. a CARS passage set). */
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
)

/** A passage grouped with all of the questions that hang off it. */
data class CarsPassageSet(
    val passage: Passage?,
    val questions: List<PracticeQuestion>,
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
    val limit: Int = 0,
)

/**
 * A recorded answer, tied to the practice [sessionId] that produced it and
 * mirroring the desktop `practice_attempts` row. Persisted locally (never in the
 * synced collection).
 */
data class Attempt(
    val id: String,
    val sessionId: String?,
    val questionId: String,
    val selectedAnswer: String,
    val correct: Boolean,
    val timeSeconds: Int,
    val section: McatSection?,
    val topic: String,
    val answeredAt: Long,
    /** Graduated hint ladder: highest tier reached before locking (0..3). */
    val hintLevelUsed: Int = 0,
    /** Graduated hint ladder: reached level 3 (penalized in Performance). */
    val assisted: Boolean = false,
    /** Wrong main-question escalation before finalize (zero Performance credit). */
    val mainWrongFirst: Boolean = false,
    /** Dashboard: first-ever no-hint attempt (null = retry or hint-assisted). */
    val firstTryNoHint: Int? = null,
)
