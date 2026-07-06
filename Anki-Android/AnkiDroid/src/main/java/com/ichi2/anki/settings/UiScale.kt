// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.settings

import android.content.Context
import android.content.res.Configuration
import com.ichi2.anki.R
import com.ichi2.anki.common.preferences.sharedPrefs

/** Desktop-parity UI scaling (100–200%, stored as integer percent in SharedPreferences). */
object UiScale {
    private const val DEFAULT_PERCENT = 100

    fun percent(context: Context): Int {
        val key = context.getString(R.string.pref_ui_scale_key)
        return context.sharedPrefs().getInt(key, DEFAULT_PERCENT).coerceIn(100, 200)
    }

    fun scaleFactor(context: Context): Float = percent(context) / 100f

    fun wrap(base: Context): Context {
        val factor = scaleFactor(base)
        if (factor == 1f) {
            return base
        }
        val config = Configuration(base.resources.configuration)
        config.fontScale = factor
        return base.createConfigurationContext(config)
    }
}
