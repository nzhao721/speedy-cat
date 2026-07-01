// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors
//
// ViewModel for the Practice Question Bank screen. It owns the one-time content
// load and thin, IO-dispatched wrappers over the repository so the composables
// stay free of disk/DB work. Mirrors the desktop practice page's data flow.

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

class PracticeViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val repo = PracticeRepository.getInstance(app)

    /** True once the bundled content has finished loading. */
    var contentReady by mutableStateOf(false)
        private set

    /** True if content loading failed (offer a retry). */
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

    /** Free-standing bank used to build the setup filters + counts. */
    fun freeStandingQuestions(): List<PracticeQuestion> = repo.freeStandingQuestions()

    suspend fun query(filter: QuestionFilter): List<PracticeQuestion> = withContext(Dispatchers.IO) { repo.getPracticeQuestions(filter) }

    suspend fun passageSet(passageId: String): CarsPassageSet = withContext(Dispatchers.IO) { repo.getCarsPassageSet(passageId) }

    fun newSessionId(): String = "ps-" + UUID.randomUUID().toString()

    /** A freshly-assembled practice session: its randomized questions + the id that seeded them. */
    data class SessionQuestions(
        val sessionId: String,
        val questions: List<PracticeQuestion>,
    )

    /**
     * Start a session: mint a fresh session id and use it to seed the per-session
     * selection + answer-choice shuffles, so repeat sessions differ. Mirrors the
     * desktop `start_practice_session`.
     */
    suspend fun startSession(filter: QuestionFilter): SessionQuestions =
        withContext(Dispatchers.IO) {
            val sessionId = newSessionId()
            SessionQuestions(sessionId, repo.getPracticeSessionQuestions(filter, sessionId))
        }

    suspend fun record(
        sessionId: String,
        question: PracticeQuestion,
        selectedAnswer: String,
        correct: Boolean,
        timeSeconds: Int,
    ) = withContext(Dispatchers.IO) {
        repo.recordAttempt(
            Attempt(
                id = "$sessionId:${question.id}",
                sessionId = sessionId,
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

    suspend fun summarizeSession(sessionId: String): PracticeSessionSummary =
        withContext(Dispatchers.IO) {
            summarizePracticeSession(repo.allAttempts().filter { it.sessionId == sessionId })
        }

    /** Cumulative per-topic stats across all practice sessions (weakest first). */
    suspend fun practiceTopicStats(): List<TopicStat> =
        withContext(Dispatchers.IO) {
            topicStats(repo.allAttempts(), AttemptSource.PRACTICE_SESSION)
                .sortedBy { it.accuracy }
        }
}
