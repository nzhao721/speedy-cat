// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors
//
// ViewModel for the Readiness screen. Prefers `PracticeService.GetReadiness`
// from the Rust backend (Memory + Performance); falls back to ReadinessLogic
// when the stock backend lacks PracticeService. Full-length Readiness uses
// synced desktop summaries when the backend pillar is unavailable.

package com.ichi2.anki.practice

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ichi2.anki.CollectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class ReadinessViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val repo = PracticeRepository.getInstance(app)

    var loading by mutableStateOf(true)
        private set

    var loadFailed by mutableStateOf(false)
        private set

    var pillars by mutableStateOf<List<ReadinessPillar>>(emptyList())
        private set

    /** Desktop-created full-length results, shown read-only in a results card. */
    var fullLengthResults by mutableStateOf<List<FullLengthSummary>>(emptyList())
        private set

    init {
        load()
    }

    fun load() {
        loading = true
        loadFailed = false
        viewModelScope.launch {
            try {
                runCatching { repo.ingestResults() }
                    .onFailure { Timber.w(it, "SpeedyCAT: results ingest failed") }
                val summaries =
                    runCatching { repo.remoteFullLengthSummaries() }
                        .getOrElse {
                            Timber.w(it, "SpeedyCAT: reading full-length summaries failed")
                            emptyList()
                        }
                fullLengthResults = summaries
                pillars = buildPillars(summaries)
            } catch (e: Exception) {
                Timber.w(e, "SpeedyCAT: failed to compute readiness")
                loadFailed = true
            } finally {
                loading = false
            }
        }
    }

    private suspend fun buildPillars(summaries: List<FullLengthSummary>): List<ReadinessPillar> {
        val attempts = withContext(Dispatchers.IO) { repo.allAttempts() }
        val sessionAttempts = attempts.filter { it.sessionId != null }
        val totalCards = collectionTotalCards()
        val performanceResult = computePerformance(sessionAttempts)

        val fromBackend =
            CollectionManager.withCol {
                ReadinessBackend.tryFetchPillars(this, sessionAttempts)
            }
        if (fromBackend != null) {
            val readiness =
                enhanceReadinessPillar(
                    fromBackend.readiness?.takeIf { it.sufficient }
                        ?: readinessPillar(computeReadiness(summaries), summaries),
                    summaries,
                )
            return listOf(
                enhanceMemoryPillar(fromBackend.memory, totalCards),
                enhancePerformancePillar(fromBackend.performance, performanceResult),
                readiness,
            )
        }

        Timber.d("SpeedyCAT: using Kotlin readiness fallback (stock backend)")
        val memory = memoryPillarFromCollection(totalCards)
        val performance = performancePillar(performanceResult)
        val readiness = readinessPillar(computeReadiness(summaries), summaries)
        return listOf(memory, performance, readiness)
    }

    private suspend fun collectionTotalCards(): Int =
        withContext(Dispatchers.IO) {
            CollectionManager.withCol { cardCount() }
        }

    /** Memory pillar fallback when GetReadiness is unavailable. */
    private suspend fun memoryPillarFromCollection(totalCards: Int): ReadinessPillar =
        CollectionManager.withCol {
            val stats =
                com.ichi2.anki.cardviewer.SpeedyCatGaming
                    .loadStats(this)
            com.ichi2.anki.cardviewer.SpeedyCatGaming.memorySuppressionMessage(stats)?.let { message ->
                return@withCol memoryPillar(
                    MemoryResult(
                        sufficient = false,
                        fsrsEnabled = true,
                        reviewedCards = stats.dailyReviews,
                        meanRetrievability = 0.0,
                        ci = ConfidenceInterval(0.0, 0.0),
                        insufficientReason = message,
                    ),
                )
            }
            val fsrsEnabled = config.get<Boolean>("fsrs", false) ?: false
            if (!fsrsEnabled) {
                return@withCol memoryPillar(computeMemory(fsrsEnabled = false, retrievabilities = emptyList()))
            }
            val reviewedCardIds = findCards("-is:new")
            val retrievabilities = ArrayList<Double>(reviewedCardIds.size)
            for (cardId in reviewedCardIds) {
                val stats = cardStatsData(cardId)
                if (stats.hasFsrsRetrievability()) {
                    retrievabilities.add(stats.fsrsRetrievability.toDouble())
                }
            }
            memoryPillar(computeMemory(fsrsEnabled = true, retrievabilities = retrievabilities), totalCards)
        }
}
