// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors
//
// Fetches the three readiness pillars from `PracticeService.GetReadiness` in the
// Rust backend when available (`local_backend=true`). The stock prebuilt backend
// lacks PracticeService RPCs — callers fall back to ReadinessLogic.kt.

package com.ichi2.anki.practice

import com.ichi2.anki.libanki.Collection
import timber.log.Timber

/** Pillars returned by the Rust backend (Memory + Performance + Readiness). */
data class BackendReadinessPillars(
    val memory: ReadinessPillar,
    val performance: ReadinessPillar,
    val readiness: ReadinessPillar,
    val performanceAvgSeconds: Double,
    val projected: ProjectedMcatScore?,
)

object ReadinessBackend {
    /**
     * Sync local attempts and desktop full-length summaries into the collection,
     * then try `GetReadiness`. Returns null when the RPC or protobuf classes are
     * unavailable (stock backend).
     */
    fun tryFetchPillars(
        col: Collection,
        attempts: List<Attempt>,
        summaries: List<FullLengthSummary>,
    ): BackendReadinessPillars? {
        if (!SpeedyCatPracticeDbSync.syncAttempts(col, attempts)) return null
        SpeedyCatPracticeDbSync.syncFullLengthSummaries(col, summaries)
        val responseBytes = callGetReadinessRaw(col, emptyGetReadinessRequest()) ?: return null
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

            val memory =
                pillarFromProto("Memory", responseClass.getMethod("getMemory").invoke(response), breakdownFromProto(response, responseClass, "getMemoryBreakdown", includeTopics = false))
            val performance =
                pillarFromProto("Performance", responseClass.getMethod("getPerformance").invoke(response), breakdownFromProto(response, responseClass, "getPerformanceBreakdown", includeTopics = true))
            val readiness =
                pillarFromProto("Readiness", responseClass.getMethod("getReadiness").invoke(response), breakdownFromProto(response, responseClass, "getReadinessBreakdown", includeTopics = false))
            val avgSeconds =
                protoDouble(response, responseClass, "getPerformanceAvgSeconds").coerceAtLeast(0.0)
            val projected = projectedFromProto(response, responseClass)

            BackendReadinessPillars(
                memory = memory,
                performance = performance,
                readiness = readiness,
                performanceAvgSeconds = avgSeconds,
                projected = projected,
            )
        } catch (e: ReflectiveOperationException) {
            Timber.w(e, "SpeedyCAT: failed to parse GetReadinessResponse")
            null
        }

    private fun protoDouble(
        obj: Any,
        cls: Class<*>,
        method: String,
    ): Double {
        val value = cls.getMethod(method).invoke(obj)
        return when (value) {
            is Double -> value
            is Float -> value.toDouble()
            is Number -> value.toDouble()
            else -> 0.0
        }
    }

    private fun pillarFromProto(
        name: String,
        proto: Any?,
        breakdown: PillarBreakdowns?,
    ): ReadinessPillar {
        if (proto == null) {
            return unavailablePillar(name, "Readiness data unavailable.")
        }
        val cls = proto.javaClass
        val available = cls.getMethod("getAvailable").invoke(proto) as Boolean
        val value = protoDouble(proto, cls, "getValue")
        val rangeLow = protoDouble(proto, cls, "getRangeLow")
        val rangeHigh = protoDouble(proto, cls, "getRangeHigh")
        val sampleSize = cls.getMethod("getSampleSize").invoke(proto) as Int
        val source = cls.getMethod("getSource").invoke(proto) as String
        val message = cls.getMethod("getMessage").invoke(proto) as String

        val sufficient =
            available &&
                message.isBlank() &&
                !(rangeLow == 0.0 && rangeHigh == 0.0 && value == 0.0 && sampleSize == 0)

        if (!sufficient) {
            return ReadinessPillar(
                name = name,
                sufficient = false,
                value = "\u2014",
                range = "",
                rangeCaption = CI_CAPTION,
                source = source.ifEmpty { "Rust backend (PracticeService.GetReadiness)" },
                detail = emptyList(),
                insufficientReason =
                    message.ifBlank {
                        "Not enough data yet."
                    },
                sampleSize = sampleSize,
                breakdown = breakdown,
            )
        }

        return ReadinessPillar(
            name = name,
            sufficient = true,
            value = formatPercent(value),
            range = formatPercentRange(ConfidenceInterval(rangeLow, rangeHigh)),
            rangeCaption = CI_CAPTION,
            source = source.ifEmpty { "Rust backend (PracticeService.GetReadiness)" },
            detail = emptyList(),
            insufficientReason = "",
            sampleSize = sampleSize,
            breakdown = breakdown,
        )
    }

    private fun breakdownFromProto(
        response: Any,
        responseClass: Class<*>,
        getter: String,
        includeTopics: Boolean,
    ): PillarBreakdowns? {
        val breakdown =
            try {
                responseClass.getMethod(getter).invoke(response) ?: return null
            } catch (_: ReflectiveOperationException) {
                return null
            }
        val cls = breakdown.javaClass
        val sections =
            try {
                @Suppress("UNCHECKED_CAST")
                val list = cls.getMethod("getSectionsList").invoke(breakdown) as List<Any>
                list.mapNotNull { sectionRowFromProto(it) }
            } catch (_: ReflectiveOperationException) {
                emptyList()
            }
        val topics =
            if (includeTopics) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    val list = cls.getMethod("getTopicsList").invoke(breakdown) as List<Any>
                    list.mapNotNull { topicRowFromProto(it) }
                } catch (_: ReflectiveOperationException) {
                    emptyList()
                }
            } else {
                emptyList()
            }
        if (sections.isEmpty() && topics.isEmpty()) return null
        return PillarBreakdowns(sections = sections, topics = topics)
    }

    private fun sectionRowFromProto(proto: Any): PillarBreakdownRow? {
        val cls = proto.javaClass
        val sectionEnum =
            try {
                cls.getMethod("getSection").invoke(proto) as? Enum<*>
            } catch (_: ReflectiveOperationException) {
                null
            }
        val label = sectionEnum?.name?.let(::sectionLabelFromProto) ?: return null
        return breakdownRowFromProto(proto, label)
    }

    private fun topicRowFromProto(proto: Any): PillarBreakdownRow? {
        val cls = proto.javaClass
        val topic =
            try {
                cls.getMethod("getTopic").invoke(proto) as String
            } catch (_: ReflectiveOperationException) {
                return null
            }
        val label = formatTopicLabel(topic)
        return breakdownRowFromProto(proto, label)
    }

    private fun breakdownRowFromProto(
        proto: Any,
        label: String,
    ): PillarBreakdownRow {
        val cls = proto.javaClass
        val available =
            try {
                cls.getMethod("getAvailable").invoke(proto) as Boolean
            } catch (_: ReflectiveOperationException) {
                false
            }
        if (!available) {
            return PillarBreakdownRow(
                label = label,
                value = PILLAR_BREAKDOWN_INSUFFICIENT_VALUE,
                sufficient = false,
            )
        }
        val rangeLow = protoDouble(proto, cls, "getRangeLow")
        val rangeHigh = protoDouble(proto, cls, "getRangeHigh")
        val value = formatPercentRange(ConfidenceInterval(rangeLow, rangeHigh))
        return PillarBreakdownRow(label = label, value = value, sufficient = true)
    }

    private fun unavailablePillar(
        name: String,
        reason: String,
    ): ReadinessPillar =
        ReadinessPillar(
            name = name,
            sufficient = false,
            value = "\u2014",
            range = "",
            rangeCaption = CI_CAPTION,
            source = "Rust backend (PracticeService.GetReadiness)",
            detail = emptyList(),
            insufficientReason = reason,
        )

    private fun projectedFromProto(
        response: Any,
        responseClass: Class<*>,
    ): ProjectedMcatScore? {
        val projected =
            try {
                responseClass.getMethod("getProjected").invoke(response)
            } catch (_: ReflectiveOperationException) {
                return null
            } ?: return null
        val cls = projected.javaClass
        val available =
            try {
                cls.getMethod("getAvailable").invoke(projected) as Boolean
            } catch (_: ReflectiveOperationException) {
                return null
            }
        val source =
            try {
                cls.getMethod("getSource").invoke(projected) as String
            } catch (_: ReflectiveOperationException) {
                ""
            }
        val message =
            try {
                cls.getMethod("getMessage").invoke(projected) as String
            } catch (_: ReflectiveOperationException) {
                ""
            }
        if (!available) {
            return ProjectedMcatScore(
                sufficient = false,
                totalRange = "",
                sections = emptyList(),
                insufficientReason = message.ifBlank { "Not enough data yet." },
                source = source.ifEmpty { "Rust backend (PracticeService.GetReadiness)" },
            )
        }
        val total = protoUInt(projected, cls, "getTotal")
        val totalLow = protoUInt(projected, cls, "getTotalLow")
        val totalHigh = protoUInt(projected, cls, "getTotalHigh")
        val sections =
            try {
                @Suppress("UNCHECKED_CAST")
                val list = cls.getMethod("getSectionsList").invoke(projected) as List<Any>
                list.mapNotNull { sectionProto -> projectedSectionLine(sectionProto) }
            } catch (_: ReflectiveOperationException) {
                emptyList()
            }
        return ProjectedMcatScore(
            sufficient = total != null,
            totalRange =
                if (total != null && totalLow != null && totalHigh != null) {
                    "$total ($totalLow\u2013$totalHigh)"
                } else {
                    ""
                },
            total = total,
            totalLow = totalLow,
            totalHigh = totalHigh,
            sections = sections,
            insufficientReason = "",
            source = source.ifEmpty { "Rust backend (PracticeService.GetReadiness)" },
        )
    }

    private fun projectedSectionLine(sectionProto: Any): ProjectedSectionLine? {
        val cls = sectionProto.javaClass
        val sectionEnum =
            try {
                cls.getMethod("getSection").invoke(sectionProto) as? Enum<*>
            } catch (_: ReflectiveOperationException) {
                null
            }
        val label = sectionEnum?.name?.let(::sectionLabelFromProto) ?: return null
        val scaled = protoUInt(sectionProto, cls, "getScaledScore")
        val scaledLow = protoUInt(sectionProto, cls, "getScaledLow")
        val scaledHigh = protoUInt(sectionProto, cls, "getScaledHigh")
        val rawCorrect =
            try {
                cls.getMethod("getRawCorrect").invoke(sectionProto) as Int
            } catch (_: ReflectiveOperationException) {
                0
            }
        val rawTotal =
            try {
                cls.getMethod("getRawTotal").invoke(sectionProto) as Int
            } catch (_: ReflectiveOperationException) {
                0
            }
        val scaledRange =
            if (scaled != null && scaledLow != null && scaledHigh != null) {
                "$scaled ($scaledLow\u2013$scaledHigh)"
            } else {
                "\u2014"
            }
        return ProjectedSectionLine(
            sectionLabel = label,
            scaledRange = scaledRange,
            rawLine = "$rawCorrect/$rawTotal",
            scaled = scaled,
            scaledLow = scaledLow,
            scaledHigh = scaledHigh,
        )
    }

    private fun sectionLabelFromProto(enumName: String): String =
        when (enumName) {
            "MCAT_SECTION_CPBS" -> "CPBS"
            "MCAT_SECTION_CARS" -> "CARS"
            "MCAT_SECTION_BBLS" -> "BBLS"
            "MCAT_SECTION_PSBB" -> "PSBB"
            else -> enumName
        }

    private fun protoUInt(
        obj: Any,
        cls: Class<*>,
        method: String,
    ): Int? {
        val hasMethod = "has" + method.removePrefix("get")
        try {
            if (cls.getMethod(hasMethod).invoke(obj) == false) {
                return null
            }
        } catch (_: ReflectiveOperationException) {
            // optional proto field may not expose has* in older generators
        }
        val value =
            try {
                cls.getMethod(method).invoke(obj)
            } catch (_: ReflectiveOperationException) {
                return null
            }
        return when (value) {
            null -> null
            is Int -> value
            is Number -> value.toInt()
            else -> null
        }
    }
}
