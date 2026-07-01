// SPDX-License-Identifier: GPL-3.0-or-later
//
// SpeedyCAT (MCAT study app) is a brownfield fork of AnkiDroid.
// AnkiDroid is licensed AGPL-3.0-or-later; Anki is created by Damien Elmes and
// the Anki contributors. This file preserves that attribution.

package com.ichi2.anki.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ichi2.anki.SpeedyCatFullDeck
import com.ichi2.anki.common.preferences.sharedPrefs
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Backfills the full, image-rich SpeedyCAT MCAT deck set after install.
 *
 * The bundled starter deck ([com.ichi2.anki.SpeedyCatBundledDeck]) is imported
 * instantly and synchronously on first run; this worker then downloads and
 * imports the complete ~385 MB set in the background, with no prompt.
 *
 * WorkManager is a deliberate fit here:
 *  - the `UNMETERED` network constraint means the large download runs only on
 *    Wi-Fi/unmetered by default (product choice for a ~385 MB payload);
 *  - `StorageNotLow` avoids kicking off a big import when the device is nearly
 *    full;
 *  - unique work + [ExistingWorkPolicy.KEEP] makes scheduling idempotent, so
 *    calling [start] on every launch never stacks duplicate downloads;
 *  - [androidx.work.ListenableWorker.Result.retry] with exponential backoff
 *    retries transient offline/failure states, and the work survives the
 *    Activity (and process) that scheduled it.
 *
 * It is intentionally a plain background worker (no foreground service /
 * notification) to keep the footprint minimal — the backfill is not something
 * the user is waiting on. [SpeedyCatFullDeck] holds the actual, unit-tested
 * download/verify/import logic.
 */
class SpeedyCatFullDeckWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        Timber.v("SpeedyCatFullDeckWorker::doWork")
        return when (val result = SpeedyCatFullDeck.importIfNeeded(applicationContext)) {
            SpeedyCatFullDeck.Result.IMPORTED,
            SpeedyCatFullDeck.Result.ALREADY_IMPORTED,
            SpeedyCatFullDeck.Result.NOT_CONFIGURED,
            -> {
                Timber.i("SpeedyCatFullDeckWorker finished: %s", result)
                Result.success()
            }
            SpeedyCatFullDeck.Result.OFFLINE,
            SpeedyCatFullDeck.Result.SKIPPED_METERED,
            SpeedyCatFullDeck.Result.FAILED,
            -> {
                Timber.i("SpeedyCatFullDeckWorker will retry: %s", result)
                Result.retry()
            }
        }
    }

    companion object {
        private const val BACKOFF_MINUTES = 30L

        /**
         * Schedules the one-time background full-deck download+import.
         *
         * Cheap and safe to call on every startup: it no-ops when the feature is
         * unconfigured or the import has already succeeded, and otherwise relies
         * on unique work to avoid duplicates.
         */
        fun start(context: Context) {
            if (SpeedyCatFullDeck.SPEEDYCAT_FULL_DECK_URL.isNullOrBlank()) {
                Timber.d("SpeedyCAT full deck URL not configured; not scheduling download")
                return
            }
            if (context.sharedPrefs().getBoolean(SpeedyCatFullDeck.IMPORTED_PREF_KEY, false)) {
                Timber.d("SpeedyCAT full deck already imported; not scheduling download")
                return
            }

            val constraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresStorageNotLow(true)
                    .build()

            val request =
                OneTimeWorkRequestBuilder<SpeedyCatFullDeckWorker>()
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                    .build()

            Timber.i("Scheduling SpeedyCAT full deck background download")
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(UniqueWorkNames.SPEEDYCAT_FULL_DECK, ExistingWorkPolicy.KEEP, request)
        }
    }
}
