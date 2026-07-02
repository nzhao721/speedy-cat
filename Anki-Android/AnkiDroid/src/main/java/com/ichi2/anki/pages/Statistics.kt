// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: Copyright (c) 2022 Brayan Oliveira <brayandso.dev@gmail.com>

package com.ichi2.anki.pages

import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintJob
import android.print.PrintManager
import android.view.View
import androidx.core.content.ContextCompat.getSystemService
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.R
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.common.time.getTimestamp
import com.ichi2.anki.databinding.PageStatisticsBinding
import com.ichi2.anki.snackbar.showSnackbar
import dev.androidbroadcast.vbpd.viewBinding
import timber.log.Timber

class Statistics : PageFragment(R.layout.page_statistics) {
    override val pagePath: String = "graphs"
    private val binding by viewBinding(PageStatisticsBinding::bind)

    /** The PrintJob launched by this Fragment */
    // After killing the app, printManager.printJobs can still list active jobs
    private var pendingPrintJob: PrintJob? = null

    @Suppress("deprecation", "API35 properly handle edge-to-edge")
    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        // Hide the back arrow when requested (e.g. hosted in bottom nav)
        if (arguments?.getBoolean(ARG_HIDE_BACK_BUTTON) == true) {
            binding.toolbar.navigationIcon = null
        }

        // SpeedyCAT: stats always cover the whole collection for all time. The
        // deck/scope picker has been removed from the layout, and the shared
        // graphs web page exposes no deck/search/time controls, so there is no
        // section or time-frame chooser to reach here.
        binding.toolbar.title = getString(R.string.statistics)

        binding.toolbar.apply {
            menu.findItem(R.id.action_export_stats).title = CollectionManager.TR.statisticsSavePdf()
            setOnMenuItemClickListener { item ->
                if (item.itemId == R.id.action_export_stats) {
                    exportWebViewContentAsPDF()
                }
                true
            }
        }
    }

    /** Prepares and initiates a printing task for the content(stats) displayed in the WebView.
     * It uses the Android PrintManager service to create a print job, based on the content of the WebView.
     * The resulting output is a PDF document. **/
    private fun exportWebViewContentAsPDF() {
        if (pendingPrintJob?.isActive == true) {
            Timber.w("Duplicate print attempted - skipping")
            showSnackbar(R.string.already_in_progress)
            return
        }
        Timber.i("Saving Stats to PDF")
        val printManager = getSystemService(requireContext(), PrintManager::class.java) ?: return
        val currentDateTime = getTimestamp(TimeManager.time)
        val jobName = "${getString(R.string.app_name)}-stats-$currentDateTime"
        val printAdapter = webViewLayout.createPrintDocumentAdapter(jobName)
        pendingPrintJob =
            printManager.print(
                jobName,
                printAdapter,
                PrintAttributes.Builder().build(),
            )
    }

    companion object {
        const val ARG_HIDE_BACK_BUTTON = "hideBackButton"
    }
}

private val PrintJob.isActive: Boolean
    get() = !isCompleted && !isFailed && !isCancelled
