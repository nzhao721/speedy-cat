// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors
//
// Reflection helpers for PracticeService protobuf messages on the local rsdroid
// backend. Keeps PracticeBackend thin and mirrors ReadinessBackend patterns.

package com.ichi2.anki.practice

import timber.log.Timber

/** Session started by the Rust `PracticeService.StartPracticeSession` RPC. */
data class BackendPracticeSession(
    val sessionId: String,
    val questions: List<PracticeQuestion>,
)

internal object PracticeProto {
    private const val PACKAGE = "anki.practice"

    fun buildStartPracticeSessionRequest(
        filter: QuestionFilter,
        timeLimitSeconds: Int,
    ): ByteArray? =
        try {
            val filterProto = buildQuestionFilter(filter) ?: return null
            val reqClass = Class.forName("$PACKAGE.StartPracticeSessionRequest")
            val builder = reqClass.getMethod("newBuilder").invoke(null)
            val builderClass = builder.javaClass
            builderClass.getMethod("setFilter", filterProto.javaClass).invoke(builder, filterProto)
            builderClass
                .getMethod("setTimeLimitSeconds", Int::class.javaPrimitiveType)
                .invoke(builder, timeLimitSeconds)
            val request = builderClass.getMethod("build").invoke(builder)
            request.javaClass.getMethod("toByteArray").invoke(request) as ByteArray
        } catch (e: ReflectiveOperationException) {
            Timber.w(e, "SpeedyCAT: failed to build StartPracticeSessionRequest")
            null
        }

    fun parseStartPracticeSessionResponse(bytes: ByteArray): BackendPracticeSession? =
        try {
            val responseClass = Class.forName("$PACKAGE.StartPracticeSessionResponse")
            val response =
                responseClass.getMethod("parseFrom", ByteArray::class.java).invoke(null, bytes)
            val sessionId = responseClass.getMethod("getSessionId").invoke(response) as String
            @Suppress("UNCHECKED_CAST")
            val protoQuestions =
                responseClass.getMethod("getQuestionsList").invoke(response) as List<Any>
            BackendPracticeSession(
                sessionId = sessionId,
                questions = protoQuestions.mapNotNull { practiceQuestionFromProto(it) },
            )
        } catch (e: ReflectiveOperationException) {
            Timber.w(e, "SpeedyCAT: failed to parse StartPracticeSessionResponse")
            null
        }

    private fun buildQuestionFilter(filter: QuestionFilter): Any? =
        try {
            val filterClass = Class.forName("$PACKAGE.QuestionFilter")
            val builder = filterClass.getMethod("newBuilder").invoke(null)
            val builderClass = builder.javaClass
            val mcatSectionClass = Class.forName("$PACKAGE.McatSection")
            for (section in filter.sections) {
                val protoSection = sectionToProtoEnum(mcatSectionClass, section) ?: continue
                builderClass.getMethod("addSections", mcatSectionClass).invoke(builder, protoSection)
            }
            for (topic in filter.topics) {
                builderClass.getMethod("addTopics", String::class.java).invoke(builder, topic)
            }
            filter.difficulty?.let { diff ->
                val diffClass = Class.forName("$PACKAGE.Difficulty")
                val protoDiff = difficultyToProtoEnum(diffClass, diff) ?: return@let
                builderClass.getMethod("setDifficulty", diffClass).invoke(builder, protoDiff)
            }
            filter.passageId?.let { pid ->
                builderClass.getMethod("setPassageId", String::class.java).invoke(builder, pid)
            }
            builderClass
                .getMethod("setIncludeFullLength", Boolean::class.javaPrimitiveType)
                .invoke(builder, false)
            builderClass
                .getMethod("setLimit", Int::class.javaPrimitiveType)
                .invoke(builder, filter.limit)
            builderClass.getMethod("build").invoke(builder)
        } catch (e: ReflectiveOperationException) {
            Timber.w(e, "SpeedyCAT: failed to build QuestionFilter")
            null
        }

    private fun sectionToProtoEnum(
        mcatSectionClass: Class<*>,
        section: McatSection,
    ): Any? {
        val name =
            when (section) {
                McatSection.CPBS -> "MCAT_SECTION_CPBS"
                McatSection.CARS -> "MCAT_SECTION_CARS"
                McatSection.BBLS -> "MCAT_SECTION_BBLS"
                McatSection.PSBB -> "MCAT_SECTION_PSBB"
            }
        return enumValue(mcatSectionClass, name)
    }

    private fun sectionFromProto(sectionProto: Any?): McatSection? {
        if (sectionProto == null) return null
        val name =
            when (sectionProto) {
                is Enum<*> -> sectionProto.name
                else ->
                    try {
                        sectionProto.javaClass.getMethod("name").invoke(sectionProto) as String
                    } catch (_: ReflectiveOperationException) {
                        sectionProto.toString()
                    }
            }
        return when (name) {
            "MCAT_SECTION_CPBS" -> McatSection.CPBS
            "MCAT_SECTION_CARS" -> McatSection.CARS
            "MCAT_SECTION_BBLS" -> McatSection.BBLS
            "MCAT_SECTION_PSBB" -> McatSection.PSBB
            else -> null
        }
    }

    private fun difficultyToProtoEnum(
        diffClass: Class<*>,
        difficulty: Difficulty,
    ): Any? {
        val name =
            when (difficulty) {
                Difficulty.EASY -> "DIFFICULTY_EASY"
                Difficulty.MEDIUM -> "DIFFICULTY_MEDIUM"
                Difficulty.HARD -> "DIFFICULTY_HARD"
            }
        return enumValue(diffClass, name)
    }

    private fun difficultyFromProto(diffProto: Any?): Difficulty? {
        if (diffProto == null) return null
        val name =
            when (diffProto) {
                is Enum<*> -> diffProto.name
                else ->
                    try {
                        diffProto.javaClass.getMethod("name").invoke(diffProto) as String
                    } catch (_: ReflectiveOperationException) {
                        diffProto.toString()
                    }
            }
        return when (name) {
            "DIFFICULTY_EASY" -> Difficulty.EASY
            "DIFFICULTY_MEDIUM" -> Difficulty.MEDIUM
            "DIFFICULTY_HARD" -> Difficulty.HARD
            else -> null
        }
    }

    private fun enumValue(
        enumClass: Class<*>,
        name: String,
    ): Any? =
        try {
            enumClass.getMethod("valueOf", String::class.java).invoke(null, name)
        } catch (_: ReflectiveOperationException) {
            null
        }

    private fun practiceQuestionFromProto(proto: Any): PracticeQuestion? =
        try {
            val cls = proto.javaClass
            val id = cls.getMethod("getId").invoke(proto) as String
            if (id.isEmpty()) return null
            val section =
                try {
                    sectionFromProto(cls.getMethod("getSection").invoke(proto))
                } catch (_: ReflectiveOperationException) {
                    null
                }
            val passageId = optionalString(proto, cls, "getPassageId")
            val stem = cls.getMethod("getStem").invoke(proto) as String
            @Suppress("UNCHECKED_CAST")
            val choiceProtos = cls.getMethod("getChoicesList").invoke(proto) as List<Any>
            val choices =
                choiceProtos.mapNotNull { choice ->
                    val choiceCls = choice.javaClass
                    val label = choiceCls.getMethod("getLabel").invoke(choice) as String
                    val text = choiceCls.getMethod("getText").invoke(choice) as String
                    if (label.isEmpty()) null else AnswerChoice(label, text)
                }
            val correctAnswer = cls.getMethod("getCorrectAnswer").invoke(proto) as String
            val explanation = cls.getMethod("getExplanation").invoke(proto) as String
            val questionType = optionalString(proto, cls, "getQuestionType")
            @Suppress("UNCHECKED_CAST")
            val topicTags = cls.getMethod("getTopicTagsList").invoke(proto) as List<String>
            val difficulty =
                try {
                    difficultyFromProto(cls.getMethod("getDifficulty").invoke(proto))
                } catch (_: ReflectiveOperationException) {
                    null
                }
            val sourceName = cls.getMethod("getSourceName").invoke(proto) as String
            val sourceLicense = cls.getMethod("getSourceLicense").invoke(proto) as String
            if (sourceName.isBlank() || sourceLicense.isBlank()) return null
            val sourceUrl = optionalString(proto, cls, "getSourceUrl")
            val answerProvenance = optionalString(proto, cls, "getAnswerProvenance")
            val notes = optionalString(proto, cls, "getNotes")
            @Suppress("UNCHECKED_CAST")
            val hintProtos = cls.getMethod("getHintsList").invoke(proto) as List<Any>
            PracticeQuestion(
                id = id,
                section = section,
                passageId = passageId,
                stem = stem,
                choices = choices,
                correctAnswer = correctAnswer,
                explanation = explanation,
                questionType = questionType,
                topicTags = topicTags,
                difficulty = difficulty,
                sourceName = sourceName,
                sourceLicense = sourceLicense,
                sourceUrl = sourceUrl,
                answerProvenance = answerProvenance,
                notes = notes,
                hints = hintProtos.mapNotNull { hintFromProto(it) },
            )
        } catch (e: ReflectiveOperationException) {
            Timber.w(e, "SpeedyCAT: failed to parse PracticeQuestion proto")
            null
        }

    private fun hintFromProto(proto: Any): HintSubquestion? =
        try {
            val cls = proto.javaClass
            val prompt = cls.getMethod("getPrompt").invoke(proto) as String
            if (prompt.isBlank()) return null
            @Suppress("UNCHECKED_CAST")
            val choiceProtos = cls.getMethod("getChoicesList").invoke(proto) as List<Any>
            if (choiceProtos.size != 4) return null
            val choices =
                choiceProtos.map {
                    val choiceCls = it.javaClass
                    HintChoice(
                        label = choiceCls.getMethod("getLabel").invoke(it) as String,
                        text = choiceCls.getMethod("getText").invoke(it) as String,
                    )
                }
            val correctAnswer = cls.getMethod("getCorrectAnswer").invoke(proto) as String
            if (correctAnswer.isBlank() || choices.none { it.label == correctAnswer }) return null
            val level = (cls.getMethod("getLevel").invoke(proto) as Number).toInt()
            val rationale = cls.getMethod("getRationale").invoke(proto) as String
            HintSubquestion(
                level = if (level in 1..3) level else 1,
                prompt = prompt,
                choices = choices,
                correctAnswer = correctAnswer,
                rationale = rationale,
            )
        } catch (_: ReflectiveOperationException) {
            null
        }

    private fun optionalString(
        proto: Any,
        cls: Class<*>,
        getter: String,
    ): String? {
        val hasMethod = "has" + getter.removePrefix("get")
        try {
            if (cls.getMethod(hasMethod).invoke(proto) == false) return null
        } catch (_: ReflectiveOperationException) {
            // optional field may not expose has*
        }
        return try {
            (cls.getMethod(getter).invoke(proto) as String?)?.takeIf { it.isNotEmpty() }
        } catch (_: ReflectiveOperationException) {
            null
        }
    }
}
