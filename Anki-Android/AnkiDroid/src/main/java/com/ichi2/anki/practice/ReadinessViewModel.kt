// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors
//
// ViewModel for the Readiness screen. It is a THIN client over the shared Rust
// engine: it projects device-local results into the collection DB and reads all
// three pillars (Memory / Performance / Readiness) from
// `PracticeService.GetReadiness`. There is deliberately NO Kotlin fallback that
// recomputes readiness — the engine (anki/rslib) is the single source of truth,
// identical to the desktop app, and it already owns the Wilson intervals + FSRS
// retrievability-histogram Memory scoring + give-up rules. When the engine RPC
// is unavailable (stock prebuilt backend without our PracticeService — i.e.
// rsdroid not built with local_backend=true), the pillars degrade to an honest
// give-up state rather than a second, drifting implementation of the formulas.

package com.ichi2.anki.practice

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ichi2.anki.CollectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
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

    var projectedScore by mutableStateOf<ProjectedMcatScore?>(null)
        private set

    /** Desktop-created full-length results, shown read-only in a results card. */
    var fullLengthResults by mutableStateOf<List<FullLengthSummary>>(emptyList())
        private set

    init {
        load()
        observeResultsSync()
    }

    /**
     * Recompute whenever a media sync has just folded in other devices' results
     * (see [PracticeRepository.resultsIngested]). The Dashboard fragment is
     * created once and reused (show/hide), so its one-time on-open ingest races
     * the asynchronous background media sync; this refresh is what makes newly
     * synced Performance/Readiness data appear without reopening the app.
     */
    private fun observeResultsSync() {
        viewModelScope.launch {
            PracticeRepository.resultsIngested.collect {
                load(showLoading = false)
            }
        }
    }

    fun load(showLoading: Boolean = true) {
        if (showLoading) loading = true
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
                val built = buildPillars(summaries)
                pillars = built.pillars
                projectedScore = built.projected
            } catch (e: Exception) {
                Timber.w(e, "SpeedyCAT: failed to compute readiness")
                loadFailed = true
            } finally {
                loading = false
            }
        }
    }

    private data class BuiltPillars(
        val pillars: List<ReadinessPillar>,
        val projected: ProjectedMcatScore?,
    )

    private suspend fun buildPillars(summaries: List<FullLengthSummary>): BuiltPillars {
        val attempts = withContext(Dispatchers.IO) { repo.allAttempts() }
        val sessionAttempts = attempts.filter { it.sessionId != null }
        Timber.i(
            "SpeedyCAT-sync: buildPillars over %d local+ingested session attempt(s), %d full-length summary(ies)",
            sessionAttempts.size,
            summaries.size,
        )
        val totalCards = collectionTotalCards()
        val studiedCards = collectionStudiedCards()

        val fromBackend =
            CollectionManager.withCol {
                ReadinessBackend.tryFetchPillars(this, sessionAttempts, summaries)
            }
        if (fromBackend != null) {
            Timber.i(
                "SpeedyCAT-sync: recomputed pillars — Memory[suff=%b n=%d] Performance[suff=%b n=%d] Readiness[suff=%b n=%d]",
                fromBackend.memory.sufficient,
                fromBackend.memory.sampleSize,
                fromBackend.performance.sufficient,
                fromBackend.performance.sampleSize,
                fromBackend.readiness.sufficient,
                fromBackend.readiness.sampleSize,
            )
            return BuiltPillars(
                pillars =
                    listOf(
                        withMemoryDetail(fromBackend.memory, totalCards, studiedCards),
                        withPerformanceDetail(fromBackend.performance, fromBackend.performanceAvgSeconds),
                        withReadinessDetail(fromBackend.readiness, summaries),
                    ),
                projected = fromBackend.projected,
            )
        }

        // The shared Rust engine isn't present in this build (stock backend). We do
        // NOT fall back to a Kotlin re-implementation — that is exactly the
        // duplicated logic we removed. Show honest give-up pillars instead.
        Timber.w(
            "SpeedyCAT: PracticeService.GetReadiness unavailable; " +
                "build rsdroid with local_backend=true to compute readiness on-device",
        )
        return BuiltPillars(
            pillars = engineUnavailablePillars(),
            projected = null,
        )
    }

    private suspend fun collectionTotalCards(): Int =
        withContext(Dispatchers.IO) {
            CollectionManager.withCol { cardCount() }
        }

    /** Lifetime cards studied at least once (non-new cards in the collection). */
    private suspend fun collectionStudiedCards(): Int =
        withContext(Dispatchers.IO) {
            CollectionManager.withCol { findCards("-is:new").size }
        }

    /** Attach the "N out of M flashcards studied" line to the engine's Memory pillar. */
    private fun withMemoryDetail(
        pillar: ReadinessPillar,
        totalCards: Int,
        studiedCards: Int,
    ): ReadinessPillar {
        if (studiedCards <= 0 || totalCards <= 0) return pillar
        return pillar.copy(
            detail = listOf(memoryStudiedLine(studiedCards, totalCards, soFar = !pillar.sufficient)),
        )
    }

    /** Attach the answered-count + average-time lines to the engine's Performance pillar. */
    private fun withPerformanceDetail(
        pillar: ReadinessPillar,
        avgSeconds: Double,
    ): ReadinessPillar {
        if (!pillar.sufficient) return pillar
        val lines =
            buildList {
                add("Based on ${pillar.sampleSize} practice questions answered")
                if (avgSeconds > 0) add("Average time of ${avgSeconds.roundToInt()} seconds")
            }
        return pillar.copy(detail = lines)
    }

    /** Attach per-exam accuracy lines (read-only synced data) to the Readiness pillar. */
    private fun withReadinessDetail(
        pillar: ReadinessPillar,
        summaries: List<FullLengthSummary>,
    ): ReadinessPillar {
        if (!pillar.sufficient) return pillar
        return pillar.copy(detail = readinessExamLines(summaries))
    }

    private fun engineUnavailablePillars(): List<ReadinessPillar> {
        val message =
            "The SpeedyCAT scoring engine isn't available in this build. Build the local " +
                "Rust backend (local_backend=true) so your readiness is computed on-device."
        return listOf("Memory", "Performance", "Readiness").map { name ->
            ReadinessPillar(
                name = name,
                sufficient = false,
                value = "\u2014",
                range = "",
                rangeCaption = CI_CAPTION,
                source = "SpeedyCAT Rust engine (PracticeService.GetReadiness)",
                detail = emptyList(),
                insufficientReason = message,
                sampleSize = 0,
            )
        }
    }
}
