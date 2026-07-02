// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors
//
// Device id + media-folder IO for cross-device results sync. The pure on-disk
// contract + merge logic live in SpeedyCatResults; this layer is the thin,
// side-effecting glue: it owns the device id (stored device-locally in
// SharedPreferences, NEVER in the synced collection config, so each device keeps
// a distinct identity + a distinct media filename) and reads/writes the
// per-device JSON file inside `collection.media`.
//
// Writing directly (rather than col.media.addFile, which appends a hash on a
// content change) keeps the filename STABLE across updates; the media
// change-tracker picks up the overwrite and uploads it on the next media sync.
// Ingest reads only OTHER devices' files and never our own, so re-publishing our
// merged store cannot double-count.

package com.ichi2.anki.practice

import android.content.Context
import timber.log.Timber
import java.io.File
import java.util.UUID

object PracticeResultsSync {
    private const val PREFS_NAME = "speedycat_sync"
    private const val KEY_DEVICE_ID = "deviceId"

    /** This device's stable, local-only id (minted + persisted on first use). */
    fun deviceId(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { if (it.isNotEmpty()) return it }
        val minted = UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString(KEY_DEVICE_ID, minted).apply()
        return minted
    }

    /**
     * Write this device's results file into [mediaDir] under its stable name
     * (overwriting the prior copy). Returns the filename, or null on failure —
     * results sync is best-effort telemetry and must never throw into a caller.
     */
    fun publish(
        mediaDir: File,
        deviceId: String,
        attempts: List<Attempt>,
        now: Long,
        fullLength: List<FullLengthSummary> = emptyList(),
    ): String? =
        try {
            if (!mediaDir.exists()) mediaDir.mkdirs()
            val payload = serializeResults(deviceId, attempts.map { it.toSynced() }, now, fullLength)
            val fname = resultsFilename(deviceId)
            val target = File(mediaDir, fname)
            val tmp = File(mediaDir, "$fname.tmp")
            tmp.writeText(payload, Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                target.writeText(payload, Charsets.UTF_8)
                tmp.delete()
            }
            fname
        } catch (e: Exception) {
            Timber.w(e, "SpeedyCAT: failed to publish results")
            null
        }

    /** Parse every `_speedycat_results_*.json` in [mediaDir] that is NOT ours. */
    fun readOtherDeviceFiles(
        mediaDir: File,
        deviceId: String,
    ): List<ResultsFile> {
        if (!mediaDir.isDirectory) return emptyList()
        val myId = sanitizeDeviceId(deviceId)
        val files = mediaDir.listFiles() ?: return emptyList()
        val out = mutableListOf<ResultsFile>()
        for (f in files) {
            val fileDevice = deviceIdFromFilename(f.name) ?: continue
            if (fileDevice == myId) continue
            val parsed =
                try {
                    parseResults(f.readText(Charsets.UTF_8))
                } catch (e: Exception) {
                    Timber.w(e, "SpeedyCAT: failed to read results file %s", f.name)
                    null
                }
            if (parsed != null) out.add(parsed)
        }
        return out
    }

    /** Deduped practice attempts published by OTHER devices. */
    fun remoteAttempts(
        mediaDir: File,
        deviceId: String,
    ): List<SyncedAttempt> = mergeAttempts(readOtherDeviceFiles(mediaDir, deviceId).map { it.attempts })

    /**
     * Completed full-length summaries published by OTHER devices (i.e. desktop),
     * deduped by attemptId, newest first. Mobile never produces these itself.
     */
    fun remoteFullLength(
        mediaDir: File,
        deviceId: String,
    ): List<FullLengthSummary> {
        val byId = LinkedHashMap<String, FullLengthSummary>()
        for (file in readOtherDeviceFiles(mediaDir, deviceId)) {
            for (fl in file.fullLength) {
                if (fl.attemptId.isNotEmpty()) byId[fl.attemptId] = fl
            }
        }
        return byId.values.sortedByDescending { it.completedAt }
    }
}
