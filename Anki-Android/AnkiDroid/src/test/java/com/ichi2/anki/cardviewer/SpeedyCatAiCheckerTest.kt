/*
 *  Copyright (c) 2024 SpeedyCAT contributors
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

import com.ichi2.anki.cardviewer.SpeedyCatAiChecker.CheckResult
import com.ichi2.anki.cardviewer.SpeedyCatAiChecker.MessageType
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure logic of the SpeedyCAT AI answer checker (mobile).
 *
 * Plain JVM test (no Robolectric): everything here is deterministic and offline.
 * `org.json` is available in JvmTests (see `testImplementation libs.json`), and
 * the deterministic verdict inputs avoid HTML entities so the shared HTML
 * stripper does not need Android's `HtmlCompat`, mirroring [ForcedRecallTest].
 */
class SpeedyCatAiCheckerTest {
    // --- Structured-output parsing (the honesty gate) ---

    @Test
    fun parsesHonestCorrect() {
        val result =
            SpeedyCatAiChecker.parseCheckerResponse(
                """{"honest_attempt": true, "verdict": "correct", "reason": "matches"}""",
            )
        assertNotNull(result)
        assertTrue(result!!.honestAttempt)
        assertTrue(result.correct)
        assertEquals("matches", result.reason)
    }

    @Test
    fun dishonestIsForcedIncorrect() {
        // Even if the model returns verdict=correct, a dishonest attempt is incorrect.
        val result =
            SpeedyCatAiChecker.parseCheckerResponse(
                """{"honest_attempt": false, "verdict": "correct", "reason": "mash"}""",
            )
        assertNotNull(result)
        assertFalse(result!!.honestAttempt)
        assertEquals("incorrect", result.verdict)
    }

    @Test
    fun malformedRepliesReturnNull() {
        assertNull(SpeedyCatAiChecker.parseCheckerResponse("not json"))
        assertNull(SpeedyCatAiChecker.parseCheckerResponse("""{"verdict": "correct", "reason": "x"}"""))
        assertNull(SpeedyCatAiChecker.parseCheckerResponse("""{"honest_attempt": "yes", "verdict": "correct"}"""))
        assertNull(SpeedyCatAiChecker.parseCheckerResponse("""{"honest_attempt": true, "verdict": "maybe"}"""))
        assertNull(SpeedyCatAiChecker.parseCheckerResponse("""{"honest_attempt": true, "reason": "x"}"""))
    }

    @Test
    fun extractsOutputTextFromConvenienceField() {
        assertEquals("hi", SpeedyCatAiChecker.extractOutputText(JSONObject().put("output_text", "hi")))
    }

    @Test
    fun extractsOutputTextFromOutputArray() {
        val content =
            JSONArray()
                .put(JSONObject().put("type", "output_text").put("text", """{"honest_attempt":"""))
                .put(JSONObject().put("type", "output_text").put("text", " true}"))
        val output = JSONArray().put(JSONObject().put("type", "message").put("content", content))
        val response = JSONObject().put("output", output)
        assertEquals("""{"honest_attempt": true}""", SpeedyCatAiChecker.extractOutputText(response))
    }

    @Test
    fun extractOutputTextEmptyWhenMissing() {
        assertEquals("", SpeedyCatAiChecker.extractOutputText(JSONObject()))
    }

    // --- Prompt + request body (named source + strict schema) ---

    @Test
    fun promptContainsFrontTypedExpected() {
        val prompt = SpeedyCatAiChecker.buildPrompt("What is the powerhouse?", "mitochondria", "Mitochondria")
        assertTrue(prompt.contains("What is the powerhouse?"))
        assertTrue(prompt.contains("mitochondria"))
        assertTrue(prompt.contains("Mitochondria"))
    }

    @Test
    fun requestBodyMatchesCloudProxySetup() {
        val body = JSONObject(SpeedyCatAiChecker.buildRequestBody("f", "t", "e"))
        assertEquals("gpt-5.4-mini", body.getString("model"))
        assertEquals(600, body.getInt("max_output_tokens"))
        assertEquals("low", body.getJSONObject("reasoning").getString("effort"))
        val format = body.getJSONObject("text").getJSONObject("format")
        assertEquals("json_schema", format.getString("type"))
        assertTrue(format.getBoolean("strict"))
        val required = format.getJSONObject("schema").getJSONArray("required")
        assertEquals(3, required.length())
    }

    @Test
    fun sourceTracesToNamedModel() {
        assertEquals("openai:gpt-5.4-mini", SpeedyCatAiChecker.SOURCE_AI)
    }

    // --- Conservative gaming heuristic (drives the AI-off FSRS lock) ---

    @Test
    fun heuristicFlagsNonAttempts() {
        for (typed in listOf("", "   ", "....", "!!!", "-----", "aaaa", "zzzzz", "asdf", "qwerty", "zxcvbn", "hjkl", "idk", "dunno")) {
            assertFalse("expected '$typed' to be flagged", SpeedyCatAiChecker.heuristicIsHonestAttempt(typed))
        }
    }

    @Test
    fun heuristicKeepsRealAttempts() {
        for (typed in listOf("C", "K+", "pH", "DNA", "mitochondria", "mitochondira", "the krebs cycle", "action potential")) {
            assertTrue("expected '$typed' to be kept", SpeedyCatAiChecker.heuristicIsHonestAttempt(typed))
        }
    }

    // --- Deterministic AI-off verdict ---

    @Test
    fun deterministicCorrectIsCaseInsensitive() {
        assertTrue(SpeedyCatAiChecker.deterministicCorrect("Mitochondria", "mitochondria"))
        assertFalse(SpeedyCatAiChecker.deterministicCorrect("insulin", "mitochondria"))
        assertFalse(SpeedyCatAiChecker.deterministicCorrect("   ", "mitochondria"))
    }

    // --- Static-first AI gate (planReveal) ---

    @Test
    fun planRevealStaticCorrectSkipsAi() {
        val plan = SpeedyCatAiChecker.planReveal("Mitochondria", "mitochondria", aiOn = true)
        assertFalse(plan.needsAi)
        assertNotNull(plan.decision)
        assertEquals("correct", plan.decision!!.verdict)
        assertEquals(SpeedyCatAiChecker.SOURCE_BASELINE, plan.decision!!.source)
        assertFalse(plan.decision!!.forceAgain)
    }

    @Test
    fun planRevealStaticIncorrectWithAiOnNeedsAi() {
        val plan = SpeedyCatAiChecker.planReveal("mitochondira", "mitochondria", aiOn = true)
        assertTrue(plan.needsAi)
        assertNull(plan.decision)
    }

    @Test
    fun planRevealStaticIncorrectAiOffUsesBaseline() {
        val plan = SpeedyCatAiChecker.planReveal("insulin", "mitochondria", aiOn = false)
        assertFalse(plan.needsAi)
        assertNotNull(plan.decision)
        assertEquals("incorrect", plan.decision!!.verdict)
        assertEquals(SpeedyCatAiChecker.SOURCE_BASELINE, plan.decision!!.source)
    }

    // --- Shared decision logic ---

    @Test
    fun decideIdkForcesAgainAndLocks() {
        val decision = SpeedyCatAiChecker.decideIdk()
        assertTrue(decision.reveal)
        assertNull(decision.verdict)
        assertTrue(decision.forceAgain)
        assertTrue(decision.lockRatings)
        assertEquals(SpeedyCatAiChecker.SOURCE_IDK, decision.source)
    }

    @Test
    fun decideRevealAiOnHonestRevealsWithModelVerdict() {
        val result = CheckResult(honestAttempt = true, verdict = "correct", reason = "ok")
        val decision = SpeedyCatAiChecker.decideReveal("mitochondria", "Mitochondria", aiOn = true, aiResult = result)
        assertTrue(decision.reveal)
        assertEquals("correct", decision.verdict)
        assertFalse(decision.forceAgain)
        assertFalse(decision.lockRatings)
        assertEquals(SpeedyCatAiChecker.SOURCE_AI, decision.source)
    }

    @Test
    fun decideRevealAiOnDishonestBlocksRevealAndStaysOnCard() {
        val result = CheckResult(honestAttempt = false, verdict = "incorrect", reason = "mash")
        val decision = SpeedyCatAiChecker.decideReveal("asdf", "Mitochondria", aiOn = true, aiResult = result)
        assertFalse(decision.reveal)
        assertFalse(decision.forceAgain)
        assertFalse(decision.lockRatings)
        assertEquals(MessageType.HONESTY_PROMPT, decision.messageType)
        assertEquals(SpeedyCatAiChecker.SOURCE_AI, decision.source)
        assertTrue(SpeedyCatAiChecker.isGamingRetryDecision(decision))
    }

    @Test
    fun decideRevealAiOffAlwaysRevealsWithStringMatch() {
        val correct = SpeedyCatAiChecker.decideReveal("Mitochondria", "mitochondria", aiOn = false, aiResult = null)
        assertTrue(correct.reveal)
        assertEquals("correct", correct.verdict)
        assertFalse(correct.forceAgain)
        assertEquals(SpeedyCatAiChecker.SOURCE_BASELINE, correct.source)

        val wrong = SpeedyCatAiChecker.decideReveal("insulin", "mitochondria", aiOn = false, aiResult = null)
        assertTrue(wrong.reveal)
        assertEquals("incorrect", wrong.verdict)
        assertFalse(wrong.forceAgain) // wrong but honest -> normal ratings
    }

    @Test
    fun decideRevealAiOffHeuristicBlocksGamingWithoutReveal() {
        val decision = SpeedyCatAiChecker.decideReveal("asdf", "mitochondria", aiOn = false, aiResult = null)
        assertFalse(decision.reveal)
        assertFalse(decision.forceAgain)
        assertFalse(decision.lockRatings)
        assertEquals(MessageType.HONESTY_PROMPT, decision.messageType)
        assertEquals(SpeedyCatAiChecker.SOURCE_BASELINE, decision.source)
        assertTrue(SpeedyCatAiChecker.isGamingRetryDecision(decision))
    }

    @Test
    fun isGamingRetryDecisionExcludesIdk() {
        assertFalse(SpeedyCatAiChecker.isGamingRetryDecision(SpeedyCatAiChecker.decideIdk()))
    }

    @Test
    fun decideRevealAiOnButNoResultFallsBack() {
        val decision = SpeedyCatAiChecker.decideReveal("Mitochondria", "mitochondria", aiOn = true, aiResult = null)
        assertTrue(decision.reveal)
        assertEquals(SpeedyCatAiChecker.SOURCE_BASELINE, decision.source)
    }

    @Test
    fun idkDelayIsFiveSeconds() {
        assertEquals(5_000L, SpeedyCatAiChecker.IDK_DELAY_MS)
    }

    // --- AI off without a key; proxy URL resolution ---

    @Test
    fun runCheckWithoutKeyFallsBackToBaselineWhenProxyUnavailable() {
        val (result, source) = SpeedyCatAiChecker.runCheck("front", "typed", "expected", null)
        assertNull(result)
        assertEquals(SpeedyCatAiChecker.SOURCE_BASELINE, source)
    }

    @Test
    fun aiPrefDefaultIsOn() {
        assertTrue(SpeedyCatAiChecker.AI_PREF_DEFAULT)
    }

    @Test
    fun resolveProxyUrlDefault() {
        assertEquals(
            "https://us-central1-speedycat-mcat.cloudfunctions.net/checkSpeedycatAnswer",
            SpeedyCatAiChecker.resolveProxyUrl(),
        )
        assertEquals(SpeedyCatAiChecker.DEFAULT_PROXY_URL, SpeedyCatAiChecker.resolveProxyUrl())
    }

    @Test
    fun runCheckViaProxyWithEmptyUrlReturnsNull() {
        assertNull(SpeedyCatAiChecker.runCheckViaProxy("front", "typed", "expected", proxyUrl = ""))
        assertNull(SpeedyCatAiChecker.runCheckViaProxy("front", "typed", "expected", proxyUrl = "   "))
    }

    @Test
    fun aiCheckerAvailableWithDefaultProxy() {
        assertTrue(SpeedyCatAiChecker.aiCheckerAvailable(null))
    }

    @Test
    fun isAiSourceRecognizesDirectAndProxy() {
        assertTrue(SpeedyCatAiChecker.isAiSource(SpeedyCatAiChecker.SOURCE_AI))
        assertTrue(SpeedyCatAiChecker.isAiSource(SpeedyCatAiChecker.SOURCE_AI_PROXY))
        assertFalse(SpeedyCatAiChecker.isAiSource(SpeedyCatAiChecker.SOURCE_BASELINE))
    }

    @Test
    fun sourceProxyTracesToNamedModel() {
        assertEquals("openai/gpt-5.4-mini via speedycat-proxy", SpeedyCatAiChecker.SOURCE_AI_PROXY)
    }

    // --- Cloze-deletion extraction (the expected-answer source) ---

    @Test
    fun clozeExtractionSingleDeletion() {
        val field = "For vector subtraction, you must change the {{c1::direction}} of the vector"
        assertEquals("direction", SpeedyCatAiChecker.clozeAnswerForOrd(field, 1))
    }

    @Test
    fun clozeExtractionMultipleDeletionsOrdered() {
        val field = "Quantum number {{c1::n}} is the principal number and gives the {{c1::energy level}}"
        assertEquals("n, energy level", SpeedyCatAiChecker.clozeAnswerForOrd(field, 1))
    }

    @Test
    fun clozeExtractionMultiWordAnswerNotSplit() {
        val field = "The acid is {{c1::acrylic acid}} and the base is {{c2::sodium hydroxide}}"
        assertEquals("acrylic acid", SpeedyCatAiChecker.clozeAnswerForOrd(field, 1))
        assertEquals("sodium hydroxide", SpeedyCatAiChecker.clozeAnswerForOrd(field, 2))
    }

    @Test
    fun clozeExtractionDropsHintAndReturnsNullWhenAbsent() {
        assertEquals("mitochondria", SpeedyCatAiChecker.clozeAnswerForOrd("{{c1::mitochondria::organelle}}", 1))
        assertNull(SpeedyCatAiChecker.clozeAnswerForOrd("no cloze here", 1))
    }

    @Test
    fun clozeExtractionRotationalEquilibriumFromMarkers() {
        val field =
            "{{c1::rotational equilibrium}} occurs in the absence of any net torques acting on an object"
        assertEquals("rotational equilibrium", SpeedyCatAiChecker.clozeAnswerForOrd(field, 1))
    }

    @Test
    fun clozeExtractionFromRenderedSpan() {
        val back =
            """<span class="cloze" data-ordinal="1">rotational equilibrium</span> occurs in the absence of any net torques acting on an object"""
        assertEquals("rotational equilibrium", SpeedyCatAiChecker.clozeAnswerForOrd(back, 1))
    }

    @Test
    fun extractClozeFromPrerenderedRotationalEquilibrium() {
        val front = "___ occurs in the absence of any net torques acting on an object"
        val back =
            "rotational equilibrium occurs in the absence of any net torques acting on an object"
        assertEquals(
            "rotational equilibrium",
            SpeedyCatAiChecker.extractClozeFromPrerendered(front, back),
        )
    }

    @Test
    fun extractClozeFromPrerenderedBracketBlank() {
        val front = "[...] occurs in the absence of any net torques acting on an object"
        val back =
            "rotational equilibrium occurs in the absence of any net torques acting on an object"
        assertEquals(
            "rotational equilibrium",
            SpeedyCatAiChecker.extractClozeFromPrerendered(front, back),
        )
    }

    @Test
    fun extractClozeFromPrerenderedTranslationalEquilibrium() {
        val front =
            "___ equilibrium occurs in the absence of any net forces acting on an object"
        val back =
            "Translational equilibrium occurs in the absence of any net forces acting on an object"
        assertEquals(
            "Translational",
            SpeedyCatAiChecker.extractClozeFromPrerendered(front, back),
        )
    }

    @Test
    fun clozeExtractionSkipsFrontPlaceholderSpan() {
        val front =
            """<span class="cloze" data-ordinal="1">[...] equilibrium</span> occurs in the absence of any net forces acting on an object"""
        assertNull(SpeedyCatAiChecker.clozeAnswerForOrd(front, 1))
        val back =
            """<span class="cloze" data-ordinal="1">Translational</span> equilibrium occurs in the absence of any net forces acting on an object"""
        assertEquals("Translational", SpeedyCatAiChecker.clozeAnswerForOrd(back, 1))
    }

    // --- Field stripping: expected-answer source cleanup ---

    @Test
    fun stripExpectedLeavesBareClozeUntouched() {
        assertEquals("direction", SpeedyCatAiChecker.stripExpected("direction"))
    }

    @Test
    fun stripExpectedRemovesStyleBreadcrumbHtmlAndLinkFooter() {
        val rendered =
            "<style>#kard { color: red; }</style>" +
                "<div class=\"tags\">Physics::Kinematics</div>" +
                "<div>change the <span class=\"cloze\">direction</span>&nbsp;of the vector</div>" +
                "<a href=\"https://khanacademy.org\">Khan Academy Link</a>"
        val cleaned = SpeedyCatAiChecker.stripExpected(rendered)
        assertThat(cleaned, not(containsString("<")))
        assertThat(cleaned, not(containsString("#kard")))
        assertThat(cleaned, not(containsString("Physics::Kinematics")))
        assertThat(cleaned, not(containsString("Khan Academy Link")))
        assertThat(cleaned, containsString("change the direction of the vector"))
    }

    @Test
    fun stripExpectedMultiClozeHtmlAndEntities() {
        val raw = "n, <i>energy level</i> or&nbsp;<i>shell number</i>"
        assertEquals("n, energy level or shell number", SpeedyCatAiChecker.stripExpected(raw))
    }

    @Test
    fun stripExpectedMultiLevelBreadcrumb() {
        assertEquals(
            "n",
            SpeedyCatAiChecker.stripExpected("General_Chemistry::Atomic_Structure::Quantum_Numbers n"),
        )
    }

    @Test
    fun stripFrontRemovesBreadcrumbAndKeepsClozeBlank() {
        val front =
            "<style>x{}</style><div class=\"tags\">Physics::Kinematics</div>" +
                "<div>change the <span class=\"cloze\">[...]</span> of the vector</div>"
        assertEquals("change the [...] of the vector", SpeedyCatAiChecker.stripFront(front))
    }

    @Test
    fun stripBreadcrumbHtmlRemovesTagWrapperOnly() {
        val html = "<div class=\"tags\">Physics::Kinematics</div><div>the question [...]</div>"
        val stripped = SpeedyCatAiChecker.stripBreadcrumbHtml(html)
        assertThat(stripped, not(containsString("Physics::Kinematics")))
        assertThat(stripped, containsString("the question [...]"))
    }

    // --- Multi-cloze flexible-separator matching (IS the AI-off fallback) ---

    @Test
    fun multiClozeSeparatorsAllMatch() {
        for (typed in listOf("a b", "a, b", "a; b", "a  b", "a | b", "a / b", "a : b", "A B")) {
            assertTrue("expected '$typed' to match", SpeedyCatAiChecker.deterministicCorrect(typed, "a, b"))
        }
    }

    @Test
    fun multiClozeOrderMatters() {
        assertFalse(SpeedyCatAiChecker.deterministicCorrect("b a", "a, b"))
    }

    @Test
    fun multiClozeMultiWordAnswersNotSplit() {
        assertTrue(
            SpeedyCatAiChecker.deterministicCorrect(
                "acrylic acid sodium hydroxide",
                "acrylic acid, sodium hydroxide",
            ),
        )
        assertTrue(SpeedyCatAiChecker.deterministicCorrect("acrylic acid", "acrylic acid"))
    }

    private fun assertNotNull(value: Any?) = assertTrue("expected non-null", value != null)
}
