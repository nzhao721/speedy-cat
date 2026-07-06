// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors
//
// Always-on AnkiWeb sync for SpeedyCAT: startup + app close run unconditionally;
// periodic (30s) and data-change triggers share a 30s minimum interval.

package com.ichi2.anki

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import anki.collection.OpChanges
import anki.sync.SyncStatusResponse
import com.ichi2.anki.common.coroutines.applicationScope
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.observability.ChangeManager
import com.ichi2.anki.practice.PracticeRepository
import com.ichi2.anki.sync.MeteredSyncPolicy
import com.ichi2.anki.worker.SyncMediaWorker
import com.ichi2.anki.worker.SyncWorker
import com.ichi2.utils.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.lang.ref.WeakReference

object SpeedyCatAutoSync :
    ChangeManager.Subscriber,
    DefaultLifecycleObserver {
    const val MIN_INTERVAL_MS = 30_000L

    enum class Trigger {
        /** First sync after the collection is ready; bypasses the throttle. */
        STARTUP,

        /** App moved to background or user exited; bypasses the throttle. */
        CLOSE,

        /** 30s tick while the app is in the foreground. */
        PERIODIC,

        /** Collection or practice data changed; respects the throttle. */
        DATA_CHANGE,
    }

    @Volatile
    private var lastAttemptMs: Long = 0L

    private var periodicJob: Job? = null
    private var deckPickerRef: WeakReference<DeckPicker>? = null

    fun install() {
        ChangeManager.subscribe(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    /** DeckPicker calls this so startup sync can use the foreground sync path. */
    fun registerDeckPicker(deckPicker: DeckPicker) {
        deckPickerRef = WeakReference(deckPicker)
    }

    fun unregisterDeckPicker(deckPicker: DeckPicker) {
        if (deckPickerRef?.get() === deckPicker) {
            deckPickerRef = null
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        periodicJob?.cancel()
        val context = AnkiDroidApp.instance.applicationContext
        periodicJob =
            applicationScope.launch {
                while (isActive) {
                    delay(MIN_INTERVAL_MS)
                    requestSync(context, Trigger.PERIODIC)
                }
            }
    }

    override fun onStop(owner: LifecycleOwner) {
        periodicJob?.cancel()
        periodicJob = null
        val context = AnkiDroidApp.instance.applicationContext
        applicationScope.launch {
            requestSync(context, Trigger.CLOSE)
        }
    }

    /** Practice / results file changed; sync when the throttle allows. */
    fun onPracticeDataChanged(context: Context) {
        applicationScope.launch {
            requestSync(context, Trigger.DATA_CHANGE)
        }
    }

    /**
     * @param foregroundSync when non-null and [Trigger.STARTUP], invoked instead of a
     * background worker so conflict / one-way dialogs can appear.
     */
    suspend fun requestSync(
        context: Context,
        trigger: Trigger,
        foregroundSync: (() -> Unit)? = null,
    ): Boolean {
        val now = TimeManager.time.intTimeMS()
        if (shouldThrottle(trigger, lastAttemptMs, now)) {
            Timber.d("SpeedyCatAutoSync: throttled (%s)", trigger)
            return false
        }
        if (!canSyncUnattended()) {
            Timber.d("SpeedyCatAutoSync: cannot sync (%s)", trigger)
            return false
        }

        lastAttemptMs = now
        publishPracticeResults(context)

        return when (trigger) {
            Trigger.STARTUP -> {
                val picker = deckPickerRef?.get()
                if (picker != null && foregroundSync != null) {
                    Timber.i("SpeedyCatAutoSync: foreground startup sync")
                    foregroundSync()
                    true
                } else {
                    Timber.i("SpeedyCatAutoSync: background startup sync")
                    runBackgroundSync(context)
                }
            }
            Trigger.CLOSE -> {
                Timber.i("SpeedyCatAutoSync: close sync")
                runBackgroundSync(context)
            }
            Trigger.PERIODIC, Trigger.DATA_CHANGE -> {
                Timber.i("SpeedyCatAutoSync: %s sync", trigger)
                runBackgroundSync(context)
            }
        }
    }

    override fun opExecuted(
        changes: OpChanges,
        handler: Any?,
    ) {
        if (handler === this) return
        if (!changes.hasAnyCollectionChange()) return
        val context = AnkiDroidApp.instance.applicationContext
        applicationScope.launch {
            requestSync(context, Trigger.DATA_CHANGE)
        }
    }

    private suspend fun runBackgroundSync(context: Context): Boolean {
        if (!areThereChangesToSync()) {
            if (shouldFetchMedia()) {
                val auth = syncAuth() ?: return false
                Timber.i("SpeedyCAT-sync: no collection changes; starting media-only sync (carries results files)")
                SyncMediaWorker.start(context, auth)
            } else {
                Timber.w("SpeedyCAT-sync: no collection changes and media fetch disabled; results cannot transfer")
            }
            setLastSyncTimeToNow()
            return false
        }
        val auth = syncAuth() ?: return false
        Timber.i("SpeedyCAT-sync: collection changes present; starting collection+media sync (media=%b)", shouldFetchMedia())
        SyncWorker.start(context, auth, shouldFetchMedia())
        return true
    }

    private suspend fun publishPracticeResults(context: Context) {
        runCatching { PracticeRepository.getInstance(context).publishResults() }
            .onFailure { Timber.w(it, "SpeedyCatAutoSync: publish practice results failed") }
    }

    private suspend fun areThereChangesToSync(): Boolean {
        val auth = syncAuth() ?: return false
        val status =
            withContext(Dispatchers.IO) {
                CollectionManager.getBackend().syncStatus(auth)
            }.required
        return when (status) {
            SyncStatusResponse.Required.FULL_SYNC,
            SyncStatusResponse.Required.NORMAL_SYNC,
            -> true
            SyncStatusResponse.Required.NO_CHANGES,
            SyncStatusResponse.Required.UNRECOGNIZED,
            null,
            -> false
        }
    }

    private fun canSyncUnattended(): Boolean {
        if (MeteredSyncPolicy.shouldBlock()) return false
        if (!NetworkUtils.isOnline) return false
        if (!isLoggedIn()) return false
        return true
    }

    fun shouldThrottle(
        trigger: Trigger,
        lastAttemptMs: Long,
        nowMs: Long,
    ): Boolean =
        trigger != Trigger.STARTUP &&
            trigger != Trigger.CLOSE &&
            (nowMs - lastAttemptMs) < MIN_INTERVAL_MS

    private fun OpChanges.hasAnyCollectionChange(): Boolean = mtime || card || note || deck || studyQueues || tag || notetype || config
}
