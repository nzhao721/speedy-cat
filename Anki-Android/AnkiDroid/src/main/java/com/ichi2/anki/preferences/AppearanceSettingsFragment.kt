/*
 *  Copyright (c) 2022 Brayan Oliveira <brayandso.dev@gmail.com>
 *
 *  This program is free software; you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation; either version 3 of the License, or (at your option) any later
 *  version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.ichi2.anki.preferences

import android.os.Build
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.preference.ListPreference
import com.ichi2.anki.R
import com.ichi2.anki.common.utils.android.systemIsInNightMode
import com.ichi2.anki.settings.Prefs
import com.ichi2.anki.settings.UiScale
import com.ichi2.anki.settings.enums.AppTheme
import com.ichi2.preferences.SliderPreference
import com.ichi2.themes.Themes
import com.ichi2.themes.Themes.updateCurrentTheme
import com.ichi2.utils.positiveButton
import com.ichi2.utils.show

class AppearanceSettingsFragment : SettingsFragment() {
    override val preferenceResource: Int
        get() = R.xml.preferences_appearance
    override val analyticsScreenNameConstant: String
        get() = "prefs.appearance"

    override fun initSubscreen() {
        setupThemePreferences()
        setupUiScalePreference()
    }

    private fun setupThemePreferences() {
        val appTheme = Prefs.appTheme
        val appThemePref = requirePreference<ListPreference>(R.string.app_theme_key)
        val dayThemePref = requirePreference<ListPreference>(R.string.day_theme_key)
        val nightThemePref = requirePreference<ListPreference>(R.string.night_theme_key)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            appThemePref.entries = resources.getStringArray(R.array.app_theme_labels).drop(1).toTypedArray()
            appThemePref.entryValues = resources.getStringArray(R.array.app_theme_values).drop(1).toTypedArray()
            if (appTheme == AppTheme.FOLLOW_SYSTEM) {
                appThemePref.value = getString(Themes.currentTheme.entryResId)
            }
        }

        appThemePref.setOnPreferenceChangeListener { newValue ->
            if (newValue != appThemePref.value) {
                val previousThemeId = Themes.currentTheme.styleResId
                appThemePref.value = newValue
                updateCurrentTheme(requireContext())

                if (previousThemeId != Themes.currentTheme.styleResId) {
                    ActivityCompat.recreate(requireActivity())
                }
            }
        }

        dayThemePref.setOnPreferenceChangeListener { newValue ->
            if (
                newValue != dayThemePref.value &&
                (appTheme == AppTheme.DAY || (appTheme == AppTheme.FOLLOW_SYSTEM && !systemIsInNightMode(requireContext())))
            ) {
                ActivityCompat.recreate(requireActivity())
            }
        }

        nightThemePref.setOnPreferenceChangeListener { newValue ->
            if (
                newValue != nightThemePref.value &&
                (appTheme == AppTheme.NIGHT || (appTheme == AppTheme.FOLLOW_SYSTEM && systemIsInNightMode(requireContext())))
            ) {
                ActivityCompat.recreate(requireActivity())
            }
        }
    }

    private fun setupUiScalePreference() {
        requirePreference<SliderPreference>(R.string.pref_ui_scale_key).setOnPreferenceChangeListener { percent ->
            if (percent == UiScale.percent(requireContext())) {
                return@setOnPreferenceChangeListener
            }
            AlertDialog.Builder(requireContext()).show {
                setMessage(R.string.pref_ui_scale_restart_message)
                positiveButton(R.string.dialog_ok) {
                    ActivityCompat.recreate(requireActivity())
                }
            }
        }
    }
}
