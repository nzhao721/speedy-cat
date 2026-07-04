// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.cardviewer

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.junit.Test

class SpeedyCatExplanationCheckerTest {
    @Test
    fun `requiresExplanationGate is deterministic`() {
        val session = "ps-test"
        assertThat(
            SpeedyCatExplanationChecker.requiresExplanationGate(session, "q-7"),
            equalTo(SpeedyCatExplanationChecker.requiresExplanationGate(session, "q-7")),
        )
    }

    @Test
    fun `buildEvaluatorPrompt includes stem not choices`() {
        val prompt =
            SpeedyCatExplanationChecker.buildEvaluatorPrompt(
                "Which base pairs with adenine?",
                "Thymine pairs via two hydrogen bonds.",
                "Thymine",
            )
        assertThat(prompt.contains("Which base pairs with adenine?"), equalTo(true))
        assertThat(prompt.contains("Thymine pairs via two hydrogen bonds."), equalTo(true))
        assertThat(prompt.contains("cytosine"), equalTo(false))
    }

    @Test
    fun `explanationFailureHint escalates`() {
        val h1 = SpeedyCatExplanationChecker.explanationFailureHint(1)
        val h2 = SpeedyCatExplanationChecker.explanationFailureHint(2)
        assertThat(h1, not(equalTo(h2)))
    }

    @Test
    fun `parseExplanationResponse accepts valid JSON`() {
        val result =
            SpeedyCatExplanationChecker.parseExplanationResponse(
                """{"pass":true,"feedback":"good"}""",
            )
        assertThat(result?.passed, equalTo(true))
        assertThat(result?.feedback, equalTo("good"))
    }
}
