// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors

package com.ichi2.anki.pages

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
import com.ichi2.anki.practice.ReadinessPillarsScreen
import com.ichi2.anki.practice.ReadinessViewModel
import com.ichi2.compose.theme.AnkiDroidTheme

/**
 * SpeedyCAT dashboard: the same three readiness pillars (Memory / Performance /
 * Readiness) as the desktop dashboard tab, rendered natively in Compose.
 */
class DashboardFragment : Fragment() {
    private val viewModel: ReadinessViewModel by viewModels()

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
                        ReadinessPillarsScreen(viewModel)
                    }
                }
            }
        }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        val hideBack = arguments?.getBoolean(ARG_HIDE_BACK_BUTTON) == true
        requireActivity().title =
            if (hideBack) {
                getString(R.string.dashboard)
            } else {
                getString(R.string.dashboard)
            }
    }

    companion object {
        const val ARG_HIDE_BACK_BUTTON = "hideBackButton"
    }
}
