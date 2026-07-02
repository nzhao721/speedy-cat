// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors
//
// ViewModel for the Readiness screen. It gathers each pillar's inputs from its
// named, deterministic source — FSRS retrievability from the Anki backend
// (Memory) and the local Practice attempt store (Performance) — and hands them
// to the pure ReadinessLogic to compute value + range + give-up. No AI is used.

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
                // Pull in results synced from other devices before computing, so
                // the pillars reflect the union of this device + desktop.
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
        val memory = memoryPillarFromCollection()
        val performance = performancePillar(computePerformance(attempts.filter { it.sessionId != null }))
        // Readiness is the desktop-created full-length raw score, read-only here.
        val readiness = readinessPillar(computeReadiness(summaries))
        return listOf(memory, performance, readiness)
    }

    /**
     * Memory pillar: mean FSRS retrievability over reviewed cards. Reads the
     * per-card retrievability the Anki backend exposes on card stats; only cards
     * that carry a retrievability (i.e. reviewed, with FSRS on) are counted.
     */
    private suspend fun memoryPillarFromCollection(): ReadinessPillar =
        CollectionManager.withCol {
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
            memoryPillar(computeMemory(fsrsEnabled = true, retrievabilities = retrievabilities))
        }
}
