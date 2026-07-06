// Copyright: Ankitects Pty Ltd and contributors
// License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

//! Engine-recommended practice topics for a session.
//!
//! Builds a topic set in stages until at least four topics are included (or all
//! bank topics are). Scores come from the Performance pillar per-topic breakdown
//! (practice-question EWMA accuracy, 0–1 fraction).

use std::collections::BTreeSet;
use std::collections::HashMap;

use anki_proto::practice as pb;

use crate::prelude::*;

use super::breakdown::is_redundant_section_topic;

/// Minimum topics before the staged expansion stops early.
pub(crate) const MIN_RECOMMENDED_TOPICS: usize = 4;
/// Performance pillar fraction below which a topic is "weak" (stage 1).
pub(crate) const WEAK_SCORE_THRESHOLD: f64 = 0.60;
/// Performance pillar fraction below which a topic is "moderate" (stage 2).
pub(crate) const MODERATE_SCORE_THRESHOLD: f64 = 0.85;

/// Per-topic Performance pillar input for the selection algorithm (unit-tested).
#[derive(Debug, Clone, PartialEq)]
pub(crate) struct TopicPerformanceScore {
    pub topic: String,
    /// `true` when the Performance breakdown has enough data for a score.
    pub available: bool,
    /// EWMA accuracy fraction in \[0, 1\] (`PillarTopicBreakdown.value`); meaningful only when `available`.
    pub score: f64,
}

/// Staged topic selection (pure). `all_topics` should be sorted for stable output.
pub(crate) fn select_recommended_practice_topics(
    all_topics: &[TopicPerformanceScore],
) -> Vec<String> {
    if all_topics.is_empty() {
        return Vec::new();
    }

    let mut selected: BTreeSet<String> = BTreeSet::new();

    for t in all_topics {
        if t.available && t.score < WEAK_SCORE_THRESHOLD {
            selected.insert(t.topic.clone());
        }
    }
    if selected.len() >= MIN_RECOMMENDED_TOPICS || selected.len() == all_topics.len() {
        return selected.into_iter().collect();
    }

    for t in all_topics {
        if t.available && t.score < MODERATE_SCORE_THRESHOLD {
            selected.insert(t.topic.clone());
        }
    }
    if selected.len() >= MIN_RECOMMENDED_TOPICS || selected.len() == all_topics.len() {
        return selected.into_iter().collect();
    }

    for t in all_topics {
        if !t.available {
            selected.insert(t.topic.clone());
        }
    }
    if selected.len() >= MIN_RECOMMENDED_TOPICS || selected.len() == all_topics.len() {
        return selected.into_iter().collect();
    }

    for t in all_topics {
        selected.insert(t.topic.clone());
    }
    selected.into_iter().collect()
}

impl Collection {
    /// Distinct `topic_tags` on free-standing practice questions (question bank).
    pub(crate) fn question_bank_topics(&self) -> Result<Vec<String>> {
        let questions = self.storage.get_questions_filtered(&[], None, None, false)?;
        let mut topics: BTreeSet<String> = BTreeSet::new();
        for q in questions {
            for tag in &q.topic_tags {
                if !is_redundant_section_topic(tag) {
                    topics.insert(tag.clone());
                }
            }
        }
        Ok(topics.into_iter().collect())
    }

    /// Recommended practice topics using Performance pillar per-topic scores.
    pub(crate) fn recommended_practice_topics(&self) -> Result<Vec<String>> {
        let bank_topics = self.question_bank_topics()?;
        if bank_topics.is_empty() {
            return Ok(Vec::new());
        }

        let topics = self.performance_topic_breakdowns()?;
        let score_by_topic = performance_scores_by_topic(&topics);

        let mut topic_scores: Vec<TopicPerformanceScore> = bank_topics
            .into_iter()
            .map(|topic| {
                let key = topic.to_lowercase();
                match score_by_topic.get(&key) {
                    Some(&(available, score)) => TopicPerformanceScore {
                        topic,
                        available,
                        score,
                    },
                    None => TopicPerformanceScore {
                        topic,
                        available: false,
                        score: 0.0,
                    },
                }
            })
            .collect();
        topic_scores.sort_by(|a, b| a.topic.cmp(&b.topic));

        Ok(select_recommended_practice_topics(&topic_scores))
    }
}

/// Lowercase topic label -> (available, score). When the same tag appears in
/// multiple sections, keep the weakest available score; any section with enough
/// data marks the topic as available.
fn performance_scores_by_topic(
    rows: &[pb::PillarTopicBreakdown],
) -> HashMap<String, (bool, f64)> {
    let mut out: HashMap<String, (bool, f64)> = HashMap::new();
    for row in rows {
        let key = row.topic.to_lowercase();
        if row.available {
            out.entry(key)
                .and_modify(|(available, score)| {
                    if *available {
                        if row.value < *score {
                            *score = row.value;
                        }
                    } else {
                        *available = true;
                        *score = row.value;
                    }
                })
                .or_insert((true, row.value));
        } else {
            out.entry(key).or_insert_with(|| (false, 0.0));
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn perf(topic: &str, available: bool, score: f64) -> TopicPerformanceScore {
        TopicPerformanceScore {
            topic: topic.to_string(),
            available,
            score,
        }
    }

    #[test]
    fn empty_bank_returns_empty() {
        assert!(select_recommended_practice_topics(&[]).is_empty());
    }

    #[test]
    fn stage1_weak_performance_topics_only() {
        let input = vec![
            perf("a", true, 0.50),
            perf("b", true, 0.55),
            perf("c", true, 0.59),
            perf("d", true, 0.40),
            perf("e", true, 0.90),
        ];
        let got = select_recommended_practice_topics(&input);
        assert_eq!(got, vec!["a", "b", "c", "d"]);
    }

    #[test]
    fn stage2_adds_moderate_when_fewer_than_four_weak() {
        let input = vec![
            perf("weak", true, 0.50),
            perf("mid-a", true, 0.70),
            perf("mid-b", true, 0.80),
            perf("mid-c", true, 0.84),
            perf("strong", true, 0.95),
        ];
        let got = select_recommended_practice_topics(&input);
        assert_eq!(got, vec!["mid-a", "mid-b", "mid-c", "weak"]);
    }

    #[test]
    fn stage3_adds_insufficient_performance_data_when_still_under_four() {
        let input = vec![
            perf("weak", true, 0.40),
            perf("nodata-a", false, 0.0),
            perf("nodata-b", false, 0.0),
            perf("nodata-c", false, 0.0),
            perf("strong", true, 0.95),
        ];
        let got = select_recommended_practice_topics(&input);
        assert_eq!(got, vec!["nodata-a", "nodata-b", "nodata-c", "weak"]);
    }

    #[test]
    fn stage4_all_topics_when_everything_high_scoring() {
        let input = vec![
            perf("a", true, 0.90),
            perf("b", true, 0.92),
            perf("c", true, 0.95),
        ];
        let got = select_recommended_practice_topics(&input);
        assert_eq!(got, vec!["a", "b", "c"]);
    }

    #[test]
    fn all_insufficient_performance_data_returns_all_topics() {
        let input = vec![
            perf("a", false, 0.0),
            perf("b", false, 0.0),
            perf("c", false, 0.0),
        ];
        let got = select_recommended_practice_topics(&input);
        assert_eq!(got, vec!["a", "b", "c"]);
    }

    #[test]
    fn exactly_three_weak_expands_via_stage2() {
        let input = vec![
            perf("w1", true, 0.30),
            perf("w2", true, 0.40),
            perf("w3", true, 0.50),
            perf("m1", true, 0.75),
            perf("high", true, 0.99),
        ];
        let got = select_recommended_practice_topics(&input);
        assert_eq!(got, vec!["m1", "w1", "w2", "w3"]);
    }

    #[test]
    fn threshold_boundaries_are_exclusive_at_85_percent() {
        let input = vec![
            perf("at85", true, 0.85),
            perf("below", true, 0.849),
            perf("high", true, 0.99),
        ];
        let got = select_recommended_practice_topics(&input);
        // Only "below" is < 0.85 in stage 1/2; then insufficient + all stages
        assert!(got.contains(&"below".to_string()));
    }

    #[test]
    fn performance_scores_merge_weakest_across_sections() {
        let rows = vec![
            pb::PillarTopicBreakdown {
                topic: "Kinetics".into(),
                section: 1,
                available: false,
                value: 0.0,
                range_low: 0.0,
                range_high: 0.0,
                sample_size: 3,
                message: "need more".into(),
                scaled_score: None,
                scaled_low: None,
                scaled_high: None,
            },
            pb::PillarTopicBreakdown {
                topic: "Kinetics".into(),
                section: 2,
                available: true,
                value: 0.55,
                range_low: 0.4,
                range_high: 0.7,
                sample_size: 12,
                message: String::new(),
                scaled_score: None,
                scaled_low: None,
                scaled_high: None,
            },
        ];
        let map = performance_scores_by_topic(&rows);
        assert_eq!(map.get("kinetics"), Some(&(true, 0.55)));
    }

    #[test]
    fn format_topic_display_does_not_break_lookup() {
        use super::super::topic_display::format_topic_display;
        let rows = vec![pb::PillarTopicBreakdown {
            topic: format_topic_display("acids and bases"),
            section: 1,
            available: true,
            value: 0.45,
            range_low: 0.3,
            range_high: 0.6,
            sample_size: 12,
            message: String::new(),
            scaled_score: None,
            scaled_low: None,
            scaled_high: None,
        }];
        let map = performance_scores_by_topic(&rows);
        assert_eq!(map.get("acids and bases"), Some(&(true, 0.45)));
    }
}
