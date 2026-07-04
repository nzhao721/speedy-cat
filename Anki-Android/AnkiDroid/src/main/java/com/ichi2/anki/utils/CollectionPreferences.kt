/*
 * Copyright (c) 2025 Brayan Oliveira <69634269+brayandso@users.noreply.github.com>
 *
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.ichi2.anki.utils

import androidx.annotation.VisibleForTesting
import com.ichi2.anki.CollectionManager.withCol
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * Helper methods related to preferences saved in the collection.
 *
 * SpeedyCAT: collection review/scheduling prefs are read-only for users.
 *
 * @see [anki.config.Preferences]
 */
object CollectionPreferences {
    // region Reviewing
    suspend fun getShowRemainingDueCounts(): Boolean = withCol { getPreferences() }.reviewing.showRemainingDueCounts

    suspend fun getHidePlayAudioButtons(): Boolean = withCol { getPreferences() }.reviewing.hideAudioPlayButtons

    suspend fun getShowIntervalOnButtons(): Boolean = withCol { getPreferences() }.reviewing.showIntervalsOnButtons

    suspend fun getTimeboxTimeLimit(): Duration = withCol { getPreferences() }.reviewing.timeLimitSecs.toDuration(DurationUnit.SECONDS)
    //endregion

    //region Scheduling
    suspend fun getLearnAheadLimit(): Duration = withCol { getPreferences() }.scheduling.learnAheadSecs.toDuration(DurationUnit.SECONDS)

    @VisibleForTesting
    suspend fun getDayOffset(): Int = withCol { getPreferences() }.scheduling.rollover
    //endregion
}
