// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors
//
// Cross-device sync of practice + full-length RESULTS, mobile side.
//
// Practice attempt history is device-local telemetry that never touches the
// synced Anki collection tables. To move it across devices over *stock* AnkiWeb
// sync we ride the MEDIA channel: each device writes ONE JSON file into
// `collection.media` named `_speedycat_results_<deviceId>.json`. Media files
// sync bidirectionally and merge per-file (distinct per-device names never
// clobber), survive the desktop's schema-19 strip (they aren't in the .anki2 at
// all), and are preserved by Check Media because the name starts with `_`
// (see anki/rslib/src/media/check.rs). This file is the on-disk CONTRACT shared
// with the desktop `anki/pylib/anki/speedycat_sync.py`; keep the two in lockstep.
//
// This module is framework-free (kotlinx.serialization + pure functions) so it
// is unit-tested directly in SpeedyCatResultsTest. The device id + file IO glue
// lives in PracticeResultsSync.

package com.ichi2.anki.practice

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Bump when the on-disk JSON shape changes incompatibly. */
const val RESULTS_SCHEMA_VERSION = 1

private const val FILENAME_PREFIX = "_speedycat_results_"
private const val FILENAME_SUFFIX = ".json"
private val FILENAME_RE = Regex("^_speedycat_results_([A-Za-z0-9_-]+)\\.json$")
private val DEVICE_ID_SANITIZE_RE = Regex("[^A-Za-z0-9_-]")

private val resultsJson =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

/**
 * One practice-session attempt as carried over the channel. Field names ARE the
 * JSON keys and must match the desktop contract. All fields default so a single
 * malformed row can never fail the whole file (empty-id rows are dropped in
 * [mergeAttempts]).
 */
@Serializable
data class SyncedAttempt(
    val id: String = "",
    val sessionId: String? = null,
    val questionId: String = "",
    val selectedAnswer: String = "",
    val correct: Boolean = false,
    val timeSeconds: Int = 0,
    val section: String = "",
    val topic: String = "",
    val answeredAt: Long = 0,
    // SpeedyCAT graduated hint ladder: carried so the Performance pillar's
    // anti-gaming penalty stays consistent across devices. Default 0/false so
    // older files (written before the hint ladder) parse cleanly.
    val hintLevelUsed: Int = 0,
    val assisted: Boolean = false,
    val mainWrongFirst: Boolean = false,
    val firstTryNoHint: Int? = null,
)

/** One section's raw score within a full-length summary. */
@Serializable
data class FullLengthSectionSummary(
    val section: String = "",
    val correct: Int = 0,
    val total: Int = 0,
    val timeSeconds: Int = 0,
    val scaledScore: Int? = null,
)

/**
 * A compact per-test summary of a COMPLETED full-length exam. Produced by the
 * desktop app only (full-length was removed from mobile); mobile renders these
 * read-only in the 3rd Readiness pillar + results card.
 */
@Serializable
data class FullLengthSummary(
    val attemptId: String = "",
    val testId: String = "",
    val title: String = "",
    val startedAt: Long = 0,
    val completedAt: Long = 0,
    val totalCorrect: Int = 0,
    val totalQuestions: Int = 0,
    val overallScaledScore: Int? = null,
    val sections: List<FullLengthSectionSummary> = emptyList(),
)

/** The whole per-device results file. */
@Serializable
data class ResultsFile(
    val schema: Int = RESULTS_SCHEMA_VERSION,
    val deviceId: String = "",
    val updatedAt: Long = 0,
    val attempts: List<SyncedAttempt> = emptyList(),
    val fullLength: List<FullLengthSummary> = emptyList(),
)

// ---- Filename helpers ------------------------------------------------------

/** Reduce an arbitrary device id to a safe media-filename token. */
fun sanitizeDeviceId(deviceId: String): String = DEVICE_ID_SANITIZE_RE.replace(deviceId, "").ifEmpty { "unknown" }

fun resultsFilename(deviceId: String): String = "$FILENAME_PREFIX${sanitizeDeviceId(deviceId)}$FILENAME_SUFFIX"

fun deviceIdFromFilename(fname: String): String? = FILENAME_RE.matchEntire(fname)?.groupValues?.get(1)

// ---- Model conversions -----------------------------------------------------

fun Attempt.toSynced(): SyncedAttempt =
    SyncedAttempt(
        id = id,
        sessionId = sessionId,
        questionId = questionId,
        selectedAnswer = selectedAnswer,
        correct = correct,
        timeSeconds = timeSeconds,
        section = section?.dbCode ?: "",
        topic = topic,
        answeredAt = answeredAt,
        hintLevelUsed = hintLevelUsed,
        assisted = assisted,
        mainWrongFirst = mainWrongFirst,
        firstTryNoHint = firstTryNoHint,
    )

fun SyncedAttempt.toAttempt(): Attempt =
    Attempt(
        id = id,
        sessionId = sessionId,
        questionId = questionId,
        selectedAnswer = selectedAnswer,
        correct = correct,
        timeSeconds = timeSeconds,
        section = McatSection.fromDb(section),
        topic = topic,
        answeredAt = answeredAt,
        hintLevelUsed = hintLevelUsed,
        assisted = assisted,
        mainWrongFirst = mainWrongFirst,
        firstTryNoHint = firstTryNoHint,
    )

// ---- Serialize / parse / merge --------------------------------------------

fun serializeResults(
    deviceId: String,
    attempts: List<SyncedAttempt>,
    now: Long,
    fullLength: List<FullLengthSummary> = emptyList(),
): String =
    resultsJson.encodeToString(
        ResultsFile(
            schema = RESULTS_SCHEMA_VERSION,
            deviceId = deviceId,
            updatedAt = now,
            attempts = attempts,
            fullLength = fullLength,
        ),
    )

/** Parse a results file, returning null on any malformed input. */
fun parseResults(text: String): ResultsFile? =
    try {
        resultsJson.decodeFromString<ResultsFile>(text)
    } catch (_: Exception) {
        null
    }

/**
 * Union + dedup attempts by stable id across any number of source files. On an
 * id collision the row with the greater answeredAt wins (a later re-answer
 * supersedes), so the merge is deterministic regardless of file order. Rows with
 * an empty id are dropped. Output is sorted by id for stability.
 */
fun mergeAttempts(attemptLists: List<List<SyncedAttempt>>): List<SyncedAttempt> {
    val byId = HashMap<String, SyncedAttempt>()
    for (list in attemptLists) {
        for (a in list) {
            if (a.id.isEmpty()) continue
            val existing = byId[a.id]
            if (existing == null || a.answeredAt >= existing.answeredAt) {
                byId[a.id] = a
            }
        }
    }
    return byId.values.sortedBy { it.id }
}
