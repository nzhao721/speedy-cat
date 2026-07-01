// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors
//
// Parses the structured content bundles shipped in `assets/speedycat/
// practice-questions/*.json` into the domain models. This mirrors the desktop
// Rust loader (`anki/rslib/src/practice/loader.rs`) so both apps ingest the
// identical content the same way, including CARS passage sets.

package com.ichi2.anki.practice

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private val json =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

// ---- Raw (JSON) representations, mirroring loader.rs -----------------------

@Serializable
private data class RawChoice(
    val label: String = "",
    val text: String = "",
)

@Serializable
private data class RawQuestion(
    val id: String = "",
    val section: String = "",
    val passageId: String? = null,
    val passage: String? = null,
    val stem: String = "",
    val choices: List<RawChoice> = emptyList(),
    val correctAnswer: String = "",
    val explanation: String = "",
    val questionType: String? = null,
    val topicTags: List<String> = emptyList(),
    val difficulty: String = "",
    val sourceName: String = "",
    val sourceLicense: String = "",
    val sourceUrl: String? = null,
    val answerProvenance: String? = null,
    val notes: String? = null,
)

@Serializable
private data class RawPassageSet(
    val passageId: String = "",
    val section: String = "",
    val title: String = "",
    val passage: String = "",
    val discipline: String? = null,
    val wordCount: Int? = null,
    val topicTags: List<String> = emptyList(),
    val difficulty: String = "",
    val sourceName: String? = null,
    val sourceLicense: String? = null,
    val questions: List<RawQuestion> = emptyList(),
)

@Serializable
private data class RawQuestionBundle(
    val questions: List<RawQuestion> = emptyList(),
    val passageSets: List<RawPassageSet> = emptyList(),
)

// ---- Parse results --------------------------------------------------------

/** Questions + passages parsed from one practice-question bundle. */
data class ParsedQuestionBundle(
    val questions: List<PracticeQuestion>,
    val passages: List<Passage>,
)

// ---- Normalization, mirroring practice/mod.rs -----------------------------

/** Canonical DB form of a bundle section string (trim + uppercase). */
fun normalizeSection(s: String): String = s.trim().uppercase()

/** Canonical DB form of a bundle difficulty string (trim + lowercase). */
fun normalizeDifficulty(s: String): String = s.trim().lowercase()

// ---- Question construction ------------------------------------------------

/**
 * PRD guardrail: every shown question must carry source attribution. The
 * desktop loader aborts a bundle missing this; on mobile we skip the individual
 * item so one malformed row can never blank out the whole bank.
 */
private fun RawQuestion.hasSourceAttribution(): Boolean =
    sourceName.trim().isNotEmpty() && sourceLicense.trim().isNotEmpty()

private fun RawQuestion.toQuestion(
    sectionDb: String,
    passageId: String?,
): PracticeQuestion =
    PracticeQuestion(
        id = id,
        section = McatSection.fromDb(sectionDb),
        passageId = passageId,
        stem = stem,
        choices = choices.map { AnswerChoice(it.label, it.text) },
        correctAnswer = correctAnswer,
        explanation = explanation,
        questionType = questionType,
        topicTags = topicTags,
        difficulty = Difficulty.fromDb(normalizeDifficulty(difficulty)),
        sourceName = sourceName,
        sourceLicense = sourceLicense,
        sourceUrl = sourceUrl,
        answerProvenance = answerProvenance,
        notes = notes,
    )

// ---- Bundle parsing -------------------------------------------------------

/**
 * Parse a Practice Question Bank bundle. Accepts both the discrete-item format
 * ({questions:[...]}, including inline passage/passageId CARS items) and the
 * CARS passage-set format ({passageSets:[...]}).
 */
fun parseQuestionBundle(jsonText: String): ParsedQuestionBundle {
    val bundle = json.decodeFromString<RawQuestionBundle>(jsonText)
    val questions = mutableListOf<PracticeQuestion>()
    val passages = mutableListOf<Passage>()
    val seenPassages = mutableSetOf<String>()

    // CARS passage-set format.
    for (set in bundle.passageSets) {
        val sectionDb = normalizeSection(set.section).ifEmpty { "CARS" }
        if (seenPassages.add(set.passageId)) {
            passages.add(
                Passage(
                    passageId = set.passageId,
                    section = McatSection.fromDb(sectionDb),
                    title = set.title,
                    passage = set.passage,
                    discipline = set.discipline,
                    wordCount = set.wordCount,
                    topicTags = set.topicTags,
                    difficulty = Difficulty.fromDb(normalizeDifficulty(set.difficulty)),
                    sourceName = set.sourceName,
                    sourceLicense = set.sourceLicense,
                ),
            )
        }
        for (raw in set.questions) {
            if (!raw.hasSourceAttribution()) continue
            questions.add(raw.toQuestion(sectionDb, set.passageId))
        }
    }

    // Discrete / inline-passage format.
    for (raw in bundle.questions) {
        if (!raw.hasSourceAttribution()) continue
        val sectionDb = normalizeSection(raw.section)
        val passageId = raw.passageId
        val passageText = raw.passage
        if (passageId != null && passageText != null) {
            if (seenPassages.add(passageId)) {
                passages.add(
                    Passage(
                        passageId = passageId,
                        section = McatSection.fromDb(sectionDb),
                        title = passageId,
                        passage = passageText,
                        discipline = null,
                        wordCount = null,
                        topicTags = raw.topicTags,
                        difficulty = Difficulty.fromDb(normalizeDifficulty(raw.difficulty)),
                        sourceName = raw.sourceName,
                        sourceLicense = raw.sourceLicense,
                    ),
                )
            }
            questions.add(raw.toQuestion(sectionDb, passageId))
        } else {
            questions.add(raw.toQuestion(sectionDb, raw.passageId))
        }
    }

    return ParsedQuestionBundle(questions, passages)
}
