// Copyright: Ankitects Pty Ltd and contributors
// License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

//! SpeedyCAT: [`PracticeService`](crate::services::PracticeService)
//! implementation — content import, question/passage queries, practice
//! sessions, full-length attempts, and per-topic tracking.

use std::collections::BTreeMap;
use std::collections::HashMap;

use anki_proto::practice as pb;
use rand::rngs::StdRng;
use rand::seq::SliceRandom;
use rand::SeedableRng;

use crate::practice::attempt_source_clause;
use crate::practice::difficulty_to_db;
use crate::practice::new_full_length_attempt_id;
use crate::practice::new_session_id;
use crate::practice::section_from_db;
use crate::practice::section_to_db;
use crate::practice::seed_from_str;
use crate::practice::StoredSectionResult;
use crate::prelude::*;
use crate::storage::practice::NewAttempt;

fn read_bundle_input(input: &pb::LoadBundleRequest) -> Result<String> {
    if !input.path.is_empty() {
        Ok(anki_io::read_to_string(&input.path)?)
    } else {
        require!(
            !input.json.is_empty(),
            "load bundle: either path or json must be provided"
        );
        Ok(input.json.clone())
    }
}

impl crate::services::PracticeService for Collection {
    // ---- Content import ---------------------------------------------------

    fn load_practice_question_bundle(
        &mut self,
        input: pb::LoadBundleRequest,
    ) -> Result<pb::LoadBundleResponse> {
        let json = read_bundle_input(&input)?;
        let replace = input.replace;
        self.transact_no_undo(|col| col.load_practice_question_bundle_json(&json, replace))
    }

    fn load_full_length_test_bundle(
        &mut self,
        input: pb::LoadBundleRequest,
    ) -> Result<pb::LoadBundleResponse> {
        let json = read_bundle_input(&input)?;
        let replace = input.replace;
        self.transact_no_undo(|col| col.load_full_length_bundle_json(&json, replace))
    }

    // ---- Querying content -------------------------------------------------

    fn get_practice_questions(
        &mut self,
        input: pb::GetPracticeQuestionsRequest,
    ) -> Result<pb::GetPracticeQuestionsResponse> {
        let filter = input.filter.unwrap_or_default();
        let questions = self.matching_questions(&filter)?;
        Ok(pb::GetPracticeQuestionsResponse { questions })
    }

    fn get_cars_passage_set(
        &mut self,
        input: pb::GetCarsPassageSetRequest,
    ) -> Result<pb::CarsPassageSet> {
        let passage = self
            .storage
            .get_practice_passage(&input.passage_id)?
            .or_invalid(format!("unknown passage_id: {}", input.passage_id))?;
        let questions = self.storage.questions_for_passage(&input.passage_id)?;
        Ok(pb::CarsPassageSet {
            passage: Some(passage),
            questions,
        })
    }

    fn list_passages(&mut self, input: pb::ListPassagesRequest) -> Result<pb::ListPassagesResponse> {
        let section_db = input.section.map(|s| section_to_db(s).to_string());
        let passages = self
            .storage
            .list_passages(section_db.as_deref(), input.test_id.as_deref())?;
        Ok(pb::ListPassagesResponse { passages })
    }

    // ---- Practice sessions ------------------------------------------------

    fn start_practice_session(
        &mut self,
        input: pb::StartPracticeSessionRequest,
    ) -> Result<pb::StartPracticeSessionResponse> {
        let filter = input.filter.unwrap_or_default();
        // The session id (which carries randomness) seeds every shuffle, so the
        // selection + choice order are reproducible within the session but
        // differ across sessions.
        let session_id = new_session_id();
        let seed = seed_from_str(&session_id);
        // Shuffle the pool (at passage-set granularity) before applying the
        // count limit, then shuffle each served question's answer choices.
        let pool = self.filtered_questions(&filter)?;
        let mut questions = assemble_serving_order(pool, filter.limit, Some(seed));
        for question in &mut questions {
            shuffle_question_choices(question, &session_id);
        }
        let question_order: Vec<String> = questions.iter().map(|q| q.id.clone()).collect();
        let filter_json = serde_json::json!({
            "sections": filter.sections,
            "topics": filter.topics,
            "difficulty": filter.difficulty,
            "passage_id": filter.passage_id,
            "missed_only": filter.missed_only,
            "include_full_length": filter.include_full_length,
            "limit": filter.limit,
            "time_limit_seconds": input.time_limit_seconds,
            // Persist the chosen order (and its seed) so the session's selection
            // is pinned at creation time.
            "seed": seed,
            "question_order": question_order,
        })
        .to_string();
        let time_limit = input.time_limit_seconds;
        let started_at = TimestampSecs::now().0;
        self.transact_no_undo(|col| {
            col.storage
                .add_practice_session(&session_id, &filter_json, time_limit, started_at)
        })?;
        Ok(pb::StartPracticeSessionResponse {
            session_id,
            questions,
        })
    }

    fn record_practice_attempt(
        &mut self,
        input: pb::RecordPracticeAttemptRequest,
    ) -> Result<pb::RecordAttemptResponse> {
        let (_, completed_at) = self
            .storage
            .get_practice_session(&input.session_id)?
            .or_invalid(format!("unknown session_id: {}", input.session_id))?;
        require!(
            completed_at.is_none(),
            "session {} is already completed",
            input.session_id
        );
        // One (final) attempt row per (session, question); a re-answer replaces.
        let attempt_id = format!("{}:{}", input.session_id, input.question_id);
        let section_db = section_to_db(input.section).to_string();
        let answered_at = TimestampSecs::now().0;
        let attempt = NewAttempt {
            id: &attempt_id,
            session_id: Some(&input.session_id),
            full_length_attempt_id: None,
            question_id: &input.question_id,
            selected_answer: &input.selected_answer,
            correct: input.correct,
            time_on_question_seconds: input.time_on_question_seconds,
            section_db: &section_db,
            topic: &input.topic,
            answered_at,
        };
        self.transact_no_undo(|col| col.storage.add_practice_attempt(&attempt))?;
        Ok(pb::RecordAttemptResponse { attempt_id })
    }

    fn end_practice_session(
        &mut self,
        input: pb::EndPracticeSessionRequest,
    ) -> Result<pb::PracticeSessionSummary> {
        let (_, completed_at) = self
            .storage
            .get_practice_session(&input.session_id)?
            .or_invalid(format!("unknown session_id: {}", input.session_id))?;
        let attempts = self.storage.practice_session_attempts(&input.session_id)?;

        let total = attempts.len() as u32;
        let correct = attempts.iter().filter(|a| a.0).count() as u32;
        let unanswered = attempts.iter().filter(|a| !a.0 && a.3.is_empty()).count() as u32;
        let incorrect = total - correct - unanswered;
        let total_time_seconds: u32 = attempts.iter().map(|a| a.1).sum();

        // (correct, total) per section.
        let mut per_section: BTreeMap<String, (u32, u32)> = BTreeMap::new();
        for (is_correct, _time, section, _selected) in &attempts {
            let entry = per_section.entry(section.clone()).or_default();
            entry.1 += 1;
            if *is_correct {
                entry.0 += 1;
            }
        }
        let section_breakdown = per_section
            .into_iter()
            .map(|(section, (c, t))| pb::SectionCount {
                section: section_from_db(&section),
                correct: c,
                total: t,
            })
            .collect();

        if completed_at.is_none() {
            let now = TimestampSecs::now().0;
            let session_id = input.session_id.clone();
            self.transact_no_undo(|col| col.storage.complete_practice_session(&session_id, now))?;
        }

        Ok(pb::PracticeSessionSummary {
            session_id: input.session_id,
            total,
            correct,
            incorrect,
            unanswered,
            total_time_seconds,
            section_breakdown,
        })
    }

    // ---- Full-length practice tests ---------------------------------------

    fn list_full_length_tests(
        &mut self,
        _input: pb::ListFullLengthTestsRequest,
    ) -> Result<pb::ListFullLengthTestsResponse> {
        let tests = self.storage.list_full_length_tests()?;
        Ok(pb::ListFullLengthTestsResponse { tests })
    }

    fn get_full_length_test(
        &mut self,
        input: pb::GetFullLengthTestRequest,
    ) -> Result<pb::FullLengthTest> {
        self.storage
            .get_full_length_test(&input.test_id)?
            .or_invalid(format!("unknown test_id: {}", input.test_id))
    }

    fn start_full_length_attempt(
        &mut self,
        input: pb::StartFullLengthAttemptRequest,
    ) -> Result<pb::StartFullLengthAttemptResponse> {
        let test = self
            .storage
            .get_full_length_test(&input.test_id)?
            .or_invalid(format!("unknown test_id: {}", input.test_id))?;
        let attempt_id = new_full_length_attempt_id();
        let started_at = TimestampSecs::now().0;
        let test_id = input.test_id.clone();
        let aamc = input.aamc_exam_id.clone();
        self.transact_no_undo(|col| {
            col.storage
                .add_full_length_attempt(&attempt_id, &test_id, aamc.as_deref(), started_at)
        })?;
        Ok(pb::StartFullLengthAttemptResponse {
            attempt_id,
            test: Some(test),
        })
    }

    fn record_full_length_answer(
        &mut self,
        input: pb::RecordFullLengthAnswerRequest,
    ) -> Result<pb::RecordAttemptResponse> {
        let (_, completed_at) = self
            .storage
            .get_full_length_attempt(&input.attempt_id)?
            .or_invalid(format!("unknown attempt_id: {}", input.attempt_id))?;
        require!(
            completed_at.is_none(),
            "full-length attempt {} is already submitted",
            input.attempt_id
        );
        // One (final) answer row per (attempt, question); a re-answer replaces.
        let attempt_id = format!("{}:{}", input.attempt_id, input.question_id);
        let section_db = section_to_db(input.section).to_string();
        let answered_at = TimestampSecs::now().0;
        let attempt = NewAttempt {
            id: &attempt_id,
            session_id: None,
            full_length_attempt_id: Some(&input.attempt_id),
            question_id: &input.question_id,
            selected_answer: &input.selected_answer,
            correct: input.correct,
            time_on_question_seconds: input.time_on_question_seconds,
            section_db: &section_db,
            topic: &input.topic,
            answered_at,
        };
        self.transact_no_undo(|col| col.storage.add_practice_attempt(&attempt))?;
        Ok(pb::RecordAttemptResponse { attempt_id })
    }

    fn submit_full_length_attempt(
        &mut self,
        input: pb::SubmitFullLengthAttemptRequest,
    ) -> Result<pb::FullLengthReport> {
        let (test_id, _completed_at) = self
            .storage
            .get_full_length_attempt(&input.attempt_id)?
            .or_invalid(format!("unknown attempt_id: {}", input.attempt_id))?;
        let counts = self.storage.full_length_section_counts(&input.attempt_id)?;

        let mut section_results = Vec::new();
        let mut stored_results = Vec::new();
        let mut total_correct = 0u32;
        let mut total_questions = 0u32;
        for (section_db, correct, total, time) in &counts {
            total_correct += correct;
            total_questions += total;
            section_results.push(pb::SectionResult {
                section: section_from_db(section_db),
                correct: *correct,
                total: *total,
                time_seconds: *time,
                // Scaled scores require licensed AAMC scoring data; the
                // AI-generated proof-of-concept forms do not provide it.
                scaled_score: None,
            });
            stored_results.push(StoredSectionResult {
                section: section_db.clone(),
                correct: *correct,
                total: *total,
                time_seconds: *time,
                scaled_score: None,
            });
        }

        let results_json = serde_json::to_string(&stored_results)?;
        let now = TimestampSecs::now().0;
        let attempt_id = input.attempt_id.clone();
        self.transact_no_undo(|col| {
            col.storage
                .complete_full_length_attempt(&attempt_id, &results_json, None, now)
        })?;

        Ok(pb::FullLengthReport {
            attempt_id: input.attempt_id,
            test_id,
            section_results,
            overall_scaled_score: None,
            total_correct,
            total_questions,
        })
    }

    // ---- Tracking ---------------------------------------------------------

    fn get_topic_stats(
        &mut self,
        input: pb::GetTopicStatsRequest,
    ) -> Result<pb::GetTopicStatsResponse> {
        let section_db = input.section.map(|s| section_to_db(s).to_string());
        let source_clause = attempt_source_clause(input.source);
        let topics = self
            .storage
            .topic_stats(section_db.as_deref(), source_clause)?;
        let sections = self
            .storage
            .section_stats(section_db.as_deref(), source_clause)?;
        Ok(pb::GetTopicStatsResponse { topics, sections })
    }

    // ---- Exam readiness ---------------------------------------------------

    fn get_readiness(
        &mut self,
        input: pb::GetReadinessRequest,
    ) -> Result<pb::GetReadinessResponse> {
        let memory = self.readiness_memory(&input.deck_search)?;
        let (performance, performance_avg_seconds) = self.readiness_performance()?;
        let (readiness, section_scores) = self.readiness_full_length()?;
        Ok(pb::GetReadinessResponse {
            memory: Some(memory),
            performance: Some(performance),
            readiness: Some(readiness),
            section_scores,
            performance_avg_seconds,
        })
    }
}

impl Collection {
    /// The pool of questions matching a [`pb::QuestionFilter`]'s structural
    /// filters (section/difficulty/passage/missed run in SQL) plus topic
    /// matching (ANY, case-insensitive). No grouping, shuffling or limit — those
    /// are applied by [`assemble_serving_order`].
    fn filtered_questions(
        &self,
        filter: &pb::QuestionFilter,
    ) -> Result<Vec<pb::PracticeQuestion>> {
        // `sections` matches ANY selected section; the unspecified sentinel and
        // an empty list both mean "all sections".
        let sections_db: Vec<String> = filter
            .sections
            .iter()
            .filter(|s| **s != pb::McatSection::Unspecified as i32)
            .map(|s| section_to_db(*s).to_string())
            .collect();
        let difficulty_db = filter.difficulty.map(|d| difficulty_to_db(d).to_string());
        let mut questions = self.storage.get_questions_filtered(
            &sections_db,
            difficulty_db.as_deref(),
            filter.passage_id.as_deref(),
            filter.include_full_length,
            filter.missed_only,
        )?;
        if !filter.topics.is_empty() {
            let wanted: Vec<String> = filter.topics.iter().map(|t| t.to_lowercase()).collect();
            questions.retain(|q| {
                q.topic_tags
                    .iter()
                    .any(|tag| wanted.iter().any(|w| *w == tag.to_lowercase()))
            });
        }
        Ok(questions)
    }

    /// Deterministic query result (stable first-seen order) used by the
    /// stateless `get_practice_questions` RPC. Practice *sessions* shuffle
    /// instead (see `start_practice_session`).
    fn matching_questions(&self, filter: &pb::QuestionFilter) -> Result<Vec<pb::PracticeQuestion>> {
        let questions = self.filtered_questions(filter)?;
        Ok(assemble_serving_order(questions, filter.limit, None))
    }
}

// ---- Exam readiness (3-pillar deterministic metric) ------------------------

/// z-score for a two-sided 95% interval (used by every pillar's range).
const READINESS_Z: f64 = 1.96;
/// Minimum reviewed cards before the Memory pillar reports (else it gives up).
const MIN_MEMORY_CARDS: u64 = 30;
/// Minimum answered practice questions before the Performance pillar reports.
const MIN_PERFORMANCE_ATTEMPTS: u32 = 30;

impl Collection {
    /// Pillar 1 — Memory: mean FSRS retrievability over the reviewed cards
    /// matched by `deck_search`, taken from the stock stats graph data (so no
    /// FSRS math is re-implemented). Gives up when FSRS is off or fewer than
    /// [`MIN_MEMORY_CARDS`] cards have a retrievability.
    fn readiness_memory(&mut self, deck_search: &str) -> Result<pb::ReadinessPillar> {
        const SOURCE: &str =
            "Anki FSRS retrievability (StatsService graph data → retrievability.average)";
        const METHOD: &str = "mean ± 1.96·SE of per-card FSRS retrievability";
        let graphs = self.graph_data_for_search(deck_search, 0)?;
        if !graphs.fsrs {
            return Ok(give_up_pillar(
                SOURCE,
                METHOD,
                "Turn on FSRS to unlock your Memory score.".to_string(),
                0,
            ));
        }
        let retr = graphs.retrievability.unwrap_or_default();
        match memory_mean_and_se(&retr.retrievability, retr.average as f64) {
            Some((mean, se, n)) if n >= MIN_MEMORY_CARDS => {
                let (low, high) = clamp_interval(mean - READINESS_Z * se, mean + READINESS_Z * se);
                Ok(pb::ReadinessPillar {
                    available: true,
                    value: mean,
                    range_low: low,
                    range_high: high,
                    sample_size: n as u32,
                    source: SOURCE.to_string(),
                    method: METHOD.to_string(),
                    message: String::new(),
                })
            }
            other => {
                let n = other.map(|(_, _, n)| n).unwrap_or(0);
                Ok(give_up_pillar(
                    SOURCE,
                    METHOD,
                    format!(
                        "Study more MCAT cards to unlock your Memory score (need ≥{MIN_MEMORY_CARDS} \
                         reviewed cards, have {n})."
                    ),
                    n as u32,
                ))
            }
        }
    }

    /// Pillar 2 — Performance: accuracy over answered practice questions, with a
    /// Wilson 95% interval. Also returns the average seconds per question.
    fn readiness_performance(&self) -> Result<(pb::ReadinessPillar, f64)> {
        const SOURCE: &str = "SpeedyCAT practice sessions (practice_attempts store)";
        const METHOD: &str = "practice accuracy with a Wilson 95% score interval";
        let (correct, answered, total_time) = self.storage.practice_accuracy_totals()?;
        if answered < MIN_PERFORMANCE_ATTEMPTS {
            return Ok((
                give_up_pillar(
                    SOURCE,
                    METHOD,
                    format!(
                        "Answer more practice questions to unlock your Performance score (need \
                         ≥{MIN_PERFORMANCE_ATTEMPTS} answered, have {answered})."
                    ),
                    answered,
                ),
                0.0,
            ));
        }
        let (value, low, high) = wilson_interval(correct, answered, READINESS_Z);
        let avg_seconds = total_time as f64 / answered as f64;
        Ok((
            pb::ReadinessPillar {
                available: true,
                value,
                range_low: low,
                range_high: high,
                sample_size: answered,
                source: SOURCE.to_string(),
                method: METHOD.to_string(),
                message: String::new(),
            },
            avg_seconds,
        ))
    }

    /// Pillar 3 — Readiness: raw score (correct ÷ total) across COMPLETED
    /// full-length tests only, with a Wilson 95% interval and a per-section
    /// breakdown. Deliberately excludes timing/pacing, breaks, question
    /// distribution and everything outside the full-length tests.
    fn readiness_full_length(
        &self,
    ) -> Result<(pb::ReadinessPillar, Vec<pb::ReadinessSectionScore>)> {
        const SOURCE: &str = "SpeedyCAT full-length tests (completed attempts, raw score)";
        const METHOD: &str =
            "raw correct ÷ total across completed full-length tests, with a Wilson 95% interval";
        let (correct, total) = self.storage.full_length_score_totals()?;
        if total == 0 {
            return Ok((
                give_up_pillar(
                    SOURCE,
                    METHOD,
                    "Finish a full-length test to unlock your Readiness score.".to_string(),
                    0,
                ),
                Vec::new(),
            ));
        }
        let (value, low, high) = wilson_interval(correct, total, READINESS_Z);
        let section_scores = self
            .storage
            .full_length_section_scores()?
            .into_iter()
            .map(|(section, correct, total)| pb::ReadinessSectionScore {
                section: section_from_db(&section),
                correct,
                total,
            })
            .collect();
        Ok((
            pb::ReadinessPillar {
                available: true,
                value,
                range_low: low,
                range_high: high,
                sample_size: total,
                source: SOURCE.to_string(),
                method: METHOD.to_string(),
                message: String::new(),
            },
            section_scores,
        ))
    }
}

/// Build a pillar in its "gave up" state (a prerequisite is off, or there is
/// not enough data): no value/range, just the sample size and an explanation.
fn give_up_pillar(
    source: &str,
    method: &str,
    message: String,
    sample_size: u32,
) -> pb::ReadinessPillar {
    pb::ReadinessPillar {
        available: false,
        value: 0.0,
        range_low: 0.0,
        range_high: 0.0,
        sample_size,
        source: source.to_string(),
        method: method.to_string(),
        message,
    }
}

/// Clamp an interval's endpoints to the valid [0, 1] fraction range.
fn clamp_interval(low: f64, high: f64) -> (f64, f64) {
    (low.clamp(0.0, 1.0), high.clamp(0.0, 1.0))
}

/// Wilson score interval for a binomial proportion at `z` (1.96 ≈ 95%). Returns
/// (point estimate, low, high) as fractions in [0, 1]. The point estimate is the
/// raw proportion; the Wilson interval is well-behaved for small samples and
/// near 0/1, and never leaves [0, 1].
fn wilson_interval(correct: u32, total: u32, z: f64) -> (f64, f64, f64) {
    if total == 0 {
        return (0.0, 0.0, 0.0);
    }
    let n = total as f64;
    let p = (correct as f64 / n).clamp(0.0, 1.0);
    let z2 = z * z;
    let denom = 1.0 + z2 / n;
    let center = (p + z2 / (2.0 * n)) / denom;
    let margin = z * ((p * (1.0 - p) / n) + z2 / (4.0 * n * n)).sqrt() / denom;
    let (low, high) = clamp_interval(center - margin, center + margin);
    (p, low, high)
}

/// Estimate the mean and standard error of per-card FSRS retrievability from the
/// stock stats retrievability histogram (bin = floor-percent → card count) plus
/// the exact mean percentage reported alongside it. Returns (mean, se, n) as
/// fractions in [0, 1], or `None` when no card has a retrievability. The mean
/// comes straight from the exact `average`; the SE is estimated from the 1%-wide
/// histogram bins, so no FSRS math is re-implemented here.
fn memory_mean_and_se(hist: &HashMap<u32, u32>, average_percent: f64) -> Option<(f64, f64, u64)> {
    let n: u64 = hist.values().map(|c| *c as u64).sum();
    if n == 0 {
        return None;
    }
    let mean = (average_percent / 100.0).clamp(0.0, 1.0);
    // Sum of squared deviations, treating each 1%-wide bin's centre as the
    // representative retrievability of the cards it holds.
    let mut sum_sq_dev = 0.0f64;
    for (bin, count) in hist {
        let representative = ((*bin as f64) + 0.5) / 100.0;
        sum_sq_dev += (representative - mean).powi(2) * (*count as f64);
    }
    let variance = sum_sq_dev / n as f64;
    let se = (variance / n as f64).sqrt();
    Some((mean, se, n))
}

/// Assemble the serving order for a set of matched questions.
///
/// CARS practice must always deliver a **complete passage set** — one passage
/// plus *all* of its questions, served together — so questions that hang off a
/// passage are grouped into an atomic unit and the limit is applied at the group
/// granularity: it never splits a passage set, and it always serves at least one
/// group. Discrete (non-passage) questions each form their own singleton group.
///
/// When `shuffle_seed` is `Some`, the *groups* are shuffled with a seeded RNG
/// before the limit is applied — so each session draws a random subset/order,
/// while CARS passage sets stay whole and contiguous (only the sets are
/// reordered, not the questions inside a set). With `None` the deterministic
/// first-seen order is preserved (equivalent to a plain truncate-to-limit for a
/// discrete-only bank).
fn assemble_serving_order(
    questions: Vec<pb::PracticeQuestion>,
    limit: u32,
    shuffle_seed: Option<u64>,
) -> Vec<pb::PracticeQuestion> {
    let mut order: Vec<String> = Vec::new();
    let mut groups: std::collections::HashMap<String, Vec<pb::PracticeQuestion>> =
        std::collections::HashMap::new();
    for (idx, q) in questions.into_iter().enumerate() {
        // Passage-linked questions group by passage id; everything else is a
        // singleton keyed by its position so it is never merged.
        let key = match q.passage_id.as_deref() {
            Some(pid) if !pid.is_empty() => format!("p:{pid}"),
            _ => format!("q:{idx}"),
        };
        if !groups.contains_key(&key) {
            order.push(key.clone());
        }
        groups.entry(key).or_default().push(q);
    }
    let mut group_list: Vec<Vec<pb::PracticeQuestion>> = order
        .into_iter()
        .map(|key| groups.remove(&key).expect("group was inserted above"))
        .collect();
    if let Some(seed) = shuffle_seed {
        let mut rng = StdRng::seed_from_u64(seed);
        group_list.shuffle(&mut rng);
    }
    let mut result: Vec<pb::PracticeQuestion> = Vec::new();
    for group in group_list {
        // Keep passage sets whole: stop before a group that would push us over
        // the limit, but always serve at least one group.
        if limit > 0 && !result.is_empty() && result.len() + group.len() > limit as usize {
            break;
        }
        result.extend(group);
    }
    result
}

/// Shuffle a question's answer choices for a session (stable per `session_id` +
/// question). The positional labels (A, B, C, …) stay in order; only the choice
/// *texts* are reordered, and `correct_answer` is remapped to the new label of
/// the originally-correct choice — so the post-submit correct/incorrect reveal
/// stays right.
fn shuffle_question_choices(question: &mut pb::PracticeQuestion, session_id: &str) {
    if question.choices.len() < 2 {
        return;
    }
    let seed = seed_from_str(&format!("{session_id}:{}", question.id));
    let mut rng = StdRng::seed_from_u64(seed);
    let positional_labels: Vec<String> = question.choices.iter().map(|c| c.label.clone()).collect();
    let mut order: Vec<usize> = (0..question.choices.len()).collect();
    order.shuffle(&mut rng);
    let mut new_choices = Vec::with_capacity(question.choices.len());
    let mut new_correct = question.correct_answer.clone();
    for (position, &orig_idx) in order.iter().enumerate() {
        let new_label = positional_labels[position].clone();
        let orig = &question.choices[orig_idx];
        if orig.label == question.correct_answer {
            new_correct = new_label.clone();
        }
        new_choices.push(pb::AnswerChoice {
            label: new_label,
            text: orig.text.clone(),
        });
    }
    question.choices = new_choices;
    question.correct_answer = new_correct;
}

#[cfg(test)]
mod test {
    use std::collections::HashMap;

    use anki_proto::practice as pb;

    use crate::collection::Collection;
    use crate::error::Result;
    use crate::services::PracticeService;
    use crate::storage::practice::NewAttempt;

    fn cpbs() -> i32 {
        pb::McatSection::Cpbs as i32
    }

    fn cars() -> i32 {
        pb::McatSection::Cars as i32
    }

    fn bbls() -> i32 {
        pb::McatSection::Bbls as i32
    }

    fn record(
        col: &mut Collection,
        session_id: &str,
        question_id: &str,
        correct: bool,
        time: u32,
        section: i32,
        topic: &str,
    ) {
        let _ = col
            .record_practice_attempt(pb::RecordPracticeAttemptRequest {
                session_id: session_id.to_string(),
                question_id: question_id.to_string(),
                selected_answer: if correct { "A".into() } else { "B".into() },
                correct,
                time_on_question_seconds: time,
                section,
                topic: topic.to_string(),
            })
            .unwrap();
    }

    fn topic<'a>(
        resp: &'a pb::GetTopicStatsResponse,
        section: i32,
        name: &str,
    ) -> &'a pb::TopicStat {
        resp.topics
            .iter()
            .find(|t| t.section == section && t.topic == name)
            .unwrap_or_else(|| panic!("missing topic stat {name}"))
    }

    fn section(resp: &pb::GetTopicStatsResponse, s: i32) -> &pb::SectionStat {
        resp.sections
            .iter()
            .find(|x| x.section == s)
            .unwrap_or_else(|| panic!("missing section stat"))
    }

    /// record_practice_attempt -> get_topic_stats returns correct per-topic
    /// accuracy + time-spent, plus per-section rollups.
    #[test]
    fn topic_stats_accuracy_and_time() -> Result<()> {
        let mut col = Collection::new();
        let session = col
            .start_practice_session(pb::StartPracticeSessionRequest {
                filter: None,
                time_limit_seconds: 0,
            })?
            .session_id;

        record(&mut col, &session, "q1", true, 30, cpbs(), "acids and bases");
        record(&mut col, &session, "q2", false, 50, cpbs(), "acids and bases");
        record(&mut col, &session, "q3", true, 20, cpbs(), "kinetics");
        record(&mut col, &session, "q4", true, 100, cars(), "philosophy");

        let stats = col.get_topic_stats(pb::GetTopicStatsRequest {
            section: None,
            source: pb::AttemptSource::All as i32,
        })?;

        let acids = topic(&stats, cpbs(), "acids and bases");
        assert_eq!(acids.attempts, 2);
        assert_eq!(acids.correct, 1);
        assert_eq!(acids.total_time_seconds, 80);
        assert_eq!(acids.accuracy, 0.5);
        assert_eq!(acids.avg_time_seconds, 40.0);

        let kinetics = topic(&stats, cpbs(), "kinetics");
        assert_eq!(kinetics.attempts, 1);
        assert_eq!(kinetics.correct, 1);
        assert_eq!(kinetics.accuracy, 1.0);
        assert_eq!(kinetics.total_time_seconds, 20);

        let philosophy = topic(&stats, cars(), "philosophy");
        assert_eq!(philosophy.attempts, 1);
        assert_eq!(philosophy.total_time_seconds, 100);

        // Per-section rollups.
        let cpbs_stat = section(&stats, cpbs());
        assert_eq!(cpbs_stat.attempts, 3);
        assert_eq!(cpbs_stat.correct, 2);
        assert_eq!(cpbs_stat.total_time_seconds, 100);
        assert!((cpbs_stat.accuracy - 2.0 / 3.0).abs() < 1e-9);

        let cars_stat = section(&stats, cars());
        assert_eq!(cars_stat.attempts, 1);
        assert_eq!(cars_stat.total_time_seconds, 100);
        Ok(())
    }

    /// get_topic_stats honours the section filter and the attempt-source filter
    /// (practice-session vs full-length attempts).
    #[test]
    fn topic_stats_section_and_source_filters() -> Result<()> {
        let mut col = Collection::new();

        // A practice-session attempt and a full-length attempt on the same
        // (section, topic), inserted directly to exercise both sources.
        col.storage.add_practice_attempt(&NewAttempt {
            id: "a-session",
            session_id: Some("s1"),
            full_length_attempt_id: None,
            question_id: "q1",
            selected_answer: "A",
            correct: true,
            time_on_question_seconds: 10,
            section_db: "CPBS",
            topic: "thermodynamics",
            answered_at: 0,
        })?;
        col.storage.add_practice_attempt(&NewAttempt {
            id: "a-fl",
            session_id: None,
            full_length_attempt_id: Some("fla1"),
            question_id: "q2",
            selected_answer: "B",
            correct: false,
            time_on_question_seconds: 20,
            section_db: "CPBS",
            topic: "thermodynamics",
            answered_at: 0,
        })?;

        // ALL: both attempts combine.
        let all = col.get_topic_stats(pb::GetTopicStatsRequest {
            section: None,
            source: pb::AttemptSource::All as i32,
        })?;
        let combined = topic(&all, cpbs(), "thermodynamics");
        assert_eq!(combined.attempts, 2);
        assert_eq!(combined.correct, 1);
        assert_eq!(combined.total_time_seconds, 30);
        assert_eq!(combined.accuracy, 0.5);

        // Practice-session only.
        let ps = col.get_topic_stats(pb::GetTopicStatsRequest {
            section: None,
            source: pb::AttemptSource::PracticeSession as i32,
        })?;
        let ps_topic = topic(&ps, cpbs(), "thermodynamics");
        assert_eq!(ps_topic.attempts, 1);
        assert_eq!(ps_topic.correct, 1);
        assert_eq!(ps_topic.accuracy, 1.0);

        // Full-length only.
        let fl = col.get_topic_stats(pb::GetTopicStatsRequest {
            section: None,
            source: pb::AttemptSource::FullLength as i32,
        })?;
        let fl_topic = topic(&fl, cpbs(), "thermodynamics");
        assert_eq!(fl_topic.attempts, 1);
        assert_eq!(fl_topic.correct, 0);
        assert_eq!(fl_topic.accuracy, 0.0);

        // Section filter excludes unrelated sections.
        let cars_only = col.get_topic_stats(pb::GetTopicStatsRequest {
            section: Some(cars()),
            source: pb::AttemptSource::All as i32,
        })?;
        assert!(cars_only.topics.is_empty());
        assert!(cars_only.sections.is_empty());
        Ok(())
    }

    /// The question-bank loader ingests both discrete and passage-set items and
    /// they are then queryable, including the CARS passage+questions grouping.
    #[test]
    fn loads_and_queries_question_bundle() -> Result<()> {
        let mut col = Collection::new();
        let json = r#"{
            "meta": {"title": "test"},
            "questions": [
                {"id": "cpbs-001", "section": "CPBS", "stem": "q?",
                 "choices": [{"label":"A","text":"x"},{"label":"B","text":"y"}],
                 "correctAnswer": "A", "explanation": "because",
                 "topicTags": ["general chemistry"], "difficulty": "easy",
                 "sourceName": "OpenStax", "sourceLicense": "CC BY 4.0"},
                {"id": "cars-001", "section": "CARS", "passageId": "p1",
                 "passage": "Long passage text.", "stem": "main idea?",
                 "choices": [{"label":"A","text":"x"},{"label":"B","text":"y"}],
                 "correctAnswer": "B", "explanation": "e",
                 "topicTags": ["humanities"], "difficulty": "medium",
                 "sourceName": "AI-generated", "sourceLicense": "POC"}
            ],
            "passageSets": [
                {"passageId": "p2", "section": "CARS", "title": "Set",
                 "passage": "Another passage.", "discipline": "humanities",
                 "wordCount": 3, "topicTags": ["ethics"], "difficulty": "hard",
                 "sourceName": "AI-generated", "sourceLicense": "POC",
                 "questions": [
                    {"id": "cars-100", "section": "CARS", "passageId": "p2",
                     "stem": "q?", "choices": [{"label":"A","text":"x"},{"label":"B","text":"y"}],
                     "correctAnswer": "A", "explanation": "e",
                     "topicTags": ["ethics"], "difficulty": "hard",
                     "sourceName": "AI-generated", "sourceLicense": "POC"}
                 ]}
            ]
        }"#;
        let resp = col.load_practice_question_bundle(pb::LoadBundleRequest {
            path: String::new(),
            json: json.to_string(),
            replace: true,
        })?;
        assert_eq!(resp.questions_imported, 3);
        assert_eq!(resp.passages_imported, 2);

        // Discrete CPBS bank query excludes CARS passage items? No — section
        // filter selects CPBS only.
        let cpbs_q = col.get_practice_questions(pb::GetPracticeQuestionsRequest {
            filter: Some(pb::QuestionFilter {
                sections: vec![cpbs()],
                ..Default::default()
            }),
        })?;
        assert_eq!(cpbs_q.questions.len(), 1);
        assert_eq!(cpbs_q.questions[0].id, "cpbs-001");

        // The CARS passage set groups its passage + question, and the question
        // row does not carry the denormalized passage text.
        let set = col.get_cars_passage_set(pb::GetCarsPassageSetRequest {
            passage_id: "p1".to_string(),
        })?;
        assert_eq!(set.passage.as_ref().unwrap().passage, "Long passage text.");
        assert_eq!(set.questions.len(), 1);
        assert_eq!(set.questions[0].id, "cars-001");
        assert_eq!(set.questions[0].passage_id.as_deref(), Some("p1"));

        // Topic filtering matches any tag, case-insensitively.
        let ethics = col.get_practice_questions(pb::GetPracticeQuestionsRequest {
            filter: Some(pb::QuestionFilter {
                topics: vec!["Ethics".to_string()],
                ..Default::default()
            }),
        })?;
        assert_eq!(ethics.questions.len(), 1);
        assert_eq!(ethics.questions[0].id, "cars-100");
        Ok(())
    }

    /// Full-length loader synthesizes the scheduled MCAT breaks and models the
    /// section timers; submit aggregates per-section results.
    #[test]
    fn full_length_breaks_and_submit() -> Result<()> {
        let mut col = Collection::new();
        let json = r#"{
            "testId": "fl-test", "title": "FL", "source": "AI", "format": "AAMC",
            "disclaimer": "POC", "totalQuestions": 2, "totalTestingSeconds": 11400,
            "sections": [
                {"sectionId": "CPBS", "order": 1, "durationSeconds": 5700, "questionCount": 1,
                 "passages": [],
                 "questions": [{"id": "fl-c1", "section": "CPBS", "stem": "q?",
                    "choices": [{"label":"A","text":"x"},{"label":"B","text":"y"}],
                    "correctAnswer": "A", "explanation": "e", "topicTags": ["electrochemistry"],
                    "difficulty": "medium", "sourceName": "AI", "sourceLicense": "POC"}]},
                {"sectionId": "CARS", "order": 2, "durationSeconds": 5400, "questionCount": 1,
                 "passages": [],
                 "questions": [{"id": "fl-r1", "section": "CARS", "stem": "q?",
                    "choices": [{"label":"A","text":"x"},{"label":"B","text":"y"}],
                    "correctAnswer": "A", "explanation": "e", "topicTags": ["ethics"],
                    "difficulty": "medium", "sourceName": "AI", "sourceLicense": "POC"}]}
            ]
        }"#;
        let loaded = col.load_full_length_test_bundle(pb::LoadBundleRequest {
            path: String::new(),
            json: json.to_string(),
            replace: true,
        })?;
        assert_eq!(loaded.tests_imported, 1);

        let started = col.start_full_length_attempt(pb::StartFullLengthAttemptRequest {
            test_id: "fl-test".to_string(),
            aamc_exam_id: None,
        })?;
        let test = started.test.unwrap();
        assert_eq!(test.sections.len(), 2);
        assert_eq!(test.total_testing_seconds, 11400);
        // Two sections -> one break after section 1 (the mid-exam label kicks in
        // only after section 2 for a full four-section exam).
        assert_eq!(test.breaks.len(), 1);
        assert_eq!(test.breaks[0].after_section, 1);
        assert_eq!(test.breaks[0].duration_seconds, 600);
        assert!(test.breaks[0].optional);
        assert_eq!(test.total_break_seconds, 600);

        let attempt_id = started.attempt_id;
        let _ = col.record_full_length_answer(pb::RecordFullLengthAnswerRequest {
            attempt_id: attempt_id.clone(),
            section: cpbs(),
            question_id: "fl-c1".to_string(),
            selected_answer: "A".to_string(),
            correct: true,
            time_on_question_seconds: 90,
            topic: "electrochemistry".to_string(),
        })?;
        let _ = col.record_full_length_answer(pb::RecordFullLengthAnswerRequest {
            attempt_id: attempt_id.clone(),
            section: cars(),
            question_id: "fl-r1".to_string(),
            selected_answer: "B".to_string(),
            correct: false,
            time_on_question_seconds: 120,
            topic: "ethics".to_string(),
        })?;

        let report = col.submit_full_length_attempt(pb::SubmitFullLengthAttemptRequest {
            attempt_id: attempt_id.clone(),
        })?;
        assert_eq!(report.total_questions, 2);
        assert_eq!(report.total_correct, 1);
        assert_eq!(report.section_results.len(), 2);
        let cpbs_result = report
            .section_results
            .iter()
            .find(|r| r.section == cpbs())
            .unwrap();
        assert_eq!(cpbs_result.correct, 1);
        assert_eq!(cpbs_result.total, 1);
        assert_eq!(cpbs_result.time_seconds, 90);

        // A submitted attempt is closed to further answers.
        let err = col.record_full_length_answer(pb::RecordFullLengthAnswerRequest {
            attempt_id,
            section: cpbs(),
            question_id: "fl-c1".to_string(),
            selected_answer: "A".to_string(),
            correct: true,
            time_on_question_seconds: 5,
            topic: "electrochemistry".to_string(),
        });
        assert!(err.is_err());
        Ok(())
    }

    /// end_practice_session tallies correct / incorrect / unanswered, total time
    /// and the per-section breakdown, and is idempotent once completed.
    #[test]
    fn end_practice_session_summary_counts() -> Result<()> {
        let mut col = Collection::new();
        let session = col
            .start_practice_session(pb::StartPracticeSessionRequest {
                filter: None,
                time_limit_seconds: 0,
            })?
            .session_id;

        // correct, incorrect (answered wrong), and unanswered (empty selection).
        record(&mut col, &session, "q1", true, 30, cpbs(), "kinetics");
        record(&mut col, &session, "q2", false, 40, cpbs(), "kinetics");
        let _ = col.record_practice_attempt(pb::RecordPracticeAttemptRequest {
            session_id: session.clone(),
            question_id: "q3".into(),
            selected_answer: String::new(),
            correct: false,
            time_on_question_seconds: 10,
            section: cpbs(),
            topic: "kinetics".into(),
        })?;

        let summary = col.end_practice_session(pb::EndPracticeSessionRequest {
            session_id: session.clone(),
        })?;
        assert_eq!(summary.total, 3);
        assert_eq!(summary.correct, 1);
        assert_eq!(summary.incorrect, 1);
        assert_eq!(summary.unanswered, 1);
        assert_eq!(summary.total_time_seconds, 80);
        assert_eq!(summary.section_breakdown.len(), 1);
        assert_eq!(summary.section_breakdown[0].section, cpbs());
        assert_eq!(summary.section_breakdown[0].correct, 1);
        assert_eq!(summary.section_breakdown[0].total, 3);

        // Ending again recomputes the same tally (completed_at is already set).
        let again = col.end_practice_session(pb::EndPracticeSessionRequest {
            session_id: session,
        })?;
        assert_eq!(again.total, 3);
        assert_eq!(again.correct, 1);
        Ok(())
    }

    /// record_practice_attempt refuses unknown sessions and sessions that have
    /// already been ended.
    #[test]
    fn record_attempt_rejects_unknown_and_completed_session() -> Result<()> {
        let mut col = Collection::new();

        let unknown = col.record_practice_attempt(pb::RecordPracticeAttemptRequest {
            session_id: "does-not-exist".into(),
            question_id: "q1".into(),
            selected_answer: "A".into(),
            correct: true,
            time_on_question_seconds: 5,
            section: cpbs(),
            topic: "kinetics".into(),
        });
        assert!(unknown.is_err());

        let session = col
            .start_practice_session(pb::StartPracticeSessionRequest {
                filter: None,
                time_limit_seconds: 0,
            })?
            .session_id;
        let _ = col.end_practice_session(pb::EndPracticeSessionRequest {
            session_id: session.clone(),
        })?;
        let after_end = col.record_practice_attempt(pb::RecordPracticeAttemptRequest {
            session_id: session,
            question_id: "q1".into(),
            selected_answer: "A".into(),
            correct: true,
            time_on_question_seconds: 5,
            section: cpbs(),
            topic: "kinetics".into(),
        });
        assert!(after_end.is_err());
        Ok(())
    }

    /// The question-bank filters — difficulty, limit, missed-only, and the
    /// free-standing vs full-length split — each narrow the result set.
    #[test]
    fn question_filters_difficulty_limit_missed_and_full_length() -> Result<()> {
        let mut col = Collection::new();
        // Three free-standing CPBS questions of differing difficulty.
        let bank = r#"{
            "questions": [
                {"id": "cpbs-a", "section": "CPBS", "stem": "q?",
                 "choices": [{"label":"A","text":"x"},{"label":"B","text":"y"}],
                 "correctAnswer": "A", "explanation": "e", "topicTags": ["kinetics"],
                 "difficulty": "easy", "sourceName": "OpenStax", "sourceLicense": "CC BY 4.0"},
                {"id": "cpbs-b", "section": "CPBS", "stem": "q?",
                 "choices": [{"label":"A","text":"x"},{"label":"B","text":"y"}],
                 "correctAnswer": "A", "explanation": "e", "topicTags": ["kinetics"],
                 "difficulty": "medium", "sourceName": "OpenStax", "sourceLicense": "CC BY 4.0"},
                {"id": "cpbs-c", "section": "CPBS", "stem": "q?",
                 "choices": [{"label":"A","text":"x"},{"label":"B","text":"y"}],
                 "correctAnswer": "A", "explanation": "e", "topicTags": ["kinetics"],
                 "difficulty": "hard", "sourceName": "OpenStax", "sourceLicense": "CC BY 4.0"}
            ]
        }"#;
        let _ = col.load_practice_question_bundle(pb::LoadBundleRequest {
            path: String::new(),
            json: bank.to_string(),
            replace: true,
        })?;
        // A full-length CPBS question (carries a test_id, so excluded by default).
        let fl = r#"{
            "testId": "fl-1", "title": "FL", "sections": [
                {"sectionId": "CPBS", "order": 1, "durationSeconds": 5700, "questionCount": 1,
                 "questions": [{"id": "fl-q1", "section": "CPBS", "stem": "q?",
                    "choices": [{"label":"A","text":"x"},{"label":"B","text":"y"}],
                    "correctAnswer": "A", "explanation": "e", "topicTags": ["kinetics"],
                    "difficulty": "hard", "sourceName": "AI", "sourceLicense": "POC"}]}
            ]
        }"#;
        let _ = col.load_full_length_test_bundle(pb::LoadBundleRequest {
            path: String::new(),
            json: fl.to_string(),
            replace: true,
        })?;

        fn ids(col: &mut Collection, filter: pb::QuestionFilter) -> Vec<String> {
            col.get_practice_questions(pb::GetPracticeQuestionsRequest {
                filter: Some(filter),
            })
            .unwrap()
            .questions
            .into_iter()
            .map(|q| q.id)
            .collect()
        }

        // Free-standing bank only: the full-length question is excluded.
        let free = ids(
            &mut col,
            pb::QuestionFilter {
                sections: vec![cpbs()],
                ..Default::default()
            },
        );
        assert_eq!(free, vec!["cpbs-a", "cpbs-b", "cpbs-c"]);

        // Including full-length adds the fl-q1 item.
        let with_fl = ids(
            &mut col,
            pb::QuestionFilter {
                sections: vec![cpbs()],
                include_full_length: true,
                ..Default::default()
            },
        );
        assert_eq!(with_fl.len(), 4);
        assert!(with_fl.contains(&"fl-q1".to_string()));

        // Difficulty filter selects a single item.
        let hard = ids(
            &mut col,
            pb::QuestionFilter {
                sections: vec![cpbs()],
                difficulty: Some(pb::Difficulty::Hard as i32),
                ..Default::default()
            },
        );
        assert_eq!(hard, vec!["cpbs-c"]);

        // Limit truncates (ordered by id).
        let limited = ids(
            &mut col,
            pb::QuestionFilter {
                sections: vec![cpbs()],
                limit: 2,
                ..Default::default()
            },
        );
        assert_eq!(limited, vec!["cpbs-a", "cpbs-b"]);

        // missed_only: mark cpbs-b wrong; it is then the only free-standing miss.
        let session = col
            .start_practice_session(pb::StartPracticeSessionRequest {
                filter: None,
                time_limit_seconds: 0,
            })?
            .session_id;
        record(&mut col, &session, "cpbs-b", false, 20, cpbs(), "kinetics");
        let missed = ids(
            &mut col,
            pb::QuestionFilter {
                sections: vec![cpbs()],
                missed_only: true,
                ..Default::default()
            },
        );
        assert_eq!(missed, vec!["cpbs-b"]);
        Ok(())
    }

    /// A full four-section test synthesizes optional breaks after sections 1-3,
    /// with the after-section-2 break labelled as the mid-exam break, and derives
    /// its total testing time from the section durations.
    #[test]
    fn full_length_four_section_breaks() -> Result<()> {
        let mut col = Collection::new();
        let json = r#"{
            "testId": "fl-4", "title": "Full", "source": "AI", "format": "AAMC",
            "sections": [
                {"sectionId": "CPBS", "order": 1, "durationSeconds": 5700, "questionCount": 0},
                {"sectionId": "CARS", "order": 2, "durationSeconds": 5400, "questionCount": 0},
                {"sectionId": "BBLS", "order": 3, "durationSeconds": 5700, "questionCount": 0},
                {"sectionId": "PSBB", "order": 4, "durationSeconds": 5700, "questionCount": 0}
            ]
        }"#;
        let _ = col.load_full_length_test_bundle(pb::LoadBundleRequest {
            path: String::new(),
            json: json.to_string(),
            replace: true,
        })?;
        let test = col.get_full_length_test(pb::GetFullLengthTestRequest {
            test_id: "fl-4".into(),
        })?;
        assert_eq!(test.sections.len(), 4);
        assert_eq!(test.breaks.len(), 3);
        assert_eq!(test.breaks[0].after_section, 1);
        assert_eq!(test.breaks[0].label, "Break");
        assert_eq!(test.breaks[0].duration_seconds, 600);
        assert_eq!(test.breaks[1].after_section, 2);
        assert_eq!(test.breaks[1].label, "Mid-exam break");
        assert_eq!(test.breaks[1].duration_seconds, 1800);
        assert_eq!(test.breaks[2].after_section, 3);
        assert_eq!(test.breaks[2].label, "Break");
        assert_eq!(test.breaks[2].duration_seconds, 600);
        // 10-min + 30-min mid-exam + 10-min = 50 min of scheduled breaks.
        assert_eq!(test.total_break_seconds, 3000);
        // total_testing_seconds defaults to the sum of section durations.
        assert_eq!(test.total_testing_seconds, 22500);
        Ok(())
    }

    /// The loader enforces the PRD guardrail that every question carries source
    /// attribution: a missing sourceName aborts the import and stores nothing.
    #[test]
    fn question_bundle_requires_source_attribution() -> Result<()> {
        let mut col = Collection::new();
        let missing_name = r#"{
            "questions": [
                {"id": "q1", "section": "CPBS", "stem": "q?",
                 "choices": [{"label":"A","text":"x"},{"label":"B","text":"y"}],
                 "correctAnswer": "A", "explanation": "e", "topicTags": ["kinetics"],
                 "difficulty": "easy", "sourceName": "", "sourceLicense": "CC BY 4.0"}
            ]
        }"#;
        let err = col.load_practice_question_bundle(pb::LoadBundleRequest {
            path: String::new(),
            json: missing_name.to_string(),
            replace: true,
        });
        assert!(err.is_err());
        // Nothing was imported.
        let all = col.get_practice_questions(pb::GetPracticeQuestionsRequest {
            filter: Some(pb::QuestionFilter {
                include_full_length: true,
                ..Default::default()
            }),
        })?;
        assert!(all.questions.is_empty());
        Ok(())
    }

    /// Loads a bank with two CARS passage sets (three + two questions) and asserts
    /// CARS practice always serves *complete* passage sets: the limit rounds to
    /// whole sets and never splits one, whatever ordering the ids impose.
    fn load_cars_sets(col: &mut Collection) -> Result<()> {
        let json = r#"{
            "passageSets": [
                {"passageId": "pA", "section": "CARS", "title": "A",
                 "passage": "Passage A.", "discipline": "humanities",
                 "difficulty": "medium", "sourceName": "AI", "sourceLicense": "POC",
                 "questions": [
                    {"id": "cars-a1", "section": "CARS", "passageId": "pA", "stem": "a1?",
                     "choices": [{"label":"A","text":"x"},{"label":"B","text":"y"}],
                     "correctAnswer": "A", "explanation": "e", "topicTags": ["ethics"],
                     "difficulty": "medium", "sourceName": "AI", "sourceLicense": "POC"},
                    {"id": "cars-a2", "section": "CARS", "passageId": "pA", "stem": "a2?",
                     "choices": [{"label":"A","text":"x"},{"label":"B","text":"y"}],
                     "correctAnswer": "A", "explanation": "e", "topicTags": ["ethics"],
                     "difficulty": "medium", "sourceName": "AI", "sourceLicense": "POC"},
                    {"id": "cars-a3", "section": "CARS", "passageId": "pA", "stem": "a3?",
                     "choices": [{"label":"A","text":"x"},{"label":"B","text":"y"}],
                     "correctAnswer": "A", "explanation": "e", "topicTags": ["ethics"],
                     "difficulty": "medium", "sourceName": "AI", "sourceLicense": "POC"}
                 ]},
                {"passageId": "pB", "section": "CARS", "title": "B",
                 "passage": "Passage B.", "discipline": "humanities",
                 "difficulty": "medium", "sourceName": "AI", "sourceLicense": "POC",
                 "questions": [
                    {"id": "cars-b1", "section": "CARS", "passageId": "pB", "stem": "b1?",
                     "choices": [{"label":"A","text":"x"},{"label":"B","text":"y"}],
                     "correctAnswer": "A", "explanation": "e", "topicTags": ["ethics"],
                     "difficulty": "medium", "sourceName": "AI", "sourceLicense": "POC"},
                    {"id": "cars-b2", "section": "CARS", "passageId": "pB", "stem": "b2?",
                     "choices": [{"label":"A","text":"x"},{"label":"B","text":"y"}],
                     "correctAnswer": "A", "explanation": "e", "topicTags": ["ethics"],
                     "difficulty": "medium", "sourceName": "AI", "sourceLicense": "POC"}
                 ]}
            ]
        }"#;
        let _ = col.load_practice_question_bundle(pb::LoadBundleRequest {
            path: String::new(),
            json: json.to_string(),
            replace: true,
        })?;
        Ok(())
    }

    fn practice_ids(col: &mut Collection, filter: pb::QuestionFilter) -> Vec<String> {
        col.get_practice_questions(pb::GetPracticeQuestionsRequest {
            filter: Some(filter),
        })
        .unwrap()
        .questions
        .into_iter()
        .map(|q| q.id)
        .collect()
    }

    /// CARS practice always delivers whole passage sets: the per-question limit
    /// rounds to complete sets and never truncates one mid-passage.
    #[test]
    fn cars_practice_serves_whole_passage_sets() -> Result<()> {
        let mut col = Collection::new();
        load_cars_sets(&mut col)?;

        // limit smaller than the first set: the whole first set is still served
        // (at least one complete set), and it is not split at two questions.
        let one = practice_ids(
            &mut col,
            pb::QuestionFilter {
                sections: vec![cars()],
                limit: 2,
                ..Default::default()
            },
        );
        assert_eq!(one, vec!["cars-a1", "cars-a2", "cars-a3"]);

        // limit=4 cannot fit both sets (3+2=5) without splitting, so only the
        // first whole set is served — never a partial second set.
        let capped = practice_ids(
            &mut col,
            pb::QuestionFilter {
                sections: vec![cars()],
                limit: 4,
                ..Default::default()
            },
        );
        assert_eq!(capped, vec!["cars-a1", "cars-a2", "cars-a3"]);

        // limit=5 fits both whole sets.
        let both = practice_ids(
            &mut col,
            pb::QuestionFilter {
                sections: vec![cars()],
                limit: 5,
                ..Default::default()
            },
        );
        assert_eq!(
            both,
            vec!["cars-a1", "cars-a2", "cars-a3", "cars-b1", "cars-b2"]
        );

        // No limit: every question of every set, grouped by passage.
        let all = practice_ids(
            &mut col,
            pb::QuestionFilter {
                sections: vec![cars()],
                ..Default::default()
            },
        );
        assert_eq!(all.len(), 5);
        // Each passage's questions stay contiguous.
        assert!(all[..3].iter().all(|id| id.starts_with("cars-a")));
        assert!(all[3..].iter().all(|id| id.starts_with("cars-b")));
        Ok(())
    }

    /// The section filter accepts several sections (matching ANY), and an empty
    /// list means all sections; non-CARS questions stay discrete.
    #[test]
    fn multi_section_filter_matches_any() -> Result<()> {
        let mut col = Collection::new();
        let bank = r#"{
            "questions": [
                {"id": "cpbs-1", "section": "CPBS", "stem": "q?",
                 "choices": [{"label":"A","text":"x"},{"label":"B","text":"y"}],
                 "correctAnswer": "A", "explanation": "e", "topicTags": ["kinetics"],
                 "difficulty": "easy", "sourceName": "OpenStax", "sourceLicense": "CC BY 4.0"},
                {"id": "bbls-1", "section": "BBLS", "stem": "q?",
                 "choices": [{"label":"A","text":"x"},{"label":"B","text":"y"}],
                 "correctAnswer": "A", "explanation": "e", "topicTags": ["genetics"],
                 "difficulty": "easy", "sourceName": "OpenStax", "sourceLicense": "CC BY 4.0"},
                {"id": "psbb-1", "section": "PSBB", "stem": "q?",
                 "choices": [{"label":"A","text":"x"},{"label":"B","text":"y"}],
                 "correctAnswer": "A", "explanation": "e", "topicTags": ["memory"],
                 "difficulty": "easy", "sourceName": "OpenStax", "sourceLicense": "CC BY 4.0"}
            ]
        }"#;
        let _ = col.load_practice_question_bundle(pb::LoadBundleRequest {
            path: String::new(),
            json: bank.to_string(),
            replace: true,
        })?;
        load_cars_sets(&mut col)?;

        // Two sections -> only those sections, CARS excluded.
        let cpbs_bbls = practice_ids(
            &mut col,
            pb::QuestionFilter {
                sections: vec![cpbs(), bbls()],
                ..Default::default()
            },
        );
        assert_eq!(cpbs_bbls, vec!["bbls-1", "cpbs-1"]);

        // CARS + CPBS -> CARS delivered as whole sets, CPBS discrete.
        let cars_cpbs = practice_ids(
            &mut col,
            pb::QuestionFilter {
                sections: vec![cars(), cpbs()],
                ..Default::default()
            },
        );
        assert_eq!(cars_cpbs.len(), 6);
        assert!(cars_cpbs.contains(&"cpbs-1".to_string()));
        assert!(cars_cpbs.contains(&"cars-a1".to_string()));

        // Empty list == all sections.
        let all = practice_ids(&mut col, pb::QuestionFilter::default());
        assert_eq!(all.len(), 8);

        // The unspecified sentinel is ignored (still all sections).
        let unspecified = practice_ids(
            &mut col,
            pb::QuestionFilter {
                sections: vec![pb::McatSection::Unspecified as i32],
                ..Default::default()
            },
        );
        assert_eq!(unspecified.len(), 8);
        Ok(())
    }

    fn q_discrete(id: &str) -> pb::PracticeQuestion {
        pb::PracticeQuestion {
            id: id.to_string(),
            section: cpbs(),
            passage_id: None,
            stem: "q".to_string(),
            choices: vec![],
            correct_answer: String::new(),
            explanation: String::new(),
            question_type: None,
            topic_tags: vec![],
            difficulty: 0,
            source_name: String::new(),
            source_license: String::new(),
            source_url: None,
            answer_provenance: None,
            notes: None,
            test_id: None,
        }
    }

    fn q_passage(id: &str, passage_id: &str) -> pb::PracticeQuestion {
        let mut q = q_discrete(id);
        q.section = cars();
        q.passage_id = Some(passage_id.to_string());
        q
    }

    fn order_ids(qs: &[pb::PracticeQuestion]) -> Vec<String> {
        qs.iter().map(|q| q.id.clone()).collect()
    }

    /// A seeded selection shuffle draws a random subset/order (so consecutive
    /// sessions differ), while the unseeded path keeps the deterministic
    /// first-seen order used by the plain query RPC.
    #[test]
    fn assemble_serving_order_shuffles_selection_across_seeds() {
        let pool: Vec<pb::PracticeQuestion> =
            (0..20).map(|i| q_discrete(&format!("q{i:02}"))).collect();

        // Unseeded == deterministic first-seen order.
        let plain = super::assemble_serving_order(pool.clone(), 0, None);
        assert_eq!(order_ids(&plain), order_ids(&pool));

        let mut sorted_pool = order_ids(&pool);
        sorted_pool.sort();
        let mut orders = Vec::new();
        for seed in 1..=5u64 {
            let shuffled = super::assemble_serving_order(pool.clone(), 0, Some(seed));
            // A shuffle is a permutation of the whole pool.
            let mut sorted = order_ids(&shuffled);
            sorted.sort();
            assert_eq!(sorted, sorted_pool, "seeded shuffle must be a permutation");
            orders.push(order_ids(&shuffled));
        }
        // At least one seed reorders vs first-seen, and seeds disagree with
        // each other (i.e. it is not always the same first N).
        assert!(orders.iter().any(|o| *o != order_ids(&plain)));
        assert!(orders.windows(2).any(|w| w[0] != w[1]));

        // The count limit still applies after shuffling (random subset of 5).
        let limited = super::assemble_serving_order(pool.clone(), 5, Some(1));
        assert_eq!(limited.len(), 5);
    }

    /// Shuffling reorders whole passage sets but keeps each set's questions
    /// contiguous and in their original internal order.
    #[test]
    fn assemble_serving_order_keeps_passage_sets_contiguous_when_shuffled() {
        let pool = vec![
            q_passage("a1", "pA"),
            q_passage("a2", "pA"),
            q_passage("a3", "pA"),
            q_discrete("d1"),
            q_passage("b1", "pB"),
            q_passage("b2", "pB"),
            q_discrete("d2"),
            q_passage("c1", "pC"),
            q_passage("c2", "pC"),
        ];
        let sets = [
            ("pA", vec!["a1", "a2", "a3"]),
            ("pB", vec!["b1", "b2"]),
            ("pC", vec!["c1", "c2"]),
        ];
        for seed in 0..8u64 {
            let out = super::assemble_serving_order(pool.clone(), 0, Some(seed));
            assert_eq!(out.len(), pool.len());
            for (pid, members) in &sets {
                let positions: Vec<usize> = out
                    .iter()
                    .enumerate()
                    .filter(|(_, q)| q.passage_id.as_deref() == Some(*pid))
                    .map(|(i, _)| i)
                    .collect();
                assert_eq!(positions.len(), members.len());
                assert!(
                    positions.windows(2).all(|w| w[1] == w[0] + 1),
                    "set {pid} not contiguous for seed {seed}"
                );
                let got: Vec<String> = positions.iter().map(|&i| out[i].id.clone()).collect();
                let want: Vec<String> = members.iter().map(|s| s.to_string()).collect();
                assert_eq!(got, want, "set {pid} internal order changed for seed {seed}");
            }
        }
    }

    fn mcq(correct: &str) -> pb::PracticeQuestion {
        let mut q = q_discrete("q1");
        q.choices = ["A", "B", "C", "D"]
            .iter()
            .zip(["w", "x", "y", "z"])
            .map(|(label, text)| pb::AnswerChoice {
                label: label.to_string(),
                text: text.to_string(),
            })
            .collect();
        q.correct_answer = correct.to_string();
        q
    }

    /// The choice shuffle keeps labels positional (A,B,C,D), reorders the texts,
    /// remaps `correct_answer` to the new label of the originally-correct choice,
    /// is stable for a given session, and varies across sessions.
    #[test]
    fn shuffle_question_choices_remaps_correct_and_is_stable() {
        // correct answer "B" => the correct text is "x".
        let mut q1 = mcq("B");
        super::shuffle_question_choices(&mut q1, "sess-1");

        // Labels stay positional.
        assert_eq!(
            q1.choices.iter().map(|c| c.label.clone()).collect::<Vec<_>>(),
            vec!["A", "B", "C", "D"]
        );
        // The choice now carrying the correct label still holds the correct text.
        let correct = q1.choices.iter().find(|c| c.label == q1.correct_answer).unwrap();
        assert_eq!(correct.text, "x");
        // No texts lost.
        let mut texts: Vec<String> = q1.choices.iter().map(|c| c.text.clone()).collect();
        texts.sort();
        assert_eq!(texts, vec!["w", "x", "y", "z"]);

        // Stable: same session + question reproduces the same order.
        let mut q1b = mcq("B");
        super::shuffle_question_choices(&mut q1b, "sess-1");
        assert_eq!(
            q1.choices.iter().map(|c| c.text.clone()).collect::<Vec<_>>(),
            q1b.choices.iter().map(|c| c.text.clone()).collect::<Vec<_>>()
        );

        // Varies across sessions, and the remap always stays correct.
        let mut orders = Vec::new();
        for s in 0..6 {
            let mut q = mcq("B");
            super::shuffle_question_choices(&mut q, &format!("sess-{s}"));
            let ct = &q.choices.iter().find(|c| c.label == q.correct_answer).unwrap().text;
            assert_eq!(ct, "x", "remap broke for sess-{s}");
            orders.push(q.choices.iter().map(|c| c.text.clone()).collect::<Vec<_>>());
        }
        assert!(
            orders.windows(2).any(|w| w[0] != w[1]),
            "choice order should differ across sessions"
        );
    }

    /// start_practice_session returns the session's shuffled questions with a
    /// still-valid correct answer, and honours the count limit.
    #[test]
    fn start_practice_session_returns_shuffled_valid_questions() -> Result<()> {
        let mut col = Collection::new();
        let items: Vec<String> = (0..10)
            .map(|i| {
                format!(
                    r#"{{"id":"q{i:02}","section":"CPBS","stem":"s",
                     "choices":[{{"label":"A","text":"a"}},{{"label":"B","text":"b"}},
                     {{"label":"C","text":"c"}},{{"label":"D","text":"d"}}],
                     "correctAnswer":"B","explanation":"e","topicTags":["t"],
                     "difficulty":"easy","sourceName":"X","sourceLicense":"Y"}}"#
                )
            })
            .collect();
        let bank = format!(r#"{{"questions":[{}]}}"#, items.join(","));
        let _ = col.load_practice_question_bundle(pb::LoadBundleRequest {
            path: String::new(),
            json: bank,
            replace: true,
        })?;

        let started = col.start_practice_session(pb::StartPracticeSessionRequest {
            filter: Some(pb::QuestionFilter {
                limit: 5,
                ..Default::default()
            }),
            time_limit_seconds: 0,
        })?;
        assert!(!started.session_id.is_empty());
        assert_eq!(started.questions.len(), 5);
        for q in &started.questions {
            assert!(q.id.starts_with('q'));
            // Choices preserved and the correct answer is a real (remapped) label.
            assert_eq!(q.choices.len(), 4);
            assert!(q.choices.iter().any(|c| c.label == q.correct_answer));
            // "b" was the correct text; whichever label it now sits under is the
            // remapped correct answer.
            let correct = q.choices.iter().find(|c| c.label == q.correct_answer).unwrap();
            assert_eq!(correct.text, "b");
        }

        // A second session also serves 5 of the 10 (still valid).
        let again = col.start_practice_session(pb::StartPracticeSessionRequest {
            filter: Some(pb::QuestionFilter {
                limit: 5,
                ..Default::default()
            }),
            time_limit_seconds: 0,
        })?;
        assert_eq!(again.questions.len(), 5);
        Ok(())
    }

    // ---- Exam readiness ---------------------------------------------------

    /// With no study data every pillar "gives up": each is unavailable, carries
    /// an explanatory message and a named source, and never reports a bare
    /// value/range.
    #[test]
    fn readiness_gives_up_without_data() -> Result<()> {
        let mut col = Collection::new();
        let r = col.get_readiness(pb::GetReadinessRequest {
            deck_search: String::new(),
        })?;

        for pillar in [
            r.memory.as_ref().unwrap(),
            r.performance.as_ref().unwrap(),
            r.readiness.as_ref().unwrap(),
        ] {
            assert!(!pillar.available, "pillar should give up: {pillar:?}");
            assert!(!pillar.message.is_empty(), "give-up needs a message");
            assert!(!pillar.source.is_empty(), "pillar needs a named source");
            assert_eq!(pillar.value, 0.0);
            assert_eq!(pillar.range_low, 0.0);
            assert_eq!(pillar.range_high, 0.0);
        }
        assert!(r.section_scores.is_empty());
        assert_eq!(r.performance_avg_seconds, 0.0);
        Ok(())
    }

    /// A populated store yields available Performance and Readiness pillars, each
    /// with a value strictly inside a [0,1] range, the right sample size, a named
    /// source, and (for Readiness) a per-section raw breakdown. Memory still
    /// gives up because the fresh collection has no reviewed cards.
    #[test]
    fn readiness_reports_performance_and_full_length() -> Result<()> {
        let mut col = Collection::new();

        // Pillar 2: 30 answered practice-session questions, 24 correct, 60s each.
        for i in 0..30 {
            col.storage.add_practice_attempt(&NewAttempt {
                id: &format!("ps:{i}"),
                session_id: Some("s1"),
                full_length_attempt_id: None,
                question_id: &format!("q{i}"),
                selected_answer: "A",
                correct: i < 24,
                time_on_question_seconds: 60,
                section_db: "CPBS",
                topic: "kinetics",
                answered_at: 0,
            })?;
        }
        // A skipped (unanswered) question must not count toward accuracy.
        col.storage.add_practice_attempt(&NewAttempt {
            id: "ps:skip",
            session_id: Some("s1"),
            full_length_attempt_id: None,
            question_id: "q-skip",
            selected_answer: "",
            correct: false,
            time_on_question_seconds: 5,
            section_db: "CPBS",
            topic: "kinetics",
            answered_at: 0,
        })?;

        // Pillar 3: one completed full-length attempt, two sections, 3/4 correct.
        col.storage
            .add_full_length_attempt("fla1", "fl-test", None, 0)?;
        let answers = [("CPBS", true), ("CPBS", true), ("CARS", true), ("CARS", false)];
        for (i, (section, correct)) in answers.iter().enumerate() {
            col.storage.add_practice_attempt(&NewAttempt {
                id: &format!("fla1:{i}"),
                session_id: None,
                full_length_attempt_id: Some("fla1"),
                question_id: &format!("f{i}"),
                selected_answer: "A",
                correct: *correct,
                time_on_question_seconds: 90,
                section_db: section,
                topic: "t",
                answered_at: 0,
            })?;
        }
        col.storage
            .complete_full_length_attempt("fla1", "[]", None, 100)?;

        let r = col.get_readiness(pb::GetReadinessRequest {
            deck_search: String::new(),
        })?;

        // Memory: no reviewed cards on a fresh collection -> give up.
        let memory = r.memory.unwrap();
        assert!(!memory.available);
        assert!(!memory.message.is_empty());

        // Performance: 24/30 = 0.8 over the 30 answered (skip excluded), inside
        // a proper [0,1] range, ~60s/question.
        let perf = r.performance.unwrap();
        assert!(perf.available);
        assert_eq!(perf.sample_size, 30);
        assert!((perf.value - 0.8).abs() < 1e-9);
        assert!(perf.range_low < perf.value && perf.value < perf.range_high);
        assert!(perf.range_low >= 0.0 && perf.range_high <= 1.0);
        assert!(!perf.source.is_empty());
        assert!((r.performance_avg_seconds - 60.0).abs() < 1e-9);

        // Readiness: 3/4 = 0.75 across the completed full-length, with a
        // per-section raw breakdown.
        let readiness = r.readiness.unwrap();
        assert!(readiness.available);
        assert_eq!(readiness.sample_size, 4);
        assert!((readiness.value - 0.75).abs() < 1e-9);
        assert!(readiness.range_low < readiness.value && readiness.value < readiness.range_high);
        assert_eq!(r.section_scores.len(), 2);
        let cpbs_score = r.section_scores.iter().find(|s| s.section == cpbs()).unwrap();
        assert_eq!((cpbs_score.correct, cpbs_score.total), (2, 2));
        let cars_score = r.section_scores.iter().find(|s| s.section == cars()).unwrap();
        assert_eq!((cars_score.correct, cars_score.total), (1, 2));
        Ok(())
    }

    /// An in-progress (not yet submitted) full-length attempt does not count
    /// toward the Readiness pillar — only completed tests contribute a score.
    #[test]
    fn readiness_ignores_incomplete_full_length() -> Result<()> {
        let mut col = Collection::new();
        col.storage
            .add_full_length_attempt("fla-open", "fl-test", None, 0)?;
        col.storage.add_practice_attempt(&NewAttempt {
            id: "fla-open:0",
            session_id: None,
            full_length_attempt_id: Some("fla-open"),
            question_id: "f0",
            selected_answer: "A",
            correct: true,
            time_on_question_seconds: 90,
            section_db: "CPBS",
            topic: "t",
            answered_at: 0,
        })?;

        let r = col.get_readiness(pb::GetReadinessRequest {
            deck_search: String::new(),
        })?;
        let readiness = r.readiness.unwrap();
        assert!(!readiness.available);
        assert_eq!(readiness.sample_size, 0);
        assert!(r.section_scores.is_empty());
        Ok(())
    }

    /// The Wilson interval brackets the point estimate, stays within [0,1] even
    /// for a perfect small sample, and returns zeros for an empty sample.
    #[test]
    fn wilson_interval_is_sane() {
        let (p, low, high) = super::wilson_interval(50, 100, super::READINESS_Z);
        assert!((p - 0.5).abs() < 1e-9);
        assert!(low < 0.5 && 0.5 < high);
        assert!(low >= 0.0 && high <= 1.0);
        // Roughly symmetric around 0.5 for a balanced sample.
        assert!(((0.5 - low) - (high - 0.5)).abs() < 1e-6);

        let (p, low, high) = super::wilson_interval(5, 5, super::READINESS_Z);
        assert!((p - 1.0).abs() < 1e-9);
        assert!(high <= 1.0 && low > 0.0 && low < 1.0);

        assert_eq!(
            super::wilson_interval(0, 0, super::READINESS_Z),
            (0.0, 0.0, 0.0)
        );
    }

    /// The Memory mean/SE is derived from the retrievability histogram: the mean
    /// tracks the reported average, a tight distribution has a near-zero SE, a
    /// spread-out one has a larger SE, and an empty histogram yields None.
    #[test]
    fn memory_mean_and_se_from_histogram() {
        let mut tight: HashMap<u32, u32> = HashMap::new();
        tight.insert(90, 40);
        let (mean, se, n) = super::memory_mean_and_se(&tight, 90.5).unwrap();
        assert_eq!(n, 40);
        assert!((mean - 0.905).abs() < 1e-9);
        assert!(se < 0.01);

        let mut spread: HashMap<u32, u32> = HashMap::new();
        spread.insert(50, 25);
        spread.insert(90, 25);
        let (mean2, se2, n2) = super::memory_mean_and_se(&spread, 70.5).unwrap();
        assert_eq!(n2, 50);
        assert!((mean2 - 0.705).abs() < 1e-9);
        assert!(se2 > se);

        assert!(super::memory_mean_and_se(&HashMap::new(), 0.0).is_none());
    }
}
