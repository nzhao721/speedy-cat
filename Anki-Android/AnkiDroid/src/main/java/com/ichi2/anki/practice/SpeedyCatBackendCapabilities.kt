// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors
//
// Runtime probes for SpeedyCAT PracticeService RPCs on the linked rsdroid
// backend. The stock Maven AAR lacks these methods; a locally-built AAR must
// be rebuilt after proto changes (see setup/build-rsdroid.cmd).

package com.ichi2.anki.practice

import com.ichi2.anki.BuildConfig
import com.ichi2.anki.libanki.Collection
import net.ankiweb.rsdroid.Backend
import timber.log.Timber

/** Why a SpeedyCAT backend RPC is unavailable at runtime. */
enum class SpeedyCatBackendIssue {
    /** APK was built without `local_backend=true` in local.properties. */
    NotConfigured,

    /** APK uses local_backend but the linked AAR predates the RPC — rebuild rsdroid. */
    StaleLocalAar,

    /** Collection DB lacks schema-19 practice tables (sync from desktop first). */
    MissingPracticeTables,

    /** Attempt sync into practice_attempts failed. */
    SyncFailed,
}

object SpeedyCatBackendCapabilities {
    fun hasGetReadiness(backend: Backend): Boolean = hasRawMethod(backend, "getReadinessRaw")

    fun hasGetRecommendedTopics(backend: Backend): Boolean =
        hasRawMethod(backend, "getRecommendedPracticeTopicsRaw")

    fun hasStartPracticeSession(backend: Backend): Boolean =
        hasRawMethod(backend, "startPracticeSessionRaw")

    private fun hasRawMethod(
        backend: Backend,
        methodName: String,
    ): Boolean =
        try {
            backend.javaClass.getMethod(methodName, ByteArray::class.java)
            true
        } catch (_: NoSuchMethodException) {
            false
        }

    /**
     * Best-effort diagnosis for missing recommended-topic support. When
     * [BuildConfig.LOCAL_BACKEND] is false the APK is on the stock backend; when
     * true but the RPC is absent the local AAR is stale.
     */
    fun recommendedTopicsIssue(
        col: Collection,
        attemptsSynced: Boolean,
    ): SpeedyCatBackendIssue? {
        if (!hasGetRecommendedTopics(col.backend)) {
            return if (BuildConfig.LOCAL_BACKEND) {
                SpeedyCatBackendIssue.StaleLocalAar
            } else {
                SpeedyCatBackendIssue.NotConfigured
            }
        }
        if (!SpeedyCatPracticeDbSync.hasPracticeTable(col)) {
            return SpeedyCatBackendIssue.MissingPracticeTables
        }
        if (!attemptsSynced) {
            return SpeedyCatBackendIssue.SyncFailed
        }
        return null
    }

    fun messageFor(issue: SpeedyCatBackendIssue): String =
        when (issue) {
            SpeedyCatBackendIssue.NotConfigured ->
                "Recommended topics need a local Rust backend. Set local_backend=true in " +
                    "Anki-Android/local.properties, rebuild rsdroid (setup/build-rsdroid.cmd), " +
                    "then rebuild the APK."
            SpeedyCatBackendIssue.StaleLocalAar ->
                "Recommended topics RPC is missing from the linked rsdroid AAR. Rebuild with " +
                    "setup/build-rsdroid.cmd (ALL_ARCHS=1), then gradlew :AnkiDroid:assemblePlayDebug."
            SpeedyCatBackendIssue.MissingPracticeTables ->
                "Practice tables are missing from your collection. Open the collection on desktop " +
                    "once so schema 19 migrates, then sync to this device."
            SpeedyCatBackendIssue.SyncFailed ->
                "Could not sync practice attempts into the collection database."
        }

    fun logCapabilities(col: Collection) {
        Timber.i(
            "SpeedyCAT backend: LOCAL_BACKEND=%s getReadiness=%s getRecommendedTopics=%s " +
                "startPracticeSession=%s practiceTable=%s",
            BuildConfig.LOCAL_BACKEND,
            hasGetReadiness(col.backend),
            hasGetRecommendedTopics(col.backend),
            hasStartPracticeSession(col.backend),
            SpeedyCatPracticeDbSync.hasPracticeTable(col),
        )
    }
}
