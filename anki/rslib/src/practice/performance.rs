// Copyright: Ankitects Pty Ltd and contributors
// License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

//! SpeedyCAT Performance pillar: progressive hint penalties + first-try dashboard
//! eligibility. Documented in `PRD.md` (*Graduated hint ladder*).
//!
//! # Performance pillar credit (per answered attempt)
//! | Outcome | Credit |
//! |---------|--------|
//! | Correct, no hints (L0) | 1.0 |
//! | Correct with hint L1 | 0.60 |
//! | Correct with hint L2 | 0.30 |
//! | Correct with hint L3 | 0.10 (~incorrect) |
//! | Incorrect on main question (`main_wrong_first`) | 0.0 |
//! | Incorrect (final) | 0.0 |
//!
//! Every answered attempt counts toward the denominator; credit is summed for the
//! Wilson interval numerator.

/// Full credit — correct with no hints.
pub(crate) const HINT_CREDIT_L0: f64 = 1.0;
/// Slightly penalized — correct after reaching hint tier 1.
pub(crate) const HINT_CREDIT_L1: f64 = 0.60;
/// More penalized — correct after reaching hint tier 2.
pub(crate) const HINT_CREDIT_L2: f64 = 0.30;
/// Heavily penalized (~incorrect) — correct only after reaching tier 3.
pub(crate) const HINT_CREDIT_L3: f64 = 0.10;

/// Target pace for the Performance pillar: 230 questions in 6h15m (22_500s / 230
/// ≈ 97.8s), rounded to 98s. Slower averages scale the accuracy score down.
pub(crate) const PERFORMANCE_TARGET_SECONDS: f64 = 98.0;

/// Named method string for the Performance readiness pillar.
pub(crate) const PERFORMANCE_METHOD: &str = "time-weighted practice accuracy (EWMA, \
     7-day half-life) with progressive hint penalties (L0=1.0, L1=0.60, L2=0.30, \
     L3=0.10; main-wrong-first=0), a Wilson 95% score interval on effective sample \
     size, and a pace penalty when EWMA average time per question exceeds 98s \
     (displayed = accuracy × 98 / avg_seconds)";

/// Multiplier applied to accuracy-based performance when average pace exceeds
/// [`PERFORMANCE_TARGET_SECONDS`]. Returns 1.0 when at or under target pace.
pub(crate) fn performance_time_penalty_factor(avg_seconds: f64) -> f64 {
    if avg_seconds <= PERFORMANCE_TARGET_SECONDS {
        1.0
    } else {
        PERFORMANCE_TARGET_SECONDS / avg_seconds
    }
}

/// Scale a performance point estimate and its Wilson interval by the pace penalty.
pub(crate) fn apply_performance_time_penalty(
    value: f64,
    low: f64,
    high: f64,
    avg_seconds: f64,
) -> (f64, f64, f64) {
    let factor = performance_time_penalty_factor(avg_seconds);
    (value * factor, low * factor, high * factor)
}

/// Fractional credit for one answered practice attempt toward the Performance pillar.
pub(crate) fn performance_credit(correct: bool, hint_level_used: u32, main_wrong_first: bool) -> f64 {
    if main_wrong_first || !correct {
        return 0.0;
    }
    match hint_level_used.min(3) {
        0 => HINT_CREDIT_L0,
        1 => HINT_CREDIT_L1,
        2 => HINT_CREDIT_L2,
        _ => HINT_CREDIT_L3,
    }
}

/// Dashboard first-try eligibility: the learner's first-ever attempt on a question
/// with no hint usage. Returns `None` for retries or hint-assisted first encounters.
pub(crate) fn first_try_no_hint(
    question_seen_before: bool,
    replacing: bool,
    prior_first_try: Option<i32>,
    hint_level_used: u32,
    selected_answer: &str,
    correct: bool,
) -> Option<i32> {
    if replacing {
        return prior_first_try;
    }
    if question_seen_before {
        return None;
    }
    if hint_level_used > 0 || selected_answer.is_empty() {
        return None;
    }
    Some(i32::from(correct))
}

#[cfg(test)]
mod test {
    use super::*;

    #[test]
    fn progressive_hint_credits() {
        assert!((performance_credit(true, 0, false) - 1.0).abs() < 1e-9);
        assert!((performance_credit(true, 1, false) - 0.60).abs() < 1e-9);
        assert!((performance_credit(true, 2, false) - 0.30).abs() < 1e-9);
        assert!((performance_credit(true, 3, false) - 0.10).abs() < 1e-9);
        assert!((performance_credit(false, 0, false)).abs() < 1e-9);
        assert!((performance_credit(true, 0, true)).abs() < 1e-9);
    }

    #[test]
    fn time_penalty_under_target_is_identity() {
        assert!((performance_time_penalty_factor(97.0) - 1.0).abs() < 1e-9);
        assert!((performance_time_penalty_factor(98.0) - 1.0).abs() < 1e-9);
    }

    #[test]
    fn time_penalty_scales_slow_pace() {
        // 90% accuracy at 147s avg → 90% × (98/147) = 60%
        let factor = performance_time_penalty_factor(147.0);
        assert!((factor - 98.0 / 147.0).abs() < 1e-9);
        let (value, low, high) = apply_performance_time_penalty(0.9, 0.8, 0.95, 147.0);
        assert!((value - 0.6).abs() < 1e-9);
        assert!((low - 0.8 * factor).abs() < 1e-9);
        assert!((high - 0.95 * factor).abs() < 1e-9);
    }

    #[test]
    fn first_try_no_hint_rules() {
        assert_eq!(
            first_try_no_hint(false, false, None, 0, "A", true),
            Some(1)
        );
        assert_eq!(
            first_try_no_hint(false, false, None, 0, "B", false),
            Some(0)
        );
        assert_eq!(first_try_no_hint(false, false, None, 2, "A", true), None);
        assert_eq!(first_try_no_hint(true, false, None, 0, "A", true), None);
        assert_eq!(
            first_try_no_hint(false, true, Some(1), 3, "A", true),
            Some(1)
        );
    }
}
