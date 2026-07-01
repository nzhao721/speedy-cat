/*
 *  Copyright (c) 2026 SpeedyCAT contributors
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

package com.ichi2.anki

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.testutils.EmptyApplication
import kotlinx.coroutines.runBlocking
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.greaterThan
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.zip.ZipInputStream

/**
 * Regression tests for the silent, idempotent first-run import of the bundled
 * SpeedyCAT MCAT deck. The backend import itself is exercised on-device; these
 * tests pin the once-only behaviour and that the deck asset actually ships.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class)
@Category(EmptyApplicationCategory::class)
class SpeedyCatBundledDeckTest : RobolectricTest() {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clearFlag() {
        context.sharedPrefs().edit { remove(SpeedyCatBundledDeck.IMPORTED_PREF_KEY) }
    }

    @Test
    fun importsOnceAndMarksFlag() =
        runBlocking {
            val prefs = context.sharedPrefs()
            var calls = 0
            val result = SpeedyCatBundledDeck.importIfNeeded(context, prefs) { calls += 1 }

            assertThat(result, equalTo(SpeedyCatBundledDeck.Result.IMPORTED))
            assertThat("import is invoked exactly once", calls, equalTo(1))
            assertThat(
                "flag is persisted after a successful import",
                prefs.getBoolean(SpeedyCatBundledDeck.IMPORTED_PREF_KEY, false),
                equalTo(true),
            )
        }

    @Test
    fun secondCallIsANoOp() =
        runBlocking {
            val prefs = context.sharedPrefs()
            prefs.edit { putBoolean(SpeedyCatBundledDeck.IMPORTED_PREF_KEY, true) }

            var calls = 0
            val result = SpeedyCatBundledDeck.importIfNeeded(context, prefs) { calls += 1 }

            assertThat(result, equalTo(SpeedyCatBundledDeck.Result.ALREADY_IMPORTED))
            assertThat("import is not re-run once the flag is set", calls, equalTo(0))
        }

    @Test
    fun failedImportIsNotMarkedSoItRetries() =
        runBlocking {
            val prefs = context.sharedPrefs()
            val result =
                SpeedyCatBundledDeck.importIfNeeded(context, prefs) { error("simulated import failure") }

            assertThat(result, equalTo(SpeedyCatBundledDeck.Result.FAILED))
            assertThat(
                "a failed import must not set the flag, so it is retried next launch",
                prefs.getBoolean(SpeedyCatBundledDeck.IMPORTED_PREF_KEY, false),
                equalTo(false),
            )
        }

    @Test
    fun bundledDeckAssetIsAValidPackage() {
        val entries = mutableListOf<String>()
        var uncompressedBytes = 0L
        context.assets.open(SpeedyCatBundledDeck.ASSET_PATH).use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    entries.add(entry.name)
                    uncompressedBytes += zip.readBytes().size.toLong()
                    entry = zip.nextEntry
                }
            }
        }

        assertThat(
            "the bundled .apkg must contain an Anki collection",
            entries.any { it == "collection.anki2" || it == "collection.anki21" },
            equalTo(true),
        )
        assertThat(
            "the bundled .apkg must contain real content, not a placeholder",
            uncompressedBytes,
            greaterThan(50_000L),
        )
    }
}
