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

import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import anki.import_export.ImportAnkiPackageOptions
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.web.HttpFetcher
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.greaterThan
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.test.assertFailsWith
import kotlin.test.fail

/**
 * Proves the SpeedyCAT full-deck backfill pipeline (download → store → import)
 * end-to-end **without any real hosting**: a tiny [HttpServer] on localhost
 * serves the bundled starter `.apkg` as the stand-in "full" payload, and the
 * production [SpeedyCatFullDeck.importIfNeeded] downloads it with the real
 * OkHttp stack and imports it through the real backend.
 *
 * Uses [CollectionStorageMode.ON_DISK] so the backend has a real media folder:
 * `.apkg` import performs media operations, which the in-memory collections
 * don't configure (they open the backend with `:memory:` and no media folder).
 */
@RunWith(AndroidJUnit4::class)
class SpeedyCatFullDeckTest : RobolectricTest() {
    override fun getCollectionStorageMode() = CollectionStorageMode.ON_DISK

    private lateinit var server: HttpServer
    private lateinit var serverExecutor: ExecutorService

    /** Bytes served as the "full" deck; the bundled starter package reused as a fixture. */
    private lateinit var payload: ByteArray

    /** Toggles HTTP Range support so the resume path can be exercised. */
    @Volatile
    private var supportRange = false

    @Before
    fun setUpDownloadServer() {
        // A fresh install has not imported the full deck yet.
        targetContext.sharedPrefs().edit { remove(SpeedyCatFullDeck.IMPORTED_PREF_KEY) }

        payload = targetContext.assets.open(SpeedyCatBundledDeck.ASSET_PATH).use { it.readBytes() }

        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/full.apkg", ::servePayload)
        server.createContext("/missing.apkg") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        serverExecutor = Executors.newSingleThreadExecutor()
        server.executor = serverExecutor
        server.start()
    }

    @After
    fun tearDownDownloadServer() {
        server.stop(0)
        serverExecutor.shutdownNow()
    }

    @Test
    fun downloadsStoresAndImportsThenSetsFlag() =
        runBlocking {
            val prefs = targetContext.sharedPrefs()
            val notesBefore = col.noteCount()

            val result =
                SpeedyCatFullDeck.importIfNeeded(
                    context = targetContext,
                    url = serverUrl("full.apkg"),
                    prefs = prefs,
                    expectedSizeBytes = payload.size.toLong(),
                    expectedSha256 = sha256Hex(payload),
                    isOnline = { true },
                    isMetered = { false },
                    // Real backend import into the collection under test.
                    importer = { path ->
                        col.importAnkiPackage(
                            path,
                            ImportAnkiPackageOptions.newBuilder().setWithScheduling(false).build(),
                        )
                    },
                )

            assertThat(result, equalTo(SpeedyCatFullDeck.Result.IMPORTED))
            assertThat(
                "notes from the downloaded package are imported into the collection",
                col.noteCount(),
                greaterThan(notesBefore),
            )
            assertThat(
                "the flag is only set after a successful import",
                prefs.getBoolean(SpeedyCatFullDeck.IMPORTED_PREF_KEY, false),
                equalTo(true),
            )
            assertThat(
                "temporary download files are cleaned up after a successful import",
                File(targetContext.cacheDir, "speedycat").listFiles()?.size ?: 0,
                equalTo(0),
            )
        }

    @Test
    fun secondRunIsANoOpAndDoesNotDownload() =
        runBlocking {
            val prefs = targetContext.sharedPrefs()
            prefs.edit { putBoolean(SpeedyCatFullDeck.IMPORTED_PREF_KEY, true) }

            var interactions = 0
            val result =
                SpeedyCatFullDeck.importIfNeeded(
                    context = targetContext,
                    url = serverUrl("full.apkg"),
                    prefs = prefs,
                    isOnline = { true },
                    isMetered = { false },
                    downloader = { _, _, _, _ -> interactions++ },
                    importer = { interactions++ },
                )

            assertThat(result, equalTo(SpeedyCatFullDeck.Result.ALREADY_IMPORTED))
            assertThat("neither download nor import runs once imported", interactions, equalTo(0))
        }

    @Test
    fun blankUrlDisablesTheFeature() =
        runBlocking {
            var downloads = 0
            val result =
                SpeedyCatFullDeck.importIfNeeded(
                    context = targetContext,
                    url = null,
                    prefs = targetContext.sharedPrefs(),
                    downloader = { _, _, _, _ -> downloads++ },
                )

            assertThat(result, equalTo(SpeedyCatFullDeck.Result.NOT_CONFIGURED))
            assertThat(downloads, equalTo(0))
        }

    @Test
    fun offlineDefersWithoutSettingFlag() =
        runBlocking {
            val prefs = targetContext.sharedPrefs()
            var downloads = 0
            val result =
                SpeedyCatFullDeck.importIfNeeded(
                    context = targetContext,
                    url = serverUrl("full.apkg"),
                    prefs = prefs,
                    isOnline = { false },
                    downloader = { _, _, _, _ -> downloads++ },
                )

            assertThat(result, equalTo(SpeedyCatFullDeck.Result.OFFLINE))
            assertThat("no download is attempted while offline", downloads, equalTo(0))
            assertThat(prefs.getBoolean(SpeedyCatFullDeck.IMPORTED_PREF_KEY, false), equalTo(false))
        }

    @Test
    fun meteredConnectionDefersLargeDownload() =
        runBlocking {
            val prefs = targetContext.sharedPrefs()
            var downloads = 0
            val result =
                SpeedyCatFullDeck.importIfNeeded(
                    context = targetContext,
                    url = serverUrl("full.apkg"),
                    prefs = prefs,
                    allowMetered = false,
                    isOnline = { true },
                    isMetered = { true },
                    downloader = { _, _, _, _ -> downloads++ },
                )

            assertThat(result, equalTo(SpeedyCatFullDeck.Result.SKIPPED_METERED))
            assertThat("the ~385 MB download is not started on a metered link", downloads, equalTo(0))
            assertThat(prefs.getBoolean(SpeedyCatFullDeck.IMPORTED_PREF_KEY, false), equalTo(false))
        }

    @Test
    fun simulatedDownloadFailureDoesNotSetFlag() =
        runBlocking {
            val prefs = targetContext.sharedPrefs()
            val result =
                SpeedyCatFullDeck.importIfNeeded(
                    context = targetContext,
                    url = serverUrl("full.apkg"),
                    prefs = prefs,
                    isOnline = { true },
                    isMetered = { false },
                    downloader = { _, _, _, _ -> throw IOException("simulated network failure") },
                    importer = { fail("import must not run when the download fails") },
                )

            assertThat(result, equalTo(SpeedyCatFullDeck.Result.FAILED))
            assertThat(
                "a failed download must not set the flag, so it retries next launch",
                prefs.getBoolean(SpeedyCatFullDeck.IMPORTED_PREF_KEY, false),
                equalTo(false),
            )
        }

    @Test
    fun httpErrorResponseIsHandledAndFlagStaysUnset() =
        runBlocking {
            val prefs = targetContext.sharedPrefs()
            val result =
                SpeedyCatFullDeck.importIfNeeded(
                    context = targetContext,
                    url = serverUrl("missing.apkg"),
                    prefs = prefs,
                    isOnline = { true },
                    isMetered = { false },
                    importer = { fail("import must not run when the server returns an error") },
                )

            assertThat(result, equalTo(SpeedyCatFullDeck.Result.FAILED))
            assertThat(prefs.getBoolean(SpeedyCatFullDeck.IMPORTED_PREF_KEY, false), equalTo(false))
        }

    @Test
    fun resumesPartialDownloadViaRangeRequest() {
        supportRange = true
        val dest = File(tempFolder.newFolder(), "resume.apkg.part")
        val half = payload.size / 2
        dest.writeBytes(payload.copyOfRange(0, half))

        SpeedyCatFullDeck.downloadWithResume(
            client = httpClient(),
            url = serverUrl("full.apkg"),
            dest = dest,
            expectedSizeBytes = payload.size.toLong(),
            expectedSha256 = sha256Hex(payload),
        )

        assertThat("the partial download is continued, not restarted", dest.length(), equalTo(payload.size.toLong()))
        assertThat("the resumed file matches the full payload", dest.readBytes().contentEquals(payload), equalTo(true))
    }

    @Test
    fun checksumMismatchDiscardsTheDownload() {
        val dest = File(tempFolder.newFolder(), "corrupt.apkg.part")
        val wrongChecksum = "0".repeat(64)

        assertFailsWith<IOException> {
            SpeedyCatFullDeck.downloadWithResume(
                client = httpClient(),
                url = serverUrl("full.apkg"),
                dest = dest,
                expectedSizeBytes = payload.size.toLong(),
                expectedSha256 = wrongChecksum,
            )
        }

        assertThat(
            "a checksum-corrupt download is deleted so the next attempt re-fetches cleanly",
            dest.exists(),
            equalTo(false),
        )
    }

    private fun serverUrl(path: String) = "http://127.0.0.1:${server.address.port}/$path"

    private fun httpClient() = HttpFetcher.getOkHttpBuilder(false).build()

    private fun servePayload(exchange: HttpExchange) {
        try {
            val range = exchange.requestHeaders.getFirst("Range")
            if (supportRange && range != null) {
                val start = range.removePrefix("bytes=").substringBefore("-").toIntOrNull() ?: 0
                if (start >= payload.size) {
                    exchange.sendResponseHeaders(416, -1)
                    exchange.close()
                    return
                }
                val slice = payload.copyOfRange(start, payload.size)
                exchange.responseHeaders.add("Content-Range", "bytes $start-${payload.size - 1}/${payload.size}")
                exchange.sendResponseHeaders(206, slice.size.toLong())
                exchange.responseBody.use { it.write(slice) }
            } else {
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
        } catch (e: Exception) {
            Timber.e(e, "test download server failed to serve payload")
            runCatching { exchange.close() }
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
