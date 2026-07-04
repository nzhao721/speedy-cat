// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors

package com.ichi2.anki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedyCatAutoSyncTest {
    @Test
    fun startupAndCloseBypassThrottle() {
        val now = 100_000L
        assertFalse(
            SpeedyCatAutoSync.shouldThrottle(
                SpeedyCatAutoSync.Trigger.STARTUP,
                lastAttemptMs = now - 1,
                nowMs = now,
            ),
        )
        assertFalse(
            SpeedyCatAutoSync.shouldThrottle(
                SpeedyCatAutoSync.Trigger.CLOSE,
                lastAttemptMs = now - 1,
                nowMs = now,
            ),
        )
    }

    @Test
    fun periodicAndDataChangeRespectThrottle() {
        val now = 100_000L
        assertTrue(
            SpeedyCatAutoSync.shouldThrottle(
                SpeedyCatAutoSync.Trigger.PERIODIC,
                lastAttemptMs = now - 5_000,
                nowMs = now,
            ),
        )
        assertTrue(
            SpeedyCatAutoSync.shouldThrottle(
                SpeedyCatAutoSync.Trigger.DATA_CHANGE,
                lastAttemptMs = now - 5_000,
                nowMs = now,
            ),
        )
        assertFalse(
            SpeedyCatAutoSync.shouldThrottle(
                SpeedyCatAutoSync.Trigger.DATA_CHANGE,
                lastAttemptMs = now - 30_000,
                nowMs = now,
            ),
        )
    }
}
