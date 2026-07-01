// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors
//
// Unit tests for the content bundle parsing + MCAT break synthesis. These mirror
// the desktop Rust loader/service tests in `anki/rslib/src/practice` so the
// mobile ingestion of the identical shipped JSON produces the same questions,
// passages, full-length definitions and scheduled breaks.

package com.ichi2.anki.practice

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.Test

class PracticeContentTest {
    @Test
    fun `normalization matches the desktop canonical forms`() {
        assertThat(normalizeSection("  cpbs "), equalTo("CPBS"))
        assertThat(normalizeSection("Cars"), equalTo("CARS"))
        assertThat(normalizeDifficulty("  Easy "), equalTo("easy"))
    }

    @Test
    fun `synthesizeBreaks places optional breaks after every section but the last`() {
        val breaks = synthesizeBreaks(4)
        assertThat(breaks.size, equalTo(3))
        assertThat(breaks[0].afterSection, equalTo(1))
        assertThat(breaks[0].label, equalTo("Break"))
        assertThat(breaks[0].durationSeconds, equalTo(600))
        assertThat(breaks[1].afterSection, equalTo(2))
        assertThat(breaks[1].label, equalTo("Mid-exam break"))
        assertThat(breaks[1].durationSeconds, equalTo(1800))
        assertThat(breaks[2].afterSection, equalTo(3))
        assertThat(breaks[2].label, equalTo("Break"))
        assertThat(breaks[2].durationSeconds, equalTo(600))
        assertThat(breaks.all { it.optional }, equalTo(true))
        // 10-min + 30-min mid-exam + 10-min = 50 min of scheduled breaks.
        assertThat(breaks.sumOf { it.durationSeconds }, equalTo(3000))
        // A single-section test synthesizes no breaks.
        assertThat(synthesizeBreaks(1).isEmpty(), equalTo(true))
    }

    @Test
    fun `question bundle ingests discrete and passage-set items`() {
        val json =
            """
            {
              "meta": {"title": "test"},
              "questions": [
                {"id": "cpbs-001", "section": "CPBS", "stem": "q?",
                 "choices": [{"label":"A","text":"x"},{"label":"B","text":"y"}],
                 "correctAnswer": "A", "explanation": "because",
                 "topicTags": ["general chemistry"], "difficulty": "easy",
                 "sourceName": "OpenStax", "sourceLicense": "CC BY 4.0"},
                {"id": "cars-001", "section": "CARS", "passageId": "p1",
                 "passage": "Long passage text.", "stem": "main idea?",
                 "choices": [{"label":"A","text":"x"},{"label":"B","text":"y"}],
                 "correctAnswer": "B", "explanation": "e",
                 "topicTags": ["humanities"], "difficulty": "medium",
                 "sourceName": "AI-generated", "sourceLicense": "POC"}
              ],
              "passageSets": [
                {"passageId": "p2", "section": "CARS", "title": "Set",
                 "passage": "Another passage.", "discipline": "humanities",
                 "wordCount": 3, "topicTags": ["ethics"], "difficulty": "hard",
                 "sourceName": "AI-generated", "sourceLicense": "POC",
                 "questions": [
                    {"id": "cars-100", "section": "CARS", "passageId": "p2",
                     "stem": "q?", "choices": [{"label":"A","text":"x"},{"label":"B","text":"y"}],
                     "correctAnswer": "A", "explanation": "e",
                     "topicTags": ["ethics"], "difficulty": "hard",
                     "sourceName": "AI-generated", "sourceLicense": "POC"}
                 ]}
              ]
            }
            """.trimIndent()
        val parsed = parseQuestionBundle(json)
        assertThat(parsed.questions.size, equalTo(3))
        assertThat(parsed.passages.size, equalTo(2))

        val cpbs = parsed.questions.first { it.id == "cpbs-001" }
        assertThat(cpbs.section, equalTo(McatSection.CPBS))
        assertThat(cpbs.difficulty, equalTo(Difficulty.EASY))

        val p1 = parsed.passages.first { it.passageId == "p1" }
        assertThat(p1.passage, equalTo("Long passage text."))

        val cars100 = parsed.questions.first { it.id == "cars-100" }
        assertThat(cars100.section, equalTo(McatSection.CARS))
        assertThat(cars100.passageId, equalTo("p2"))
    }

    @Test
    fun `question bundle skips items missing source attribution`() {
        val json =
            """
            {
              "questions": [
                {"id": "q1", "section": "CPBS", "stem": "q?",
                 "choices": [{"label":"A","text":"x"},{"label":"B","text":"y"}],
                 "correctAnswer": "A", "explanation": "e", "topicTags": ["kinetics"],
                 "difficulty": "easy", "sourceName": "", "sourceLicense": "CC BY 4.0"}
              ]
            }
            """.trimIndent()
        assertThat(parseQuestionBundle(json).questions.isEmpty(), equalTo(true))
    }

    @Test
    fun `two-section full-length uses one break and the provided testing time`() {
        val json =
            """
            {
              "testId": "fl-test", "title": "FL", "source": "AI", "format": "AAMC",
              "disclaimer": "POC", "totalQuestions": 2, "totalTestingSeconds": 11400,
              "sections": [
                {"sectionId": "CPBS", "order": 1, "durationSeconds": 5700, "questionCount": 1,
                 "questions": [{"id": "fl-c1", "section": "CPBS", "stem": "q?",
                    "choices": [{"label":"A","text":"x"},{"label":"B","text":"y"}],
                    "correctAnswer": "A", "explanation": "e", "topicTags": ["electrochemistry"],
                    "difficulty": "medium", "sourceName": "AI", "sourceLicense": "POC"}]},
                {"sectionId": "CARS", "order": 2, "durationSeconds": 5400, "questionCount": 1,
                 "questions": [{"id": "fl-r1", "section": "CARS", "stem": "q?",
                    "choices": [{"label":"A","text":"x"},{"label":"B","text":"y"}],
                    "correctAnswer": "A", "explanation": "e", "topicTags": ["ethics"],
                    "difficulty": "medium", "sourceName": "AI", "sourceLicense": "POC"}]}
              ]
            }
            """.trimIndent()
        val parsed = parseFullLengthBundle(json)
        assertThat(parsed.test.sections.size, equalTo(2))
        assertThat(parsed.test.totalTestingSeconds, equalTo(11400))
        assertThat(parsed.test.breaks.size, equalTo(1))
        assertThat(parsed.test.breaks[0].afterSection, equalTo(1))
        assertThat(parsed.test.breaks[0].durationSeconds, equalTo(600))
        assertThat(parsed.test.breaks[0].optional, equalTo(true))
        assertThat(parsed.test.totalBreakSeconds, equalTo(600))
        assertThat(parsed.questions.size, equalTo(2))
        assertThat(parsed.questions.all { it.testId == "fl-test" }, equalTo(true))
    }

    @Test
    fun `four-section full-length synthesizes breaks and derives totals`() {
        val json =
            """
            {
              "testId": "fl-4", "title": "Full", "source": "AI", "format": "AAMC",
              "sections": [
                {"sectionId": "CPBS", "order": 1, "durationSeconds": 5700, "questionCount": 0},
                {"sectionId": "CARS", "order": 2, "durationSeconds": 5400, "questionCount": 0},
                {"sectionId": "BBLS", "order": 3, "durationSeconds": 5700, "questionCount": 0},
                {"sectionId": "PSBB", "order": 4, "durationSeconds": 5700, "questionCount": 0}
              ]
            }
            """.trimIndent()
        val test = parseFullLengthBundle(json).test
        assertThat(test.sections.size, equalTo(4))
        assertThat(test.breaks.size, equalTo(3))
        assertThat(test.breaks[0].afterSection, equalTo(1))
        assertThat(test.breaks[0].label, equalTo("Break"))
        assertThat(test.breaks[0].durationSeconds, equalTo(600))
        assertThat(test.breaks[1].afterSection, equalTo(2))
        assertThat(test.breaks[1].label, equalTo("Mid-exam break"))
        assertThat(test.breaks[1].durationSeconds, equalTo(1800))
        assertThat(test.breaks[2].afterSection, equalTo(3))
        assertThat(test.breaks[2].label, equalTo("Break"))
        assertThat(test.breaks[2].durationSeconds, equalTo(600))
        // 10-min + 30-min mid-exam + 10-min = 50 min of scheduled breaks.
        assertThat(test.totalBreakSeconds, equalTo(3000))
        // total_testing_seconds defaults to the sum of section durations.
        assertThat(test.totalTestingSeconds, equalTo(22500))
    }
}
