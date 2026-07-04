/*
 *  Copyright (c) 2020 David Allison <davidallisongithub@gmail.com>
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
package com.ichi2.anki.dialogs.help

import android.app.Dialog
import android.os.Bundle
import android.view.View
import androidx.activity.addCallback
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.core.os.BundleCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.analytics.AnalyticsConstants.Actions
import com.ichi2.anki.analytics.AnalyticsConstants.Category
import com.ichi2.anki.analytics.UsageAnalytics
import com.ichi2.anki.ankiActivity
import com.ichi2.anki.databinding.DialogHelpBinding
import com.ichi2.anki.databinding.FragmentHelpPageBinding
import com.ichi2.anki.databinding.ItemHelpEntryBinding
import com.ichi2.anki.dialogs.help.HelpItem.Action.OpenUrl
import com.ichi2.anki.dialogs.help.HelpItem.Action.OpenUrlResource
import com.ichi2.anki.dialogs.help.HelpItem.Action.Rate
import com.ichi2.anki.dialogs.help.HelpItem.Action.SendReport
import com.ichi2.anki.utils.ext.setCompoundDrawablesRelativeWithIntrinsicBoundsKt
import com.ichi2.utils.createAndApply
import com.ichi2.utils.customView
import com.ichi2.utils.dp
import com.ichi2.utils.title
import dev.androidbroadcast.vbpd.viewBinding

/**
 * [DialogFragment] responsible for showing the help/support menus.
 */
class HelpDialog : DialogFragment() {
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    lateinit var actionsDispatcher: HelpItemActionsDispatcher

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogHelpBinding.inflate(requireActivity().layoutInflater)
        ankiActivity?.let { ankiActivity ->
            actionsDispatcher = AnkiActivityHelpActionsDispatcher(ankiActivity)
        }
        childFragmentManager.setFragmentResultListener(
            REQUEST_HELP_PAGE,
            this,
        ) { _, arguments -> handleItemSelection(arguments) }
        return AlertDialog
            .Builder(requireContext())
            .title(requireArguments().getInt(ARG_MENU_TITLE))
            .customView(binding.root)
            .createAndApply {
                // the dialog captures the BACK call so we manually pop the inner FragmentManager
                // if there's a second page
                onBackPressedDispatcher.addCallback(this@HelpDialog, true) {
                    if (childFragmentManager.backStackEntryCount > 1) {
                        childFragmentManager.popBackStack()
                    } else {
                        dismiss()
                    }
                }
                setOnShowListener {
                    // if there's no fragment added this is a fresh start so add the initial page from arguments
                    if (childFragmentManager.findFragmentByTag(PAGE_TAG) == null) {
                        newHelpPage(requireArgsHelpEntries())
                    }
                }
            }
    }

    private fun handleItemSelection(from: Bundle) {
        from.classLoader = javaClass.classLoader
        val selectedItem =
            BundleCompat.getParcelable(
                from,
                ARG_SELECTED_MENU_ITEM,
                HelpItem::class.java,
            ) ?: return
        when (selectedItem.action) {
            is OpenUrl -> actionsDispatcher.onOpenUrl(selectedItem.action.url)
            is OpenUrlResource -> actionsDispatcher.onOpenUrlResource(selectedItem.action.urlResourceId)
            Rate -> actionsDispatcher.onRate()
            SendReport -> actionsDispatcher.onSendReport()
            null -> {
                // there's no action so check if the selected item has children to show
                val children = childHelpMenuItems.filter { it.parentId == selectedItem.id }
                if (children.isNotEmpty()) {
                    newHelpPage(children.toTypedArray())
                }
            }
        }
    }

    private fun newHelpPage(items: Array<HelpItem>) {
        val menuPage =
            HelpPageFragment().apply {
                arguments = Bundle().apply { putParcelableArray(ARG_MENU_ITEMS, items) }
            }
        childFragmentManager.commit {
            replace(R.id.fragment_container, menuPage, PAGE_TAG).addToBackStack(null)
        }
    }

    companion object {
        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        const val ARG_MENU_TITLE = "arg_menu_title"
        private const val PAGE_TAG = "HelpMenuPage"
    }
}

@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
internal fun Fragment.requireArgsHelpEntries(): Array<HelpItem> {
    requireArguments().classLoader = javaClass.classLoader
    val retrievedItems =
        BundleCompat.getParcelableArray(
            requireArguments(),
            ARG_MENU_ITEMS,
            HelpItem::class.java,
        ) ?: error("Unable to retrieve current help menu items")
    return retrievedItems.map { it as HelpItem }.toTypedArray()
}

internal const val ARG_MENU_ITEMS = "arg_menu_items"
internal const val REQUEST_HELP_PAGE = "request_help_page"
internal const val ARG_SELECTED_MENU_ITEM = " selected_menu_item"

/**
 * This fragment is responsible for showing a list of menu items in the application's [HelpDialog].
 */
class HelpPageFragment : Fragment(R.layout.fragment_help_page) {
    private val binding by viewBinding(FragmentHelpPageBinding::bind)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        requireArgsHelpEntries().forEach { menuItem ->
            val contentRow = ItemHelpEntryBinding.inflate(layoutInflater, binding.pageContent, false).root
            contentRow.apply {
                setText(menuItem.titleResId)
                setCompoundDrawablesRelativeWithIntrinsicBoundsKt(start = menuItem.iconResId)
                compoundDrawablePadding = 16.dp.toPx(requireContext())
                setOnClickListener {
                    UsageAnalytics.sendAnalyticsEvent(Category.LINK_CLICKED, menuItem.analyticsId)
                    parentFragmentManager.setFragmentResult(
                        REQUEST_HELP_PAGE,
                        Bundle().apply { putParcelable(ARG_SELECTED_MENU_ITEM, menuItem) },
                    )
                }
                binding.pageContent.addView(this)
            }
        }
    }
}

/**
 * The top level menu items being shown in the help menu.
 * The help menu is a two level menu and for simplicity and ease of handling the corresponding menu
 * items are split in two arrays: this one which shows the main items and [childHelpMenuItems] which
 * groups all the children of the menu items from this array.
 */
@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
internal val mainHelpMenuItems =
    arrayOf(
        HelpItem(
            titleResId = R.string.help_title_get_help,
            iconResId = R.drawable.ic_help_black_24dp,
            analyticsId = Actions.OPENED_GET_HELP,
            id = 2,
        ),
        HelpItem(
            titleResId = R.string.help_title_community,
            iconResId = R.drawable.ic_people_black_24dp,
            analyticsId = Actions.OPENED_COMMUNITY,
            id = 3,
        ),
    )

/** This array contains all the children of the top level menu items from the help menu. */
@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
internal val childHelpMenuItems =
    arrayOf(
        HelpItem(
            titleResId = R.string.help_item_mailing_list,
            iconResId = R.drawable.ic_email_black_24dp,
            analyticsId = Actions.OPENED_MAILING_LIST,
            id = 200,
            parentId = 2,
            action = OpenUrlResource(R.string.link_forum),
        ),
        HelpItem(
            titleResId = R.string.help_item_report_bug,
            iconResId = R.drawable.ic_bug_report_black_24dp,
            analyticsId = Actions.OPENED_REPORT_BUG,
            id = 201,
            parentId = 2,
            action = OpenUrl(AnkiDroidApp.feedbackUrl),
        ),
        HelpItem(
            titleResId = R.string.help_title_send_exception,
            iconResId = R.drawable.ic_round_assignment_24,
            analyticsId = Actions.EXCEPTION_REPORT,
            id = 202,
            parentId = 2,
            action = SendReport,
        ),
        HelpItem(
            titleResId = R.string.help_item_mailing_list,
            iconResId = R.drawable.ic_email_black_24dp,
            analyticsId = Actions.OPENED_MAILING_LIST,
            id = 301,
            parentId = 3,
            action = OpenUrlResource(R.string.link_forum),
        ),
        HelpItem(
            titleResId = R.string.help_item_reddit,
            iconResId = R.drawable.ic_link,
            analyticsId = Actions.OPENED_REDDIT,
            id = 302,
            parentId = 3,
            action = OpenUrlResource(R.string.link_reddit),
        ),
        HelpItem(
            titleResId = R.string.help_item_discord,
            iconResId = R.drawable.ic_link,
            analyticsId = Actions.OPENED_DISCORD,
            id = 303,
            parentId = 3,
            action = OpenUrlResource(R.string.link_discord),
        ),
        HelpItem(
            titleResId = R.string.help_item_facebook,
            iconResId = R.drawable.ic_link,
            analyticsId = Actions.OPENED_FACEBOOK,
            id = 304,
            parentId = 3,
            action = OpenUrlResource(R.string.link_facebook),
        ),
        HelpItem(
            titleResId = R.string.help_item_twitter,
            iconResId = R.drawable.ic_link,
            analyticsId = Actions.OPENED_TWITTER,
            id = 305,
            parentId = 3,
            action = OpenUrlResource(R.string.link_twitter),
        ),
    )
