// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors
//
// Local persistence for practice attempts. This is a small, dedicated SQLite
// database that is deliberately SEPARATE from the synced Anki collection: MCAT
// attempt history is device-local study telemetry, not flashcard review data, so
// it never touches sync. It mirrors the columns of the desktop
// `practice_attempts` table so the same missed-only and per-topic tracking
// queries apply.

package com.ichi2.anki.practice

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Stores every recorded [Attempt] locally so missed-only practice and
 * cross-session per-topic stats survive between sessions.
 *
 * A row is keyed by [Attempt.id] (`"{sessionId}:{questionId}"`) with
 * `insert or replace`, so re-answering a question replaces its prior attempt —
 * exactly like the desktop engine.
 */
class PracticeStore(
    context: Context,
) : SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            create table $TABLE (
                id text primary key not null,
                session_id text,
                question_id text not null,
                selected_answer text not null,
                correct integer not null,
                time_seconds integer not null,
                section text not null,
                topic text not null,
                answered_at integer not null,
                hint_level_used integer not null default 0,
                assisted integer not null default 0
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) {
        // Attempt history is regenerable study telemetry; a clean rebuild is fine.
        db.execSQL("drop table if exists $TABLE")
        onCreate(db)
    }

    /** Record one attempt, replacing any prior attempt for the same id. */
    fun recordAttempt(attempt: Attempt) {
        writableDatabase.insertWithOnConflict(TABLE, null, attempt.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * Bulk upsert (replace-on-conflict by id) used when ingesting attempts
     * synced from other devices. Wrapped in a single transaction; dedup by
     * primary key means re-ingesting the same rows is idempotent.
     */
    fun upsertAll(attempts: List<Attempt>) {
        if (attempts.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (attempt in attempts) {
                db.insertWithOnConflict(TABLE, null, attempt.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun Attempt.toValues(): ContentValues =
        ContentValues().apply {
            put("id", id)
            put("session_id", sessionId)
            put("question_id", questionId)
            put("selected_answer", selectedAnswer)
            put("correct", if (correct) 1 else 0)
            put("time_seconds", timeSeconds)
            put("section", section?.dbCode ?: "")
            put("topic", topic)
            put("answered_at", answeredAt)
            put("hint_level_used", hintLevelUsed)
            put("assisted", if (assisted) 1 else 0)
        }

    /** Every recorded attempt, for pure-Kotlin aggregation (see PracticeLogic). */
    fun allAttempts(): List<Attempt> {
        val out = mutableListOf<Attempt>()
        readableDatabase
            .query(
                TABLE,
                null,
                null,
                null,
                null,
                null,
                "answered_at asc",
            ).use { c ->
                val idIdx = c.getColumnIndexOrThrow("id")
                val sessionIdx = c.getColumnIndexOrThrow("session_id")
                val questionIdx = c.getColumnIndexOrThrow("question_id")
                val selectedIdx = c.getColumnIndexOrThrow("selected_answer")
                val correctIdx = c.getColumnIndexOrThrow("correct")
                val timeIdx = c.getColumnIndexOrThrow("time_seconds")
                val sectionIdx = c.getColumnIndexOrThrow("section")
                val topicIdx = c.getColumnIndexOrThrow("topic")
                val answeredIdx = c.getColumnIndexOrThrow("answered_at")
                val hintLevelIdx = c.getColumnIndexOrThrow("hint_level_used")
                val assistedIdx = c.getColumnIndexOrThrow("assisted")
                while (c.moveToNext()) {
                    out.add(
                        Attempt(
                            id = c.getString(idIdx),
                            sessionId = c.stringOrNull(sessionIdx),
                            questionId = c.getString(questionIdx),
                            selectedAnswer = c.getString(selectedIdx),
                            correct = c.getInt(correctIdx) != 0,
                            timeSeconds = c.getInt(timeIdx),
                            section = McatSection.fromDb(c.getString(sectionIdx)),
                            topic = c.getString(topicIdx),
                            answeredAt = c.getLong(answeredIdx),
                            hintLevelUsed = c.getInt(hintLevelIdx),
                            assisted = c.getInt(assistedIdx) != 0,
                        ),
                    )
                }
            }
        return out
    }

    /** Ids of questions the user previously answered incorrectly (missed-only). */
    fun missedQuestionIds(): Set<String> {
        val out = mutableSetOf<String>()
        readableDatabase
            .rawQuery("select distinct question_id from $TABLE where correct = 0", null)
            .use { c ->
                while (c.moveToNext()) {
                    out.add(c.getString(0))
                }
            }
        return out
    }

    private fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    companion object {
        private const val DATABASE_NAME = "speedycat_practice.db"
        // v2: SpeedyCAT graduated hint ladder adds hint_level_used + assisted.
        // Attempt history is regenerable telemetry, so onUpgrade rebuilds.
        private const val DATABASE_VERSION = 2
        private const val TABLE = "practice_attempts"
    }
}
