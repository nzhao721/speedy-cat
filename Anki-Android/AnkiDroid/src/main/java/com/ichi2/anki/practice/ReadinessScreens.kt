// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors

package com.ichi2.anki.practice

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Shared Memory / Performance / Readiness pillar UI for Dashboard and More. */
@Composable
fun ReadinessPillarsScreen(
    vm: ReadinessViewModel,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        when {
            vm.loading ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Calculating your readiness…", color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
fun PillarCard(pillar: ReadinessPillar) {
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
