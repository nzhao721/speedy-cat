// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors
//
// Local persistence for practice/full-length attempts. This is a small,
// dedicated SQLite database that is deliberately SEPARATE from the synced Anki
// collection: MCAT attempt history is device-local study telemetry, not
// flashcard review data, so it never touches sync. It mirrors the columns of the
// desktop `practice_attempts` table so the same missed-only and per-topic
// tracking queries apply.

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
 * A row is keyed by [Attempt.id] (`"{sessionId|attemptId}:{questionId}"`) with
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
                full_length_attempt_id text,
                question_id text not null,
                selected_answer text not null,
                correct integer not null,
                time_seconds integer not null,
                section text not null,
                topic text not null,
                answered_at integer not null
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
        val values =
            ContentValues().apply {
                put("id", attempt.id)
                put("session_id", attempt.sessionId)
                put("full_length_attempt_id", attempt.fullLengthAttemptId)
                put("question_id", attempt.questionId)
                put("selected_answer", attempt.selectedAnswer)
                put("correct", if (attempt.correct) 1 else 0)
                put("time_seconds", attempt.timeSeconds)
                put("section", attempt.section?.dbCode ?: "")
                put("topic", attempt.topic)
                put("answered_at", attempt.answeredAt)
            }
        writableDatabase.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
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
                val flIdx = c.getColumnIndexOrThrow("full_length_attempt_id")
                val questionIdx = c.getColumnIndexOrThrow("question_id")
                val selectedIdx = c.getColumnIndexOrThrow("selected_answer")
                val correctIdx = c.getColumnIndexOrThrow("correct")
                val timeIdx = c.getColumnIndexOrThrow("time_seconds")
                val sectionIdx = c.getColumnIndexOrThrow("section")
                val topicIdx = c.getColumnIndexOrThrow("topic")
                val answeredIdx = c.getColumnIndexOrThrow("answered_at")
                while (c.moveToNext()) {
                    out.add(
                        Attempt(
                            id = c.getString(idIdx),
                            sessionId = c.stringOrNull(sessionIdx),
                            fullLengthAttemptId = c.stringOrNull(flIdx),
                            questionId = c.getString(questionIdx),
                            selectedAnswer = c.getString(selectedIdx),
                            correct = c.getInt(correctIdx) != 0,
                            timeSeconds = c.getInt(timeIdx),
                            section = McatSection.fromDb(c.getString(sectionIdx)),
                            topic = c.getString(topicIdx),
                            answeredAt = c.getLong(answeredIdx),
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
        private const val DATABASE_VERSION = 1
        private const val TABLE = "practice_attempts"
    }
}
