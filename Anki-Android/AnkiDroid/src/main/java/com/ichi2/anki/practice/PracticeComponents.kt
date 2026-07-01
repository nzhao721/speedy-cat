// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors
//
// Shared Compose UI for the two MCAT study modes: the multiple-choice question
// card (immediate feedback in practice, deferred in full-length) and the reading
// passage panel. Presentational only — all flow control lives in the callers,
// mirroring the desktop `QuestionView.svelte` / `PassagePanel.svelte`.

package com.ichi2.anki.practice

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

internal val CorrectGreen = Color(0xFF2E9E4F)
internal val IncorrectRed = Color(0xFFD1434B)
internal val FlagAmber = Color(0xFFE0A34E)

@Composable
private fun Badge(
    text: String,
    emphasized: Boolean = false,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor =
            if (emphasized) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * One multiple-choice question. When [revealed] is true the correct answer, the
 * verdict and the explanation + source are shown (practice feedback / post-test
 * review); otherwise only selection/elimination affordances are shown.
 */
@Composable
fun QuestionCard(
    question: PracticeQuestion,
    number: Int,
    selected: String,
    eliminated: List<String>,
    flagged: Boolean,
    revealed: Boolean,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onEliminate: (String) -> Unit,
    onToggleFlag: () -> Unit,
    modifier: Modifier = Modifier,
    allowEliminate: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Question $number",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Badge(sectionShort(question.section), emphasized = true)
                val difficulty = difficultyLabel(question.difficulty)
                if (difficulty.isNotEmpty()) {
                    Badge(difficulty)
                }
                Surface(
                    color =
                        if (flagged) FlagAmber.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (flagged) FlagAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.clickable(onClick = onToggleFlag),
                ) {
                    Text(
                        text = if (flagged) "\u2691 Flagged" else "\u2690 Flag",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }

        Text(text = question.stem, style = MaterialTheme.typography.bodyLarge)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (choice in question.choices) {
                ChoiceRow(
                    choice = choice,
                    correctAnswer = question.correctAnswer,
                    selected = selected,
                    struck = eliminated.contains(choice.label),
                    revealed = revealed,
                    enabled = enabled,
                    allowEliminate = allowEliminate,
                    onSelect = { onSelect(choice.label) },
                    onEliminate = { onEliminate(choice.label) },
                )
            }
        }

        if (revealed) {
            HorizontalDivider()
            val correct = selected == question.correctAnswer
            val verdict =
                when {
                    correct -> "Correct"
                    selected.isNotEmpty() -> "Incorrect — correct answer is ${question.correctAnswer}"
                    else -> "Not answered — correct answer is ${question.correctAnswer}"
                }
            Text(
                text = verdict,
                color = if (correct) CorrectGreen else IncorrectRed,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(text = question.explanation, style = MaterialTheme.typography.bodyMedium)
            if (question.sourceName.isNotEmpty()) {
                val license = if (question.sourceLicense.isNotEmpty()) " — ${question.sourceLicense}" else ""
                Text(
                    text = "Source: ${question.sourceName}$license",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    choice: AnswerChoice,
    correctAnswer: String,
    selected: String,
    struck: Boolean,
    revealed: Boolean,
    enabled: Boolean,
    allowEliminate: Boolean,
    onSelect: () -> Unit,
    onEliminate: () -> Unit,
) {
    val isSelected = choice.label == selected
    val isCorrect = choice.label == correctAnswer
    val border =
        when {
            revealed && isCorrect -> CorrectGreen
            revealed && isSelected && !isCorrect -> IncorrectRed
            !revealed && isSelected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outlineVariant
        }
    val background =
        when {
            revealed && isCorrect -> CorrectGreen.copy(alpha = 0.16f)
            revealed && isSelected && !isCorrect -> IncorrectRed.copy(alpha = 0.16f)
            !revealed && isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            else -> MaterialTheme.colorScheme.surface
        }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = background,
            shape = RoundedCornerShape(8.dp),
            modifier =
                Modifier
                    .weight(1f)
                    .border(1.dp, border, RoundedCornerShape(8.dp))
                    .then(if (enabled) Modifier.clickable(onClick = onSelect) else Modifier),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = choice.label,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = choice.text,
                    style = MaterialTheme.typography.bodyMedium,
                    textDecoration = if (struck) TextDecoration.LineThrough else null,
                    color =
                        if (struck) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    modifier = Modifier.weight(1f),
                )
                if (revealed && isCorrect) {
                    Text("\u2713", fontWeight = FontWeight.Bold)
                } else if (revealed && isSelected) {
                    Text("\u2717", fontWeight = FontWeight.Bold)
                }
            }
        }
        if (allowEliminate && !revealed && enabled) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor =
                    if (struck) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.clickable(onClick = onEliminate),
            ) {
                Text(
                    text = if (struck) "undo" else "\u2715",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/**
 * The reading passage for a CARS / full-length passage set, shown above its
 * questions on the narrow mobile layout.
 */
@Composable
fun PassageCard(
    passageSet: CarsPassageSet?,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        // No inner scroll: the passage flows inline in the page's own scroll on
        // the narrow mobile layout (a nested same-axis scroll would crash).
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val passage = passageSet?.passage
            when {
                passage != null -> {
                    if (passage.title.isNotEmpty()) {
                        Text(
                            text = passage.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(text = passage.passage, style = MaterialTheme.typography.bodyMedium)
                }

                loading -> Text("Loading passage…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Text("Passage unavailable.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A horizontally-scrolling strip of question-number chips with status colors. */
@Composable
fun NavStrip(
    count: Int,
    currentIndex: Int,
    statusColor: (Int) -> Color?,
    flagged: (Int) -> Boolean,
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (i in 0 until count) {
            val status = statusColor(i)
            val chipBorder =
                when {
                    i == currentIndex -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                    flagged(i) -> BorderStroke(2.dp, FlagAmber)
                    else -> null
                }
            Surface(
                color = status?.copy(alpha = 0.2f) ?: MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(6.dp),
                border = chipBorder,
                modifier = Modifier.clickable { onJump(i) },
            ) {
                Text(
                    text = (i + 1).toString(),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}
