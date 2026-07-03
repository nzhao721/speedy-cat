// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors
//
// Shared Compose UI for the MCAT Practice Question Bank: the multiple-choice
// question card (with immediate post-submit feedback) and the reading passage
// panel. Presentational only — all flow control lives in the callers, mirroring
// the desktop `QuestionView.svelte` / `PassagePanel.svelte`.

package com.ichi2.anki.practice

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

/**
 * A radio-style indicator: a hollow ring that fills with [fillColor] (plus a
 * white centre dot) when the choice is active — accent when picked pre-submit,
 * green/red once the answer is revealed. Mirrors the desktop `.radio` affordance.
 */
@Composable
private fun RadioDot(
    fillColor: Color?,
    outlineColor: Color,
) {
    Box(
        modifier =
            Modifier
                .size(18.dp)
                .border(2.dp, fillColor ?: outlineColor, CircleShape)
                .background(fillColor ?: Color.Transparent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (fillColor != null) {
            Box(Modifier.size(7.dp).background(Color.White, CircleShape))
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
    // The pre-submit "picked" state (accent) is distinct from the post-submit
    // correct/incorrect colouring, and is replaced by it once revealed.
    val pickedPreSubmit = !revealed && isSelected
    // Non-null => an "active" (filled) state; drives the ring, tint and radio.
    val activeColor =
        when {
            revealed && isCorrect -> CorrectGreen
            revealed && isSelected && !isCorrect -> IncorrectRed
            pickedPreSubmit -> MaterialTheme.colorScheme.primary
            else -> null
        }
    val border = activeColor ?: MaterialTheme.colorScheme.outlineVariant
    val background = activeColor?.copy(alpha = if (pickedPreSubmit) 0.18f else 0.16f) ?: MaterialTheme.colorScheme.surface
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
                    // 2dp accent ring pre-submit (mirrors the desktop inset ring).
                    .border(if (pickedPreSubmit) 2.dp else 1.dp, border, RoundedCornerShape(8.dp))
                    .then(if (enabled) Modifier.clickable(onClick = onSelect) else Modifier),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Radio affordance: hollow circle that fills (accent pre-submit,
                // green/red once revealed) to clearly mark the picked choice.
                RadioDot(fillColor = activeColor, outlineColor = MaterialTheme.colorScheme.outline)
                Text(
                    text = choice.label,
                    fontWeight = FontWeight.Bold,
                    color = if (pickedPreSubmit) MaterialTheme.colorScheme.primary else Color.Unspecified,
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
 * The reading passage for a CARS passage set, shown above its questions on the
 * narrow mobile layout.
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

/**
 * SpeedyCAT graduated hint ladder for one practice question. Each hint is a
 * self-contained 4-choice SUBQUESTION that scaffolds toward the main question
 * without revealing its answer. The learner works through them ONE AT A TIME:
 * they MUST answer the currently-revealed subquestion (no skip) before revealing
 * the next tier or re-answering the main question. Presentational only — the
 * caller (PracticeActivity) owns [progress], the trigger paths and the
 * assisted/hintLevelUsed tracking. Mirrors the desktop `HintLadder.svelte`.
 */
@Composable
fun HintLadderCard(
    hints: List<HintSubquestion>,
    progress: HintProgress,
    pendingChoice: String,
    onRequestHint: () -> Unit,
    onPendingChoiceChange: (String) -> Unit,
    onSubmitHint: (index: Int, label: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (progress.revealed == 0) {
                Text(
                    "Stuck? Work through a guided hint before answering.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onRequestHint) { Text("\uD83D\uDCA1 Request a hint") }
            } else {
                Text(
                    "Guided hints",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                for (i in 0 until minOf(progress.revealed, hints.size)) {
                    val hint = hints[i]
                    val answered = progress.picks[i] != null
                    val isCurrent = i == progress.revealed - 1
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Hint ${i + 1}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            Badge("Level ${hint.level.takeIf { it in 1..3 } ?: (i + 1)}")
                        }
                        Text(hint.prompt, style = MaterialTheme.typography.bodyMedium)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (c in hint.choices) {
                                HintChoiceRow(
                                    choice = c,
                                    correctAnswer = hint.correctAnswer,
                                    picked = progress.picks[i],
                                    pending = if (isCurrent && !answered) pendingChoice else "",
                                    answered = answered,
                                    enabled = isCurrent && !answered,
                                    onSelect = { onPendingChoiceChange(c.label) },
                                )
                            }
                        }
                        if (answered) {
                            val correct = hintAnswerCorrect(hint, progress.picks[i] ?: "")
                            Text(
                                text =
                                    if (correct) {
                                        "Correct"
                                    } else {
                                        "Not quite — the answer to this hint is ${hint.correctAnswer}"
                                    },
                                color = if (correct) CorrectGreen else IncorrectRed,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (hint.rationale.isNotEmpty()) {
                                Text(
                                    hint.rationale,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else if (isCurrent) {
                            Button(
                                enabled = pendingChoice.isNotEmpty(),
                                onClick = { if (pendingChoice.isNotEmpty()) onSubmitHint(i, pendingChoice) },
                            ) {
                                Text("Submit hint answer")
                            }
                            Text(
                                "Answer this hint to continue.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                when {
                    canRevealNextHint(hints, progress) ->
                        OutlinedButton(onClick = onRequestHint) { Text("\uD83D\uDCA1 Reveal next hint") }
                    progress.revealed >= hints.size && pendingHintIndex(progress) == -1 ->
                        Text(
                            "You've worked through all the hints — now answer the question above.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
            }
        }
    }
}

@Composable
private fun HintChoiceRow(
    choice: HintChoice,
    correctAnswer: String,
    picked: String?,
    pending: String,
    answered: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    val isCorrect = choice.label == correctAnswer
    val activeColor =
        when {
            answered && isCorrect -> CorrectGreen
            answered && choice.label == picked && !isCorrect -> IncorrectRed
            !answered && choice.label == pending -> MaterialTheme.colorScheme.primary
            else -> null
        }
    val border = activeColor ?: MaterialTheme.colorScheme.outlineVariant
    val background = activeColor?.copy(alpha = 0.18f) ?: MaterialTheme.colorScheme.surface
    Surface(
        color = background,
        shape = RoundedCornerShape(7.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .border(2.dp, border, RoundedCornerShape(7.dp))
                .then(if (enabled) Modifier.clickable(onClick = onSelect) else Modifier),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(choice.label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            Text(choice.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (answered && isCorrect) {
                Text("\u2713", fontWeight = FontWeight.Bold)
            } else if (answered && choice.label == picked) {
                Text("\u2717", fontWeight = FontWeight.Bold)
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
