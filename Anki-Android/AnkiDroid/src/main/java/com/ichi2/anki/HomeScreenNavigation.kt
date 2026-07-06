// SPDX-FileCopyrightText: 2026 Shaan Narendran <shaannaren06@gmail.com>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.ichi2.anki

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat.Type.navigationBars
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.FragmentTransaction
import androidx.fragment.app.commit
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.ichi2.anki.common.annotations.NeedsTest
import com.ichi2.anki.pages.DashboardFragment
import com.ichi2.anki.practice.PracticeActivity

private const val DASHBOARD_FRAGMENT_TAG = "speedycat_dashboard"

/**
 * SpeedyCAT home navigation: Dashboard is the startup screen; Flashcards and
 * Practice are reached from dashboard actions. Bottom nav and the navigation
 * drawer are not used on phones.
 */
@NeedsTest("startup shows Dashboard, not deck picker")
@NeedsTest("back press returns from Flashcards to Dashboard before exiting")
fun DeckPicker.setupBottomNavigation() {
    if (fragmented) return

    val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)
    bottomNav.isVisible = false

    val contentWrapper = findViewById<View>(R.id.deck_picker_content_wrapper)
    val fragmentContainer = findViewById<View>(R.id.bottom_nav_fragment_container)
    val toolbarContainer = findViewById<View>(R.id.toolbar_container)

    val flashcardsBackCallback =
        object : OnBackPressedCallback(enabled = false) {
            override fun handleOnBackPressed() {
                showSpeedyCatDashboard(
                    contentWrapper = contentWrapper,
                    fragmentContainer = fragmentContainer,
                    toolbarContainer = toolbarContainer,
                    flashcardsBackCallback = this,
                )
            }
        }
    onBackPressedDispatcher.addCallback(this, flashcardsBackCallback)

    speedyCatHomeViews =
        DeckPicker.SpeedyCatHomeViews(
            contentWrapper = contentWrapper,
            fragmentContainer = fragmentContainer,
            toolbarContainer = toolbarContainer,
            flashcardsBackCallback = flashcardsBackCallback,
        )

    showSpeedyCatDashboard(
        contentWrapper = contentWrapper,
        fragmentContainer = fragmentContainer,
        toolbarContainer = toolbarContainer,
        flashcardsBackCallback = flashcardsBackCallback,
    )
}

internal fun DeckPicker.showSpeedyCatDashboard(
    contentWrapper: View,
    fragmentContainer: View,
    toolbarContainer: View,
    flashcardsBackCallback: OnBackPressedCallback,
) {
    speedyCatOnFlashcards = false
    flashcardsBackCallback.isEnabled = false
    toolbarContainer.isVisible = false
    contentWrapper.isVisible = false
    fragmentContainer.isVisible = true
    supportActionBar?.setDisplayHomeAsUpEnabled(false)

    ViewCompat.setOnApplyWindowInsetsListener(fragmentContainer) { view, insets ->
        val navBars = insets.getInsets(navigationBars())
        view.updatePadding(bottom = navBars.bottom)
        insets
    }

    supportFragmentManager.commit {
        hideSpeedyCatHomeFragmentsIn(this)
        val existing = supportFragmentManager.findFragmentByTag(DASHBOARD_FRAGMENT_TAG)
        if (existing != null) {
            show(existing)
        } else {
            add(
                R.id.bottom_nav_fragment_container,
                DashboardFragment().apply {
                    arguments =
                        Bundle().apply {
                            putBoolean(DashboardFragment.ARG_HIDE_BACK_BUTTON, true)
                        }
                },
                DASHBOARD_FRAGMENT_TAG,
            )
        }
    }
}

internal fun DeckPicker.showSpeedyCatFlashcards(
    contentWrapper: View,
    fragmentContainer: View,
    toolbarContainer: View,
    flashcardsBackCallback: OnBackPressedCallback,
) {
    speedyCatOnFlashcards = true
    flashcardsBackCallback.isEnabled = true
    toolbarContainer.isVisible = true
    contentWrapper.isVisible = true
    fragmentContainer.isVisible = false

    supportFragmentManager.commit {
        hideSpeedyCatHomeFragmentsIn(this)
    }

    title = getString(R.string.decks)
    supportActionBar?.subtitle = null
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    supportActionBar?.setHomeButtonEnabled(true)
    findViewById<Toolbar>(R.id.toolbar).setNavigationOnClickListener {
        showSpeedyCatDashboard(
            contentWrapper = contentWrapper,
            fragmentContainer = fragmentContainer,
            toolbarContainer = toolbarContainer,
            flashcardsBackCallback = flashcardsBackCallback,
        )
    }
}

internal fun DeckPicker.navigateToSpeedyCatPractice() {
    startActivity(Intent(this, PracticeActivity::class.java))
}

private fun DeckPicker.hideSpeedyCatHomeFragmentsIn(transaction: FragmentTransaction) {
    supportFragmentManager.fragments.forEach { fragment ->
        if (fragment.id == R.id.bottom_nav_fragment_container) transaction.hide(fragment)
    }
}
