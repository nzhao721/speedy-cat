// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors
//
// Parses the structured content bundles shipped in `assets/speedycat/
// practice-questions/*.json` and `assets/speedycat/full-length-tests/*.json`
// into the domain models. This mirrors the desktop Rust loader
// (`anki/rslib/src/practice/loader.rs`) so both apps ingest the identical
// content the same way, including CARS passage sets and synthesized MCAT breaks.

package com.ichi2.anki.practice

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * A regular ~10-minute break (seconds), matching the real AAMC MCAT breaks
 * after sections 1 (Chem/Phys) and 3 (Bio/Biochem).
 */
private const val BREAK_SECONDS = 600

/**
 * The 30-minute mid-exam break (seconds) after section 2 (CARS) on the real
 * AAMC MCAT.
 */
private const val MID_EXAM_BREAK_SECONDS = 1800

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

@Serializable
private data class RawFullLengthPassage(
    val passageId: String = "",
    val title: String = "",
    val passage: String = "",
    val discipline: String? = null,
    val wordCount: Int? = null,
    val topicTags: List<String> = emptyList(),
    val difficulty: String = "",
)

@Serializable
private data class RawFullLengthSection(
    val sectionId: String = "",
    val order: Int = 0,
    val durationSeconds: Int = 0,
    val questionCount: Int = 0,
    val passages: List<RawFullLengthPassage> = emptyList(),
    val questions: List<RawQuestion> = emptyList(),
)

@Serializable
private data class RawBreak(
    val afterSection: Int = 0,
    val durationSeconds: Int = BREAK_SECONDS,
    val optional: Boolean = true,
    val label: String = "",
)

@Serializable
private data class RawFullLengthBundle(
    val testId: String = "",
    val title: String = "",
    val source: String = "",
    val format: String = "",
    val disclaimer: String = "",
    val totalQuestions: Int = 0,
    val totalTestingSeconds: Int = 0,
    val sections: List<RawFullLengthSection> = emptyList(),
    val breaks: List<RawBreak> = emptyList(),
)

// ---- Parse results --------------------------------------------------------

/** Questions + passages parsed from one practice-question bundle. */
data class ParsedQuestionBundle(
    val questions: List<PracticeQuestion>,
    val passages: List<Passage>,
)

/** A full-length definition plus the section questions/passages it carries. */
data class ParsedFullLengthBundle(
    val test: FullLengthTest,
    val questions: List<PracticeQuestion>,
    val passages: List<Passage>,
)

// ---- Normalization, mirroring practice/mod.rs -----------------------------

/** Canonical DB form of a bundle section string (trim + uppercase). */
fun normalizeSection(s: String): String = s.trim().uppercase()

/** Canonical DB form of a bundle difficulty string (trim + lowercase). */
fun normalizeDifficulty(s: String): String = s.trim().lowercase()

/**
 * Synthesize the standard AAMC MCAT scheduled breaks for a test with
 * [numSections] sections: optional breaks after every section except the last.
 * The break after section 2 (CARS) is the 30-minute mid-exam break; the others
 * are regular 10-minute breaks.
 */
fun synthesizeBreaks(numSections: Int): List<FullLengthBreak> =
    (1 until numSections).map { afterSection ->
        FullLengthBreak(
            afterSection = afterSection,
            durationSeconds = if (afterSection == 2) MID_EXAM_BREAK_SECONDS else BREAK_SECONDS,
            optional = true,
            label = if (afterSection == 2) "Mid-exam break" else "Break",
        )
    }

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
    testId: String?,
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
        testId = testId,
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
                    testId = null,
                ),
            )
        }
        for (raw in set.questions) {
            if (!raw.hasSourceAttribution()) continue
            questions.add(raw.toQuestion(sectionDb, set.passageId, testId = null))
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
                        testId = null,
                    ),
                )
            }
            questions.add(raw.toQuestion(sectionDb, passageId, testId = null))
        } else {
            questions.add(raw.toQuestion(sectionDb, raw.passageId, testId = null))
        }
    }

    return ParsedQuestionBundle(questions, passages)
}

/**
 * Parse a Full-Length Test definition bundle. Scheduled MCAT breaks are
 * synthesized when the bundle omits them, and the testing/question totals fall
 * back to the sum of the section values, mirroring the desktop loader.
 */
fun parseFullLengthBundle(jsonText: String): ParsedFullLengthBundle {
    val bundle = json.decodeFromString<RawFullLengthBundle>(jsonText)

    val sections =
        bundle.sections.map { s ->
            FullLengthSection(
                section = McatSection.fromDb(normalizeSection(s.sectionId)),
                order = s.order,
                durationSeconds = s.durationSeconds,
                questionCount = s.questionCount,
            )
        }
    val breaks =
        if (bundle.breaks.isEmpty()) {
            synthesizeBreaks(bundle.sections.size)
        } else {
            bundle.breaks.map { b ->
                FullLengthBreak(
                    afterSection = b.afterSection,
                    durationSeconds = b.durationSeconds,
                    optional = b.optional,
                    label = b.label.ifEmpty { "Break" },
                )
            }
        }
    val totalTestingSeconds =
        if (bundle.totalTestingSeconds > 0) {
            bundle.totalTestingSeconds
        } else {
            sections.sumOf { it.durationSeconds }
        }
    val totalQuestions =
        if (bundle.totalQuestions > 0) {
            bundle.totalQuestions
        } else {
            sections.sumOf { it.questionCount }
        }
    val test =
        FullLengthTest(
            testId = bundle.testId,
            title = bundle.title,
            source = bundle.source,
            format = bundle.format,
            disclaimer = bundle.disclaimer,
            totalQuestions = totalQuestions,
            totalTestingSeconds = totalTestingSeconds,
            sections = sections,
            breaks = breaks,
            totalBreakSeconds = breaks.sumOf { it.durationSeconds },
        )

    val questions = mutableListOf<PracticeQuestion>()
    val passages = mutableListOf<Passage>()
    for (section in bundle.sections) {
        val sectionDb = normalizeSection(section.sectionId)
        for (p in section.passages) {
            passages.add(
                Passage(
                    passageId = p.passageId,
                    section = McatSection.fromDb(sectionDb),
                    title = p.title,
                    passage = p.passage,
                    discipline = p.discipline,
                    wordCount = p.wordCount,
                    topicTags = p.topicTags,
                    difficulty = Difficulty.fromDb(normalizeDifficulty(p.difficulty)),
                    sourceName = null,
                    sourceLicense = null,
                    testId = bundle.testId,
                ),
            )
        }
        for (raw in section.questions) {
            if (!raw.hasSourceAttribution()) continue
            questions.add(raw.toQuestion(sectionDb, raw.passageId, testId = bundle.testId))
        }
    }

    return ParsedFullLengthBundle(test, questions, passages)
}
