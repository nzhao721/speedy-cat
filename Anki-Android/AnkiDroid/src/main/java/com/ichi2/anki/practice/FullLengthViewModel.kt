// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors
//
// ViewModel for the Full-Length Tests screen: content load, test listing,
// section-question fetching, per-answer recording, and the final report. Mirrors
// the desktop full-length data flow (`anki/ts/routes/full-length`).

package com.ichi2.anki.practice

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class FullLengthViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val repo = PracticeRepository.getInstance(app)

    var contentReady by mutableStateOf(false)
        private set

    var loadFailed by mutableStateOf(false)
        private set

    init {
        loadContent()
    }

    fun loadContent() {
        loadFailed = false
        viewModelScope.launch {
            try {
                repo.ensureLoaded()
                contentReady = true
            } catch (e: Exception) {
                loadFailed = true
                contentReady = false
            }
        }
    }

    fun listTests(): List<FullLengthTestSummary> = repo.listFullLengthTests()

    fun getTest(testId: String): FullLengthTest? = repo.getFullLengthTest(testId)

    fun newAttemptId(): String = "fla-" + UUID.randomUUID().toString()

    suspend fun sectionQuestions(
        testId: String,
        section: McatSection?,
    ): List<PracticeQuestion> = withContext(Dispatchers.IO) { repo.fullLengthSectionQuestions(testId, section) }

    suspend fun passageSet(passageId: String): CarsPassageSet = withContext(Dispatchers.IO) { repo.getCarsPassageSet(passageId) }

    suspend fun recordAnswer(
        attemptId: String,
        question: PracticeQuestion,
        selectedAnswer: String,
        correct: Boolean,
        timeSeconds: Int,
    ) = withContext(Dispatchers.IO) {
        repo.recordAttempt(
            Attempt(
                id = "$attemptId:${question.id}",
                sessionId = null,
                fullLengthAttemptId = attemptId,
                questionId = question.id,
                selectedAnswer = selectedAnswer,
                correct = correct,
                timeSeconds = timeSeconds,
                section = question.section,
                topic = primaryTopic(question),
                answeredAt = System.currentTimeMillis() / 1000,
            ),
        )
    }

    suspend fun report(
        attemptId: String,
        testId: String,
    ): FullLengthReport =
        withContext(Dispatchers.IO) {
            fullLengthReport(testId, repo.allAttempts().filter { it.fullLengthAttemptId == attemptId })
        }
}
