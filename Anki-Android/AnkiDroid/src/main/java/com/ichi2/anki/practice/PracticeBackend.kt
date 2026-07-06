// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors
//
// Fetches engine-recommended practice topics from `PracticeService` in the Rust
// backend when available (`local_backend=true`). The stock prebuilt backend
// lacks PracticeService RPCs — callers should disable the recommended button.

package com.ichi2.anki.practice

import com.ichi2.anki.libanki.Collection
import timber.log.Timber

sealed class RecommendedTopicsResult {
    data class Ok(
        val topics: List<String>,
    ) : RecommendedTopicsResult()

    data class Unavailable(
        val issue: SpeedyCatBackendIssue,
    ) : RecommendedTopicsResult()
}

object PracticeBackend {
    /**
     * Start a practice session via `PracticeService.StartPracticeSession` (Rust
     * engine + collection question bank). Returns null when the RPC is missing or
     * the request fails.
     */
    fun startPracticeSession(
        col: Collection,
        filter: QuestionFilter,
        timeLimitSeconds: Int = 0,
    ): BackendPracticeSession? {
        if (!SpeedyCatBackendCapabilities.hasStartPracticeSession(col.backend)) return null
        if (!SpeedyCatPracticeDbSync.hasPracticeTable(col)) return null
        val requestBytes =
            PracticeProto.buildStartPracticeSessionRequest(filter, timeLimitSeconds) ?: return null
        val responseBytes = callStartPracticeSessionRaw(col, requestBytes) ?: return null
        return PracticeProto.parseStartPracticeSessionResponse(responseBytes)
    }

    /**
     * Sync local attempts into the collection, then call
     * `GetRecommendedPracticeTopics`.
     */
    fun getRecommendedTopics(
        col: Collection,
        attempts: List<Attempt>,
    ): RecommendedTopicsResult {
        if (!SpeedyCatBackendCapabilities.hasGetRecommendedTopics(col.backend)) {
            val issue =
                SpeedyCatBackendCapabilities.recommendedTopicsIssue(col, attemptsSynced = true)
                    ?: SpeedyCatBackendIssue.NotConfigured
            return RecommendedTopicsResult.Unavailable(issue)
        }
        if (!SpeedyCatPracticeDbSync.hasPracticeTable(col)) {
            return RecommendedTopicsResult.Unavailable(SpeedyCatBackendIssue.MissingPracticeTables)
        }
        if (!SpeedyCatPracticeDbSync.syncAttempts(col, attempts)) {
            return RecommendedTopicsResult.Unavailable(SpeedyCatBackendIssue.SyncFailed)
        }
        val responseBytes = callGetRecommendedTopicsRaw(col) ?: return RecommendedTopicsResult.Unavailable(SpeedyCatBackendIssue.StaleLocalAar)
        val topics = parseTopics(responseBytes) ?: return RecommendedTopicsResult.Unavailable(SpeedyCatBackendIssue.SyncFailed)
        return RecommendedTopicsResult.Ok(topics)
    }

    /** @deprecated use [getRecommendedTopics] for actionable error detail */
    fun tryGetRecommendedTopics(
        col: Collection,
        attempts: List<Attempt>,
    ): List<String>? =
        when (val result = getRecommendedTopics(col, attempts)) {
            is RecommendedTopicsResult.Ok -> result.topics
            is RecommendedTopicsResult.Unavailable -> null
        }

    private fun callStartPracticeSessionRaw(
        col: Collection,
        request: ByteArray,
    ): ByteArray? =
        try {
            val method =
                col.backend.javaClass.getMethod(
                    "startPracticeSessionRaw",
                    ByteArray::class.java,
                )
            method.invoke(col.backend, request) as ByteArray
        } catch (e: ReflectiveOperationException) {
            Timber.d(e, "SpeedyCAT: StartPracticeSession RPC unavailable (stock backend?)")
            null
        }

    private fun callGetRecommendedTopicsRaw(col: Collection): ByteArray? =
        try {
            val method =
                col.backend.javaClass.getMethod(
                    "getRecommendedPracticeTopicsRaw",
                    ByteArray::class.java,
                )
            method.invoke(col.backend, ByteArray(0)) as ByteArray
        } catch (e: ReflectiveOperationException) {
            Timber.d(e, "SpeedyCAT: GetRecommendedPracticeTopics RPC unavailable (stock backend?)")
            null
        }

    private fun parseTopics(bytes: ByteArray): List<String>? =
        try {
            val responseClass = Class.forName("anki.practice.GetRecommendedPracticeTopicsResponse")
            val parseFrom = responseClass.getMethod("parseFrom", ByteArray::class.java)
            val response = parseFrom.invoke(null, bytes)
            @Suppress("UNCHECKED_CAST")
            val list = responseClass.getMethod("getTopicsList").invoke(response) as List<String>
            list.toList()
        } catch (e: ReflectiveOperationException) {
            Timber.w(e, "SpeedyCAT: failed to parse GetRecommendedPracticeTopicsResponse")
            null
        }
}
