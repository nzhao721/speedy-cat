// Copyright: Ankitects Pty Ltd and contributors
// License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

//! SpeedyCAT anti-gaming: track gamed lapses, suppress Memory score, escalate
//! the forced-recall "I don't know" wait gate, and apply extra FSRS punishment.

use anki_proto::practice as pb;
use serde::Deserialize;
use serde::Serialize;

use crate::card::Card;
use crate::card::CardType;
use crate::prelude::*;

/// Collection-config key (JSON); syncs across devices.
pub(crate) const CONFIG_KEY: &str = "speedycatGamingStats";

/// Suppress Memory when daily gamed lapses exceed this fraction of reviews.
pub(crate) const DAILY_GAMED_RATE: f64 = 0.10;
/// Cooldown from earning Memory score after further gamed lapses while suppressed.
pub(crate) const LOCKOUT_MS: i64 = 3_600_000;
/// Escalating "I don't know" wait gate: 5s → 10s → 15s.
pub(crate) const IDK_DELAYS_MS: [u32; 3] = [5000, 10_000, 15_000];

pub(crate) const MEMORY_SUPPRESSION_MSG: &str = "Memory Score Unavailable: Excessive guessing detected. \
Focus on genuine retrieval practice to restore your score.";

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
pub(crate) struct GamingStats {
    pub(crate) day: u32,
    pub(crate) daily_reviews: u32,
    pub(crate) daily_gamed: u32,
    pub(crate) session_gamed: u32,
    pub(crate) session_reviews: u32,
    pub(crate) idk_bypass_count: u32,
    pub(crate) lockout_until_ms: i64,
}

impl Collection {
    pub(crate) fn speedycat_gaming_stats(&self) -> Result<GamingStats> {
        Ok(self.get_config_optional(CONFIG_KEY).unwrap_or_default())
    }

    fn save_speedycat_gaming_stats(&mut self, stats: &GamingStats) -> Result<()> {
        self.set_config_json(CONFIG_KEY, stats, true)?;
        Ok(())
    }

    fn speedycat_ensure_day(&mut self, stats: &mut GamingStats) -> Result<()> {
        let today = self.timing_today()?.days_elapsed;
        if stats.day != today {
            stats.day = today;
            stats.daily_reviews = 0;
            stats.daily_gamed = 0;
        }
        Ok(())
    }

    pub(crate) fn speedycat_reset_review_session(&mut self) -> Result<pb::SpeedycatGamingStatus> {
        let mut stats = self.speedycat_gaming_stats()?;
        self.speedycat_ensure_day(&mut stats)?;
        stats.session_gamed = 0;
        stats.session_reviews = 0;
        stats.idk_bypass_count = 0;
        self.save_speedycat_gaming_stats(&stats)?;
        Ok(speedycat_gaming_status_from_stats(&stats))
    }

    pub(crate) fn speedycat_record_honest_review(&mut self) -> Result<pb::SpeedycatGamingStatus> {
        let mut stats = self.speedycat_gaming_stats()?;
        self.speedycat_ensure_day(&mut stats)?;
        stats.daily_reviews = stats.daily_reviews.saturating_add(1);
        stats.session_reviews = stats.session_reviews.saturating_add(1);
        self.save_speedycat_gaming_stats(&stats)?;
        Ok(speedycat_gaming_status_from_stats(&stats))
    }

    pub(crate) fn speedycat_record_gamed_lapse(
        &mut self,
        idk: bool,
    ) -> Result<pb::SpeedycatGamingStatus> {
        let mut stats = self.speedycat_gaming_stats()?;
        self.speedycat_ensure_day(&mut stats)?;
        stats.daily_reviews = stats.daily_reviews.saturating_add(1);
        stats.session_reviews = stats.session_reviews.saturating_add(1);
        stats.daily_gamed = stats.daily_gamed.saturating_add(1);
        stats.session_gamed = stats.session_gamed.saturating_add(1);
        if idk {
            stats.idk_bypass_count = stats.idk_bypass_count.saturating_add(1);
        }
        let now = TimestampMillis::now().0;
        if memory_suppressed(&stats, now).is_some() {
            stats.lockout_until_ms = now + LOCKOUT_MS;
        }
        self.save_speedycat_gaming_stats(&stats)?;
        Ok(speedycat_gaming_status_from_stats(&stats))
    }

    pub(crate) fn speedycat_gaming_status(&self) -> Result<pb::SpeedycatGamingStatus> {
        let stats = self.speedycat_gaming_stats()?;
        Ok(speedycat_gaming_status_from_stats(&stats))
    }

    /// Memory pillar give-up when anti-gaming thresholds are exceeded.
    pub(crate) fn speedycat_memory_suppression_message(&self) -> Result<Option<String>> {
        let stats = self.speedycat_gaming_stats()?;
        let now = TimestampMillis::now().0;
        Ok(memory_suppressed(&stats, now).map(str::to_string))
    }
}

pub(crate) fn idk_delay_ms(idk_bypass_count: u32) -> u32 {
    let idx = idk_bypass_count.min((IDK_DELAYS_MS.len() - 1) as u32) as usize;
    IDK_DELAYS_MS[idx]
}

fn memory_suppressed(stats: &GamingStats, now_ms: i64) -> Option<&'static str> {
    if now_ms < stats.lockout_until_ms {
        return Some(MEMORY_SUPPRESSION_MSG);
    }
    if stats.daily_reviews > 0 {
        let rate = stats.daily_gamed as f64 / stats.daily_reviews as f64;
        if rate > DAILY_GAMED_RATE {
            return Some(MEMORY_SUPPRESSION_MSG);
        }
    }
    None
}

fn speedycat_gaming_status_from_stats(stats: &GamingStats) -> pb::SpeedycatGamingStatus {
    let now = TimestampMillis::now().0;
    let suppressed = memory_suppressed(stats, now);
    pb::SpeedycatGamingStatus {
        session_gamed: stats.session_gamed,
        session_reviews: stats.session_reviews,
        daily_gamed: stats.daily_gamed,
        daily_reviews: stats.daily_reviews,
        memory_suppressed: suppressed.is_some(),
        memory_message: suppressed.unwrap_or("").to_string(),
        idk_delay_ms: idk_delay_ms(stats.idk_bypass_count),
        lockout_until_ms: stats.lockout_until_ms,
        idk_bypass_count: stats.idk_bypass_count,
    }
}

/// Extra FSRS punishment beyond a plain Again: one additional learning/relearning step.
pub(crate) fn apply_gamed_lapse_punishment(card: &mut Card) {
    match card.ctype {
        CardType::Learn | CardType::Relearn => {
            let steps = card.remaining_steps();
            card.remaining_steps = steps.saturating_add(1);
        }
        _ => {}
    }
}

#[cfg(test)]
mod test {
    use super::*;

    #[test]
    fn idk_delay_escalates() {
        assert_eq!(idk_delay_ms(0), 5000);
        assert_eq!(idk_delay_ms(1), 10_000);
        assert_eq!(idk_delay_ms(2), 15_000);
        assert_eq!(idk_delay_ms(99), 15_000);
    }

    #[test]
    fn session_burst_does_not_suppress_memory() {
        let stats = GamingStats {
            session_gamed: 4,
            session_reviews: 8,
            daily_reviews: 100,
            daily_gamed: 5,
            ..Default::default()
        };
        assert!(memory_suppressed(&stats, 0).is_none());
    }

    #[test]
    fn daily_rate_suppresses_memory() {
        let stats = GamingStats {
            daily_reviews: 100,
            daily_gamed: 11,
            ..Default::default()
        };
        assert!(memory_suppressed(&stats, 0).is_some());
    }

    #[test]
    fn lockout_suppresses_memory() {
        let stats = GamingStats {
            lockout_until_ms: 9_999_999_999_999,
            ..Default::default()
        };
        assert!(memory_suppressed(&stats, 0).is_some());
    }

    #[test]
    fn honest_session_does_not_suppress() {
        let stats = GamingStats {
            session_gamed: 2,
            session_reviews: 10,
            daily_reviews: 50,
            daily_gamed: 2,
            ..Default::default()
        };
        assert!(memory_suppressed(&stats, 0).is_none());
    }

    #[test]
    fn record_gamed_lapse_sets_lockout_when_suppressed() -> Result<()> {
        let mut col = Collection::new();
        for _ in 0..4 {
            let _ = col.speedycat_record_gamed_lapse(false)?;
        }
        let status = col.speedycat_gaming_status()?;
        assert!(status.memory_suppressed);
        assert!(status.lockout_until_ms > TimestampMillis::now().0);
        Ok(())
    }

    #[test]
    fn reset_session_clears_counters() -> Result<()> {
        let mut col = Collection::new();
        let _ = col.speedycat_record_gamed_lapse(true)?;
        let _ = col.speedycat_reset_review_session()?;
        let status = col.speedycat_gaming_status()?;
        assert_eq!(status.session_gamed, 0);
        assert_eq!(status.idk_bypass_count, 0);
        assert_eq!(status.idk_delay_ms, 5000);
        Ok(())
    }
}
