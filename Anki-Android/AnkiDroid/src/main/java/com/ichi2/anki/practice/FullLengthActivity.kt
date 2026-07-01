// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors
//
// The Full-Length Tests screen: pick an AAMC-style exam, run each section under
// an enforced countdown with the scheduled MCAT breaks between sections, and see
// a per-section report. Before a test starts, a prominent note strongly
// recommends taking full-lengths in the SpeedyCAT desktop app for a viewing
// experience that better mirrors the real exam. Mirrors the desktop full-length
// UX (`anki/ts/routes/full-length`) natively in Compose.

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.unit.dp
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.R
import com.ichi2.compose.theme.AnkiDroidTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class FullLengthActivity : AnkiActivity() {
    private val viewModel: FullLengthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AnkiDroidTheme {
                FullLengthScreen(viewModel, onClose = { finish() })
            }
        }
    }
}

@Composable
private fun FullLengthScreen(
    vm: FullLengthViewModel,
    onClose: () -> Unit,
) {
    var test by remember { mutableStateOf<FullLengthTest?>(null) }
    var attemptId by remember { mutableStateOf("") }
    var sectionIndex by remember { mutableStateOf(0) }
    var currentBreak by remember { mutableStateOf<FullLengthBreak?>(null) }
    var report by remember { mutableStateOf<FullLengthReport?>(null) }
    val scope = rememberCoroutineScope()

    val activeTest = test
    val sections = remember(activeTest) { activeTest?.sections?.sortedBy { it.order } ?: emptyList() }
    val breaksByAfter = remember(activeTest) { activeTest?.breaks?.associateBy { it.afterSection } ?: emptyMap() }

    fun reset() {
        test = null
        attemptId = ""
        sectionIndex = 0
        currentBreak = null
        report = null
    }

    fun submit() {
        val t = activeTest ?: return
        scope.launch { report = vm.report(attemptId, t.testId) }
    }

    fun advance() {
        val next = sectionIndex + 1
        if (next < sections.size) {
            sectionIndex = next
        } else {
            sectionIndex = next
            submit()
        }
    }

    fun onSectionDone() {
        val order = sections.getOrNull(sectionIndex)?.order ?: 0
        val scheduled = breaksByAfter[order]
        if (scheduled != null && sectionIndex + 1 < sections.size) {
            currentBreak = scheduled
        } else {
            advance()
        }
    }

    fun startTest(testId: String) {
        val t = vm.getTest(testId) ?: return
        test = t
        attemptId = vm.newAttemptId()
        sectionIndex = 0
        currentBreak = null
        report = null
        if (t.sections.isEmpty()) submit()
    }

    val currentReport = report
    val activeBreak = currentBreak
    val runningSection = sections.getOrNull(sectionIndex)
    when {
        currentReport != null ->
            ReportScreen(report = currentReport, title = activeTest?.title ?: "", onDone = ::reset, onClose = onClose)

        activeBreak != null ->
            BreakScreen(
                breakInfo = activeBreak,
                nextSectionLabel = sections.getOrNull(sectionIndex + 1)?.let { sectionLong(it.section) } ?: "",
                onDone = {
                    currentBreak = null
                    advance()
                },
            )

        activeTest != null && runningSection != null ->
            key(sectionIndex) {
                ExamSectionRunner(
                    vm = vm,
                    attemptId = attemptId,
                    testId = activeTest.testId,
                    section = runningSection,
                    sectionOrder = sectionIndex + 1,
                    sectionTotal = sections.size,
                    onDone = ::onSectionDone,
                    onClose = onClose,
                )
            }

        // A test is running but no section remains and the report hasn't loaded
        // yet: we are submitting. Avoid briefly flashing the picker.
        activeTest != null -> CenteredMessage("Scoring…")

        else ->
            PickerScreen(vm = vm, onClose = onClose, onStart = ::startTest)
    }
}

@Composable
private fun FlTopBar(
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

// ---- Picker + desktop-recommendation note ---------------------------------

@Composable
private fun PickerScreen(
    vm: FullLengthViewModel,
    onClose: () -> Unit,
    onStart: (String) -> Unit,
) {
    var pending by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = { FlTopBar("Full-Length Tests", onClose) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                !vm.contentReady && !vm.loadFailed -> CenteredMessage("Loading tests…")
                vm.loadFailed ->
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("No full-length tests are loaded yet.")
                        Button(onClick = { vm.loadContent() }) { Text("Retry") }
                    }

                else -> {
                    val tests = remember(vm.contentReady) { vm.listTests() }
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "AAMC-style four-section practice exams with enforced section timers and " +
                                "scheduled breaks. Once a section's timer ends you can't return to it — just like the real MCAT.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        DesktopRecommendationBanner()
                        if (tests.isEmpty()) {
                            Text("No full-length tests are available.")
                        } else {
                            for (t in tests) {
                                TestCard(t, onStart = { pending = t.testId })
                            }
                        }
                        Text(
                            "These proof-of-concept forms mirror the AAMC full-length structure but are not official AAMC " +
                                "content and may not match real MCAT difficulty or formatting.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    val pendingTestId = pending
    if (pendingTestId != null) {
        DesktopRecommendationDialog(
            onConfirm = {
                pending = null
                onStart(pendingTestId)
            },
            onDismiss = { pending = null },
        )
    }
}

@Composable
private fun DesktopRecommendationBanner() {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Best on desktop", fontWeight = FontWeight.SemiBold)
            Text(
                "Full-length exams are best taken in the SpeedyCAT desktop app, whose layout more accurately " +
                    "reflects the real exam environment.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DesktopRecommendationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Take full-lengths on desktop") },
        text = {
            Text(
                "For the most realistic experience we strongly recommend taking full-length tests in the " +
                    "SpeedyCAT desktop app. The larger screen shows the passage and questions side by side and more " +
                    "accurately reflects the real MCAT test-day environment.\n\n" +
                    "You can continue on mobile, but the viewing experience will be more cramped.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Continue on mobile") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TestCard(
    test: FullLengthTestSummary,
    onStart: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(test.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                val breaks =
                    if (test.totalBreakSeconds > 0) " · ${formatDurationLong(test.totalBreakSeconds)} breaks" else ""
                Text(
                    "${test.totalQuestions} questions · ${formatDurationLong(test.totalTestingSeconds)} testing$breaks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onStart) { Text("Start test") }
        }
    }
}

// ---- Section runner -------------------------------------------------------

@Composable
private fun ExamSectionRunner(
    vm: FullLengthViewModel,
    attemptId: String,
    testId: String,
    section: FullLengthSection,
    sectionOrder: Int,
    sectionTotal: Int,
    onDone: () -> Unit,
    onClose: () -> Unit,
) {
    var ordered by remember { mutableStateOf<List<PracticeQuestion>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var index by remember { mutableStateOf(0) }
    val selected = remember { mutableStateMapOf<String, String>() }
    val flagged = remember { mutableStateMapOf<String, Boolean>() }
    val eliminated = remember { mutableStateMapOf<String, List<String>>() }
    val timeSpent = remember { mutableMapOf<String, Int>() }
    val passageCache = remember { mutableStateMapOf<String, CarsPassageSet>() }
    var remaining by remember { mutableStateOf(section.durationSeconds) }
    var secondsOnCurrent by remember { mutableStateOf(0) }
    var ending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        ordered = vm.sectionQuestions(testId, section.section)
        loading = false
    }

    val current = ordered.getOrNull(index)

    fun flushTime() {
        val q = ordered.getOrNull(index)
        if (q != null && secondsOnCurrent > 0) {
            timeSpent[q.id] = (timeSpent[q.id] ?: 0) + secondsOnCurrent
        }
        secondsOnCurrent = 0
    }

    suspend fun endSection() {
        if (ending) return
        ending = true
        flushTime()
        for (q in ordered) {
            val answer = selected[q.id] ?: ""
            val correct = answer.isNotEmpty() && answer == q.correctAnswer
            vm.recordAnswer(attemptId, q, answer, correct, timeSpent[q.id] ?: 0)
        }
        onDone()
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            remaining -= 1
            secondsOnCurrent += 1
            if (remaining <= 0) {
                endSection()
                break
            }
        }
    }
    LaunchedEffect(current?.passageId) {
        val pid = current?.passageId
        if (pid != null && !passageCache.containsKey(pid)) {
            passageCache[pid] = vm.passageSet(pid)
        }
    }

    fun goto(newIndex: Int) {
        if (newIndex < 0 || newIndex >= ordered.size || newIndex == index) return
        flushTime()
        index = newIndex
    }

    val answeredCount = ordered.count { !selected[it.id].isNullOrEmpty() }
    // Read the theme color here (composable context); the NavStrip status lambda
    // is a plain lambda and can't read MaterialTheme.
    val answeredColor = MaterialTheme.colorScheme.primary

    Scaffold(
        topBar = {
            Column {
                FlTopBar("Full-Length Tests", onClose)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Section $sectionOrder of $sectionTotal",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(sectionLong(section.section), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        formatClock(remaining),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (remaining <= 300) MaterialTheme.colorScheme.error else Color.Unspecified,
                    )
                    OutlinedButton(enabled = !ending, onClick = { scope.launch { endSection() } }) { Text("End section") }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                loading -> CenteredMessage("Loading section…")
                ordered.isEmpty() ->
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("No questions found for this section.")
                        Button(onClick = { scope.launch { endSection() } }) { Text("Continue") }
                    }

                else ->
                    Column(Modifier.fillMaxSize()) {
                        NavStrip(
                            count = ordered.size,
                            currentIndex = index,
                            statusColor = { if (!selected[ordered[it].id].isNullOrEmpty()) answeredColor else null },
                            flagged = { flagged[ordered[it].id] == true },
                            onJump = { goto(it) },
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
                                QuestionCard(
                                    question = current,
                                    number = index + 1,
                                    selected = selected[current.id] ?: "",
                                    eliminated = eliminated[current.id] ?: emptyList(),
                                    flagged = flagged[current.id] == true,
                                    revealed = false,
                                    enabled = true,
                                    onSelect = { selected[current.id] = it },
                                    onEliminate = { label ->
                                        val list = eliminated[current.id] ?: emptyList()
                                        eliminated[current.id] = if (list.contains(label)) list - label else list + label
                                    },
                                    onToggleFlag = { flagged[current.id] = !(flagged[current.id] ?: false) },
                                )
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
                                "$answeredCount / ${ordered.size} answered",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(enabled = index < ordered.size - 1, onClick = { goto(index + 1) }) { Text("Next") }
                        }
                    }
            }
        }
    }
}

// ---- Break ----------------------------------------------------------------

@Composable
private fun BreakScreen(
    breakInfo: FullLengthBreak,
    nextSectionLabel: String,
    onDone: () -> Unit,
) {
    var remaining by remember { mutableStateOf(breakInfo.durationSeconds) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            remaining -= 1
            if (remaining <= 0) {
                onDone()
                break
            }
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(
                Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    breakInfo.label.ifEmpty { "Break" },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(formatClock(remaining), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Text(
                    if (breakInfo.optional) {
                        "This break is optional — you can skip it when you're ready."
                    } else {
                        "Your next section will begin automatically when the break ends."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (nextSectionLabel.isNotEmpty()) {
                    Text("Up next: $nextSectionLabel", fontWeight = FontWeight.SemiBold)
                }
                Button(onClick = onDone) {
                    Text(if (breakInfo.optional) "Skip break & continue" else "End break early")
                }
            }
        }
    }
}

// ---- Report ---------------------------------------------------------------

@Composable
private fun ReportScreen(
    report: FullLengthReport,
    title: String,
    onDone: () -> Unit,
    onClose: () -> Unit,
) {
    Scaffold(topBar = { FlTopBar("Results", onClose) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (title.isNotEmpty()) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            val pct = if (report.totalQuestions > 0) (report.totalCorrect * 100 / report.totalQuestions) else 0
            Text(
                "${report.totalCorrect} / ${report.totalQuestions} correct ($pct%)",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Scaled section scores (118–132) require licensed AAMC scoring data and are not available for these " +
                    "proof-of-concept forms.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            for (r in report.sectionResults) {
                val sp = if (r.total > 0) (r.correct * 100 / r.total) else 0
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(sectionLong(r.section), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text("${r.correct} / ${r.total}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("$sp%", fontWeight = FontWeight.SemiBold)
                }
            }
            Button(onClick = onDone) { Text("Back to tests") }
        }
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
