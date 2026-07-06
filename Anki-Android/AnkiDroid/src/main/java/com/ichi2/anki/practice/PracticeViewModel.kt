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
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.SpeedyCatAutoSync
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
                // Union in attempts synced from other devices so per-topic stats
                // reflect practice done on desktop too.
                runCatching { repo.ingestResults() }
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
     * Start a session via the Rust `StartPracticeSession` RPC when the local
     * backend is available (same path as desktop). Falls back to the bundled
     * in-memory JSON bank when the RPC or collection question bank is missing.
     */
    suspend fun startSession(
        filter: QuestionFilter,
        timeLimitSeconds: Int = 0,
    ): SessionQuestions =
        withContext(Dispatchers.IO) {
            CollectionManager.withOpenColOrNull {
                SpeedyCatBackendCapabilities.logCapabilities(this)
                PracticeBackend.startPracticeSession(this, filter, timeLimitSeconds)
            }?.takeIf { it.questions.isNotEmpty() }?.let {
                return@withContext SessionQuestions(it.sessionId, it.questions)
            }
            val sessionId = newSessionId()
            SessionQuestions(sessionId, repo.getPracticeSessionQuestions(filter, sessionId))
        }

    /**
     * Engine-recommended topics from the Rust backend, or a structured failure
     * when the RPC / collection prerequisites are missing.
     */
    suspend fun recommendedTopics(): RecommendedTopicsResult =
        withContext(Dispatchers.IO) {
            CollectionManager.withOpenColOrNull {
                SpeedyCatBackendCapabilities.logCapabilities(this)
                PracticeBackend.getRecommendedTopics(this, repo.allAttempts())
            } ?: RecommendedTopicsResult.Unavailable(SpeedyCatBackendIssue.SyncFailed)
        }

    suspend fun record(
        sessionId: String,
        question: PracticeQuestion,
        selectedAnswer: String,
        correct: Boolean,
        timeSeconds: Int,
        hintLevelUsed: Int = 0,
        assisted: Boolean = false,
        mainWrongFirst: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        val level = hintLevelUsed.coerceIn(0, 3)
        val attemptId = "$sessionId:${question.id}"
        val existing = repo.allAttempts()
        val prior = existing.find { it.id == attemptId }
        val firstTry =
            firstTryNoHint(
                questionSeenBefore = existing.any { it.questionId == question.id && it.id != attemptId },
                replacing = prior != null,
                priorFirstTry = prior?.firstTryNoHint,
                hintLevelUsed = level,
                selectedAnswer = selectedAnswer,
                correct = correct,
            )
        repo.recordAttempt(
            Attempt(
                id = attemptId,
                sessionId = sessionId,
                questionId = question.id,
                selectedAnswer = selectedAnswer,
                correct = correct,
                timeSeconds = timeSeconds,
                section = question.section,
                topic = primaryTopic(question),
                answeredAt = System.currentTimeMillis() / 1000,
                hintLevelUsed = level,
                assisted = assisted || level >= 3,
                mainWrongFirst = mainWrongFirst,
                firstTryNoHint = firstTry,
            ),
        )
    }

    suspend fun summarizeSession(sessionId: String): PracticeSessionSummary =
        withContext(Dispatchers.IO) {
            val summary = summarizePracticeSession(repo.allAttempts().filter { it.sessionId == sessionId })
            // Publish this device's attempts so the next media sync carries the
            // just-finished session to other devices. Best-effort.
            runCatching { repo.publishResults() }
            SpeedyCatAutoSync.onPracticeDataChanged(getApplication())
            summary
        }

    /** Cumulative per-topic stats across all practice sessions (weakest first). */
    suspend fun practiceTopicStats(): List<TopicStat> =
        withContext(Dispatchers.IO) {
            topicStats(repo.allAttempts(), AttemptSource.PRACTICE_SESSION)
                .sortedBy { it.accuracy }
        }
}
