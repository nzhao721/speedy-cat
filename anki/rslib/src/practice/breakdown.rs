// Copyright: Ankitects Pty Ltd and contributors
// License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

//! Per-section breakdowns for Memory and Readiness; Performance also includes
//! per-topic scores in its pillar breakdown.
//!
//! All aggregation lives here (and in [`super::service`]'s memory path) so desktop
//! and mobile only render the protobuf tables.

use std::collections::HashMap;

use anki_proto::practice as pb;
use fsrs::FSRS;
use fsrs::FSRS5_DEFAULT_DECAY;

use crate::prelude::*;
use crate::scheduler::timing::SchedTimingToday;
use crate::search::SortMode;

use super::ewma;
use super::performance;
use super::scoring;
use super::section_from_db;
use super::topic_display::format_topic_display;
use super::McatSection;

/// z-score for a two-sided 95% interval (used by every pillar's range).
const READINESS_Z: f64 = 1.96;
/// Canonical MCAT sections in display order.
const PROJECTED_SECTIONS: [&str; 4] = ["CPBS", "CARS", "BBLS", "PSBB"];

/// Minimum reviewed cards before a Memory sub-breakdown reports.
///
/// Sub-breakdown thresholds are lower than pillar-level minimums (Memory 30,
/// Performance 30) so users can drill into sections before the headline score
/// unlocks, but still high enough for a meaningful Wilson 95% interval.
pub(crate) const MIN_MEMORY_BREAKDOWN_CARDS: u64 = 10;
/// Minimum answered practice questions before a Performance sub-breakdown reports.
///
/// Matches [`MIN_MEMORY_BREAKDOWN_CARDS`] (10): with n < 10 the Wilson interval
/// is too wide to be useful in a per-section table. Pillar-level
/// Performance still requires 30 overall ([`super::service::MIN_PERFORMANCE_ATTEMPTS`]).
pub(crate) const MIN_PERFORMANCE_BREAKDOWN_ATTEMPTS: u32 = 10;
/// Minimum full-length answers before a Readiness sub-breakdown reports.
///
/// Same rationale as Performance: 10 per section for a stable interval.
/// Pillar-level Readiness uses all completed full-length data; projected MCAT
/// per-section practice still uses 5 ([`super::service::MIN_PROJECTED_SECTION_ATTEMPTS`]).
pub(crate) const MIN_READINESS_BREAKDOWN_ANSWERS: u32 = 10;

/// Map a SpeedyCAT flashcard theme deck name to its MCAT section. CARS has no
/// bundled flashcards; unknown decks map to `None`.
pub(crate) fn flashcard_theme_to_section(theme: &str) -> Option<i32> {
    let section = match theme {
        "General Chemistry" | "Organic Chemistry" | "Physics and Math" | "Equations & Constants" => {
            McatSection::Cpbs
        }
        "Biochemistry" | "Biology" => McatSection::Bbls,
        "Behavioral Sciences"
        | "Sensation & Perception"
        | "Learning, Memory & Cognition"
        | "Emotion, Stress & Motivation"
        | "Identity, Personality & Biological Bases of Behavior"
        | "Social Processes & Socialization"
        | "Attitudes & Behavior Change"
        | "Self-Identity & Social Cognition"
        | "Social Interactions"
        | "Social Structures & Institutions"
        | "Demographics & Culture"
        | "Social Inequality"
        | "Research Methods & Statistics" => McatSection::Psbb,
        _ => return None,
    };
    Some(section as i32)
}

fn clamp_interval(low: f64, high: f64) -> (f64, f64) {
    (low.clamp(0.0, 1.0), high.clamp(0.0, 1.0))
}

/// `true` when a topic label duplicates an MCAT section name and should not
/// appear in per-topic breakdown tables (the section row already covers it).
///
/// Practice attempts without `topic_tags` fall back to the section label
/// (e.g. `"CARS"`); that row is redundant next to the CARS section breakdown.
pub(crate) fn is_redundant_section_topic(topic: &str) -> bool {
    topic.trim().eq_ignore_ascii_case("cars")
}

fn wilson_interval(correct: u32, total: u32, z: f64) -> (f64, f64, f64) {
    if total == 0 {
        return (0.0, 0.0, 0.0);
    }
    wilson_interval_fraction(correct as f64 / total as f64, total, z)
}

fn wilson_interval_fraction(p: f64, total: u32, z: f64) -> (f64, f64, f64) {
    if total == 0 {
        return (0.0, 0.0, 0.0);
    }
    let n = total as f64;
    let p = p.clamp(0.0, 1.0);
    let z2 = z * z;
    let denom = 1.0 + z2 / n;
    let center = (p + z2 / (2.0 * n)) / denom;
    let margin = z * ((p * (1.0 - p) / n) + z2 / (4.0 * n * n)).sqrt() / denom;
    let (low, high) = clamp_interval(center - margin, center + margin);
    (p, low, high)
}

fn effective_sample_size_for_wilson(effective_n: f64) -> u32 {
    if effective_n <= 0.0 {
        return 0;
    }
    effective_n.round().max(1.0) as u32
}

fn give_up_section_breakdown(section: i32, sample_size: u32, message: String) -> pb::PillarSectionBreakdown {
    pb::PillarSectionBreakdown {
        section,
        available: false,
        value: 0.0,
        range_low: 0.0,
        range_high: 0.0,
        sample_size,
        message,
        scaled_score: None,
        scaled_low: None,
        scaled_high: None,
    }
}

fn give_up_topic_breakdown(
    topic: String,
    section: i32,
    sample_size: u32,
    message: String,
) -> pb::PillarTopicBreakdown {
    pb::PillarTopicBreakdown {
        topic: format_topic_display(&topic),
        section,
        available: false,
        value: 0.0,
        range_low: 0.0,
        range_high: 0.0,
        sample_size,
        message,
        scaled_score: None,
        scaled_low: None,
        scaled_high: None,
    }
}

fn available_fraction_breakdown(
    section: i32,
    value: f64,
    low: f64,
    high: f64,
    sample_size: u32,
) -> pb::PillarSectionBreakdown {
    pb::PillarSectionBreakdown {
        section,
        available: true,
        value,
        range_low: low,
        range_high: high,
        sample_size,
        message: String::new(),
        scaled_score: None,
        scaled_low: None,
        scaled_high: None,
    }
}

fn available_topic_fraction_breakdown(
    topic: String,
    section: i32,
    value: f64,
    low: f64,
    high: f64,
    sample_size: u32,
) -> pb::PillarTopicBreakdown {
    pb::PillarTopicBreakdown {
        topic: format_topic_display(&topic),
        section,
        available: true,
        value,
        range_low: low,
        range_high: high,
        sample_size,
        message: String::new(),
        scaled_score: None,
        scaled_low: None,
        scaled_high: None,
    }
}

/// Mean ± 1.96·SE from per-card retrievability fractions.
fn mean_and_se_from_values(values: &[f64]) -> Option<(f64, f64, u64)> {
    let n = values.len() as u64;
    if n == 0 {
        return None;
    }
    let mean = values.iter().sum::<f64>() / n as f64;
    let variance = values.iter().map(|v| (v - mean).powi(2)).sum::<f64>() / n as f64;
    let se = (variance / n as f64).sqrt();
    Some((mean, se, n))
}

fn memory_section_row(values: &[f64], section: i32) -> pb::PillarSectionBreakdown {
    match mean_and_se_from_values(values) {
        Some((mean, se, n)) if n >= MIN_MEMORY_BREAKDOWN_CARDS => {
            let (low, high) = clamp_interval(mean - READINESS_Z * se, mean + READINESS_Z * se);
            available_fraction_breakdown(section, mean, low, high, n as u32)
        }
        other => {
            let n = other.map(|(_, _, n)| n).unwrap_or(0);
            give_up_section_breakdown(
                section,
                n as u32,
                format!(
                    "Study more cards in this section (need ≥{MIN_MEMORY_BREAKDOWN_CARDS}, have {n})."
                ),
            )
        }
    }
}

impl Collection {
    /// Memory pillar breakdowns: per-card FSRS retrievability grouped by MCAT
    /// section (via theme-deck mapping).
    pub(crate) fn readiness_memory_breakdowns(
        &mut self,
        deck_search: &str,
        fsrs_enabled: bool,
    ) -> Result<pb::PillarBreakdowns> {
        if !fsrs_enabled {
            return Ok(empty_breakdowns_give_up(
                "Turn on FSRS to unlock Memory breakdowns.",
            ));
        }
        let timing = self.timing_today()?;
        let guard = self.search_cards_into_table(deck_search, SortMode::NoOrder)?;
        let cards = guard.col.storage.all_searched_cards()?;
        drop(guard);

        let fsrs = FSRS::new(None).unwrap();
        let sched = SchedTimingToday {
            days_elapsed: timing.days_elapsed,
            now: TimestampSecs::now(),
            next_day_at: timing.next_day_at,
        };

        let mut deck_names: HashMap<DeckId, String> = HashMap::new();
        let mut by_section: HashMap<i32, Vec<f64>> = HashMap::new();

        for card in &cards {
            let Some(state) = card.memory_state else {
                continue;
            };
            let elapsed = card.seconds_since_last_review(&sched).unwrap_or_default();
            let r = fsrs.current_retrievability_seconds(
                state.into(),
                elapsed,
                card.decay.unwrap_or(FSRS5_DEFAULT_DECAY),
            ) as f64;
            let deck_id = card.original_or_current_deck_id();
            let deck_name = if let Some(name) = deck_names.get(&deck_id) {
                name.clone()
            } else {
                let name = self
                    .storage
                    .get_deck(deck_id)?
                    .map(|d| d.name.human_name())
                    .unwrap_or_default();
                deck_names.insert(deck_id, name.clone());
                name
            };
            // Strip any legacy parent prefix so themes match flashcard_theme_to_section.
            let theme = deck_name
                .rsplit("::")
                .next()
                .unwrap_or(&deck_name)
                .to_string();
            if let Some(section) = flashcard_theme_to_section(&theme) {
                by_section.entry(section).or_default().push(r as f64);
            }
        }

        let mut sections = Vec::new();
        for section_db in PROJECTED_SECTIONS {
            let section = section_from_db(section_db);
            let values = by_section.get(&section).map(|v| v.as_slice()).unwrap_or(&[]);
            sections.push(memory_section_row(values, section));
        }

        Ok(pb::PillarBreakdowns {
            sections,
            topics: Vec::new(),
        })
    }

    /// Performance pillar breakdowns: EWMA accuracy per MCAT section and per topic.
    pub(crate) fn readiness_performance_breakdowns(&self) -> Result<pb::PillarBreakdowns> {
        let now = TimestampSecs::now().0;
        let by_section = self
            .storage
            .practice_performance_observations_by_section_with_time()?;

        let mut sections = Vec::new();
        for section_db in PROJECTED_SECTIONS {
            let section = section_from_db(section_db);
            let obs = by_section
                .get(section_db)
                .map(|v| v.as_slice())
                .unwrap_or(&[]);
            sections.push(performance_section_row(obs, section, now));
        }

        let topics = self.performance_topic_breakdowns()?;

        Ok(pb::PillarBreakdowns { sections, topics })
    }

    /// Per-topic Performance scores (shared by pillar breakdown and recommendations).
    pub(crate) fn performance_topic_breakdowns(&self) -> Result<Vec<pb::PillarTopicBreakdown>> {
        let now = TimestampSecs::now().0;
        let by_topic = self.storage.practice_performance_observations_by_topic()?;

        let mut topic_keys: Vec<_> = by_topic.keys().cloned().collect();
        topic_keys.sort_by(|a, b| a.0.cmp(&b.0).then(a.1.cmp(&b.1)));
        Ok(topic_keys
            .into_iter()
            .filter(|(_, topic)| !is_redundant_section_topic(topic))
            .map(|(section_db, topic)| {
                let section = section_from_db(&section_db);
                let obs = by_topic
                    .get(&(section_db, topic.clone()))
                    .map(|v| v.as_slice())
                    .unwrap_or(&[]);
                performance_topic_row(obs, topic, section, now)
            })
            .collect())
    }

    /// Readiness pillar breakdowns: EWMA accuracy per MCAT section over completed
    /// full-length answers.
    pub(crate) fn readiness_full_length_breakdowns(&self) -> Result<pb::PillarBreakdowns> {
        let now = TimestampSecs::now().0;
        let by_section = self.storage.full_length_readiness_observations_by_section()?;

        let mut sections = Vec::new();
        for section_db in PROJECTED_SECTIONS {
            let section = section_from_db(section_db);
            let obs = by_section
                .get(section_db)
                .map(|v| v.as_slice())
                .unwrap_or(&[]);
            sections.push(readiness_section_row(obs, section, now));
        }

        Ok(pb::PillarBreakdowns {
            sections,
            topics: Vec::new(),
        })
    }
}

/// Empty breakdown tables with a give-up message on every section row.
pub(crate) fn empty_breakdowns_give_up(message: &str) -> pb::PillarBreakdowns {
    let msg = message.to_string();
    let sections = PROJECTED_SECTIONS
        .iter()
        .map(|s| give_up_section_breakdown(section_from_db(s), 0, msg.clone()))
        .collect();
    pb::PillarBreakdowns {
        sections,
        topics: Vec::new(),
    }
}

fn performance_section_row(
    obs: &[(f64, i64, u32)],
    section: i32,
    now: i64,
) -> pb::PillarSectionBreakdown {
    let n = obs.len() as u32;
    if n < MIN_PERFORMANCE_BREAKDOWN_ATTEMPTS {
        return give_up_section_breakdown(
            section,
            n,
            format!(
                "Answer more practice questions in this section (need \
                 ≥{MIN_PERFORMANCE_BREAKDOWN_ATTEMPTS}, have {n})."
            ),
        );
    }
    let credit_obs: Vec<(f64, i64)> = obs.iter().map(|(c, t, _)| (*c, *t)).collect();
    let time_obs: Vec<(u32, i64)> = obs.iter().map(|(_, t, s)| (*s, *t)).collect();
    let agg = ewma::ewma_aggregate(&credit_obs, ewma::PERFORMANCE_HALF_LIFE_DAYS, now);
    let avg_seconds = ewma::ewma_weighted_seconds(&time_obs, ewma::PERFORMANCE_HALF_LIFE_DAYS, now);
    let n_eff = effective_sample_size_for_wilson(agg.effective_n);
    let (value, low, high) =
        wilson_interval_fraction(agg.mean.clamp(0.0, 1.0), n_eff, READINESS_Z);
    let (value, low, high) = performance::apply_performance_time_penalty(value, low, high, avg_seconds);
    available_fraction_breakdown(section, value, low, high, n)
}

fn performance_topic_row(
    obs: &[(f64, i64, u32)],
    topic: String,
    section: i32,
    now: i64,
) -> pb::PillarTopicBreakdown {
    let n = obs.len() as u32;
    if n < MIN_PERFORMANCE_BREAKDOWN_ATTEMPTS {
        return give_up_topic_breakdown(
            topic,
            section,
            n,
            format!(
                "Answer more practice questions on this topic (need \
                 ≥{MIN_PERFORMANCE_BREAKDOWN_ATTEMPTS}, have {n})."
            ),
        );
    }
    let credit_obs: Vec<(f64, i64)> = obs.iter().map(|(c, t, _)| (*c, *t)).collect();
    let time_obs: Vec<(u32, i64)> = obs.iter().map(|(_, t, s)| (*s, *t)).collect();
    let agg = ewma::ewma_aggregate(&credit_obs, ewma::PERFORMANCE_HALF_LIFE_DAYS, now);
    let avg_seconds = ewma::ewma_weighted_seconds(&time_obs, ewma::PERFORMANCE_HALF_LIFE_DAYS, now);
    let n_eff = effective_sample_size_for_wilson(agg.effective_n);
    let (value, low, high) =
        wilson_interval_fraction(agg.mean.clamp(0.0, 1.0), n_eff, READINESS_Z);
    let (value, low, high) = performance::apply_performance_time_penalty(value, low, high, avg_seconds);
    available_topic_fraction_breakdown(topic, section, value, low, high, n)
}

fn readiness_section_row(obs: &[(f64, i64)], section: i32, now: i64) -> pb::PillarSectionBreakdown {
    let n = obs.len() as u32;
    if n < MIN_READINESS_BREAKDOWN_ANSWERS {
        return give_up_section_breakdown(
            section,
            n,
            format!(
                "Finish more full-length questions in this section (need \
                 ≥{MIN_READINESS_BREAKDOWN_ANSWERS}, have {n})."
            ),
        );
    }
    let agg = ewma::ewma_aggregate(obs, ewma::READINESS_HALF_LIFE_DAYS, now);
    let n_eff = effective_sample_size_for_wilson(agg.effective_n);
    let (value, low, high) =
        wilson_interval_fraction(agg.mean.clamp(0.0, 1.0), n_eff, READINESS_Z);
    let correct = obs.iter().filter(|(c, _)| *c >= 0.5).count() as u32;
    let scaled_score = scoring::section_scaled_score(correct, n);
    let (scaled_score, scaled_low, scaled_high) = if let Some(score) = scaled_score {
        let (_p, plo, phi) = wilson_interval(correct, n, READINESS_Z);
        (
            Some(score),
            Some(scoring::scaled_from_fraction(plo)),
            Some(scoring::scaled_from_fraction(phi)),
        )
    } else {
        (None, None, None)
    };
    pb::PillarSectionBreakdown {
        section,
        available: true,
        value,
        range_low: low,
        range_high: high,
        sample_size: n,
        message: String::new(),
        scaled_score,
        scaled_low,
        scaled_high,
    }
}

#[cfg(test)]
mod test {
    use super::*;

    use crate::collection::Collection;
    use crate::error::Result;
    use crate::storage::practice::NewAttempt;

    #[test]
    fn flashcard_themes_map_to_sections() {
        assert_eq!(
            flashcard_theme_to_section("General Chemistry"),
            Some(section_from_db("CPBS"))
        );
        assert_eq!(
            flashcard_theme_to_section("Biology"),
            Some(section_from_db("BBLS"))
        );
        assert_eq!(
            flashcard_theme_to_section("Sensation & Perception"),
            Some(section_from_db("PSBB"))
        );
        assert!(flashcard_theme_to_section("Unknown Deck").is_none());
    }

    #[test]
    fn mean_and_se_handles_empty() {
        assert!(mean_and_se_from_values(&[]).is_none());
    }

    #[test]
    fn performance_section_row_gives_up_below_minimum() {
        let row = performance_section_row(&[], section_from_db("CPBS"), 0);
        assert!(!row.available);
        assert!(!row.message.is_empty());
    }

    #[test]
    fn redundant_section_topic_matches_cars_variants() {
        assert!(is_redundant_section_topic("CARS"));
        assert!(is_redundant_section_topic("cars"));
        assert!(is_redundant_section_topic("Cars"));
        assert!(!is_redundant_section_topic("philosophy"));
        assert!(!is_redundant_section_topic("kinetics"));
    }

    #[test]
    fn performance_breakdown_omits_redundant_cars_topic() -> Result<()> {
        let col = Collection::new();
        for i in 0..10 {
            col.storage.add_practice_attempt(&NewAttempt {
                id: &format!("cars-topic:{i}"),
                session_id: Some("s1"),
                full_length_attempt_id: None,
                question_id: &format!("q{i}"),
                selected_answer: "A",
                correct: true,
                time_on_question_seconds: 60,
                section_db: "CARS",
                topic: "CARS",
                answered_at: 0,
                hint_level_used: 0,
                assisted: false,
                main_wrong_first: false,
                first_try_no_hint: Some(1),
            })?;
        }
        for i in 0..10 {
            col.storage.add_practice_attempt(&NewAttempt {
                id: &format!("phil:{i}"),
                session_id: Some("s1"),
                full_length_attempt_id: None,
                question_id: &format!("p{i}"),
                selected_answer: "A",
                correct: true,
                time_on_question_seconds: 60,
                section_db: "CARS",
                topic: "philosophy",
                answered_at: 0,
                hint_level_used: 0,
                assisted: false,
                main_wrong_first: false,
                first_try_no_hint: Some(1),
            })?;
        }
        let bd = col.readiness_performance_breakdowns()?;
        let cars_section = bd
            .sections
            .iter()
            .find(|s| s.section == section_from_db("CARS"))
            .unwrap();
        assert!(cars_section.available);
        assert_eq!(cars_section.sample_size, 20);
        assert!(bd.topics.iter().all(|t| !is_redundant_section_topic(&t.topic)));
        assert!(bd.topics.iter().any(|t| t.topic == "Philosophy" && t.available));
        Ok(())
    }
}
