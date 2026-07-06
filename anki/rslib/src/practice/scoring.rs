// Copyright: Ankitects Pty Ltd and contributors
// License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

//! SpeedyCAT: MCAT-scale score ESTIMATE (number-correct -> scaled).
//!
//! Real MCAT raw->scaled conversions are form-specific and never published:
//! AAMC *equates* each exam form after test day, so there is no single official
//! table (see the AAMC scoring pages cited below). What we ship here is a
//! deterministic, representative *average* conversion — an ESTIMATE, clearly
//! labelled as such in the UI — so the AI-generated proof-of-concept full-length
//! forms can report a familiar 472–528 total alongside the raw correct/total.
//!
//! # Named source
//! AAMC — official MCAT scoring guidance, `students-residents.aamc.org`:
//! "How is the MCAT Exam Scored?" and "Your Questions Answered: The MCAT
//! Scoring Process". AAMC publishes:
//! * the section scale: **118 (low) – 132 (high)**, total **472 – 528**;
//! * two illustrative number-correct -> scaled *anchors* that apply "on one of
//!   the sections": **"35–37 correct → 123"** and **"46–48 correct → 128"**.
//!
//! # Method (documented in PRD.md)
//! We treat AAMC's illustrative anchors as belonging to a representative
//! 59-question section (their `46–48` upper example only makes sense on a
//! ~59-question section) and express them as *fraction-correct* anchors, adding
//! the AAMC scale endpoints as the floor/ceiling:
//!
//! | fraction correct            | scaled | origin                              |
//! |-----------------------------|--------|-------------------------------------|
//! | 0.000                       | 118    | AAMC section floor                  |
//! | 36/59 ≈ 0.610               | 123    | AAMC example "35–37 → 123" (mid 36) |
//! | 47/59 ≈ 0.797               | 128    | AAMC example "46–48 → 128" (mid 47) |
//! | 1.000                       | 132    | AAMC section ceiling                |
//!
//! A section's scaled score is a **monotonic piecewise-linear interpolation** of
//! its fraction correct (`correct / total`) between those anchors, rounded to the
//! nearest integer and clamped to 118–132. Applying the *same* representative
//! curve to each section — by that section's own question count (CPBS 59,
//! CARS 53, BBLS 59, PSBB 59) — yields a distinct raw->scaled mapping per
//! section while keeping one "fairly average" curve. The total is the sum of the
//! four section scaled scores, clamped to 472–528.
//!
//! This is a plain deterministic lookup/interpolation. It is **not** an AI model
//! and must never be presented as one; it is an estimate, and both apps must run
//! with it exactly the same whether AI features are on or off.

/// Lowest / highest per-section scaled score (AAMC scale). The valid total
/// range is a function of these (four sections → 472..=528); see
/// [`clamp_total_for_sections`].
pub(crate) const SECTION_MIN: u32 = 118;
pub(crate) const SECTION_MAX: u32 = 132;

/// Standard AAMC section lengths used when mapping a blended fraction to an
/// equivalent raw score before scaling (CPBS/BBLS/PSBB 59, CARS 53).
pub(crate) fn section_question_count(section_db: &str) -> u32 {
    match section_db {
        "CARS" => 53,
        _ => 59,
    }
}

// The user-facing named source + method for this estimate deliberately live in
// the presentation layers that actually display them (desktop `ts/routes/
// practice/lib.ts`, mobile `ReadinessLogic.kt`) and in the module docs above /
// PRD.md, rather than as engine constants the Rust never renders.

/// Fraction-correct -> scaled anchors (see the module docs). Must stay sorted by
/// fraction with strictly increasing scaled values so the interpolation is
/// monotonic.
fn anchors() -> [(f64, f64); 4] {
    [
        (0.0, SECTION_MIN as f64),
        (36.0 / 59.0, 123.0),
        (47.0 / 59.0, 128.0),
        (1.0, SECTION_MAX as f64),
    ]
}

/// Map a fraction correct (0..=1) to a scaled section score via monotonic
/// piecewise-linear interpolation between [`anchors`], rounded and clamped to
/// [`SECTION_MIN`]..=[`SECTION_MAX`]. Exposed so the readiness aggregate can map
/// a section's Wilson interval bounds (fractions) to a scaled range.
pub(crate) fn scaled_from_fraction(fraction: f64) -> u32 {
    let f = fraction.clamp(0.0, 1.0);
    let anchors = anchors();
    let mut scaled = SECTION_MAX as f64;
    for pair in anchors.windows(2) {
        let (f0, s0) = pair[0];
        let (f1, s1) = pair[1];
        if f <= f1 {
            let span = f1 - f0;
            let t = if span.abs() < f64::EPSILON {
                0.0
            } else {
                (f - f0) / span
            };
            scaled = s0 + t * (s1 - s0);
            break;
        }
    }
    scaled
        .round()
        .clamp(SECTION_MIN as f64, SECTION_MAX as f64) as u32
}

/// Estimated scaled score (118–132) for a section given `correct` of `total`
/// answered. Returns `None` when `total` is 0 (no questions -> refuse to invent
/// a score). `correct` is capped at `total` for safety.
pub(crate) fn section_scaled_score(correct: u32, total: u32) -> Option<u32> {
    if total == 0 {
        return None;
    }
    let fraction = correct.min(total) as f64 / total as f64;
    Some(scaled_from_fraction(fraction))
}

/// Clamp a summed total to the valid range for `num_sections` sections
/// (`num_sections`·118 .. `num_sections`·132 — i.e. 472..=528 for the standard
/// four-section MCAT). Because each section score is already clamped this is a
/// guard/no-op, but it documents intent and absorbs any rounding drift.
pub(crate) fn clamp_total_for_sections(sum: u32, num_sections: u32) -> u32 {
    if num_sections == 0 {
        return 0;
    }
    sum.clamp(num_sections * SECTION_MIN, num_sections * SECTION_MAX)
}

#[cfg(test)]
mod test {
    use super::*;

    /// Standard AAMC section question counts, used to exercise raw->scaled at
    /// the real section lengths.
    const CPBS: u32 = 59;
    const CARS: u32 = 53;

    /// The anchors the module is built on reproduce exactly: 0 correct -> 118,
    /// the two AAMC examples (35–37→123 / 46–48→128, taken at their midpoints on
    /// a 59-question section), and a perfect section -> 132.
    #[test]
    fn section_anchor_points_reproduce() {
        assert_eq!(section_scaled_score(0, CPBS), Some(118));
        assert_eq!(section_scaled_score(36, CPBS), Some(123));
        assert_eq!(section_scaled_score(47, CPBS), Some(128));
        assert_eq!(section_scaled_score(CPBS, CPBS), Some(132));
    }

    /// The AAMC anchor example ranges (35–37 and 46–48) all land on their stated
    /// scaled score, not just the midpoints.
    #[test]
    fn aamc_example_ranges_hold() {
        for raw in 35..=37 {
            assert_eq!(section_scaled_score(raw, CPBS), Some(123), "raw {raw}");
        }
        for raw in 46..=48 {
            assert_eq!(section_scaled_score(raw, CPBS), Some(128), "raw {raw}");
        }
    }

    /// CARS (53 questions) uses the same representative curve, so its endpoints
    /// still pin to the scale bounds while its interior raw->scaled differs from
    /// the 59-question sections.
    #[test]
    fn cars_endpoints_and_shorter_length() {
        assert_eq!(section_scaled_score(0, CARS), Some(118));
        assert_eq!(section_scaled_score(CARS, CARS), Some(132));
        // ~61% correct sits on the 123 anchor regardless of section length.
        assert_eq!(section_scaled_score(32, CARS), Some(123)); // 32/53 = 0.604
    }

    /// A section score is monotonic non-decreasing in raw correct and never
    /// leaves the 118–132 band, for both section lengths.
    #[test]
    fn section_scaled_is_monotonic_and_bounded() {
        for total in [CARS, CPBS] {
            let mut prev = 0;
            for correct in 0..=total {
                let s = section_scaled_score(correct, total).unwrap();
                assert!((SECTION_MIN..=SECTION_MAX).contains(&s), "{s} out of band");
                assert!(s >= prev, "not monotonic at {correct}/{total}");
                prev = s;
            }
        }
    }

    /// No questions -> no score (refuse rather than invent), and `correct` is
    /// capped at `total`.
    #[test]
    fn empty_section_gives_no_score() {
        assert_eq!(section_scaled_score(0, 0), None);
        assert_eq!(section_scaled_score(5, 0), None);
        // correct > total is clamped to a perfect section.
        assert_eq!(section_scaled_score(99, CPBS), Some(132));
    }

    /// A perfect four-section exam totals 528, an all-wrong one 472, and the
    /// total clamp holds the sum inside 472–528.
    #[test]
    fn total_bounds_and_clamp() {
        // Four-section totals clamp to the MCAT 472–528 range (a guard/no-op
        // since each section is already clamped to 118–132).
        assert_eq!(clamp_total_for_sections(4 * SECTION_MAX, 4), 528);
        assert_eq!(clamp_total_for_sections(4 * SECTION_MIN, 4), 472);
        assert_eq!(clamp_total_for_sections(500, 4), 500);
        assert_eq!(clamp_total_for_sections(999, 4), 528);
        assert_eq!(clamp_total_for_sections(1, 4), 472);
        // Two-section (non-standard) total clamps to its own 236–264 range.
        assert_eq!(clamp_total_for_sections(999, 2), 264);
        assert_eq!(clamp_total_for_sections(0, 0), 0);
    }

    /// A mid exam: ~50% on each 59-question science section plus a 53-question
    /// CARS lands in a believable, in-range total.
    #[test]
    fn representative_mid_exam_total() {
        let sections = [(30, CPBS), (27, CARS), (30, CPBS), (30, CPBS)];
        let sum: u32 = sections
            .iter()
            .map(|&(c, t)| section_scaled_score(c, t).unwrap())
            .sum();
        let total = clamp_total_for_sections(sum, 4);
        assert!((472..=528).contains(&total), "{total}");
        // ~50% correct is a below-average performance -> below the 500 midpoint.
        assert!(total < 500, "expected sub-500 for ~50% correct, got {total}");
    }
}
