// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors
//
// Loads the bundled MCAT content from the APK assets and exposes the query +
// attempt-recording API used by the Practice screen. This is the mobile (Kotlin)
// analogue of the desktop content loader + PracticeService: it reads every JSON
// bundle under `assets/speedycat/practice-questions` (upserting by id, exactly
// like the desktop loader) and keeps the resulting bank in memory, while
// attempts are persisted via [PracticeStore].

package com.ichi2.anki.practice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * In-memory MCAT question bank, loaded once from the bundled assets, plus
 * device-local attempt tracking.
 *
 * All content is shipped inside the APK, so this works fully offline and with AI
 * features off. Use [getInstance] for the app-wide singleton.
 */
class PracticeRepository internal constructor(
    private val appContext: Context,
    private val store: PracticeStore = PracticeStore(appContext),
) {
    private val loadMutex = Mutex()

    @Volatile
    private var loaded = false
    private var allQuestions: List<PracticeQuestion> = emptyList()
    private var passagesById: Map<String, Passage> = emptyMap()

    /** Load every bundled JSON once. Safe to call repeatedly. */
    suspend fun ensureLoaded() {
        if (loaded) return
        loadMutex.withLock {
            if (loaded) return
            withContext(Dispatchers.IO) { load() }
            loaded = true
        }
    }

    private fun load() {
        val questionsById = LinkedHashMap<String, PracticeQuestion>()
        val passages = LinkedHashMap<String, Passage>()

        for (name in listBundle(PRACTICE_DIR)) {
            runCatching {
                val parsed = parseQuestionBundle(readAsset("$PRACTICE_DIR/$name"))
                parsed.questions.forEach { questionsById[it.id] = it }
                parsed.passages.forEach { passages[it.passageId] = it }
            }.onFailure { Timber.w(it, "SpeedyCAT: failed to parse practice bundle %s", name) }
        }

        allQuestions = questionsById.values.toList()
        passagesById = passages
        Timber.i(
            "SpeedyCAT content loaded: %d questions, %d passages",
            allQuestions.size,
            passagesById.size,
        )
    }

    private fun listBundle(dir: String): List<String> =
        (appContext.assets.list(dir) ?: emptyArray())
            .filter { it.endsWith(".json") && !it.startsWith("_") }
            .sorted()

    private fun readAsset(path: String): String = appContext.assets.open(path).bufferedReader().use { it.readText() }

    // ---- Querying content -------------------------------------------------

    /** The full question bank; used by the setup screen to build filters + counts. */
    fun freeStandingQuestions(): List<PracticeQuestion> = allQuestions

    /** Apply a [filter], resolving missed-only against the local attempt log. */
    fun getPracticeQuestions(filter: QuestionFilter): List<PracticeQuestion> {
        val missed = if (filter.missedOnly) store.missedQuestionIds() else emptySet()
        return matchingQuestions(allQuestions, filter, missed)
    }

    /**
     * Assemble the questions for one practice session: the [sessionId] seeds a
     * per-session shuffle of the selection (before the count limit) and of each
     * question's answer choices, so repeat sessions with the same filters draw
     * different questions and choice orders. Mirrors the desktop
     * `start_practice_session`.
     */
    fun getPracticeSessionQuestions(
        filter: QuestionFilter,
        sessionId: String,
    ): List<PracticeQuestion> {
        val missed = if (filter.missedOnly) store.missedQuestionIds() else emptySet()
        return sessionQuestions(allQuestions, filter, missed, sessionId)
    }

    /** A passage together with all of its questions (id-ordered). */
    fun getCarsPassageSet(passageId: String): CarsPassageSet {
        val questions = allQuestions.filter { it.passageId == passageId }.sortedBy { it.id }
        return CarsPassageSet(passage = passagesById[passageId], questions = questions)
    }

    fun getPassage(passageId: String): Passage? = passagesById[passageId]

    // ---- Attempt tracking -------------------------------------------------

    fun recordAttempt(attempt: Attempt) = store.recordAttempt(attempt)

    fun allAttempts(): List<Attempt> = store.allAttempts()

    companion object {
        private const val PRACTICE_DIR = "speedycat/practice-questions"

        @Volatile
        private var instance: PracticeRepository? = null

        fun getInstance(context: Context): PracticeRepository =
            instance ?: synchronized(this) {
                instance ?: PracticeRepository(context.applicationContext).also { instance = it }
            }
    }
}
