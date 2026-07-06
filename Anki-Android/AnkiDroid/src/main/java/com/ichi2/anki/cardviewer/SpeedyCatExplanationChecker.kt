/*
 *  Copyright (c) 2026 SpeedyCAT contributors
 *
 *  SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.ichi2.anki.cardviewer

import android.content.Context
import com.ichi2.anki.practice.seedFromStr
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * SpeedyCAT AI explanation verifier for practice multiple-choice questions.
 *
 * Kotlin twin of `anki/pylib/anki/speedycat_explanation.py` and the desktop
 * `explanationGate.ts` module. Pure helpers are unit-tested; [runCheck] performs
 * the blocking HTTPS call (off the main thread).
 */
object SpeedyCatExplanationChecker {
    const val MODEL = SpeedyCatAiChecker.MODEL
    const val SOURCE_AI = SpeedyCatAiChecker.SOURCE_AI
    const val SOURCE_AI_PROXY = SpeedyCatAiChecker.SOURCE_AI_PROXY
    const val SOURCE_BASELINE = SpeedyCatAiChecker.SOURCE_BASELINE

    const val EXPLANATION_GATE_MODULO = 5
    const val EXPLANATION_GATE_SUFFIX = ":explanation-gate"

    private const val OPENAI_RESPONSES_URL = "https://api.openai.com/v1/responses"
    private const val MAX_OUTPUT_TOKENS = 600
    private const val REASONING_EFFORT = "low"
    private const val REQUEST_TIMEOUT_SECONDS = 20L

    private const val ENV_PROXY_URL = "SPEEDYCAT_EXPLANATION_PROXY_URL"
    const val DEFAULT_PROXY_URL =
        "https://us-central1-speedycat-mcat.cloudfunctions.net/checkPracticeExplanation"

    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

    private val GENERIC_FAIL_HINTS =
        listOf(
            "Walk through your reasoning step by step. What concept does this question test, " +
                "and how does it support your conclusion?",
            "Be more specific about the underlying principle. How does it apply to the " +
                "scenario in the stem?",
            "Connect the stem's details to the science. Explain why your answer follows " +
                "from that principle — without just restating the question.",
        )

    private val systemInstruction =
        """
        You are SpeedyCAT's practice-question explanation evaluator for MCAT study. The learner answered a multiple-choice question correctly and must explain WHY.
        You receive:
        - QUESTION STEM (no answer choices shown to the learner)
        - LEARNER'S WRITTEN EXPLANATION
        - CORRECT ANSWER (internal reference ONLY — use it to judge correctness; NEVER quote it, name its letter, or paraphrase it closely in feedback)
        Judge whether the explanation:
        1. Is substantive: at least 2–3 sentences with genuine reasoning (not filler, gaming, or a single vague phrase).
        2. Demonstrates understanding of WHY the correct answer is correct for this specific question.
        Return JSON with:
        - pass: true only when BOTH criteria are met.
        - feedback: brief coaching for the learner (max ~30 words). When pass is false, nudge them toward deeper reasoning WITHOUT revealing or closely restating the correct answer.
        Answer with ONLY the JSON object.
        """.trimIndent()

    data class ExplanationResult(
        val passed: Boolean,
        val feedback: String,
    )

    fun requiresExplanationGate(
        sessionId: String,
        questionId: String,
    ): Boolean {
        val key = "$sessionId:$questionId$EXPLANATION_GATE_SUFFIX"
        return seedFromStr(key) % EXPLANATION_GATE_MODULO == 0L
    }

    fun shouldUseExplanationGate(
        sessionId: String,
        questionId: String,
        aiOn: Boolean,
        aiAvailable: Boolean,
    ): Boolean {
        if (!aiOn || !aiAvailable) return false
        return requiresExplanationGate(sessionId, questionId)
    }

    fun buildEvaluatorPrompt(
        stem: String,
        userExplanation: String,
        correctAnswer: String,
    ): String =
        buildString {
            appendLine("QUESTION STEM: ${stem.trim().ifEmpty { "(empty)" }}")
            appendLine(
                "LEARNER'S WRITTEN EXPLANATION: ${userExplanation.trim().ifEmpty { "(blank)" }}",
            )
            appendLine(
                "CORRECT ANSWER (internal reference — judge only; do not reveal): " +
                    correctAnswer.trim().ifEmpty { "(empty)" },
            )
            appendLine()
            append("Return the JSON verdict now.")
        }

    fun buildUserVisiblePrompt(@Suppress("UNUSED_PARAMETER") stem: String): String =
        "You answered correctly. Before moving on, explain your reasoning in a few " +
            "sentences."

    fun explanationFailureHint(
        failCount: Int,
        hintPrompts: List<String> = emptyList(),
    ): String {
        val level = maxOf(1, failCount)
        if (level <= GENERIC_FAIL_HINTS.size) return GENERIC_FAIL_HINTS[level - 1]
        val hintIdx = level - GENERIC_FAIL_HINTS.size - 1
        if (hintIdx in hintPrompts.indices) return "Consider: ${hintPrompts[hintIdx]}"
        return GENERIC_FAIL_HINTS.last()
    }

    fun itemBlocksProgress(
        questions: List<com.ichi2.anki.practice.PracticeQuestion>,
        progress: Map<String, com.ichi2.anki.practice.ExplanationProgress>,
    ): Boolean =
        questions.any { q ->
            val p = progress[q.id]
            p?.active == true && p.passed != true
        }

    fun resolveProxyUrl(): String? {
        System.getenv(ENV_PROXY_URL)?.let { env ->
            val trimmed = env.trim()
            return trimmed.takeIf { it.isNotEmpty() }
        }
        return DEFAULT_PROXY_URL
    }

    fun explanationAiAvailable(context: Context? = null): Boolean =
        SpeedyCatAiChecker.aiCheckerAvailable(context) || resolveProxyUrl() != null

    fun parseExplanationResponse(rawText: String): ExplanationResult? {
        val json =
            try {
                JSONObject(rawText)
            } catch (e: Exception) {
                Timber.d(e, "unparseable explanation check reply")
                return null
            }
        if (!json.has("pass") || json.opt("pass") !is Boolean) return null
        return ExplanationResult(
            passed = json.getBoolean("pass"),
            feedback = json.optString("feedback").trim(),
        )
    }

    private fun jsonSchema(): JSONObject {
        val properties =
            JSONObject()
                .put(
                    "pass",
                    JSONObject()
                        .put("type", "boolean")
                        .put(
                            "description",
                            "true when the explanation is substantive and shows why the correct answer is correct.",
                        ),
                ).put(
                    "feedback",
                    JSONObject()
                        .put("type", "string")
                        .put(
                            "description",
                            "Brief coaching message; must not reveal the correct answer.",
                        ),
                )
        return JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put("properties", properties)
            .put("required", org.json.JSONArray(listOf("pass", "feedback")))
    }

    fun buildRequestBody(
        stem: String,
        userExplanation: String,
        correctAnswer: String,
    ): String {
        val format =
            JSONObject()
                .put("type", "json_schema")
                .put("name", "speedycat_explanation_check")
                .put("schema", jsonSchema())
                .put("strict", true)
        return JSONObject()
            .put("model", MODEL)
            .put("instructions", systemInstruction)
            .put("input", buildEvaluatorPrompt(stem, userExplanation, correctAnswer))
            .put("max_output_tokens", MAX_OUTPUT_TOKENS)
            .put("reasoning", JSONObject().put("effort", REASONING_EFFORT))
            .put("text", JSONObject().put("format", format))
            .toString()
    }

    fun runCheckViaProxy(
        stem: String,
        userExplanation: String,
        correctAnswer: String,
        proxyUrl: String? = null,
    ): ExplanationResult? {
        val url = proxyUrl?.trim()?.takeIf { it.isNotEmpty() } ?: resolveProxyUrl() ?: return null
        val payload =
            JSONObject()
                .put("stem", stem)
                .put("userExplanation", userExplanation)
                .put("correctAnswer", correctAnswer)
                .toString()
        return try {
            val client =
                OkHttpClient
                    .Builder()
                    .callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build()
            val request =
                Request
                    .Builder()
                    .url(url)
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.d("SpeedyCAT explanation proxy HTTP %d", response.code)
                    return null
                }
                parseExplanationResponse(response.body.string())
            }
        } catch (e: Exception) {
            Timber.d(e, "SpeedyCAT explanation proxy failed")
            null
        }
    }

    fun runCheckDirect(
        stem: String,
        userExplanation: String,
        correctAnswer: String,
        key: String,
    ): ExplanationResult? {
        if (key.isBlank()) return null
        return try {
            val client =
                OkHttpClient
                    .Builder()
                    .callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build()
            val request =
                Request
                    .Builder()
                    .url(OPENAI_RESPONSES_URL)
                    .header("Authorization", "Bearer $key")
                    .post(
                        buildRequestBody(stem, userExplanation, correctAnswer)
                            .toRequestBody(JSON_MEDIA_TYPE),
                    ).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                parseExplanationResponse(
                    SpeedyCatAiChecker.extractOutputText(JSONObject(response.body.string())),
                )
            }
        } catch (e: Exception) {
            Timber.d(e, "SpeedyCAT explanation direct check failed")
            null
        }
    }

    fun runCheck(
        context: Context?,
        stem: String,
        userExplanation: String,
        correctAnswer: String,
    ): Pair<ExplanationResult?, String> {
        val proxyResult = runCheckViaProxy(stem, userExplanation, correctAnswer)
        if (proxyResult != null) return proxyResult to SOURCE_AI_PROXY
        val key = SpeedyCatAiChecker.loadKey(context)
        if (!key.isNullOrBlank()) {
            val result = runCheckDirect(stem, userExplanation, correctAnswer, key)
            return result to if (result != null) SOURCE_AI else SOURCE_BASELINE
        }
        return null to SOURCE_BASELINE
    }
}
