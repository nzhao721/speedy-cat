// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors
//
// Unit tests for the practice-question content bundle parsing. These mirror the
// desktop Rust loader/service tests in `anki/rslib/src/practice` so the mobile
// ingestion of the identical shipped JSON produces the same questions and
// passages.

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
}
