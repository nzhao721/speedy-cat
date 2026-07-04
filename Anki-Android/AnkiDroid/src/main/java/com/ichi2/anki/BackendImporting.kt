// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: Copyright (c) 2022 Ankitects Pty Ltd <http://apps.ankiweb.net>

package com.ichi2.anki

import android.net.Uri
import androidx.fragment.app.FragmentActivity
import anki.collection.OpChangesOnly
import anki.import_export.ImportAnkiPackageRequest
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.libanki.importCsvRaw
import com.ichi2.anki.observability.undoableOp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun importAnkiPackageUndoable(input: ByteArray): ByteArray {
    val request = ImportAnkiPackageRequest.parseFrom(input)
    val path = Uri.encode(request.packagePath, "/")
    return withContext(Dispatchers.Main) {
        val output = withCol { importAnkiPackage(path, request.options) }
        undoableOp { output.changes }
        output.toByteArray()
    }
}

suspend fun importCsvRaw(input: ByteArray): ByteArray =
    withContext(Dispatchers.Main) {
        val output = withCol { importCsvRaw(input) }
        val changes = OpChangesOnly.parseFrom(output)
        undoableOp { changes }
        output
    }

/** Import-page hook: browse was removed; accept the request and no-op. */
suspend fun FragmentActivity.searchInBrowser(input: ByteArray): ByteArray = input
