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
     * Human-readable form of the [expected] answer for display next to the verdict, so the learner
     * can see exactly what the checker compared their typed answer against.
     *
     * Uses the same HTML & special-field stripping and whitespace collapsing as [normalize] (so it
     * reflects what was actually compared), but intentionally does NOT lower-case: matching is
     * case-insensitive, so the answer is shown in its original case for readability. Returns an
     * empty string when there is nothing to show.
     */
    fun displayAnswer(expected: String): String =
        stripHTMLAndSpecialFields(expected)
            .replace('\u00a0', ' ') // non-breaking space -> regular space
            .replace(whitespace, " ")
            .trim()
}
