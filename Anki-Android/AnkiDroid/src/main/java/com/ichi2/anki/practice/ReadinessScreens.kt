// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors

package com.ichi2.anki.practice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ichi2.anki.R
import com.ichi2.utils.show

@Composable
fun DashboardScreen(
    viewModel: ReadinessViewModel,
    onFlashcardsClick: () -> Unit,
    onPracticeClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dashboard)) },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings_black),
                            contentDescription = stringResource(R.string.open_settings),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onFlashcardsClick,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.decks))
                }
                Button(
                    onClick = onPracticeClick,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.speedycat_practice_questions))
                }
            }
            ReadinessPillarsScreen(viewModel, Modifier.weight(1f))
        }
    }
}

@Composable
fun ProjectedScoreCard(projected: ProjectedMcatScore) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Projected MCAT score",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (projected.sufficient) {
                Text(
                    projected.totalRange,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                val total = projected.total
                val totalLow = projected.totalLow
                val totalHigh = projected.totalHigh
                if (total != null && totalLow != null && totalHigh != null) {
                    McatScaledRangeRow(
                        scaleMin = MCAT_TOTAL_MIN,
                        scaleMax = MCAT_TOTAL_MAX,
                        low = totalLow,
                        high = totalHigh,
                        marker = total,
                    )
                }
                Column(
                    modifier = Modifier.padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    for (line in projected.sections) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                line.sectionLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                line.scaledRange,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            val scaled = line.scaled
                            val scaledLow = line.scaledLow
                            val scaledHigh = line.scaledHigh
                            if (scaled != null && scaledLow != null && scaledHigh != null) {
                                McatScaledRangeRow(
                                    scaleMin = MCAT_SECTION_MIN,
                                    scaleMax = MCAT_SECTION_MAX,
                                    low = scaledLow,
                                    high = scaledHigh,
                                    marker = scaled,
                                    compact = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
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
                Text(
                    projected.insufficientReason,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

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

            else -> {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    vm.projectedScore?.let { projected ->
                        ProjectedScoreCard(projected)
                    }
                    for (pillar in vm.pillars) {
                        PillarCard(pillar = pillar)
                    }
                }
            }
        }
    }
}

@Composable
fun PillarCard(pillar: ReadinessPillar) {
    val breakdown = pillar.breakdown

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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(pillar.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    PillarHelpIcon(pillar.name)
                }
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

            if (breakdown != null && breakdown.sections.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                BreakdownSection(
                    title = "By section",
                    rows = breakdown.sections,
                )
            }
            if (pillar.name == "Performance" && breakdown != null && breakdown.topics.isNotEmpty()) {
                BreakdownSection(
                    title = "By topic",
                    rows = breakdown.topics,
                )
            }
        }
    }
}

@Composable
private fun BreakdownSection(
    title: String,
    rows: List<PillarBreakdownRow>,
) {
    var expanded by remember(title) { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            if (expanded) "\u25B2" else "\u25BC",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    AnimatedVisibility(visible = expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (row in rows) {
                BreakdownRow(row)
            }
        }
    }
}

@Composable
private fun BreakdownRow(row: PillarBreakdownRow) {
    val context = LocalContext.current
    val insufficientMessage =
        stringResource(R.string.speedycat_pillar_breakdown_insufficient)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            row.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            row.value,
            style = MaterialTheme.typography.bodySmall,
            color =
                if (row.sufficient) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier =
                Modifier
                    .weight(1.2f)
                    .then(
                        if (!row.sufficient) {
                            Modifier
                                .semantics { contentDescription = insufficientMessage }
                                .clickable {
                                    MaterialAlertDialogBuilder(context).show {
                                        setMessage(insufficientMessage)
                                    }
                                }
                        } else {
                            Modifier
                        },
                    ),
        )
    }
}

/** Range bar row with scale labels (e.g. 472 | bar | 528), matching desktop pillar bars. */
@Composable
private fun McatScaledRangeRow(
    scaleMin: Int,
    scaleMax: Int,
    low: Int,
    high: Int,
    marker: Int,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val barHeight = if (compact) 6.dp else 8.dp
    val labelStyle =
        if (compact) {
            MaterialTheme.typography.labelSmall
        } else {
            MaterialTheme.typography.labelMedium
        }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            scaleMin.toString(),
            style = labelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        McatRangeBar(
            low = low,
            high = high,
            marker = marker,
            scaleMin = scaleMin,
            scaleMax = scaleMax,
            barHeight = barHeight,
            modifier = Modifier.weight(1f),
        )
        Text(
            scaleMax.toString(),
            style = labelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Filled range bar for a projected MCAT score on a fixed scale. */
@Composable
private fun McatRangeBar(
    low: Int,
    high: Int,
    marker: Int,
    scaleMin: Int,
    scaleMax: Int,
    barHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val markerColor = MaterialTheme.colorScheme.primary
    val (leftFraction, widthFraction) = mcatScaledSpan(scaleMin, scaleMax, low, high)
    val markerFraction = mcatScaledMarker(scaleMin, scaleMax, marker)

    BoxWithConstraints(
        modifier =
            modifier
                .height(barHeight)
                .clip(RoundedCornerShape(barHeight / 2))
                .background(trackColor)
                .border(1.dp, borderColor, RoundedCornerShape(barHeight / 2)),
    ) {
        val barWidthPx = constraints.maxWidth.toFloat()
        val density = LocalDensity.current
        Box(
            Modifier
                .fillMaxHeight()
                .width(with(density) { (barWidthPx * widthFraction).toDp() })
                .offset(x = with(density) { (barWidthPx * leftFraction).toDp() })
                .background(fillColor),
        )
        Box(
            Modifier
                .fillMaxHeight()
                .width(2.dp)
                .offset(
                    x =
                        with(density) {
                            ((barWidthPx * markerFraction) - 1f).coerceAtLeast(0f).toDp()
                        },
                )
                .background(markerColor),
        )
    }
}

/** Tap-to-explain help icon next to a pillar title (Material help pattern). */
@Composable
private fun PillarHelpIcon(pillarName: String) {
    val tooltipRes = pillarTooltipResId(pillarName) ?: return
    val context = LocalContext.current
    val tooltip = stringResource(tooltipRes)
    val helpLabel =
        stringResource(R.string.help_button_content_description, pillarName)
    Icon(
        painter = painterResource(R.drawable.ic_help_black_24dp),
        contentDescription = helpLabel,
        modifier =
            Modifier
                .size(20.dp)
                .clickable {
                    MaterialAlertDialogBuilder(context).show {
                        setTitle(pillarName)
                        setIcon(R.drawable.ic_help_black_24dp)
                        setMessage(tooltip)
                    }
                },
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
