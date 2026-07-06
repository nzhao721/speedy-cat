// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors
//
// Materializes SpeedyCAT results into the collection's schema-19 tables so the
// shared Rust engine (`PracticeService.GetReadiness`) — not Kotlin — computes
// every readiness pillar:
//   * practice-session attempts -> `practice_attempts` (Performance pillar)
//   * completed full-length summaries -> `full_length_attempts` + synthesized
//     per-answer `practice_attempts` rows (Readiness pillar)
// Mirrors `anki/pylib/anki/speedycat_sync.py` ingest SQL. Full-length exams are
// taken on desktop and arrive here read-only via the media results file; we
// project their per-section raw scores into the canonical attempt rows the
// engine already aggregates, so mobile runs the identical Rust readiness path as
// desktop (no duplicated formula on the client).

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
        if (!hasPracticeTable(col)) {
            Timber.w("SpeedyCAT-sync: practice_attempts table missing (stock backend?); cannot project attempts")
            return false
        }
        val sessionAttempts = attempts.filter { it.sessionId != null && it.selectedAnswer.isNotEmpty() }
        if (sessionAttempts.isEmpty()) {
            Timber.i("SpeedyCAT-sync: no session attempts to project into the collection DB")
            return true
        }
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
            Timber.i("SpeedyCAT-sync: projected %d practice attempt(s) into the collection DB", sessionAttempts.size)
            true
        } catch (e: Exception) {
            Timber.w(e, "SpeedyCAT: failed to sync attempts into collection DB")
            runCatching { col.db.execute("rollback") }
            false
        }
    }

    /**
     * Materialize completed full-length [summaries] (published read-only by the
     * desktop app) into `full_length_attempts` + synthesized per-answer
     * `practice_attempts` rows, so `PracticeService.GetReadiness`'s Rust
     * `readiness_full_length()` aggregates them into the Readiness pillar exactly
     * like it does on desktop.
     *
     * The summary carries per-section raw scores (correct/total); we project each
     * section into `total` answer rows (`correct` of them marked correct), all
     * stamped with the exam's completion time. The engine's EWMA has a 30-day
     * half-life, so collapsing an exam's answers onto its completion instant is
     * numerically identical to the desktop's per-answer timestamps (all within
     * one exam day). Idempotent: rows are keyed by stable ids and each attempt's
     * synthesized rows are replaced wholesale on re-ingest. Returns false when
     * the table is missing or the write fails.
     */
    fun syncFullLengthSummaries(
        col: Collection,
        summaries: List<FullLengthSummary>,
    ): Boolean {
        if (!hasPracticeTable(col)) return false
        val completed = summaries.filter { it.attemptId.isNotEmpty() && it.totalQuestions > 0 }
        if (completed.isEmpty()) {
            Timber.i("SpeedyCAT-sync: no completed full-length summaries to project into the collection DB")
            return true
        }
        return try {
            col.db.execute("begin immediate transaction")
            for (summary in completed) {
                col.db.execute(
                    """
                    insert or replace into full_length_attempts
                    (id, test_id, aamc_exam_id, started_at, completed_at, section_results,
                     overall_scaled_score, counts_for_readiness, abandoned)
                    values (?, ?, NULL, ?, ?, '[]', ?, 1, 0)
                    """.trimIndent(),
                    summary.attemptId,
                    summary.testId.ifEmpty { summary.attemptId },
                    summary.startedAt,
                    summary.completedAt,
                    summary.overallScaledScore,
                )
                // Replace any prior synthesized rows for this attempt so a
                // re-published summary can't leave stale answers behind.
                col.db.execute(
                    "delete from practice_attempts where full_length_attempt_id = ?",
                    summary.attemptId,
                )
                for (section in summary.sections) {
                    if (section.section.isEmpty() || section.total <= 0) continue
                    for (i in 0 until section.total) {
                        val rowId = "${summary.attemptId}:${section.section}:$i"
                        col.db.execute(
                            """
                            insert or replace into practice_attempts
                            (id, session_id, full_length_attempt_id, question_id, selected_answer,
                             correct, time_on_question_seconds, section, topic, answered_at,
                             hint_level_used, assisted, main_wrong_first, first_try_no_hint)
                            values (?, NULL, ?, ?, '', ?, 0, ?, '', ?, 0, 0, 0, NULL)
                            """.trimIndent(),
                            rowId,
                            summary.attemptId,
                            "$rowId-q",
                            if (i < section.correct) 1 else 0,
                            section.section,
                            summary.completedAt,
                        )
                    }
                }
            }
            col.db.execute("commit")
            Timber.i("SpeedyCAT-sync: projected %d full-length summary(ies) into the collection DB", completed.size)
            true
        } catch (e: Exception) {
            Timber.w(e, "SpeedyCAT: failed to sync full-length summaries into collection DB")
            runCatching { col.db.execute("rollback") }
            false
        }
    }
}
