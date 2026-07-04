// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors
//
// The Readiness screen: the MCAT readiness metric (Memory / Performance /
// Readiness) presented natively in Compose. Each pillar shows a value, an
// explicit range (95% CI), and minimal sample lines — or a give-up message when
// there isn't enough data. A bare readiness number is never shown.

package com.ichi2.anki.practice

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.R
import com.ichi2.compose.theme.AnkiDroidTheme

class ReadinessActivity : AnkiActivity() {
    private val viewModel: ReadinessViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AnkiDroidTheme {
                ReadinessScreen(viewModel, onClose = { finish() })
            }
        }
    }
}

@Composable
private fun ReadinessScreen(
    vm: ReadinessViewModel,
    onClose: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dashboard") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.ic_baseline_arrow_back_24), contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            ReadinessPillarsScreen(vm)
        }
    }
}
