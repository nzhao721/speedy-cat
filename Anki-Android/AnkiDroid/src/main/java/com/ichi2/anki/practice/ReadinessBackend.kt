// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors
//
// Fetches the three readiness pillars from `PracticeService.GetReadiness` in the
// Rust backend when available (`local_backend=true`). The stock prebuilt backend
// lacks PracticeService RPCs — callers fall back to ReadinessLogic.kt.

package com.ichi2.anki.practice

import com.ichi2.anki.libanki.Collection
import timber.log.Timber

/** Pillars returned by the Rust backend (Memory + Performance + optional Readiness). */
data class BackendReadinessPillars(
    val memory: ReadinessPillar,
    val performance: ReadinessPillar,
    val readiness: ReadinessPillar?,
    val performanceAvgSeconds: Double,
)

object ReadinessBackend {
    /**
     * Try `GetReadiness` after syncing [attempts] into the collection DB.
     * Returns null when the RPC or protobuf classes are unavailable (stock backend).
     */
    fun tryFetchPillars(
        col: Collection,
        attempts: List<Attempt>,
    ): BackendReadinessPillars? {
        if (!SpeedyCatPracticeDbSync.syncAttempts(col, attempts)) return null
        val responseBytes =
            callGetReadinessRaw(col, emptyGetReadinessRequest()) ?: return null
        return parseResponse(responseBytes)
    }

    private fun emptyGetReadinessRequest(): ByteArray = ByteArray(0)

    private fun callGetReadinessRaw(
        col: Collection,
        request: ByteArray,
    ): ByteArray? =
        try {
            val method =
                col.backend.javaClass.getMethod("getReadinessRaw", ByteArray::class.java)
            method.invoke(col.backend, request) as ByteArray
        } catch (e: ReflectiveOperationException) {
            Timber.d(e, "SpeedyCAT: GetReadiness RPC unavailable (stock backend?)")
            null
        }

    private fun parseResponse(bytes: ByteArray): BackendReadinessPillars? =
        try {
            val responseClass = Class.forName("anki.practice.GetReadinessResponse")
            val parseFrom = responseClass.getMethod("parseFrom", ByteArray::class.java)
            val response = parseFrom.invoke(null, bytes)

            val memory = pillarFromProto("Memory", responseClass.getMethod("getMemory").invoke(response))
            val performance =
                pillarFromProto("Performance", responseClass.getMethod("getPerformance").invoke(response))
            val readinessProto = responseClass.getMethod("getReadiness").invoke(response)
            val readiness =
                if (readinessProto != null) {
                    pillarFromProto("Readiness", readinessProto)
                } else {
                    null
                }
            val avgSeconds =
                (responseClass.getMethod("getPerformanceAvgSeconds").invoke(response) as Double)
                    .coerceAtLeast(0.0)
            val timePenaltyApplied =
                try {
                    responseClass.getMethod("getPerformanceTimePenaltyApplied").invoke(response) as Boolean
                } catch (_: ReflectiveOperationException) {
                    performanceTimePenaltyFactor(avgSeconds) < 1.0
                }

            BackendReadinessPillars(
                memory = memory,
                performance = performance.withPerformanceExtras(avgSeconds, timePenaltyApplied),
                readiness = readiness,
                performanceAvgSeconds = avgSeconds,
            )
        } catch (e: ReflectiveOperationException) {
            Timber.w(e, "SpeedyCAT: failed to parse GetReadinessResponse")
            null
        }

    private fun pillarFromProto(
        name: String,
        proto: Any?,
    ): ReadinessPillar {
        if (proto == null) {
            return ReadinessPillar(
                name = name,
                sufficient = false,
                value = "\u2014",
                range = "",
                rangeCaption = CI_CAPTION,
                source = "Rust backend (PracticeService.GetReadiness)",
                detail = emptyList(),
                insufficientReason = "Readiness data unavailable.",
            )
        }
        val cls = proto.javaClass
        val available = cls.getMethod("getAvailable").invoke(proto) as Boolean
        val value = cls.getMethod("getValue").invoke(proto) as Double
        val rangeLow = cls.getMethod("getRangeLow").invoke(proto) as Double
        val rangeHigh = cls.getMethod("getRangeHigh").invoke(proto) as Double
        val sampleSize = cls.getMethod("getSampleSize").invoke(proto) as Int
        val source = cls.getMethod("getSource").invoke(proto) as String
        val message = cls.getMethod("getMessage").invoke(proto) as String

        return ReadinessPillar(
            name = name,
            sufficient = available,
            value = if (available) formatPercent(value) else "\u2014",
            range = if (available) formatPercentRange(ConfidenceInterval(rangeLow, rangeHigh)) else "",
            rangeCaption = CI_CAPTION,
            source = source.ifEmpty { "Rust backend (PracticeService.GetReadiness)" },
            detail = emptyList(),
            insufficientReason = message,
            sampleSize = sampleSize,
        )
    }

    private fun ReadinessPillar.withPerformanceExtras(
        avgSeconds: Double,
        timePenaltyApplied: Boolean,
    ): ReadinessPillar = this
}
