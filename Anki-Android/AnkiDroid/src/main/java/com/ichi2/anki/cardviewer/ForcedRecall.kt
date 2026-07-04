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

import com.ichi2.anki.backend.stripHTMLAndSpecialFields
import org.intellij.lang.annotations.Language

/**
 * Deterministic (AI-off) helpers for SpeedyCAT's "forced active recall" feature.
 *
 * The learner is forced to type an answer before the back of a card can be revealed.
 * On reveal we compare what they typed against the card's expected answer. Matching is
 * intentionally simple and offline so the result is reproducible and traceable: it does
 * not call out to any model, and reuses AnkiDroid's existing [stripHTMLAndSpecialFields]
 * helper for HTML handling.
 */
object ForcedRecall {
    private val whitespace = Regex("\\s+")

    /**
     * SpeedyCAT: separator punctuation treated as whitespace so MULTI-CLOZE answers match
     * regardless of the learner's separator (`, ; : / |`). With ordered cloze answers joined
     * by single spaces, typing `a b`, `a, b`, or `a; b` all normalize identically to `a b`.
     */
    private val separators = Regex("[,;:/|\\\\]+")

    /**
     * Normalizes an answer for comparison: strips HTML & special fields (e.g. `[[type:...]]`),
     * treats separator punctuation (`, ; : / |`) as whitespace so multi-cloze answers match
     * regardless of the learner's separator, collapses runs of whitespace to a single space,
     * trims, and lower-cases.
     *
     * Note: this delegates to [stripHTMLAndSpecialFields], which uses Android's `HtmlCompat`
     * to decode HTML entities, so it must run on an Android (or Robolectric) runtime.
     */
    fun normalize(text: String): String =
        stripHTMLAndSpecialFields(text)
            .replace('\u00a0', ' ') // non-breaking space -> regular space
            .replace(separators, " ") // SpeedyCAT: separator-flexible multi-cloze matching
            .replace(whitespace, " ")
            .trim()
            .lowercase()

    /**
     * Whether the learner's [typed] answer matches the [expected] answer after [normalize].
     *
     * Returns false when the normalized typed answer is empty, so a blank submission is never
     * treated as correct even if the expected answer is also blank.
     */
    fun matches(
        typed: String,
        expected: String,
    ): Boolean {
        val normalizedTyped = normalize(typed)
        if (normalizedTyped.isEmpty()) return false
        return normalizedTyped == normalize(expected)
    }

    /**
     * Whole-answer Correct/Incorrect banner for forced-recall reveals (mirrors desktop
     * ``Reviewer._format_match_feedback``).
     */
    @Language("HTML")
    fun formatVerdictBanner(correct: Boolean): String =
        if (correct) {
            """<div id="type-result" style="color:#1b873b;font-weight:bold;margin-bottom:6px">&#x2714; Correct</div>"""
        } else {
            """<div id="type-result" style="color:#c0392b;font-weight:bold;margin-bottom:6px">&#x2718; Incorrect</div>"""
        }

    /**
     * Human-readable form of the [expected] answer (HTML stripped, case preserved).
     * Used internally by the AI checker prompt/fallback — not shown on the reveal UI.
     */
    fun displayAnswer(expected: String): String =
        stripHTMLAndSpecialFields(expected)
            .replace('\u00a0', ' ') // non-breaking space -> regular space
            .replace(whitespace, " ")
            .trim()
}
