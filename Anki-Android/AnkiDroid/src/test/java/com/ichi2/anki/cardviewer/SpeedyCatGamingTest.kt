/*
 *  Copyright (c) 2026 SpeedyCAT contributors
 *  SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.ichi2.anki.cardviewer

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.nullValue
import org.junit.Test

class SpeedyCatGamingTest {
    @Test
    fun idkDelayEscalates() {
        assertThat(SpeedyCatGaming.idkDelayMs(0), equalTo(5000L))
        assertThat(SpeedyCatGaming.idkDelayMs(1), equalTo(10_000L))
        assertThat(SpeedyCatGaming.idkDelayMs(2), equalTo(15_000L))
        assertThat(SpeedyCatGaming.idkDelayMs(99), equalTo(15_000L))
    }

    @Test
    fun sessionBurstDoesNotSuppressMemory() {
        val stats =
            GamingStats(
                sessionGamed = 4,
                sessionReviews = 8,
                dailyReviews = 100,
                dailyGamed = 5,
            )
        assertThat(SpeedyCatGaming.memorySuppressionMessage(stats, nowMs = 0L), nullValue())
    }

    @Test
    fun dailyRateSuppressesMemory() {
        val stats =
            GamingStats(
                dailyReviews = 100,
                dailyGamed = 11,
            )
        assertThat(
            SpeedyCatGaming.memorySuppressionMessage(stats, nowMs = 0L),
            containsString("Excessive guessing"),
        )
    }

    @Test
    fun honestSessionDoesNotSuppress() {
        val stats =
            GamingStats(
                sessionGamed = 2,
                sessionReviews = 10,
                dailyReviews = 50,
                dailyGamed = 2,
            )
        assertThat(SpeedyCatGaming.memorySuppressionMessage(stats, nowMs = 0L), nullValue())
    }
}
