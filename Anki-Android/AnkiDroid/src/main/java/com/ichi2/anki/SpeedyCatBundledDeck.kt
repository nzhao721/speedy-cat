// SPDX-License-Identifier: GPL-3.0-or-later
//
// SpeedyCAT (MCAT study app) is a brownfield fork of AnkiDroid.
// AnkiDroid is licensed AGPL-3.0-or-later; Anki is created by Damien Elmes and
// the Anki contributors. This file preserves that attribution.
//
// The bundled deck (assets/speedycat/speedycat-mcat.apkg) is built from the
// text cards of the free, redistributable community MCAT decks
// "Mr. Pankow P/S" (Psychology/Sociology) and "MileDown MCAT", used with
// attribution. See docs and the parent deck description shown in-app.

package com.ichi2.anki

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import anki.import_export.ImportAnkiPackageOptions
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.common.preferences.sharedPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException

/**
 * Ships the SpeedyCAT MCAT flashcard set inside the APK and imports it
 * automatically, silently, exactly once on first run.
 *
 * There is intentionally **no** download/import prompt: a fresh install already
 * contains the deck. The import is idempotent via the
 * [IMPORTED_PREF_KEY] preference; on top of that, Anki's `.apkg` import matches
 * notes by GUID, so even a repeated import would not create duplicates.
 *
 * The full image-rich MileDown + Mr. Pankow decks are ~385 MB (mostly media)
 * and are not shippable inside an APK; this bundles the text-only cards
 * (~1,074, predominantly Psychology/Sociology). The complete decks remain
 * available on desktop and propagate to mobile via normal Anki sync.
 */
object SpeedyCatBundledDeck {
    /** Path of the bundled package within `src/main/assets`. */
    const val ASSET_PATH = "speedycat/speedycat-mcat.apkg"

    /** Preference flag recording that the one-time import has succeeded. */
    const val IMPORTED_PREF_KEY = "speedycatDeckImported"

    private const val CACHE_FILENAME = "speedycat-mcat.apkg"

    enum class Result {
        /** The bundled deck was imported during this call. */
        IMPORTED,

        /** Import already happened on a previous launch; nothing to do. */
        ALREADY_IMPORTED,

        /** No bundled deck asset is present in the APK; nothing to do. */
        ASSET_MISSING,

        /** Import was attempted but failed; it will be retried on next launch. */
        FAILED,
    }

    /**
     * Imports the bundled deck if it has not been imported yet.
     *
     * Safe to call on every startup: it is a cheap preference check once the
     * deck is present. The actual import runs on the collection/IO threads, so
     * this must be called from a coroutine (e.g. `launchCatchingTask`), never
     * blocking the main thread.
     *
     * @param importer injected for testing; defaults to the real backend import.
     */
    suspend fun importIfNeeded(
        context: Context,
        prefs: SharedPreferences = context.sharedPrefs(),
        importer: suspend (packagePath: String) -> Unit = ::defaultImport,
    ): Result {
        if (prefs.getBoolean(IMPORTED_PREF_KEY, false)) {
            Timber.d("SpeedyCAT bundled deck already imported; skipping")
            return Result.ALREADY_IMPORTED
        }

        val cacheFile = File(context.cacheDir, CACHE_FILENAME)
        try {
            if (!withContext(Dispatchers.IO) { copyAssetToCache(context, cacheFile) }) {
                return Result.ASSET_MISSING
            }
            importer(cacheFile.absolutePath)
            prefs.edit { putBoolean(IMPORTED_PREF_KEY, true) }
            Timber.i("SpeedyCAT bundled deck imported from assets")
            return Result.IMPORTED
        } catch (e: Exception) {
            // Don't set the flag: a transient failure should be retried next launch.
            Timber.w(e, "SpeedyCAT bundled deck import failed; will retry on next startup")
            return Result.FAILED
        } finally {
            if (cacheFile.exists()) {
                runCatching { cacheFile.delete() }
            }
        }
    }

    /** @return true if the asset existed and was copied to [dest]. */
    private fun copyAssetToCache(
        context: Context,
        dest: File,
    ): Boolean =
        try {
            context.assets.open(ASSET_PATH).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            true
        } catch (e: FileNotFoundException) {
            Timber.i(e, "SpeedyCAT bundled deck asset not present (%s); skipping import", ASSET_PATH)
            false
        }

    private suspend fun defaultImport(packagePath: String) {
        withCol {
            importAnkiPackage(
                packagePath,
                ImportAnkiPackageOptions.newBuilder().setWithScheduling(false).build(),
            )
        }
    }
}
