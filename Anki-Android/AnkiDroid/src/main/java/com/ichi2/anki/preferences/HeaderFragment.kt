// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: Copyright (c) 2022 Brayan Oliveira <brayandso.dev@gmail.com>

package com.ichi2.anki.preferences

import android.os.Bundle
import android.view.View
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.bytehamster.lib.preferencesearch.SearchConfiguration
import com.ichi2.anki.R
import com.ichi2.anki.preferences.profiles.SwitchProfilesFragment
import com.ichi2.anki.preferences.reviewer.ReviewerMenuSettingsFragment
import com.ichi2.preferences.HeaderPreference

class HeaderFragment : SettingsFragment() {
    override val analyticsScreenNameConstant: String
        get() = "prefs.initialPage"
    override val preferenceResource: Int
        get() = R.xml.preference_headers

    private var highlightedPreferenceKey: String = ""

    override fun initSubscreen() {
        // SpeedyCAT: no conditional headers — General, Appearance, and Account remain in XML.
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener {
            requireActivity().finish()
        }
    }

    fun highlightPreference(
        @StringRes keyRes: Int,
    ) {
        val key = getString(keyRes)
        findPreference<HeaderPreference>(highlightedPreferenceKey)?.setHighlighted(false)
        findPreference<HeaderPreference>(key)?.setHighlighted(true)
        highlightedPreferenceKey = key
    }

    companion object {
        /** SpeedyCAT: search limited to General, Appearance, and Account preferences. */
        fun configureSearchBar(
            activity: AppCompatActivity,
            searchConfiguration: SearchConfiguration,
        ) {
            with(searchConfiguration) {
                setActivity(activity)
                setBreadcrumbsEnabled(true)
                setFuzzySearchEnabled(false)
                setHistoryEnabled(true)
                setFragmentContainerViewId(android.R.id.list_container)
                index(R.xml.preferences_general)
                index(R.xml.preferences_appearance)
                index(R.xml.preferences_account)
            }
        }

        /**
         * @return the key for the [HeaderPreference] that corresponds to the given [fragment]
         * in the Preference tree.
         */
        @StringRes
        fun getHeaderKeyForFragment(fragment: Fragment): Int? =
            when (fragment) {
                is GeneralSettingsFragment -> R.string.pref_general_screen_key
                is AppearanceSettingsFragment -> R.string.pref_appearance_screen_key
                is AccountSettingsFragment -> R.string.pref_account_screen_key
                // Deep-link targets kept for internal navigation (not shown in header list).
                is AdvancedSettingsFragment -> R.string.pref_advanced_screen_key
                is AccessibilitySettingsFragment -> R.string.pref_accessibility_screen_key
                is ReviewingSettingsFragment -> R.string.pref_reviewing_screen_key
                is SyncSettingsFragment, is CustomSyncServerSettingsFragment -> R.string.pref_sync_screen_key
                is NotificationsSettingsFragment -> R.string.pref_notifications_screen_key
                is ControlsSettingsFragment -> R.string.pref_controls_screen_key
                is BackupLimitsSettingsFragment -> R.string.pref_backup_limits_screen_key
                is ReviewerOptionsFragment, is ReviewerMenuSettingsFragment -> R.string.new_reviewer_options_key
                is DeveloperOptionsFragment -> R.string.pref_developer_options_screen_key
                is AboutFragment -> R.string.about_screen_key
                is SwitchProfilesFragment -> R.string.pref_switch_profile_screen_key
                else -> null
            }
    }
}
