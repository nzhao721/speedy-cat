// Copyright: Ankitects Pty Ltd and contributors
// License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

//! Time-weighted exponential moving average (EWMA) for SpeedyCAT readiness pillars.
//!
//! For half-life *H* (days), decay constant λ = ln(2) / *H* and weight at age *t*
//! days is `w(t) = exp(-λ·t) = 2^(-t/H)`. The weighted mean is
//! `Σ(wᵢ·vᵢ) / Σ(wᵢ)` over all observations with timestamps.

/// Performance pillar: half the weight comes from data within the last 7 days.
pub(crate) const PERFORMANCE_HALF_LIFE_DAYS: f64 = 7.0;
/// Readiness pillar: half the weight comes from data within the last 30 days.
pub(crate) const READINESS_HALF_LIFE_DAYS: f64 = 30.0;

/// Decay constant λ = ln(2) / *half_life_days* (per day).
pub(crate) fn decay_per_day(half_life_days: f64) -> f64 {
    std::f64::consts::LN_2 / half_life_days
}

/// Weight for an observation *t* days old: `2^(-t/H)`.
pub(crate) fn weight_for_age_days(age_days: f64, half_life_days: f64) -> f64 {
    if age_days <= 0.0 {
        return 1.0;
    }
    2f64.powf(-age_days / half_life_days)
}

/// Age in days from [`now_secs`] to [`timestamp_secs`]. Missing timestamps (`≤ 0`)
/// are treated as current so legacy rows without a clock still count at full weight.
pub(crate) fn observation_age_days(now_secs: i64, timestamp_secs: i64) -> f64 {
    if timestamp_secs <= 0 {
        return 0.0;
    }
    now_secs.saturating_sub(timestamp_secs) as f64 / 86_400.0
}

pub(crate) struct EwmaAggregate {
    pub mean: f64,
    /// Kish effective sample size: `(Σw)² / Σ(w²)`.
    pub effective_n: f64,
    pub count: usize,
}

/// Weighted mean of `(value, timestamp_secs)` pairs.
pub(crate) fn ewma_aggregate(
    observations: &[(f64, i64)],
    half_life_days: f64,
    now_secs: i64,
) -> EwmaAggregate {
    let mut weighted_sum = 0.0;
    let mut weight_sum = 0.0;
    let mut weight_sq_sum = 0.0;
    for &(value, ts) in observations {
        let w = weight_for_age_days(observation_age_days(now_secs, ts), half_life_days);
        weighted_sum += w * value;
        weight_sum += w;
        weight_sq_sum += w * w;
    }
    let mean = if weight_sum > 0.0 {
        weighted_sum / weight_sum
    } else {
        0.0
    };
    let effective_n = if weight_sq_sum > 0.0 {
        weight_sum * weight_sum / weight_sq_sum
    } else {
        0.0
    };
    EwmaAggregate {
        mean,
        effective_n,
        count: observations.len(),
    }
}

/// Weighted average of `(seconds, timestamp_secs)` for the pace penalty.
pub(crate) fn ewma_weighted_seconds(
    observations: &[(u32, i64)],
    half_life_days: f64,
    now_secs: i64,
) -> f64 {
    let mut weighted_sum = 0.0;
    let mut weight_sum = 0.0;
    for &(seconds, ts) in observations {
        let w = weight_for_age_days(observation_age_days(now_secs, ts), half_life_days);
        weighted_sum += w * seconds as f64;
        weight_sum += w;
    }
    if weight_sum > 0.0 {
        weighted_sum / weight_sum
    } else {
        0.0
    }
}

#[cfg(test)]
mod test {
    use super::*;

    #[test]
    fn weight_halves_at_half_life() {
        let h = 7.0;
        let w0 = weight_for_age_days(0.0, h);
        let w7 = weight_for_age_days(h, h);
        assert!((w0 - 1.0).abs() < 1e-12);
        assert!((w7 - 0.5).abs() < 1e-12);
        assert!((decay_per_day(h) - std::f64::consts::LN_2 / h).abs() < 1e-12);
    }

    #[test]
    fn older_observation_has_half_weight_at_half_life() {
        let now = 1_700_000_000i64;
        let half_life = 7.0;
        let age_secs = (half_life * 86_400.0) as i64;
        // value 1.0 now, value 0.0 exactly one half-life ago → mean 2/3
        let obs = [(1.0, now), (0.0, now - age_secs)];
        let agg = ewma_aggregate(&obs, half_life, now);
        assert!((agg.mean - 2.0 / 3.0).abs() < 1e-9);
        assert_eq!(agg.count, 2);
    }

    #[test]
    fn missing_timestamp_treated_as_current() {
        let now = 1_700_000_000i64;
        let obs = [(1.0, 0), (0.0, 0)];
        let agg = ewma_aggregate(&obs, PERFORMANCE_HALF_LIFE_DAYS, now);
        assert!((agg.mean - 0.5).abs() < 1e-9);
    }

    #[test]
    fn readiness_half_life_is_30_days() {
        let now = 2_000_000_000i64;
        let age_secs = (30.0 * 86_400.0) as i64;
        let obs = [(1.0, now), (0.0, now - age_secs)];
        let agg = ewma_aggregate(&obs, READINESS_HALF_LIFE_DAYS, now);
        assert!((agg.mean - 2.0 / 3.0).abs() < 1e-9);
    }
}
