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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
                title = { Text("Readiness") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.ic_baseline_arrow_back_24), contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                vm.loading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Computing readiness…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                vm.loadFailed ->
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Couldn't compute readiness right now.")
                        Button(onClick = { vm.load() }) { Text("Retry") }
                    }

                else ->
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        for (pillar in vm.pillars) {
                            PillarCard(pillar)
                        }
                    }
            }
        }
    }
}

@Composable
private fun PillarCard(pillar: ReadinessPillar) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(pillar.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (pillar.sufficient) {
                    Text(
                        pillar.range,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    // Give-up state: an explicit label instead of a bare number.
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Text(
                            "Not enough data",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            if (pillar.sufficient) {
                for (line in pillar.detail) {
                    Text(line, style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Text(
                    pillar.insufficientReason,
                    style = MaterialTheme.typography.bodyMedium,
                )
                for (line in pillar.detail) {
                    Text(line, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
