// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors
//
// Unit tests for cross-device results sync (media-channel transport): the on-disk
// JSON contract shared with the desktop `anki/pylib/anki/speedycat_sync.py`, the
// union+dedup merge, and the per-device media-file read/write glue.

package com.ichi2.anki.practice

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.nullValue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SpeedyCatResultsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun attempt(
        id: String,
        correct: Boolean = true,
        section: McatSection? = McatSection.CPBS,
        answeredAt: Long = 1000,
        sessionId: String? = "ps-x",
    ) = Attempt(
        id = id,
        sessionId = sessionId,
        questionId = id.substringAfter(":"),
        selectedAnswer = if (correct) "A" else "B",
        correct = correct,
        timeSeconds = 30,
        section = section,
        topic = "kinetics",
        answeredAt = answeredAt,
    )

    // ---- Contract: serialize / parse ------------------------------------

    @Test
    fun `serialize then parse round-trips attempts`() {
        val payload =
            serializeResults(
                deviceId = "mobile",
                attempts = listOf(attempt("ps-x:q1").toSynced(), attempt("ps-x:q2", correct = false).toSynced()),
                now = 42,
            )
        val parsed = parseResults(payload)!!
        assertThat(parsed.deviceId, equalTo("mobile"))
        assertThat(parsed.schema, equalTo(RESULTS_SCHEMA_VERSION))
        assertThat(parsed.updatedAt, equalTo(42L))
        assertThat(parsed.attempts, hasSize(2))
        assertThat(parsed.attempts.map { it.id }, contains("ps-x:q1", "ps-x:q2"))
        assertThat(parsed.attempts[0].correct, equalTo(true))
        assertThat(parsed.attempts[1].selectedAnswer, equalTo("B"))
    }

    @Test
    fun `parse rejects garbage and tolerates missing fields`() {
        assertThat(parseResults("not json"), nullValue())
        assertThat(parseResults("[1,2,3]"), nullValue())
        val minimal = parseResults("""{"deviceId":"x"}""")!!
        assertThat(minimal.attempts, hasSize(0))
        assertThat(minimal.fullLength, hasSize(0))
    }

    /** The desktop writes the same camelCase keys; parsing its shape must work. */
    @Test
    fun `parses a desktop-shaped results file including full-length summary`() {
        val desktopJson =
            """
            {"schema":1,"deviceId":"desktop","updatedAt":123,
             "attempts":[{"id":"ps-d:q1","sessionId":"ps-d","questionId":"q1",
               "selectedAnswer":"A","correct":true,"timeSeconds":30,"section":"CPBS",
               "topic":"kinetics","answeredAt":1000}],
             "fullLength":[{"attemptId":"fla-1","testId":"fl-1","title":"Full Length 1",
               "startedAt":1,"completedAt":2000,"totalCorrect":70,"totalQuestions":112,
               "overallScaledScore":null,
               "sections":[{"section":"CPBS","correct":40,"total":59,"timeSeconds":5100,
                 "scaledScore":null}]}]}
            """.trimIndent()
        val parsed = parseResults(desktopJson)!!
        assertThat(parsed.attempts, hasSize(1))
        assertThat(parsed.attempts[0].id, equalTo("ps-d:q1"))
        assertThat(parsed.fullLength, hasSize(1))
        val fl = parsed.fullLength[0]
        assertThat(fl.attemptId, equalTo("fla-1"))
        assertThat(fl.totalCorrect, equalTo(70))
        assertThat(fl.sections, hasSize(1))
        assertThat(fl.sections[0].section, equalTo("CPBS"))
        assertThat(fl.sections[0].correct, equalTo(40))
    }

    @Test
    fun `attempt survives conversion to synced and back`() {
        val original = attempt("ps-x:q1", correct = false, section = McatSection.CARS)
        val restored = original.toSynced().toAttempt()
        assertThat(restored, equalTo(original))
    }

    // ---- Merge / dedup ---------------------------------------------------

    @Test
    fun `merge dedups by id and keeps the latest answer`() {
        val old = attempt("ps:q", correct = false, answeredAt = 100).toSynced()
        val new = attempt("ps:q", correct = true, answeredAt = 200).toSynced()
        val other = attempt("ps:q2", answeredAt = 50).toSynced()
        val merged = mergeAttempts(listOf(listOf(old, other), listOf(new)))
        assertThat(merged.map { it.id }, contains("ps:q", "ps:q2"))
        assertThat(merged.first { it.id == "ps:q" }.correct, equalTo(true))
    }

    @Test
    fun `merge drops rows with an empty id`() {
        val bad = SyncedAttempt(id = "")
        assertThat(mergeAttempts(listOf(listOf(bad))), hasSize(0))
    }

    // ---- Filename helpers ------------------------------------------------

    @Test
    fun `filename helpers round-trip and sanitize`() {
        assertThat(resultsFilename("abc123"), equalTo("_speedycat_results_abc123.json"))
        assertThat(resultsFilename("a/b .c"), equalTo("_speedycat_results_abc.json"))
        assertThat(deviceIdFromFilename("_speedycat_results_abc123.json"), equalTo("abc123"))
        assertThat(deviceIdFromFilename("cat.jpg"), nullValue())
        assertThat(deviceIdFromFilename("_speedycat_results_.json"), nullValue())
    }

    // ---- Media-folder IO -------------------------------------------------

    @Test
    fun `publish writes a stable-named file and overwrites in place`() {
        val dir = tempFolder.newFolder("media")
        val first = PracticeResultsSync.publish(dir, "mobile", listOf(attempt("ps-x:q1")), now = 1)
        assertThat(first, equalTo("_speedycat_results_mobile.json"))

        // Re-publishing with more data overwrites the SAME file (no hash suffix).
        PracticeResultsSync.publish(dir, "mobile", listOf(attempt("ps-x:q1"), attempt("ps-x:q2")), now = 2)
        val speedycatFiles = dir.listFiles()!!.map { it.name }.filter { it.startsWith("_speedycat") }
        assertThat(speedycatFiles, contains("_speedycat_results_mobile.json"))
        val parsed = parseResults(java.io.File(dir, first!!).readText())!!
        assertThat(parsed.attempts, hasSize(2))
    }

    @Test
    fun `ingest reads other devices files and skips our own`() {
        val dir = tempFolder.newFolder("media")
        // Our own published file must be ignored by ingest.
        PracticeResultsSync.publish(dir, "mobile", listOf(attempt("ps-mine:q1")), now = 1)
        // A remote desktop file with attempts + a full-length summary.
        val remote =
            serializeResults(
                deviceId = "desktop",
                attempts = listOf(attempt("ps-d:q1").toSynced(), attempt("ps-d:q2", correct = false).toSynced()),
                fullLength =
                    listOf(
                        FullLengthSummary(
                            attemptId = "fla-1",
                            title = "FL 1",
                            completedAt = 2000,
                            totalCorrect = 70,
                            totalQuestions = 112,
                            sections = listOf(FullLengthSectionSummary("CPBS", 40, 59, 5100, null)),
                        ),
                    ),
                now = 5,
            )
        java.io.File(dir, resultsFilename("desktop")).writeText(remote)

        val attempts = PracticeResultsSync.remoteAttempts(dir, "mobile")
        assertThat(attempts.map { it.id }, containsInAnyOrder("ps-d:q1", "ps-d:q2"))

        val summaries = PracticeResultsSync.remoteFullLength(dir, "mobile")
        assertThat(summaries, hasSize(1))
        assertThat(summaries[0].totalCorrect, equalTo(70))
    }
}
