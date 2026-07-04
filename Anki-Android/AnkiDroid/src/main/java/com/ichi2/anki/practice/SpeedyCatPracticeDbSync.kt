// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors
//
// Upserts practice-session attempts into the collection's schema-19
// `practice_attempts` table so `PracticeService.GetReadiness` can aggregate
// them in Rust. Mirrors `anki/pylib/anki/speedycat_sync.py` ingest SQL.

package com.ichi2.anki.practice

import com.ichi2.anki.libanki.Collection
import timber.log.Timber

object SpeedyCatPracticeDbSync {
    /** True when the collection DB has the SpeedyCAT practice tables. */
    fun hasPracticeTable(col: Collection): Boolean =
        try {
            col.db.queryScalar(
                "select count(*) from sqlite_master where type = 'table' and name = 'practice_attempts'",
            ) > 0
        } catch (e: Exception) {
            false
        }

    /**
     * Upsert [attempts] into `practice_attempts` (session rows only). Returns
     * false when the table is missing or the write fails.
     */
    fun syncAttempts(
        col: Collection,
        attempts: List<Attempt>,
    ): Boolean {
        if (!hasPracticeTable(col)) return false
        val sessionAttempts = attempts.filter { it.sessionId != null && it.selectedAnswer.isNotEmpty() }
        if (sessionAttempts.isEmpty()) return true
        return try {
            col.db.execute("begin immediate transaction")
            for (attempt in sessionAttempts) {
                col.db.execute(
                    """
                    insert or replace into practice_attempts
                    (id, session_id, full_length_attempt_id, question_id, selected_answer,
                     correct, time_on_question_seconds, section, topic, answered_at,
                     hint_level_used, assisted, main_wrong_first, first_try_no_hint)
                    values (?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    attempt.id,
                    attempt.sessionId,
                    attempt.questionId,
                    attempt.selectedAnswer,
                    if (attempt.correct) 1 else 0,
                    attempt.timeSeconds,
                    attempt.section?.dbCode ?: "",
                    attempt.topic,
                    attempt.answeredAt,
                    attempt.hintLevelUsed,
                    if (attempt.assisted) 1 else 0,
                    if (attempt.mainWrongFirst) 1 else 0,
                    attempt.firstTryNoHint,
                )
            }
            col.db.execute("commit")
            true
        } catch (e: Exception) {
            Timber.w(e, "SpeedyCAT: failed to sync attempts into collection DB")
            runCatching { col.db.execute("rollback") }
            false
        }
    }
}
