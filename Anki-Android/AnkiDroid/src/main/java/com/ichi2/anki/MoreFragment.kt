// SPDX-FileCopyrightText: 2026 Shaan Narendran <shaannaren06@gmail.com>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.ichi2.anki

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.ichi2.anki.common.destinations.PreferencesDestination
import com.ichi2.anki.common.destinations.navigate
import com.ichi2.anki.databinding.FragmentMoreBinding
import com.ichi2.anki.dialogs.help.HelpDialog
import com.ichi2.anki.practice.FullLengthActivity
import com.ichi2.anki.practice.PracticeActivity
import com.ichi2.anki.utils.ext.showDialogFragment
import dev.androidbroadcast.vbpd.viewBinding

/**
 * Full-screen "More" destination in the bottom navigation bar.
 *
 * Shows Settings and a Privacy policy link. The Help and Support sections were removed;
 * the privacy policy remains reachable here and post-login via
 * [HelpDialog.newPrivacyPolicyInstance].
 */
class MoreFragment : Fragment(R.layout.fragment_more) {
    private val binding by viewBinding(FragmentMoreBinding::bind)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        binding.morePractice.setOnClickListener {
            startActivity(Intent(requireContext(), PracticeActivity::class.java))
        }

        binding.moreFullLength.setOnClickListener {
            startActivity(Intent(requireContext(), FullLengthActivity::class.java))
        }

        binding.moreSettings.setOnClickListener {
            navigate(PreferencesDestination.Root)
        }

        binding.moreHelpPrivacy.setOnClickListener {
            requireActivity().showDialogFragment(HelpDialog.newPrivacyPolicyInstance())
        }
    }
}
