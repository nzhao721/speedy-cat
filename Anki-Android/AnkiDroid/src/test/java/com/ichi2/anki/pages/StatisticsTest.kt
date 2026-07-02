// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: Copyright (c) 2025 lukstbit <52494258+lukstbit@users.noreply.github.com>

package com.ichi2.anki.pages

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.SingleFragmentActivity
import com.ichi2.anki.common.destinations.StatisticsDestination
import com.ichi2.anki.common.destinations.launchActivity
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StatisticsTest : RobolectricTest() {
    @Test
    fun `shows statistics title instead of deck picker`() =
        runTest {
            launchActivity<SingleFragmentActivity>(StatisticsDestination).use {
                advanceUntilIdle()
                onView(withText("Statistics")).check(matches(isDisplayed()))
                onView(withId(R.id.deck_name)).check { view, _ ->
                    assert(view.visibility == android.view.View.GONE)
                }
            }
        }
}
