// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.preferences

import android.content.Context
import android.content.Intent
import com.ichi2.anki.common.destinations.PreferencesDestination

/** Builds the [Intent] that opens settings at this destination. */
fun PreferencesDestination.toIntent(context: Context): Intent =
    when (this) {
        PreferencesDestination.Root -> PreferencesActivity.getIntent(context)
        // SpeedyCAT: advanced settings kept for internal deep links (e.g. collection path).
        PreferencesDestination.Advanced ->
            PreferencesActivity.getIntent(context, AdvancedSettingsFragment::class)
        // SpeedyCAT: accessibility settings removed from UI; open root settings instead.
        PreferencesDestination.Accessibility -> PreferencesActivity.getIntent(context)
    }
