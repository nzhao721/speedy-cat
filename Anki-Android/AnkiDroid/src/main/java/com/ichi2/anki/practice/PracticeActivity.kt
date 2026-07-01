// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors
//
// The Practice Question Bank screen: a UWorld-like MCAT multiple-choice bank
// with multi-select section/topic filters, a free-text question count, an
// optional timer, immediate post-submit explanations, CARS passage sets served
// whole, and a per-topic session summary. Mirrors the finalized desktop practice
// UX (`anki/ts/routes/practice`) natively in Compose.

package com.ichi2.anki.practice

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.R
import com.ichi2.compose.theme.AnkiDroidTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class PracticeActivity : AnkiActivity() {
    private val viewModel: PracticeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AnkiDroidTheme {
                PracticeScreen(viewModel, onClose = { finish() })
            }
        }
    }
}

private data class PracticeSession(
    val sessionId: String,
    val questions: List<PracticeQuestion>,
    val timeLimitSeconds: Int,
)

@Composable
private fun PracticeScreen(
    vm: PracticeViewModel,
    onClose: () -> Unit,
) {
    var session by remember { mutableStateOf<PracticeSession?>(null) }
    var summary by remember { mutableStateOf<PracticeSessionSummary?>(null) }

    val currentSummary = summary
    val currentSession = session
    when {
        currentSummary != null ->
            SummaryScreen(
                vm = vm,
                summary = currentSummary,
                onNewSession = {
                    summary = null
                    session = null
                },
                onClose = onClose,
            )

        currentSession != null ->
            RunnerScreen(
                vm = vm,
                session = currentSession,
                onFinished = { summary = it },
                onClose = onClose,
            )

        else ->
            SetupScreen(
                vm = vm,
                onClose = onClose,
                onStart = { session = it },
            )
    }
}

@Composable
private fun TopBar(
    title: String,
    onBack: () -> Unit,
) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_baseline_arrow_back_24), contentDescription = "Back")
            }
        },
    )
}

// ---- Setup ----------------------------------------------------------------

@Composable
private fun SetupScreen(
    vm: PracticeViewModel,
    onClose: () -> Unit,
    onStart: (PracticeSession) -> Unit,
) {
    Scaffold(topBar = { TopBar("Practice Questions", onClose) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                !vm.contentReady && !vm.loadFailed ->
                    CenterText("Loading question bank…")

                vm.loadFailed ->
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("No practice questions are loaded yet.")
                        Button(onClick = { vm.loadContent() }) { Text("Retry") }
                    }

                else -> SetupForm(vm, onStart)
            }
        }
    }
}

@Composable
private fun SetupForm(
    vm: PracticeViewModel,
    onStart: (PracticeSession) -> Unit,
) {
    val bank = remember { vm.freeStandingQuestions() }
    val scope = rememberCoroutineScope()

    val selectedSections = remember { mutableStateMapOf<McatSection, Boolean>() }
    val selectedTopics = remember { mutableStateMapOf<String, Boolean>() }
    var missedOnly by remember { mutableStateOf(false) }
    var countInput by remember { mutableStateOf("20") }
    var untimed by remember { mutableStateOf(true) }
    var timerInput by remember { mutableStateOf("20") }
    var noMatch by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }

    val chosenSections = McatSection.TEST_ORDER.filter { selectedSections[it] == true }
    val topicOptions =
        remember(bank, chosenSections) {
            bank
                .filter { chosenSections.isEmpty() || it.section in chosenSections }
                .flatMap { it.topicTags }
                .distinct()
                .sorted()
        }
    // Drop chosen topics that no longer apply to the section selection.
    val chosenTopics = topicOptions.filter { selectedTopics[it] == true }

    val availableForFilter =
        bank.count { q ->
            (chosenSections.isEmpty() || q.section in chosenSections) &&
                (chosenTopics.isEmpty() || q.topicTags.any { it in chosenTopics })
        }
    val countParse = parseQuestionCount(countInput, availableForFilter)
    val timerParse = parseTimerMinutes(timerInput)
    val canStart = !starting && bank.isNotEmpty() && countParse.valid && (untimed || timerParse.valid)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "MCAT-style multiple-choice practice — discrete questions and CARS passage sets. " +
                "The explanation is shown after you submit.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in McatSection.TEST_ORDER) {
                val n = bank.count { it.section == s }
                CountPill(count = n, label = s.longLabel)
            }
        }

        FieldLabel("Section")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in McatSection.TEST_ORDER) {
                FilterChip(
                    selected = selectedSections[s] == true,
                    onClick = { selectedSections[s] = !(selectedSections[s] ?: false) },
                    label = { Text(s.shortLabel) },
                )
            }
        }

        FieldLabel("Topic (empty = all)")
        if (topicOptions.isEmpty()) {
            Text("No topics for this selection.", style = MaterialTheme.typography.bodySmall)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (t in topicOptions) {
                    FilterChip(
                        selected = selectedTopics[t] == true,
                        onClick = { selectedTopics[t] = !(selectedTopics[t] ?: false) },
                        label = { Text(t) },
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FieldLabel("Number of questions")
            OutlinedTextField(
                value = countInput,
                onValueChange = { countInput = it },
                singleLine = true,
                isError = !countParse.valid,
                // Plain text (not Number) so "max"/"all" can be typed, mirroring desktop.
                modifier = Modifier.fillMaxWidth(),
            )
            if (!countParse.valid) {
                HintText("Enter a positive number, or leave blank / \u201Cmax\u201D for all.", error = true)
            } else {
                HintText("Blank or \u201Cmax\u201D = all available ($availableForFilter).")
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FieldLabel("Timer")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = untimed, onCheckedChange = { untimed = it })
                Text("Untimed")
                OutlinedTextField(
                    value = timerInput,
                    onValueChange = { timerInput = it },
                    singleLine = true,
                    enabled = !untimed,
                    isError = !untimed && !timerParse.valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.widthIn(min = 96.dp),
                )
                Text("min", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!untimed && !timerParse.valid) {
                HintText("Enter a positive number of minutes.", error = true)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = missedOnly, onCheckedChange = { missedOnly = it })
            Text("Only questions I previously missed")
        }

        if (noMatch) {
            Text(
                "No questions match those filters. Try widening them.",
                color = MaterialTheme.colorScheme.error,
            )
        }

        Button(
            enabled = canStart,
            onClick = {
                starting = true
                noMatch = false
                scope.launch {
                    val filter =
                        QuestionFilter(
                            sections = chosenSections,
                            topics = chosenTopics,
                            missedOnly = missedOnly,
                            includeFullLength = false,
                            limit = countParse.limit,
                        )
                    val qs = vm.query(filter)
                    if (qs.isEmpty()) {
                        noMatch = true
                    } else {
                        onStart(
                            PracticeSession(
                                sessionId = vm.newSessionId(),
                                questions = qs,
                                timeLimitSeconds = if (untimed) 0 else timerParse.seconds,
                            ),
                        )
                    }
                    starting = false
                }
            },
        ) {
            Text(if (starting) "Starting…" else "Start practice")
        }
    }
}

// ---- Runner ---------------------------------------------------------------

@Composable
private fun RunnerScreen(
    vm: PracticeViewModel,
    session: PracticeSession,
    onFinished: (PracticeSessionSummary) -> Unit,
    onClose: () -> Unit,
) {
    val runItems = remember(session) { groupIntoRunItems(session.questions) }
    val allQuestions = remember(runItems) { runItems.flatMap { it.questions } }
    val itemOfQuestion =
        remember(runItems) {
            buildMap {
                runItems.forEachIndexed { i, item -> item.questions.forEach { put(it.id, i) } }
            }
        }

    var index by remember { mutableStateOf(0) }
    val selected = remember { mutableStateMapOf<String, String>() }
    val submitted = remember { mutableStateMapOf<String, Boolean>() }
    val eliminated = remember { mutableStateMapOf<String, List<String>>() }
    val flagged = remember { mutableStateMapOf<String, Boolean>() }
    val timeSpent = remember { mutableMapOf<String, Double>() }
    val passageCache = remember { mutableStateMapOf<String, CarsPassageSet>() }
    var elapsed by remember { mutableStateOf(0) }
    var finishing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Track time on the current item and split it across its unsubmitted
    // questions on flush (a shared passage is read for all of them).
    var secondsOnItem by remember { mutableStateOf(0) }

    fun flushItemTime() {
        val item = runItems.getOrNull(index)
        if (item != null && secondsOnItem > 0) {
            val pending = item.questions.filter { submitted[it.id] != true }
            val targets = pending.ifEmpty { item.questions }
            val per = secondsOnItem.toDouble() / targets.size
            for (q in targets) timeSpent[q.id] = (timeSpent[q.id] ?: 0.0) + per
        }
        secondsOnItem = 0
    }

    suspend fun doFinish() {
        if (finishing) return
        finishing = true
        flushItemTime()
        for (q in allQuestions) {
            if (submitted[q.id] != true) {
                submitted[q.id] = true
                vm.record(session.sessionId, q, "", false, (timeSpent[q.id] ?: 0.0).roundToInt())
            }
        }
        onFinished(vm.summarizeSession(session.sessionId))
    }

    val current = runItems.getOrNull(index)

    // Countdown / elapsed ticker; auto-finishes a timed session at zero.
    androidx.compose.runtime.LaunchedEffect(session) {
        while (true) {
            delay(1000)
            elapsed += 1
            secondsOnItem += 1
            if (session.timeLimitSeconds > 0 && elapsed >= session.timeLimitSeconds) {
                doFinish()
                break
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(current?.passageId) {
        val pid = current?.passageId
        if (pid != null && !passageCache.containsKey(pid)) {
            passageCache[pid] = vm.passageSet(pid)
        }
    }

    fun goto(newIndex: Int) {
        if (newIndex < 0 || newIndex >= runItems.size || newIndex == index) return
        flushItemTime()
        index = newIndex
    }

    val answeredCount = allQuestions.count { submitted[it.id] == true }
    val remaining = if (session.timeLimitSeconds > 0) maxOf(0, session.timeLimitSeconds - elapsed) else 0

    Scaffold(
        topBar = {
            Column {
                TopBar("Practice Questions", onClose)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "$answeredCount / ${allQuestions.size} answered",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (session.timeLimitSeconds > 0) formatClock(remaining) else formatClock(elapsed),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (session.timeLimitSeconds > 0 && remaining <= 60) MaterialTheme.colorScheme.error else Color.Unspecified,
                    )
                    OutlinedButton(enabled = !finishing, onClick = { scope.launch { doFinish() } }) {
                        Text("Finish")
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            NavStrip(
                count = allQuestions.size,
                currentIndex = allQuestions.indexOfFirst { itemOfQuestion[it.id] == index },
                statusColor = { i ->
                    val q = allQuestions[i]
                    when {
                        submitted[q.id] != true -> null
                        selected[q.id].isNullOrEmpty() -> Color.Gray
                        selected[q.id] == q.correctAnswer -> CorrectGreen
                        else -> IncorrectRed
                    }
                },
                flagged = { flagged[allQuestions[it].id] == true },
                onJump = { i -> goto(itemOfQuestion[allQuestions[i].id] ?: 0) },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            HorizontalDivider()

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (current != null) {
                    if (current.passageId != null) {
                        PassageCard(
                            passageSet = passageCache[current.passageId],
                            loading = !passageCache.containsKey(current.passageId),
                        )
                    }
                    current.questions.forEachIndexed { qi, q ->
                        val number = allQuestions.indexOfFirst { it.id == q.id } + 1
                        QuestionCard(
                            question = q,
                            number = number,
                            selected = selected[q.id] ?: "",
                            eliminated = eliminated[q.id] ?: emptyList(),
                            flagged = flagged[q.id] == true,
                            revealed = submitted[q.id] == true,
                            enabled = submitted[q.id] != true,
                            onSelect = { if (submitted[q.id] != true) selected[q.id] = it },
                            onEliminate = { label ->
                                val list = eliminated[q.id] ?: emptyList()
                                eliminated[q.id] = if (list.contains(label)) list - label else list + label
                            },
                            onToggleFlag = { flagged[q.id] = !(flagged[q.id] ?: false) },
                        )
                        if (submitted[q.id] != true) {
                            Button(
                                enabled = !selected[q.id].isNullOrEmpty(),
                                onClick = {
                                    scope.launch {
                                        flushItemTime()
                                        val answer = selected[q.id] ?: ""
                                        val correct = answer.isNotEmpty() && answer == q.correctAnswer
                                        submitted[q.id] = true
                                        vm.record(
                                            session.sessionId,
                                            q,
                                            answer,
                                            correct,
                                            (timeSpent[q.id] ?: 0.0).roundToInt(),
                                        )
                                    }
                                },
                            ) {
                                Text("Submit")
                            }
                        }
                        if (qi < current.questions.size - 1) {
                            HorizontalDivider()
                        }
                    }
                }
            }

            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(enabled = index > 0, onClick = { goto(index - 1) }) { Text("Previous") }
                Text(
                    "Item ${index + 1} / ${runItems.size}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(enabled = index < runItems.size - 1, onClick = { goto(index + 1) }) { Text("Next") }
            }
        }
    }
}

// ---- Summary --------------------------------------------------------------

@Composable
private fun SummaryScreen(
    vm: PracticeViewModel,
    summary: PracticeSessionSummary,
    onNewSession: () -> Unit,
    onClose: () -> Unit,
) {
    var topics by remember { mutableStateOf<List<TopicStat>?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        topics = vm.practiceTopicStats()
    }
    val accuracy = if (summary.total > 0) (summary.correct * 100 / summary.total) else 0

    Scaffold(topBar = { TopBar("Session complete", onClose) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("$accuracy%", "Accuracy")
                StatCard("${summary.correct}", "Correct", CorrectGreen)
                StatCard("${summary.incorrect}", "Incorrect", IncorrectRed)
                StatCard("${summary.unanswered}", "Unanswered")
                StatCard(formatDurationLong(summary.totalTimeSeconds), "Total time")
            }

            if (summary.sectionBreakdown.isNotEmpty()) {
                SectionHeader("By section")
                for (sc in summary.sectionBreakdown) {
                    val pct = if (sc.total > 0) (sc.correct * 100 / sc.total) else 0
                    TableRow(sectionShort(sc.section), "${sc.correct} / ${sc.total}", "$pct%")
                }
            }

            SectionHeader("By topic")
            Text(
                "Weakest topics first — based on your recorded practice-session attempts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val topicList = topics
            when {
                topicList == null -> Text("Loading topic stats…", style = MaterialTheme.typography.bodySmall)
                topicList.isEmpty() -> Text("No per-topic data yet.", style = MaterialTheme.typography.bodySmall)
                else ->
                    for (t in topicList) {
                        TableRow(
                            t.topic,
                            "${sectionShort(t.section)} · ${t.correct}/${t.attempts}",
                            "${(t.accuracy * 100).roundToInt()}%",
                        )
                    }
            }

            Button(onClick = onNewSession) { Text("New session") }
        }
    }
}

// ---- Small shared building blocks -----------------------------------------

@Composable
private fun CenterText(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun HintText(
    text: String,
    error: Boolean = false,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CountPill(
    count: Int,
    label: String,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$count", fontWeight = FontWeight.Bold)
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatCard(
    value: String,
    label: String,
    valueColor: Color = Color.Unspecified,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.widthIn(min = 96.dp),
    ) {
        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = valueColor)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun TableRow(
    a: String,
    b: String,
    c: String,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(a, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(b, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(c, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
