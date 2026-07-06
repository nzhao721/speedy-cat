// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.preferences

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.preference.ListPreference
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.R
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.utils.LanguageUtil
import com.ichi2.utils.LanguageUtil.getStringByLocale
import com.ichi2.utils.LanguageUtil.getSystemLocale
import kotlinx.coroutines.runBlocking

class GeneralSettingsFragment : SettingsFragment() {
    override val preferenceResource: Int
        get() = R.xml.preferences_general
    override val analyticsScreenNameConstant: String
        get() = "prefs.general"

    override fun initSubscreen() {
        initializeLanguagePref()
        initializeVideoDriverPref()
    }

    private fun initializeLanguagePref() {
        val sortedLanguages = LanguageUtil.APP_LANGUAGES.toSortedMap(java.lang.String.CASE_INSENSITIVE_ORDER)
        val systemLocale = getSystemLocale()
        requirePreference<ListPreference>(R.string.pref_language_key).apply {
            entries = arrayOf(getStringByLocale(R.string.language_system, systemLocale), *sortedLanguages.keys.toTypedArray())
            entryValues = arrayOf(LanguageUtil.SYSTEM_LANGUAGE_TAG, *sortedLanguages.values.toTypedArray())
            setOnPreferenceChangeListener { selectedLanguage ->
                LanguageUtil.setDefaultBackendLanguages(selectedLanguage)
                runBlocking { CollectionManager.discardBackend() }

                val localeCode =
                    if (selectedLanguage != LanguageUtil.SYSTEM_LANGUAGE_TAG) {
                        selectedLanguage
                    } else {
                        null
                    }
                val localeList = LocaleListCompat.forLanguageTags(localeCode)
                AppCompatDelegate.setApplicationLocales(localeList)
            }
        }
    }

    private fun initializeVideoDriverPref() {
        val softwareRenderKey = getString(R.string.disable_hardware_render_key)
        val usesSoftwareRender = requireContext().sharedPrefs().getBoolean(softwareRenderKey, false)
        requirePreference<ListPreference>(R.string.pref_video_driver_key).apply {
            value = if (usesSoftwareRender) VIDEO_DRIVER_SOFTWARE else VIDEO_DRIVER_HARDWARE
            setOnPreferenceChangeListener { newValue ->
                requireContext().sharedPrefs()
                    .edit()
                    .putBoolean(softwareRenderKey, newValue == VIDEO_DRIVER_SOFTWARE)
                    .apply()
            }
        }
    }

    companion object {
        private const val VIDEO_DRIVER_HARDWARE = "hardware"
        private const val VIDEO_DRIVER_SOFTWARE = "software"
    }
}
