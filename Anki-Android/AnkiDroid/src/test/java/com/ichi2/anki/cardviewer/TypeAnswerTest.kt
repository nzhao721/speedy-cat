/*
 *  Copyright (c) 2021 David Allison <davidallisongithub@gmail.com>
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

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.cardviewer.TypeAnswer.Companion.contentForCloze
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.intellij.lang.annotations.Language
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TypeAnswerTest : RobolectricTest() {
    override fun setUp() {
        super.setUp()
        col
    }

    @Test
    fun testTypeAnsAnswerFilterNormalCorrect() {
        @Language("HTML")
        val buf = """<style>.card {
 font-family: arial;
 font-size: 20px;
 text-align: center;
 color: black;
 background-color: white;
}
</style>Type in hello
[[type:Back]]

<hr id=answer>

$!"""

        @Language("HTML")
        val expectedOutput = """<style>.card {
 font-family: arial;
 font-size: 20px;
 text-align: center;
 color: black;
 background-color: white;
}
</style>Type in hello
<div class="type-answer-result type-answer-correct"><code id=typeans><span class=typeGood>hello</span></code></div>

<hr id=answer>

$!"""
        assertEquals(expectedOutput, typeAnsAnswerFilter(buf, "hello", "hello"))
    }

    @Test
    fun testTypeAnsAnswerFilterNormalIncorrect() {
        @Language("HTML")
        val buf = """<style>.card {
 font-family: arial;
 font-size: 20px;
 text-align: center;
 color: black;
 background-color: white;
}
</style>Type in hello
[[type:Back]]

<hr id=answer>

hello"""

        @Language("HTML")
        val expectedOutput = """<style>.card {
 font-family: arial;
 font-size: 20px;
 text-align: center;
 color: black;
 background-color: white;
}
</style>Type in hello
<div class="type-answer-result type-answer-incorrect"><code id=typeans><span class=typeBad>hello</span><br><span id=typearrow>&darr;</span><br><span class=typeMissed>xyzzy$$$22</span></code></div>

<hr id=answer>

hello"""
        // Make sure $! as typed shows up as $!
        assertEquals(expectedOutput, typeAnsAnswerFilter(buf, "hello", "xyzzy$$$22"))
    }

    @Test
    fun testTypeAnsAnswerFilterNormalEmpty() {
        @Language("HTML")
        val buf = """<style>.card {
 font-family: arial;
 font-size: 20px;
 text-align: center;
 color: black;
 background-color: white;
}
</style>Type in hello
[[type:Back]]

<hr id=answer>

hello"""

        @Language("HTML")
        val expectedOutput = """<style>.card {
 font-family: arial;
 font-size: 20px;
 text-align: center;
 color: black;
 background-color: white;
}
</style>Type in hello
<code id=typeans>hello</code>

<hr id=answer>

hello"""
        // Make sure $! as typed shows up as $!
        assertEquals(expectedOutput, typeAnsAnswerFilter(buf, "", "hello"))
    }

    @Test
    fun testTypeAnsAnswerFilterDollarSignsCorrect() {
        @Language("HTML")
        val buf = """<style>.card {
 font-family: arial;
 font-size: 20px;
 text-align: center;
 color: black;
 background-color: white;
}
</style>Type in $!
[[type:Back]]

<hr id=answer>

$!"""

        @Language("HTML")
        val expectedOutput = """<style>.card {
 font-family: arial;
 font-size: 20px;
 text-align: center;
 color: black;
 background-color: white;
}
</style>Type in $!
<div class="type-answer-result type-answer-correct"><code id=typeans><span class=typeGood>$!</span></code></div>

<hr id=answer>

$!"""
        // Make sure $! as typed shows up as $!
        assertEquals(expectedOutput, typeAnsAnswerFilter(buf, "$!", "$!"))
    }

    @Test
    fun testTypeAnsAnswerFilterDollarSignsIncorrect() {
        @Language("HTML")
        val buf = """<style>.card {
 font-family: arial;
 font-size: 20px;
 text-align: center;
 color: black;
 background-color: white;
}
</style>Type in $!
[[type:Back]]

<hr id=answer>

$!"""

        @Language("HTML")
        val expectedOutput = """<style>.card {
 font-family: arial;
 font-size: 20px;
 text-align: center;
 color: black;
 background-color: white;
}
</style>Type in $!
<div class="type-answer-result type-answer-incorrect"><code id=typeans><span class=typeBad>$!</span><br><span id=typearrow>&darr;</span><br><span class=typeMissed>hello</span></code></div>

<hr id=answer>

$!"""
        // Make sure $! as typed shows up as $!
        assertEquals(expectedOutput, typeAnsAnswerFilter(buf, "$!", "hello"))
    }

    @Test
    fun testTypeAnsAnswerFilterDollarSignsEmpty() {
        @Language("HTML")
        val buf = """<style>.card {
 font-family: arial;
 font-size: 20px;
 text-align: center;
 color: black;
 background-color: white;
}
</style>Type in $!
[[type:Back]]

<hr id=answer>

$!"""

        @Language("HTML")
        val expectedOutput = """<style>.card {
 font-family: arial;
 font-size: 20px;
 text-align: center;
 color: black;
 background-color: white;
}
</style>Type in $!
<code id=typeans>$!</code>

<hr id=answer>

$!"""
        // Make sure $! as typed shows up as $!
        assertEquals(expectedOutput, typeAnsAnswerFilter(buf, "", "$!"))
    }

    @Test
    fun testTypeAnsAnswerFilterCaseInsensitiveCorrect() {
        // SpeedyCAT: typing the right answer in a different case counts as correct,
        // and the case difference is not flagged as wrong.
        val output = typeAnsAnswerFilter("[[type:Back]]", "hello", "HELLO")
        assertThat(output, containsString("type-answer-correct"))
        assertThat(output, not(containsString("type-answer-incorrect")))
    }

    @Test
    fun forcedRecallRevealDoesNotShowExpectedAnswer() {
        val correct = typeAnsAnswerFilter("[[type:Back]]", "hello", "hello")
        assertThat(correct, not(containsString("type-answer-expected")))
        assertThat(correct, not(containsString("Expected:")))

        val incorrect = typeAnsAnswerFilter("[[type:Back]]", "hallo", "hello")
        assertThat(incorrect, not(containsString("Expected:")))
    }

    @Test
    fun forcedRecallExpectedAnswerHiddenWhenNothingTyped() {
        // No typed answer -> no verdict and no expected line (the answer is shown as-is).
        val output = typeAnsAnswerFilter("[[type:Back]]", "", "hello")
        assertThat(output, not(containsString("type-answer-expected")))
    }

    @Test
    fun testClozeWithRepeatedWords() {
        // 8229
        val cloze1 = "This is {{c1::test}} which is containing {{c1::test}} word twice"
        assertEquals("test", contentForCloze(cloze1, 1))
        val cloze2 = "This is {{c1::test}} which is containing {{c1::test}} word twice {{c1::test2}}"
        assertEquals("test, test, test2", contentForCloze(cloze2, 1))
    }

    private fun typeAnsAnswerFilter(
        answer: String,
        correctAnswer: String,
        userAnswer: String,
    ): String =
        TypeAnswer(
            useInputTag = false,
            autoFocus = false,
        ).filterAnswer(answer, correctAnswer, userAnswer)
}
