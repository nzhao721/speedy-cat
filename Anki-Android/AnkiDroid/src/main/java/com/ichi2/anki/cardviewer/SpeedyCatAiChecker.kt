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
 * * calling the SpeedyCAT cloud proxy when no local key is present (fresh installs);
 * * the model id (the *named source* every AI verdict traces to), the prompt,
 *   and the strict `json_schema` structured-output contract;
 * * parsing the model reply into a [CheckResult];
 * * the deterministic **AI-off** fallbacks — a case-insensitive string match
 *   (reusing [ForcedRecall.matches]) and a conservative "genuine attempt?"
 *   heuristic that drives the FSRS *Again* lock without the model;
 * * the shared [decideReveal] / [decideIdk] / [planReveal] decision logic the viewer
 *   consumes.
 *
 * [runCheck] performs the impure HTTPS call — **only after** [planReveal] finds a
 * static *incorrect* verdict. It is a blocking call to the OpenAI
 * *responses* API (model `gpt-5.4-mini`, strict `json_schema`, low reasoning
 * effort, a bounded `max_output_tokens`; see `functions/src/speedycatChecker.ts`).
 * Callers must invoke it off the main thread; it returns `null` on ANY problem
 * (no key, network/HTTP error, unparseable reply) so the caller falls back to
 * the deterministic path. The app therefore works fully with **AI off**.
 */
object SpeedyCatAiChecker {
    /** The model id. Every AI verdict traces to this *named source*. */
    const val MODEL = "gpt-5.4-mini"

    /** Source of a [Decision] verdict (shown to the user / used by the eval). */
    const val SOURCE_AI = "openai:$MODEL"
    const val SOURCE_AI_PROXY = "openai/$MODEL via speedycat-proxy"
    const val SOURCE_BASELINE = "baseline:string-match+heuristic"
    const val SOURCE_IDK = "user:i-dont-know"

    /** True when [source] names a model-generated verdict (direct or via proxy). */
    fun isAiSource(source: String): Boolean = source == SOURCE_AI || source == SOURCE_AI_PROXY

    /** SharedPreferences key for the user-facing AI on/off toggle. */
    const val AI_PREF_KEY = "speedycatAiChecker"

    /** Default when the preference is unset (AI on for fresh installs). */
    const val AI_PREF_DEFAULT = true

    /** Delay before the "I don't know" affordance appears (spec: 5 seconds). */
    const val IDK_DELAY_MS = 5_000L

    private const val OPENAI_RESPONSES_URL = "https://api.openai.com/v1/responses"
    private const val MAX_OUTPUT_TOKENS = 600
    private const val REASONING_EFFORT = "low"
    private const val REQUEST_TIMEOUT_SECONDS = 20L

    private const val ENV_KEY = "SPEEDYCAT_OPENAI_API_KEY"
    private const val ENV_KEY_FILE = "SPEEDYCAT_OPENAI_KEY_FILE"
    private const val KEY_FILENAME = ".speedycat-openai.key"

    /** Default cloud proxy URL (speedycat-mcat Firebase project; see docs/speedycat-ai-proxy.md). */
    const val DEFAULT_PROXY_URL =
        "https://us-central1-speedycat-mcat.cloudfunctions.net/checkSpeedycatAnswer"
    private const val ENV_PROXY_URL = "SPEEDYCAT_AI_PROXY_URL"

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

    // Placeholders baked into pre-rendered SpeedyCAT Front/Back fields (`[...]`, `___`, hints).
    private val blankPlaceholderRegex = Regex("""\[\.\.\.\]|\[[^\]]{1,40}]|_{3,}|…+""")
    private val renderedClozeSpanRegex =
        Regex(
            """<span\b[^>]*\bclass="[^"]*\bcloze\b[^"]*"[^>]*\bdata-ordinal="([^"]*)"[^>]*>(.*?)</span>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )

    /**
     * Unmistakable keyboard-mash / filler tokens. Kept deliberately tiny and
     * obvious so the heuristic never punishes a real (if short) answer.
     */
    private val mashTokens =
        setOf(
            "asdf",
            "asdfg",
            "asdfgh",
            "asdfghjkl",
            "asdfjkl",
            "qwer",
            "qwert",
            "qwerty",
            "qwertyuiop",
            "zxcv",
            "zxcvbn",
            "zxcvbnm",
            "jkl",
            "hjkl",
            "fjfj",
            "jfjf",
            "sdf",
            "lkj",
            "lkjh",
            "idk",
            "dunno",
            "idfk",
            "nfi",
        )

    private val systemInstruction =
        """
        You are SpeedyCAT's answer checker for a spaced-repetition flashcard app used to study for the MCAT. The learner is forced to type an answer from memory (active recall) before the back of the card is revealed. You are given the flashcard FRONT (the prompt), the learner's TYPED answer, and the EXPECTED answer (the back of the card).
        Decide two things and return them as JSON:
        1. honest_attempt: true if the typed answer is a GENUINE, good-faith attempt to recall THIS card's answer (even if wrong or partial). Set it false only for clear non-attempts: blank/whitespace, random keyboard mashing, gibberish, a single unrelated character, filler like 'idk'/'i don't know'/'dunno', or text unrelated to the question that looks like gaming the reveal.
        2. verdict: 'correct' if the typed answer matches the expected answer in MEANING (ignore case, spacing, punctuation, word order, and reasonable synonyms/abbreviations, and minor misspellings/typos that preserve the intended meaning), otherwise 'incorrect'. If honest_attempt is false, set verdict to 'incorrect'.
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
        System
            .getenv(ENV_KEY)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        System.getenv(ENV_KEY_FILE)?.trim()?.takeIf { it.isNotEmpty() }?.let { path ->
            readKeyFile(File(path))?.let { return it }
        }
        context?.let { ctx ->
            readKeyFile(File(ctx.filesDir, KEY_FILENAME))?.let { return it }
        }
        return null
    }

    /** True when a non-empty local OpenAI key is available. */
    fun keyPresent(context: Context?): Boolean = loadKey(context) != null

    /**
     * Return the configured cloud-proxy URL, or null when proxy is disabled.
     * Precedence: `SPEEDYCAT_AI_PROXY_URL` env var (empty disables), then [DEFAULT_PROXY_URL].
     */
    fun resolveProxyUrl(): String? {
        System.getenv(ENV_PROXY_URL)?.let { env ->
            val trimmed = env.trim()
            return trimmed.takeIf { it.isNotEmpty() }
        }
        return DEFAULT_PROXY_URL
    }

    /** True when AI checking can run: a local key OR the cloud proxy is configured. */
    fun aiCheckerAvailable(context: Context? = null): Boolean = keyPresent(context) || resolveProxyUrl() != null

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
                                "(case/format/synonym/typo insensitive), else 'incorrect'.",
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
     * Call the OpenAI *responses* API directly with a local key. **Blocking** —
     * call from a background dispatcher.
     */
    fun runCheckDirect(
        front: String,
        typed: String,
        expected: String,
        key: String,
    ): CheckResult? {
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

    /**
     * Call the SpeedyCAT cloud proxy. **Blocking** — call from a background dispatcher.
     */
    fun runCheckViaProxy(
        front: String,
        typed: String,
        expected: String,
        proxyUrl: String? = null,
    ): CheckResult? {
        val url = proxyUrl?.trim()?.takeIf { it.isNotEmpty() } ?: resolveProxyUrl() ?: return null
        val payload =
            JSONObject()
                .put("front", stripFront(front))
                .put("typed", typed)
                .put("expected", stripExpected(expected))
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
                    Timber.d("SpeedyCAT proxy check HTTP %d", response.code)
                    return null
                }
                val body = response.body.string()
                parseCheckerResponse(body)
            }
        } catch (e: Exception) {
            Timber.d(e, "SpeedyCAT proxy check failed; falling back")
            null
        }
    }

    /**
     * Run the AI answer check. Precedence: cloud proxy > local key (direct OpenAI).
     * Returns the [CheckResult] (or null) and the named [source] string.
     */
    fun runCheck(
        front: String,
        typed: String,
        expected: String,
        key: String?,
    ): Pair<CheckResult?, String> {
        val proxyResult = runCheckViaProxy(front, typed, expected)
        if (proxyResult != null) {
            return proxyResult to SOURCE_AI_PROXY
        }
        if (!key.isNullOrBlank()) {
            val result = runCheckDirect(front, typed, expected, key)
            return result to if (result != null) SOURCE_AI else SOURCE_BASELINE
        }
        return null to SOURCE_BASELINE
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

    private fun isClozePlaceholder(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t == "..." || t == "[...]") return true
        if (blankPlaceholderRegex.matches(t)) return true
        // Rendered front blanks often show "[...] remainder" where only the blank is hidden.
        if (t.startsWith("[...]")) return true
        if (Regex("^_+$").matches(t)) return true
        return false
    }

    /**
     * The cloze deletion(s) for [ordinal] parsed from a cloze [fieldText], or null when there are
     * none. Handles ``{{cN::answer}}`` markers and answer-side ``<span class="cloze">`` elements.
     * A nested ``::hint`` is dropped, identical deletions collapse to one, and distinct deletions
     * are joined with ", " in order. The caller runs the result through [stripExpected].
     */
    fun clozeAnswerForOrd(
        fieldText: String,
        ordinal: Int,
    ): String? {
        if (ordinal < 1) return null
        val parts = mutableListOf<String>()
        Regex("\\{\\{c$ordinal::(.+?)\\}\\}")
            .findAll(fieldText)
            .forEach { match ->
                val body = match.groupValues[1]
                val hint = body.indexOf("::")
                parts.add(if (hint > -1) body.take(hint) else body)
            }
        renderedClozeSpanRegex.findAll(fieldText).forEach { match ->
            val ordinals = match.groupValues[1].split(",").map { it.trim() }
            if (ordinal.toString() !in ordinals) return@forEach
            val inner = stripDisplay(match.groupValues[2])
            if (inner.isNotEmpty() && !isClozePlaceholder(inner)) {
                parts.add(inner)
            }
        }
        if (parts.isEmpty()) return null
        return if (parts.toSet().size == 1) parts[0] else parts.joinToString(", ")
    }

    /**
     * Derive the cloze answer from a pre-rendered Front/Back field pair (SpeedyCAT import).
     * The front carries a ``[...]`` / ``___`` blank; the back carries the filled sentence.
     */
    fun extractClozeFromPrerendered(
        front: String,
        back: String,
        ordinal: Int = 1,
    ): String? {
        if (ordinal < 1) return null
        val frontPlain = stripFront(front)
        val backPlain = stripExpected(back)
        val blanks = blankPlaceholderRegex.findAll(frontPlain).toList()
        if (blanks.isEmpty()) return null
        val blank = blanks[minOf(ordinal - 1, blanks.lastIndex)]
        val prefix = frontPlain.substring(0, blank.range.first)
        val suffix = frontPlain.substring(blank.range.last + 1)
        if (suffix.isNotEmpty() && backPlain.startsWith(prefix) && backPlain.endsWith(suffix)) {
            return backPlain
                .substring(prefix.length, backPlain.length - suffix.length)
                .trim()
                .takeIf { it.isNotEmpty() }
        }
        if (suffix.isEmpty() && backPlain.startsWith(prefix)) {
            return backPlain.substring(prefix.length).trim().takeIf { it.isNotEmpty() }
        }
        if (suffix.isNotEmpty()) {
            val suffixAt = backPlain.indexOf(suffix)
            if (suffixAt >= prefix.length) {
                return backPlain.substring(prefix.length, suffixAt).trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
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

    /**
     * Static-first gate before any AI call.
     *
     * @param decision set when the answer can be decided without AI (static correct,
     *   or AI off); null when [needsAi] is true
     * @param needsAi true only when static said incorrect and AI checking is enabled
     */
    data class RevealPlan(
        val decision: Decision?,
        val needsAi: Boolean,
    )

    /**
     * Static-first reveal plan: skip AI when the deterministic match is correct.
     *
     * 1. Static correct -> immediate correct verdict, needsAi=false.
     * 2. Static incorrect + AI off -> deterministic fallback, needsAi=false.
     * 3. Static incorrect + AI on -> needsAi=true (caller runs [runCheck]).
     */
    fun planReveal(
        typed: String,
        expected: String,
        aiOn: Boolean,
    ): RevealPlan {
        if (deterministicCorrect(typed, expected)) {
            return RevealPlan(
                decision =
                    Decision(
                        reveal = true,
                        verdict = "correct",
                        forceAgain = false,
                        lockRatings = false,
                        messageType = MessageType.NONE,
                        honest = true,
                        source = SOURCE_BASELINE,
                    ),
                needsAi = false,
            )
        }
        if (!aiOn) {
            return RevealPlan(
                decision = decideReveal(typed, expected, aiOn = false, aiResult = null),
                needsAi = false,
            )
        }
        return RevealPlan(decision = null, needsAi = true)
    }

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
     * Callers should use [planReveal] first so AI runs only after a static
     * *incorrect* verdict. This applies the AI result (or the full deterministic
     * fallback when AI is off or the call failed).
     *
     * AI ON with a usable result (static incorrect path): dishonest -> DO NOT
     * reveal; keep the learner on the question side with the honesty prompt.
     * Honest -> reveal with the model's verdict and normal ratings. AI OFF (or
     * the call failed): reveal with the case-insensitive verdict when the attempt
     * looks genuine; a clear non-attempt is blocked the same way (no reveal).
     */
    fun decideReveal(
        typed: String,
        expected: String,
        aiOn: Boolean,
        aiResult: CheckResult?,
        aiSource: String = SOURCE_AI,
    ): Decision {
        if (aiOn && aiResult != null) {
            return if (!aiResult.honestAttempt) {
                gamingRetryDecision(aiSource, aiResult.reason)
            } else {
                Decision(
                    reveal = true,
                    verdict = aiResult.verdict,
                    forceAgain = false,
                    lockRatings = false,
                    messageType = MessageType.NONE,
                    honest = true,
                    source = aiSource,
                    reason = aiResult.reason,
                )
            }
        }
        val honest = heuristicIsHonestAttempt(typed)
        if (!honest) {
            return gamingRetryDecision(SOURCE_BASELINE)
        }
        return Decision(
            reveal = true,
            verdict = if (deterministicCorrect(typed, expected)) "correct" else "incorrect",
            forceAgain = false,
            lockRatings = false,
            messageType = MessageType.NONE,
            honest = true,
            source = SOURCE_BASELINE,
        )
    }

    /** True when the learner must retry on the same card (gaming / non-attempt). */
    fun isGamingRetryDecision(decision: Decision): Boolean = !decision.reveal && !decision.honest && decision.source != SOURCE_IDK

    private fun gamingRetryDecision(
        source: String,
        reason: String = "",
    ): Decision =
        Decision(
            reveal = false,
            verdict = null,
            forceAgain = false,
            lockRatings = false,
            messageType = MessageType.HONESTY_PROMPT,
            honest = false,
            source = source,
            reason = reason,
        )
}
