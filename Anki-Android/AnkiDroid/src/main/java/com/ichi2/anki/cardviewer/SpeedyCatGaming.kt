// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors
//
// SpeedyCAT anti-gaming stats stored in collection config JSON (syncs via Anki).
// Pure logic mirrors rslib/src/speedycat/gaming.rs so mobile works without
// PracticeService RPCs on the stock backend.

package com.ichi2.anki.cardviewer

import com.ichi2.anki.libanki.Collection
import org.json.JSONObject

/** Collection-config key for synced anti-gaming counters. */
const val GAMING_CONFIG_KEY = "speedycatGamingStats"

/** Suppress Memory when session gamed lapses exceed this count (within [SESSION_REVIEW_WINDOW]). */
const val SESSION_GAMED_LIMIT = 3
const val SESSION_REVIEW_WINDOW = 10
const val DAILY_GAMED_RATE = 0.10
const val LOCKOUT_MS = 3_600_000L
val IDK_DELAYS_MS = longArrayOf(5_000L, 10_000L, 15_000L)

const val MEMORY_SUPPRESSION_MSG =
    "Memory Score Unavailable: Excessive guessing detected. " +
        "Focus on genuine retrieval practice to restore your score."

data class GamingStats(
    val day: Int = 0,
    val dailyReviews: Int = 0,
    val dailyGamed: Int = 0,
    val sessionGamed: Int = 0,
    val sessionReviews: Int = 0,
    val idkBypassCount: Int = 0,
    val lockoutUntilMs: Long = 0L,
)

object SpeedyCatGaming {
    fun idkDelayMs(idkBypassCount: Int): Long {
        val idx = idkBypassCount.coerceAtMost(IDK_DELAYS_MS.lastIndex)
        return IDK_DELAYS_MS[idx]
    }

    fun memorySuppressionMessage(
        stats: GamingStats,
        nowMs: Long = System.currentTimeMillis(),
    ): String? {
        if (nowMs < stats.lockoutUntilMs) return MEMORY_SUPPRESSION_MSG
        if (stats.sessionReviews <= SESSION_REVIEW_WINDOW && stats.sessionGamed > SESSION_GAMED_LIMIT) {
            return MEMORY_SUPPRESSION_MSG
        }
        if (stats.dailyReviews > 0) {
            val rate = stats.dailyGamed.toDouble() / stats.dailyReviews
            if (rate > DAILY_GAMED_RATE) return MEMORY_SUPPRESSION_MSG
        }
        return null
    }

    fun loadStats(col: Collection): GamingStats {
        val raw = col.config.get<JSONObject>(GAMING_CONFIG_KEY) ?: return GamingStats()
        return GamingStats(
            day = raw.optInt("day", 0),
            dailyReviews = raw.optInt("daily_reviews", 0),
            dailyGamed = raw.optInt("daily_gamed", 0),
            sessionGamed = raw.optInt("session_gamed", 0),
            sessionReviews = raw.optInt("session_reviews", 0),
            idkBypassCount = raw.optInt("idk_bypass_count", 0),
            lockoutUntilMs = raw.optLong("lockout_until_ms", 0L),
        )
    }

    private fun saveStats(
        col: Collection,
        stats: GamingStats,
    ) {
        val obj =
            JSONObject()
                .put("day", stats.day)
                .put("daily_reviews", stats.dailyReviews)
                .put("daily_gamed", stats.dailyGamed)
                .put("session_gamed", stats.sessionGamed)
                .put("session_reviews", stats.sessionReviews)
                .put("idk_bypass_count", stats.idkBypassCount)
                .put("lockout_until_ms", stats.lockoutUntilMs)
        col.config.set(GAMING_CONFIG_KEY, obj)
    }

    private fun ensureDay(
        col: Collection,
        stats: GamingStats,
    ): GamingStats {
        val daysElapsed = col.sched.today
        return if (stats.day != daysElapsed) {
            stats.copy(day = daysElapsed, dailyReviews = 0, dailyGamed = 0)
        } else {
            stats
        }
    }

    fun resetReviewSession(col: Collection): GamingStats {
        var stats = ensureDay(col, loadStats(col))
        stats =
            stats.copy(
                sessionGamed = 0,
                sessionReviews = 0,
                idkBypassCount = 0,
            )
        saveStats(col, stats)
        return stats
    }

    fun recordHonestReview(col: Collection): GamingStats {
        var stats = ensureDay(col, loadStats(col))
        stats =
            stats.copy(
                dailyReviews = stats.dailyReviews + 1,
                sessionReviews = stats.sessionReviews + 1,
            )
        saveStats(col, stats)
        return stats
    }

    fun recordGamedLapse(
        col: Collection,
        idk: Boolean,
    ): GamingStats {
        var stats = ensureDay(col, loadStats(col))
        stats =
            stats.copy(
                dailyReviews = stats.dailyReviews + 1,
                sessionReviews = stats.sessionReviews + 1,
                dailyGamed = stats.dailyGamed + 1,
                sessionGamed = stats.sessionGamed + 1,
                idkBypassCount = if (idk) stats.idkBypassCount + 1 else stats.idkBypassCount,
            )
        val now = System.currentTimeMillis()
        if (memorySuppressionMessage(stats, now) != null) {
            stats = stats.copy(lockoutUntilMs = now + LOCKOUT_MS)
        }
        saveStats(col, stats)
        return stats
    }
}
