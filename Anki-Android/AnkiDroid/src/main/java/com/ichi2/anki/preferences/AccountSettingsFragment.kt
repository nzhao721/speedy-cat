// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors

package com.ichi2.anki.preferences

import androidx.preference.Preference
import com.ichi2.anki.R
import com.ichi2.anki.account.AccountActivity
import com.ichi2.anki.isLoggedIn
import com.ichi2.anki.settings.Prefs

/** SpeedyCAT: sign in / sign out via the existing AnkiWeb account flow. */
class AccountSettingsFragment : SettingsFragment() {
    override val preferenceResource: Int
        get() = R.xml.preferences_account
    override val analyticsScreenNameConstant: String
        get() = "prefs.account"

    override fun initSubscreen() {
        requirePreference<Preference>(R.string.sync_account_key).setOnPreferenceClickListener {
            startActivity(AccountActivity.getIntent(requireContext()))
            true
        }
        refreshAccountStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshAccountStatus()
    }

    private fun refreshAccountStatus() {
        val name = Prefs.username
        requirePreference<Preference>(R.string.sync_account_key).summary =
            if (isLoggedIn() && !name.isNullOrBlank()) {
                name
            } else {
                getString(R.string.speedycat_not_signed_in)
            }
    }
}
