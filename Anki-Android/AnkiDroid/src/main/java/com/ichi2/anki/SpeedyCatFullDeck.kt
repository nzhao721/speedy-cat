// SPDX-License-Identifier: GPL-3.0-or-later
//
// SpeedyCAT (MCAT study app) is a brownfield fork of AnkiDroid.
// AnkiDroid is licensed AGPL-3.0-or-later; Anki is created by Damien Elmes and
// the Anki contributors. This file preserves that attribution.
//
// This composes with [SpeedyCatBundledDeck]: the small text-only starter deck
// ships inside the APK and imports instantly on first run, while this backfills
// the full image-rich MileDown + Mr. Pankow set from a remote URL in the
// background. Both source decks are the free, redistributable community MCAT
// decks used with attribution (see [SpeedyCatBundledDeck]).

package com.ichi2.anki

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import anki.import_export.ImportAnkiPackageOptions
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.web.HttpFetcher
import com.ichi2.utils.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

/**
 * Downloads the **full** SpeedyCAT MCAT deck set once, in the background, and
 * merges it into the collection.
 *
 * The bundled starter deck ([SpeedyCatBundledDeck]) gives instant offline
 * content on install; this backfills the complete, image-rich set afterwards.
 * There is intentionally **no** prompt — the download is scheduled silently by
 * [com.ichi2.anki.worker.SpeedyCatFullDeckWorker] and gated on an unmetered
 * connection by default (see that worker).
 *
 * Design notes:
 *  - **Idempotent**: [IMPORTED_PREF_KEY] is set *only* after a successful
 *    import, so any offline/download/import failure simply retries on a later
 *    launch. On top of that, Anki's `.apkg` import matches notes by GUID, so a
 *    repeated import (or the overlap with the bundled starter cards) never
 *    creates duplicates.
 *  - **Resumable**: the download streams to a `.part` file and continues with an
 *    HTTP `Range` request if a partial file is already present, so a dropped
 *    ~385 MB transfer is not restarted from scratch.
 *  - **Verified**: an optional expected size and SHA-256 are checked before the
 *    package is imported; a corrupt payload is discarded and re-fetched.
 *  - **Merge, don't replace**: it uses `importAnkiPackage` (a `.apkg` deck
 *    package that merges into the existing "SpeedyCAT MCAT" deck tree), *not* a
 *    `.colpkg` (which would replace the whole collection).
 */
object SpeedyCatFullDeck {
    /**
     * Remote location of the **combined** full SpeedyCAT MCAT deck package.
     *
     * TODO(speedycat): set this to the real hosted CDN URL before shipping.
     *
     * While this is `null`/blank the background download is disabled and only
     * the bundled starter deck is imported — this must NOT be pointed at a
     * placeholder/fake production URL.
     *
     * The full set is the two source packages that live (gitignored) in the
     * repo at `content/flashcards/_packages`: `MileDown.apkg` (~227 MB) and
     * `Pankow.apkg` (~150 MB) — ~385 MB total, mostly media. Combine them into
     * **one** `.apkg` on desktop Anki (import both into a profile, then
     * File → Export → "Anki Deck Package (.apkg)" with media included and
     * scheduling excluded, keeping the decks under the same "SpeedyCAT MCAT"
     * parent) and host that single file at this URL. A `.apkg` is required
     * because [Collection.importAnkiPackage] merges notes by GUID into the
     * existing collection, whereas a `.colpkg` would replace it entirely.
     */
    val SPEEDYCAT_FULL_DECK_URL: String? = null

    /**
     * Expected size of the download in bytes, or `0` to skip the size check.
     *
     * When known (`> 0`) it is used both to reject a truncated/oversized
     * download and to recognise an already-complete `.part` file so the fetch
     * can be skipped. TODO(speedycat): set alongside [SPEEDYCAT_FULL_DECK_URL].
     */
    const val SPEEDYCAT_FULL_DECK_SIZE_BYTES: Long = 0L

    /**
     * Lowercase hex SHA-256 of the hosted package, or `null`/blank to skip the
     * integrity check. Strongly recommended for a large CDN download.
     * TODO(speedycat): set alongside [SPEEDYCAT_FULL_DECK_URL].
     */
    val SPEEDYCAT_FULL_DECK_SHA256: String? = null

    /** Preference flag recording that the full-deck import has succeeded. */
    const val IMPORTED_PREF_KEY = "speedycatFullDeckImported"

    private const val DOWNLOAD_DIR = "speedycat"
    private const val PART_FILENAME = "speedycat-full.apkg.part"
    private const val PACKAGE_FILENAME = "speedycat-full.apkg"
    private const val BUFFER_SIZE = 64 * 1024

    /** HTTP 206 Partial Content — the server honoured our resume `Range`. */
    private const val HTTP_PARTIAL_CONTENT = 206

    /** HTTP 416 Range Not Satisfiable — we already hold the whole file. */
    private const val HTTP_RANGE_NOT_SATISFIABLE = 416

    enum class Result {
        /** The full deck was downloaded and imported during this call. */
        IMPORTED,

        /** Import already succeeded on a previous launch; nothing to do. */
        ALREADY_IMPORTED,

        /** [SPEEDYCAT_FULL_DECK_URL] is not configured; feature disabled. */
        NOT_CONFIGURED,

        /** No usable network; retry on a later launch. */
        OFFLINE,

        /** Connection is metered and metered downloads are disallowed; retry later. */
        SKIPPED_METERED,

        /** Download or import failed; the flag is left unset so it is retried. */
        FAILED,
    }

    /**
     * Downloads + imports the full deck if it hasn't been imported yet.
     *
     * Safe to call on every launch: it is a cheap preference check once done.
     * The download runs on [Dispatchers.IO]; the import runs on the collection
     * thread via [withCol]. Never call this on the main thread.
     *
     * All collaborators are injectable for testing; production callers use the
     * defaults (real network + backend import).
     *
     * @param allowMetered when false (default) the download is skipped on a
     *   metered connection. The scheduling worker also enforces this via a
     *   WorkManager `UNMETERED` constraint; the in-code check fails fast and
     *   keeps the logic testable.
     */
    suspend fun importIfNeeded(
        context: Context,
        url: String? = SPEEDYCAT_FULL_DECK_URL,
        prefs: SharedPreferences = context.sharedPrefs(),
        expectedSizeBytes: Long = SPEEDYCAT_FULL_DECK_SIZE_BYTES,
        expectedSha256: String? = SPEEDYCAT_FULL_DECK_SHA256,
        allowMetered: Boolean = false,
        isOnline: () -> Boolean = { NetworkUtils.isOnline },
        isMetered: () -> Boolean = { NetworkUtils.isActiveNetworkMetered() },
        downloader: suspend (url: String, dest: File, expectedSizeBytes: Long, expectedSha256: String?) -> Unit =
            ::defaultDownload,
        importer: suspend (packagePath: String) -> Unit = ::defaultImport,
    ): Result {
        if (prefs.getBoolean(IMPORTED_PREF_KEY, false)) {
            Timber.d("SpeedyCAT full deck already imported; skipping")
            return Result.ALREADY_IMPORTED
        }
        if (url.isNullOrBlank()) {
            Timber.i("SpeedyCAT full deck URL not configured; skipping background download")
            return Result.NOT_CONFIGURED
        }
        if (!isOnline()) {
            Timber.i("SpeedyCAT full deck: offline; will retry on a later launch")
            return Result.OFFLINE
        }
        if (!allowMetered && isMetered()) {
            Timber.i("SpeedyCAT full deck: metered connection; deferring large download")
            return Result.SKIPPED_METERED
        }

        val downloadDir = File(context.cacheDir, DOWNLOAD_DIR).apply { mkdirs() }
        val partFile = File(downloadDir, PART_FILENAME)
        val packageFile = File(downloadDir, PACKAGE_FILENAME)
        try {
            withContext(Dispatchers.IO) {
                // Downloads/resumes into partFile and returns only once it is a
                // complete, verified package. Rename (not copy) so we never need
                // 2x the space on a storage-constrained device.
                downloader(url, partFile, expectedSizeBytes, expectedSha256)
                if (packageFile.exists()) packageFile.delete()
                if (!partFile.renameTo(packageFile)) {
                    // Fallback across filesystems: copy then drop the source.
                    partFile.copyTo(packageFile, overwrite = true)
                    partFile.delete()
                }
            }
            importer(packageFile.absolutePath)
            prefs.edit { putBoolean(IMPORTED_PREF_KEY, true) }
            Timber.i("SpeedyCAT full deck imported")
            return Result.IMPORTED
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            // Leave the flag unset so the whole flow retries on a later launch.
            Timber.w(e, "SpeedyCAT full deck download/import failed; will retry")
            return Result.FAILED
        } finally {
            // The fully-materialised package is always disposable. The .part file
            // is only removed once we've succeeded; otherwise it is kept so the
            // next attempt can resume the partial download.
            runCatching { if (packageFile.exists()) packageFile.delete() }
            if (prefs.getBoolean(IMPORTED_PREF_KEY, false)) {
                runCatching { if (partFile.exists()) partFile.delete() }
            }
        }
    }

    private suspend fun defaultDownload(
        url: String,
        dest: File,
        expectedSizeBytes: Long,
        expectedSha256: String?,
    ) = downloadWithResume(HttpFetcher.getOkHttpBuilder(false).build(), url, dest, expectedSizeBytes, expectedSha256)

    private suspend fun defaultImport(packagePath: String) {
        withCol {
            importAnkiPackage(
                packagePath,
                ImportAnkiPackageOptions.newBuilder().setWithScheduling(false).build(),
            )
        }
    }

    /**
     * Streams [url] into [dest], resuming a partial file via an HTTP `Range`
     * request, and verifies the result. On normal return [dest] is a complete,
     * size/checksum-verified package; any problem throws (so the caller retries
     * later). A checksum-corrupt file is deleted to force a clean re-download.
     *
     * Visible for tests, which point [url] at a local server.
     */
    @VisibleForTesting
    fun downloadWithResume(
        client: OkHttpClient,
        url: String,
        dest: File,
        expectedSizeBytes: Long,
        expectedSha256: String?,
    ) {
        dest.parentFile?.mkdirs()

        // Fast path: a previous run already fetched the whole file (e.g. the
        // download finished but the import then failed) — skip the network.
        if (expectedSizeBytes > 0 && dest.exists()) {
            when {
                dest.length() == expectedSizeBytes -> {
                    if (expectedSha256.isNullOrBlank() || sha256Hex(dest).equals(expectedSha256, ignoreCase = true)) {
                        Timber.i("SpeedyCAT full deck already fully downloaded; skipping fetch")
                        return
                    }
                    Timber.w("SpeedyCAT full deck partial file failed checksum; re-downloading")
                    dest.delete()
                }
                dest.length() > expectedSizeBytes -> {
                    Timber.w("SpeedyCAT full deck partial file larger than expected; re-downloading")
                    dest.delete()
                }
            }
        }

        val existing = if (dest.exists()) dest.length() else 0L
        val requestBuilder = Request.Builder().url(url).get()
        if (existing > 0) {
            Timber.i("Resuming SpeedyCAT full deck download from %d bytes", existing)
            requestBuilder.header("Range", "bytes=$existing-")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            when {
                response.code == HTTP_RANGE_NOT_SATISFIABLE -> {
                    // Server says our range starts at/after EOF: we already have
                    // the whole file. Fall through to verification below.
                    Timber.i("SpeedyCAT full deck: server reports download already complete")
                }
                existing > 0 && response.code == HTTP_PARTIAL_CONTENT -> {
                    writeBody(response.body.byteStream(), dest, append = true)
                }
                response.isSuccessful -> {
                    // 200 OK: a fresh download, or the server ignored our Range
                    // header — either way, (re)write from the start.
                    writeBody(response.body.byteStream(), dest, append = false)
                }
                else -> throw IOException("Unexpected HTTP ${response.code} downloading SpeedyCAT full deck")
            }
        }

        if (expectedSizeBytes > 0 && dest.length() != expectedSizeBytes) {
            throw IOException("SpeedyCAT full deck size mismatch: expected $expectedSizeBytes, got ${dest.length()}")
        }
        if (!expectedSha256.isNullOrBlank()) {
            val actual = sha256Hex(dest)
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                dest.delete()
                throw IOException("SpeedyCAT full deck checksum mismatch")
            }
        }
    }

    private fun writeBody(
        input: java.io.InputStream,
        dest: File,
        append: Boolean,
    ) {
        input.use { source ->
            FileOutputStream(dest, append).use { output ->
                source.copyTo(output, BUFFER_SIZE)
            }
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
