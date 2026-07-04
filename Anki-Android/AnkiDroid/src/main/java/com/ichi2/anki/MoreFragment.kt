// SPDX-FileCopyrightText: 2026 Shaan Narendran <shaannaren06@gmail.com>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.ichi2.anki

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.ichi2.anki.account.AccountActivity
import com.ichi2.anki.common.destinations.PreferencesDestination
import com.ichi2.anki.common.destinations.navigate
import com.ichi2.anki.databinding.FragmentMoreBinding
import com.ichi2.anki.practice.PracticeActivity
import com.ichi2.anki.practice.ReadinessActivity
import com.ichi2.anki.settings.Prefs
import dev.androidbroadcast.vbpd.viewBinding

/**
 * Full-screen "More" destination in the bottom navigation bar.
 *
 * Shows Settings. The Help, Support, and privacy policy sections were removed.
 */
class MoreFragment : Fragment(R.layout.fragment_more) {
    private val binding by viewBinding(FragmentMoreBinding::bind)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        binding.moreAccount.setOnClickListener {
            startActivity(AccountActivity.getIntent(requireContext()))
        }

        binding.morePractice.setOnClickListener {
            startActivity(Intent(requireContext(), PracticeActivity::class.java))
        }

        binding.moreReadiness.setOnClickListener {
            startActivity(Intent(requireContext(), ReadinessActivity::class.java))
        }

        binding.moreSettings.setOnClickListener {
            navigate(PreferencesDestination.Root)
        }

        binding.moreHelpPrivacy.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        // Refresh on resume so the identity updates after the user logs in/out
        // on the account screen and returns here.
        refreshAccount()
    }

    /** Show the signed-in account's name/email, or a neutral label when signed out. */
    private fun refreshAccount() {
        val name = Prefs.username
        binding.moreAccountSubtitle.text =
            if (isLoggedIn() && !name.isNullOrBlank()) name else getString(R.string.speedycat_not_signed_in)
    }
}
