// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors

package com.ichi2.anki.pages

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.ichi2.anki.R
import com.ichi2.anki.common.destinations.PreferencesDestination
import com.ichi2.anki.common.destinations.navigate
import com.ichi2.anki.practice.DashboardScreen
import com.ichi2.anki.practice.ReadinessViewModel
import com.ichi2.compose.theme.AnkiDroidTheme

/**
 * SpeedyCAT dashboard: readiness pillars plus navigation to Flashcards,
 * Practice Questions, and Settings.
 */
class DashboardFragment : Fragment() {
    interface Callbacks {
        fun onFlashcardsClick()

        fun onPracticeClick()
    }

    private val viewModel: ReadinessViewModel by viewModels()
    private var callbacks: Callbacks? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callbacks = context as? Callbacks
    }

    override fun onDetach() {
        callbacks = null
        super.onDetach()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AnkiDroidTheme {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        DashboardScreen(
                            viewModel = viewModel,
                            onFlashcardsClick = { callbacks?.onFlashcardsClick() },
                            onPracticeClick = { callbacks?.onPracticeClick() },
                            onSettingsClick = { navigate(PreferencesDestination.Root) },
                        )
                    }
                }
            }
        }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().title = getString(R.string.dashboard)
    }

    companion object {
        const val ARG_HIDE_BACK_BUTTON = "hideBackButton"
    }
}
