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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the deterministic forced-recall answer matcher.
 *
 * Inputs intentionally avoid HTML entities (e.g. `&amp;`) so the underlying HTML stripper does not
 * need Android's `HtmlCompat`, mirroring [com.ichi2.anki.backend.HtmlUtilsTest].
 */
class ForcedRecallTest {
    @Test
    fun exactMatch() {
        assertTrue(ForcedRecall.matches("Mitochondria", "Mitochondria"))
    }

    @Test
    fun caseInsensitive() {
        assertTrue(ForcedRecall.matches("mitochondria", "Mitochondria"))
    }

    @Test
    fun trimsAndCollapsesWhitespace() {
        assertTrue(ForcedRecall.matches("  the   krebs\tcycle ", "the krebs cycle"))
    }

    @Test
    fun ignoresHtmlInExpectedAnswer() {
        assertTrue(ForcedRecall.matches("aorta", "<b>aorta</b>"))
    }

    @Test
    fun ignoresTypeSpecialField() {
        // [[type:...]] is a processed type-answer placeholder and should be stripped before comparison
        assertTrue(ForcedRecall.matches("aorta", "[[type:Back]]aorta"))
    }

    @Test
    fun nonBreakingSpaceTreatedAsSpace() {
        assertTrue(ForcedRecall.matches("new york", "new\u00A0york"))
    }

    @Test
    fun mismatchReturnsFalse() {
        assertFalse(ForcedRecall.matches("liver", "kidney"))
    }

    @Test
    fun blankTypedNeverMatches() {
        assertFalse(ForcedRecall.matches("", ""))
        assertFalse(ForcedRecall.matches("   ", "anything"))
        // even if the expected answer is also effectively blank
        assertFalse(ForcedRecall.matches("   ", "<br>"))
    }
}
