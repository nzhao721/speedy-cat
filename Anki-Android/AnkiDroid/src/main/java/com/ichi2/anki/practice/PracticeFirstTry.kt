// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors
//
// TRANSITIONAL: dashboard first-try eligibility classifier for the LOCAL
// attempt-recording path.
//
// The authoritative implementation lives in the Rust engine
// (`crate::practice::performance::first_try_no_hint`, called from
// `record_practice_attempt`). Mobile still records practice attempts into a
// device-local store ([PracticeStore]) instead of calling the Rust
// `RecordPracticeAttempt` RPC, so it needs this value at record time. Once the
// mobile practice-recording flow is routed through the Rust engine (requires
// `local_backend=true` + rsdroid), delete this file and let the engine compute
// it. Until then this is the only remaining record-time classifier on mobile and
// it is kept byte-for-byte equivalent to the Rust logic.

package com.ichi2.anki.practice

/**
 * Dashboard first-try eligibility: the learner's first-ever attempt on a
 * question with no hint usage. Returns null for retries or hint-assisted first
 * encounters. Mirrors Rust `performance::first_try_no_hint`.
 */
fun firstTryNoHint(
    questionSeenBefore: Boolean,
    replacing: Boolean,
    priorFirstTry: Int?,
    hintLevelUsed: Int,
    selectedAnswer: String,
    correct: Boolean,
): Int? {
    if (replacing) return priorFirstTry
    if (questionSeenBefore) return null
    if (hintLevelUsed > 0 || selectedAnswer.isEmpty()) return null
    return if (correct) 1 else 0
}
