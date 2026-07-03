/*
 *  Copyright (c) 2024 SpeedyCAT contributors
 *
 *  Based on AnkiDroid (https://github.com/ankidroid/Anki-Android), which is in
 *  turn based on Anki (https://apps.ankiweb.net/) by Damien Elmes.
 *
 *  This program is free software; you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation; either version 3 of the License, or (at your option) any later
 *  version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.ichi2.anki.cardviewer

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * SpeedyCAT AI answer checker for forced-active-recall flashcards (mobile).
 *
 * Kotlin twin of the desktop `anki.speedycat_ai` module. It owns the parts that
 * can be reasoned about (and unit-tested) without a device or network:
 *
 * * loading the OpenAI key (env var or a local file) — never hardcoded;
 * * the model id (the *named source* every AI verdict traces to), the prompt,
 *   and the strict `json_schema` structured-output contract;
 * * parsing the model reply into a [CheckResult];
 * * the deterministic **AI-off** fallbacks — a case-insensitive string match
 *   (reusing [ForcedRecall.matches]) and a conservative "genuine attempt?"
 *   heuristic that drives the FSRS *Again* lock without the model;
 * * the shared [decideReveal] / [decideIdk] decision logic the viewer consumes.
 *
 * [runCheck] performs the one impure step — a blocking HTTPS call to the OpenAI
 * *responses* API (mirroring the `brilliant-clone` setup: model `gpt-5.4-mini`,
 * strict `json_schema`, low reasoning effort, a bounded `max_output_tokens`).
 * Callers must invoke it off the main thread; it returns `null` on ANY problem
 * (no key, network/HTTP error, unparseable reply) so the caller falls back to
 * the deterministic path. The app therefore works fully with **AI off**.
 */
object SpeedyCatAiChecker {
    /** The model id. Every AI verdict traces to this *named source*. */
    const val MODEL = "gpt-5.4-mini"

    /** Source of a [Decision] verdict (shown to the user / used by the eval). */
    const val SOURCE_AI = "openai:$MODEL"
    const val SOURCE_BASELINE = "baseline:string-match+heuristic"
    const val SOURCE_IDK = "user:i-dont-know"

    /** SharedPreferences key for the user-facing AI on/off opt-in (default OFF). */
    const val AI_PREF_KEY = "speedycatAiChecker"

    /** Delay before the "I don't know" affordance appears (spec: 5 seconds). */
    const val IDK_DELAY_MS = 5_000L

    private const val OPENAI_RESPONSES_URL = "https://api.openai.com/v1/responses"
    private const val MAX_OUTPUT_TOKENS = 600
    private const val REASONING_EFFORT = "low"
    private const val REQUEST_TIMEOUT_SECONDS = 20L

    private const val ENV_KEY = "SPEEDYCAT_OPENAI_API_KEY"
    private const val ENV_KEY_FILE = "SPEEDYCAT_OPENAI_KEY_FILE"
    private const val KEY_FILENAME = ".speedycat-openai.key"

    private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    private val whitespace = Regex("\\s+")

    // The bundled cloze notetype bakes extras into the RENDERED field that must
    // never pollute the expected answer / prompt / displayed lines: a <style>
    // CSS block, a leading `Subject::Subtopic` tag breadcrumb, and a trailing
    // source footer (rendered as <a>…Khan Academy Link</a>). We strip these
    // deterministically; the real expected answer comes from the cloze deletion.
    private val styleRegex = Regex("<style[^>]*>.*?</style>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val anchorRegex = Regex("<a\\b[^>]*>.*?</a>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val mediaRegex = Regex("\\[(?:sound|anki):[^]]*]")
    private val htmlTagRegex = Regex("<[^>]+>")
    private val breadcrumbRegex = Regex("^\\s*\\w+(?:::\\w+)+\\s*")
    private val footerRegex = Regex("\\s*(?:[A-Z][A-Za-z0-9.'&-]*\\s+){1,3}Link\\s*$")
    // The rendered breadcrumb wrapper (<div class="tags">…</div>) stripped from
    // the displayed question, mirroring the desktop `strip_tag_breadcrumb_html`.
    private val tagBreadcrumbHtmlRegex =
        Regex("<div class=\"tags\"[^>]*>.*?</div>\\s*", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    // Separator punctuation treated as a single space so multi-cloze answers
    // match regardless of the learner's separator (", " / "; " / " : " / "/" / "|").
    private val separatorRegex = Regex("[,;:/|\\\\]+")

    /**
     * Unmistakable keyboard-mash / filler tokens. Kept deliberately tiny and
     * obvious so the heuristic never punishes a real (if short) answer.
     */
    private val mashTokens =
        setOf(
            "asdf", "asdfg", "asdfgh", "asdfghjkl", "asdfjkl",
            "qwer", "qwert", "qwerty", "qwertyuiop",
            "zxcv", "zxcvbn", "zxcvbnm",
            "jkl", "hjkl", "fjfj", "jfjf", "sdf", "lkj", "lkjh",
            "idk", "dunno", "idfk", "nfi",
        )

    private val systemInstruction =
        """
        You are SpeedyCAT's answer checker for a spaced-repetition flashcard app used to study for the MCAT. The learner is forced to type an answer from memory (active recall) before the back of the card is revealed. You are given the flashcard FRONT (the prompt), the learner's TYPED answer, and the EXPECTED answer (the back of the card).
        Decide two things and return them as JSON:
        1. honest_attempt: true if the typed answer is a GENUINE, good-faith attempt to recall THIS card's answer (even if wrong or partial). Set it false only for clear non-attempts: blank/whitespace, random keyboard mashing, gibberish, a single unrelated character, filler like 'idk'/'i don't know'/'dunno', or text unrelated to the question that looks like gaming the reveal.
        2. verdict: 'correct' if the typed answer matches the expected answer in MEANING (ignore case, spacing, punctuation, word order, and reasonable synonyms/abbreviations), otherwise 'incorrect'. If honest_attempt is false, set verdict to 'incorrect'.
        Also give a brief (max ~15 words) reason. Answer with ONLY the JSON object.
        """.trimIndent()

    /** What message the viewer should surface for a [Decision] (mapped to a string resource). */
    enum class MessageType { NONE, HONESTY_PROMPT, FORCED_AGAIN_NOTE, IDK_NOTE }

    /** A parsed, validated verdict from the AI checker. */
    data class CheckResult(
        val honestAttempt: Boolean,
        val verdict: String,
        val reason: String,
    ) {
        val correct: Boolean get() = verdict == "correct"
    }

    /**
     * What the viewer should do after a reveal / "I don't know" action.
     *
     * @param reveal show the back of the card
     * @param verdict "correct"/"incorrect" to show, or null (no grade)
     * @param forceAgain auto-send the *Again* rating to the scheduler
     * @param lockRatings hide/disable Hard/Good/Easy (forced to *Again*)
     * @param messageType which message to surface, if any
     * @param honest whether the input was judged a genuine attempt
     * @param source the named source of the judgement (model id / baseline)
     * @param reason the model's short reason (empty for the baseline)
     */
    data class Decision(
        val reveal: Boolean,
        val verdict: String?,
        val forceAgain: Boolean,
        val lockRatings: Boolean,
        val messageType: MessageType,
        val honest: Boolean,
        val source: String,
        val reason: String = "",
    )

    // --- Key loading (env var OR local file; NEVER hardcoded) ----------------

    /**
     * Return the OpenAI key from the env var or a local file, else null. On a
     * stock device none of these exist, so AI stays OFF unless the user provides
     * a key. Never throws.
     */
    fun loadKey(context: Context?): String? {
        System.getenv(ENV_KEY)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        System.getenv(ENV_KEY_FILE)?.trim()?.takeIf { it.isNotEmpty() }?.let { path ->
            readKeyFile(File(path))?.let { return it }
        }
        context?.let { ctx ->
            readKeyFile(File(ctx.filesDir, KEY_FILENAME))?.let { return it }
        }
        return null
    }

    fun keyPresent(context: Context?): Boolean = loadKey(context) != null

    private fun readKeyFile(file: File): String? =
        try {
            if (file.isFile) file.readText().trim().takeIf { it.isNotEmpty() } else null
        } catch (e: Exception) {
            Timber.d(e, "could not read SpeedyCAT key file")
            null
        }

    // --- Structured-output contract ------------------------------------------

    fun buildPrompt(
        front: String,
        typed: String,
        expected: String,
    ): String =
        buildString {
            appendLine("FRONT (the prompt shown to the learner): ${stripFront(front).ifEmpty { "(empty)" }}")
            appendLine("TYPED answer (what the learner recalled): ${typed.trim().ifEmpty { "(blank)" }}")
            appendLine("EXPECTED answer (the back of the card): ${stripExpected(expected).ifEmpty { "(empty)" }}")
            appendLine()
            append("Return the JSON verdict now.")
        }

    /** The strict `json_schema` used for structured output. */
    private fun jsonSchema(): JSONObject {
        val properties =
            JSONObject()
                .put(
                    "honest_attempt",
                    JSONObject()
                        .put("type", "boolean")
                        .put(
                            "description",
                            "true if the typed answer is a genuine good-faith recall attempt; " +
                                "false for blank/gibberish/keyboard-mash/unrelated/gaming input.",
                        ),
                ).put(
                    "verdict",
                    JSONObject()
                        .put("type", "string")
                        .put("enum", JSONArray(listOf("correct", "incorrect")))
                        .put(
                            "description",
                            "'correct' if the typed answer matches the expected answer in meaning " +
                                "(case/format/synonym insensitive), else 'incorrect'.",
                        ),
                ).put(
                    "reason",
                    JSONObject()
                        .put("type", "string")
                        .put("description", "Brief explanation of the judgement (max ~15 words)."),
                )
        return JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put("properties", properties)
            .put("required", JSONArray(listOf("honest_attempt", "verdict", "reason")))
    }

    fun buildRequestBody(
        front: String,
        typed: String,
        expected: String,
    ): String {
        val format =
            JSONObject()
                .put("type", "json_schema")
                .put("name", "speedycat_answer_check")
                .put("schema", jsonSchema())
                .put("strict", true)
        return JSONObject()
            .put("model", MODEL)
            .put("instructions", systemInstruction)
            .put("input", buildPrompt(front, typed, expected))
            .put("max_output_tokens", MAX_OUTPUT_TOKENS)
            .put("reasoning", JSONObject().put("effort", REASONING_EFFORT))
            .put("text", JSONObject().put("format", format))
            .toString()
    }

    /** Pull the assistant text out of an OpenAI *responses* reply (or ""). */
    fun extractOutputText(response: JSONObject): String {
        response.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }
        val output = response.optJSONArray("output") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until output.length()) {
            val content = output.optJSONObject(i)?.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                if (part.optString("type") == "output_text") {
                    sb.append(part.optString("text"))
                }
            }
        }
        return sb.toString()
    }

    /**
     * Parse the model's JSON reply into a [CheckResult], or null for anything
     * malformed (so the caller falls back to the deterministic path). A
     * dishonest attempt is never scored correct.
     */
    fun parseCheckerResponse(rawText: String): CheckResult? {
        val json =
            try {
                JSONObject(rawText)
            } catch (e: Exception) {
                Timber.d(e, "unparseable AI checker reply")
                return null
            }
        if (!json.has("honest_attempt") || json.opt("honest_attempt") !is Boolean) return null
        val honest = json.getBoolean("honest_attempt")
        var verdict = json.optString("verdict")
        if (verdict != "correct" && verdict != "incorrect") return null
        if (!honest) verdict = "incorrect"
        return CheckResult(honest, verdict, json.optString("reason").trim())
    }

    /**
     * Call the OpenAI *responses* API and return a [CheckResult], or null on any
     * failure. **Blocking** — call from a background dispatcher.
     */
    fun runCheck(
        front: String,
        typed: String,
        expected: String,
        key: String?,
    ): CheckResult? {
        if (key.isNullOrBlank()) return null
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
                    .post(buildRequestBody(front, typed, expected).toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.d("SpeedyCAT AI check HTTP %d", response.code)
                    return null
                }
                val body = response.body.string()
                parseCheckerResponse(extractOutputText(JSONObject(body)))
            }
        } catch (e: Exception) {
            Timber.d(e, "SpeedyCAT AI check failed; falling back")
            null
        }
    }

    // --- Deterministic (AI-off) fallbacks ------------------------------------

    /** Case-insensitive whole-answer match (AI-off verdict + baseline). */
    fun deterministicCorrect(
        typed: String,
        expected: String,
    ): Boolean = ForcedRecall.matches(typed, expected)

    /**
     * Conservative AI-off "genuine attempt?" gate driving the FSRS lock.
     *
     * Returns false only for clear non-attempts — blank/whitespace,
     * punctuation/symbol-only, a single character repeated, or an obvious
     * keyboard-mash/filler token — and true otherwise, so a real (even short or
     * misspelled) answer is never punished.
     */
    fun heuristicIsHonestAttempt(typed: String): Boolean {
        val collapsed = typed.replace(whitespace, " ").trim()
        if (collapsed.isEmpty()) return false
        if (collapsed.none { it.isLetterOrDigit() }) return false
        val compact = collapsed.replace(" ", "").lowercase()
        if (compact.length >= 2 && compact.toSet().size == 1) return false
        if (compact in mashTokens) return false
        return true
    }

    /** Strip HTML tags / AV refs and collapse whitespace (case preserved). */
    private fun stripDisplay(text: String): String =
        unescapeCommonEntities(
            text
                .replace(mediaRegex, " ")
                .replace(htmlTagRegex, " "),
        ).replace('\u00a0', ' ')
            .replace(whitespace, " ")
            .trim()

    /** Decode the handful of HTML entities that appear in the bundled fields (e.g. `&nbsp;`). */
    private fun unescapeCommonEntities(s: String): String =
        s
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")

    /**
     * The cloze deletion(s) for [ordinal] parsed from a cloze [fieldText], or null when there are
     * none. Mirrors [TypeAnswer.contentForCloze] (Anki's cloze-deletion semantics): a nested
     * `::hint` is dropped, identical deletions collapse to one, and distinct deletions are joined
     * with ", " in order. The caller runs the result through [stripExpected].
     */
    fun clozeAnswerForOrd(
        fieldText: String,
        ordinal: Int,
    ): String? {
        val matches =
            Regex("\\{\\{c$ordinal::(.+?)\\}\\}")
                .findAll(fieldText)
                .map { match ->
                    val body = match.groupValues[1]
                    val hint = body.indexOf("::")
                    if (hint > -1) body.take(hint) else body
                }.toList()
        if (matches.isEmpty()) return null
        return if (matches.toSet().size == 1) matches[0] else matches.joinToString(", ")
    }

    /**
     * Clean an expected answer before it is used (prompt + fallback) or shown (the
     * expected-answer line): strips a `<style>` block, a source-link `<a>` element, all
     * HTML, a leading `Subject::Subtopic` breadcrumb, and a trailing `…Link` footer. A
     * bare cloze deletion (e.g. `direction`) is returned unchanged.
     */
    fun stripExpected(text: String): String = stripField(text, stripFooter = true)

    /**
     * Clean the question/front for display and the AI prompt: strips a `<style>` block, all
     * HTML, and the leading `Subject::Subtopic` breadcrumb (the cloze `[...]` blank is kept).
     */
    fun stripFront(text: String): String = stripField(text, stripFooter = false)

    private fun stripField(
        text: String,
        stripFooter: Boolean,
    ): String {
        var t = styleRegex.replace(text, " ")
        t = anchorRegex.replace(t, " ")
        t = stripDisplay(t)
        t = breadcrumbRegex.replace(t, "").trim()
        if (stripFooter) t = footerRegex.replace(t, "").trim()
        return t
    }

    /** Remove the baked-in `<div class="tags">…</div>` breadcrumb from rendered card HTML. */
    fun stripBreadcrumbHtml(html: String): String = tagBreadcrumbHtmlRegex.replace(html, "")

    // --- Shared decision logic (viewer consumes this) ------------------------

    /** Decision for the "I don't know" button: reveal, but force Again + lock. */
    fun decideIdk(): Decision =
        Decision(
            reveal = true,
            verdict = null,
            forceAgain = true,
            lockRatings = true,
            messageType = MessageType.IDK_NOTE,
            honest = false,
            source = SOURCE_IDK,
        )

    /**
     * Decision when the learner submits a typed answer to reveal the card.
     *
     * AI ON with a usable result: dishonest -> DO NOT reveal, show the honesty
     * prompt, force Again + lock; honest -> reveal with the model's verdict and
     * normal ratings. AI OFF (or the call failed): ALWAYS reveal with the
     * case-insensitive verdict, and the heuristic still forces Again + lock on a
     * clear non-attempt.
     */
    fun decideReveal(
        typed: String,
        expected: String,
        aiOn: Boolean,
        aiResult: CheckResult?,
    ): Decision {
        if (aiOn && aiResult != null) {
            return if (!aiResult.honestAttempt) {
                Decision(
                    reveal = false,
                    verdict = null,
                    forceAgain = true,
                    lockRatings = true,
                    messageType = MessageType.HONESTY_PROMPT,
                    honest = false,
                    source = SOURCE_AI,
                    reason = aiResult.reason,
                )
            } else {
                Decision(
                    reveal = true,
                    verdict = aiResult.verdict,
                    forceAgain = false,
                    lockRatings = false,
                    messageType = MessageType.NONE,
                    honest = true,
                    source = SOURCE_AI,
                    reason = aiResult.reason,
                )
            }
        }
        val honest = heuristicIsHonestAttempt(typed)
        return Decision(
            reveal = true,
            verdict = if (deterministicCorrect(typed, expected)) "correct" else "incorrect",
            forceAgain = !honest,
            lockRatings = !honest,
            messageType = if (honest) MessageType.NONE else MessageType.FORCED_AGAIN_NOTE,
            honest = honest,
            source = SOURCE_BASELINE,
        )
    }
}
