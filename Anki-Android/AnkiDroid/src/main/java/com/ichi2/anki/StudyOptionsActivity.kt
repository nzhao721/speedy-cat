/*
 * Copyright (c) 2012 Norbert Nagold <norbert.nagold@gmail.com>
 *
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.ichi2.anki

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.fragment.app.commit
import anki.collection.OpChanges
import com.ichi2.anki.observability.ChangeManager
import com.ichi2.anki.utils.ext.setFragmentResultListener
import timber.log.Timber

/**
 * Hosts [StudyOptionsFragment] when non-fragmented
 */
class StudyOptionsActivity :
    AnkiActivity(R.layout.activity_study_options),
    ChangeManager.Subscriber {
    override fun onCreate(savedInstanceState: Bundle?) {
        if (showedActivityFailedScreen(savedInstanceState)) {
            return
        }
        super.onCreate(savedInstanceState)
        enableToolbar().apply { title = "" }
        if (savedInstanceState == null) {
            loadStudyOptionsFragment()
        }
        setResult(RESULT_OK)

        setFragmentResultListener(StudyOptionsFragment.REQUEST_STUDY_OPTIONS_STUDY) { _, _ ->
            Timber.d("Opening study screen from study options screen")
            val reviewer = Reviewer.getIntent(this)
            // go back to DeckPicker after studying when not in tablet mode
            reviewer.flags = Intent.FLAG_ACTIVITY_FORWARD_RESULT
            startActivity(reviewer)
            finish()
        }
    }

    private fun loadStudyOptionsFragment() {
        val currentFragment = StudyOptionsFragment()
        supportFragmentManager.commit {
            replace(R.id.studyoptions_frame, currentFragment)
        }
    }

    private val currentFragment: StudyOptionsFragment?
        get() = supportFragmentManager.findFragmentById(R.id.studyoptions_frame) as StudyOptionsFragment?

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }
            else -> return super.onOptionsItemSelected(item)
        }
    }

    override fun opExecuted(
        changes: OpChanges,
        handler: Any?,
    ) {
        currentFragment?.refreshInterface()
    }
}
