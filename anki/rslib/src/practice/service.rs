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
use crate::practice::ewma;
use crate::practice::new_full_length_attempt_id;
use crate::practice::new_session_id;
use crate::practice::scoring;
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
        // SpeedyCAT graduated hint ladder: clamp the reported tier to 0..3 and
        // derive `assisted` from it (reaching level 3) so the flags are always
        // internally consistent regardless of what the client sends.
        let hint_level_used = input.hint_level_used.min(3);
        let assisted = input.assisted || hint_level_used >= 3;
        let main_wrong_first = input.main_wrong_first;
        let existing_first_try = self.storage.attempt_first_try_no_hint(&attempt_id)?;
        let replacing = existing_first_try.is_some();
        let question_seen = self
            .storage
            .question_attempted_before(&input.question_id, &attempt_id)?;
        let first_try_no_hint = crate::practice::performance::first_try_no_hint(
            question_seen,
            replacing,
            existing_first_try.flatten(),
            hint_level_used,
            &input.selected_answer,
            input.correct,
        );
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
            hint_level_used,
            assisted,
            main_wrong_first,
            first_try_no_hint,
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
            // Full-length exams have no hint ladder.
            hint_level_used: 0,
            assisted: false,
            main_wrong_first: false,
            first_try_no_hint: None,
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

        let (section_results, total_correct, total_questions, overall_scaled_score) =
            section_results_from_counts(&counts);
        let stored_results: Vec<StoredSectionResult> = section_results
            .iter()
            .zip(counts.iter())
            .map(|(sr, (section_db, correct, total, time))| StoredSectionResult {
                section: section_db.clone(),
                correct: *correct,
                total: *total,
                time_seconds: *time,
                scaled_score: sr.scaled_score,
            })
            .collect();

        let results_json = serde_json::to_string(&stored_results)?;
        let now = TimestampSecs::now().0;
        let attempt_id = input.attempt_id.clone();
        let prior_incomplete = self
            .storage
            .count_incomplete_full_length_attempts(Some(&attempt_id))?;
        let counts_for_readiness = prior_incomplete == 0;
        self.transact_no_undo(|col| {
            col.storage.complete_full_length_attempt(
                &attempt_id,
                &results_json,
                overall_scaled_score,
                now,
                counts_for_readiness,
            )
        })?;

        Ok(pb::FullLengthReport {
            attempt_id: input.attempt_id,
            test_id,
            section_results,
            overall_scaled_score,
            total_correct,
            total_questions,
            counts_for_readiness,
        })
    }

    fn abandon_full_length_attempt(
        &mut self,
        input: pb::AbandonFullLengthAttemptRequest,
    ) -> Result<()> {
        let (_, completed_at) = self
            .storage
            .get_full_length_attempt(&input.attempt_id)?
            .or_invalid(format!("unknown attempt_id: {}", input.attempt_id))?;
        require!(
            completed_at.is_none(),
            "full-length attempt {} is already finished",
            input.attempt_id
        );
        let now = TimestampSecs::now().0;
        let attempt_id = input.attempt_id.clone();
        self.transact_no_undo(|col| col.storage.abandon_full_length_attempt(&attempt_id, now))?;
        Ok(())
    }

    fn list_full_length_attempts(
        &mut self,
        _input: pb::ListFullLengthAttemptsRequest,
    ) -> Result<pb::ListFullLengthAttemptsResponse> {
        let rows = self.storage.list_completed_full_length_attempts()?;
        let attempts = rows
            .into_iter()
            .map(|row| pb::FullLengthAttemptSummary {
                attempt_id: row.attempt_id,
                test_id: row.test_id,
                test_title: row.test_title,
                started_at: row.started_at,
                completed_at: row.completed_at,
                overall_scaled_score: row.overall_scaled_score,
                total_correct: row.total_correct,
                total_questions: row.total_questions,
                counts_for_readiness: row.counts_for_readiness,
            })
            .collect();
        Ok(pb::ListFullLengthAttemptsResponse { attempts })
    }

    fn get_full_length_stats(
        &mut self,
        input: pb::GetFullLengthStatsRequest,
    ) -> Result<pb::FullLengthStats> {
        let meta = self
            .storage
            .get_full_length_attempt_meta(&input.attempt_id)?
            .or_invalid(format!("unknown attempt_id: {}", input.attempt_id))?;
        require!(
            meta.completed_at.is_some() && !meta.abandoned,
            "attempt {} is not a completed full-length test",
            input.attempt_id
        );
        let test = self
            .storage
            .get_full_length_test(&meta.test_id)?
            .or_invalid(format!("unknown test_id: {}", meta.test_id))?;
        let counts = self.storage.full_length_section_counts(&input.attempt_id)?;
        let (section_results, total_correct, total_questions, overall_scaled_score) =
            section_results_from_counts(&counts);
        let topic_scores = self
            .storage
            .full_length_topic_scores(&input.attempt_id)?
            .into_iter()
            .map(|(section_db, topic, correct, total)| pb::TopicScore {
                topic: crate::practice::topic_display::format_topic_display(&topic),
                section: section_from_db(&section_db),
                correct,
                total,
            })
            .collect();
        Ok(pb::FullLengthStats {
            attempt_id: input.attempt_id,
            test_id: meta.test_id,
            test_title: test.title,
            section_results,
            overall_scaled_score,
            total_correct,
            total_questions,
            topic_scores,
            counts_for_readiness: meta.counts_for_readiness,
        })
    }

    fn get_full_length_review(
        &mut self,
        input: pb::GetFullLengthReviewRequest,
    ) -> Result<pb::GetFullLengthReviewResponse> {
        let meta = self
            .storage
            .get_full_length_attempt_meta(&input.attempt_id)?
            .or_invalid(format!("unknown attempt_id: {}", input.attempt_id))?;
        require!(
            meta.completed_at.is_some() && !meta.abandoned,
            "attempt {} is not available for review",
            input.attempt_id
        );
        let test = self
            .storage
            .get_full_length_test(&meta.test_id)?
            .or_invalid(format!("unknown test_id: {}", meta.test_id))?;
        let answers = self
            .storage
            .full_length_attempt_answers(&input.attempt_id)?;
        let answer_map: HashMap<String, (String, bool)> = answers
            .into_iter()
            .map(|(qid, selected, correct, _)| (qid, (selected, correct)))
            .collect();
        let questions = self.storage.questions_for_test(&meta.test_id)?;
        let mut items = Vec::new();
        for question in questions {
            let (selected_answer, correct) = answer_map
                .get(&question.id)
                .map(|(s, c)| (s.clone(), *c))
                .unwrap_or_else(|| (String::new(), false));
            items.push(pb::FullLengthReviewItem {
                question: Some(question),
                selected_answer,
                correct,
            });
        }
        Ok(pb::GetFullLengthReviewResponse {
            attempt_id: input.attempt_id,
            test_id: meta.test_id,
            test_title: test.title.clone(),
            test: Some(test),
            items,
        })
    }

    // ---- Tracking ---------------------------------------------------------

    fn get_topic_stats(
        &mut self,
        input: pb::GetTopicStatsRequest,
    ) -> Result<pb::GetTopicStatsResponse> {
        let section_db = input.section.map(|s| section_to_db(s).to_string());
        let source_clause = attempt_source_clause(input.source);
        let first_only = input.first_attempt_no_hint_only;
        let topics = self
            .storage
            .topic_stats(
                section_db.as_deref(),
                source_clause,
                first_only,
            )?
            .into_iter()
            .map(|mut t| {
                t.topic = crate::practice::topic_display::format_topic_display(&t.topic);
                t
            })
            .collect();
        let sections = self.storage.section_stats(
            section_db.as_deref(),
            source_clause,
            first_only,
        )?;
        Ok(pb::GetTopicStatsResponse { topics, sections })
    }

    fn get_recommended_practice_topics(
        &mut self,
        _input: pb::GetRecommendedPracticeTopicsRequest,
    ) -> Result<pb::GetRecommendedPracticeTopicsResponse> {
        let topics = self.recommended_practice_topics()?;
        Ok(pb::GetRecommendedPracticeTopicsResponse { topics })
    }

    // ---- Exam readiness ---------------------------------------------------

    fn get_readiness(
        &mut self,
        input: pb::GetReadinessRequest,
    ) -> Result<pb::GetReadinessResponse> {
        let memory = self.readiness_memory(&input.deck_search)?;
        let (performance, performance_avg_seconds) = self.readiness_performance()?;
        let (readiness, section_scores, scaled) = self.readiness_full_length()?;
        let projected = self.readiness_projected_mcat(
            Some(&performance),
            performance_avg_seconds,
        )?;
        let graphs = self.graph_data_for_search(&input.deck_search, 0)?;
        let fsrs_on = graphs.fsrs;
        let memory_breakdown = if memory.available || fsrs_on {
            self.readiness_memory_breakdowns(&input.deck_search, fsrs_on)?
        } else {
            crate::practice::breakdown::empty_breakdowns_give_up(&memory.message)
        };
        let performance_breakdown = self.readiness_performance_breakdowns()?;
        let readiness_breakdown = self.readiness_full_length_breakdowns()?;
        Ok(pb::GetReadinessResponse {
            memory: Some(memory),
            performance: Some(performance),
            readiness: Some(readiness),
            section_scores,
            performance_avg_seconds,
            readiness_scaled_score: scaled.as_ref().map(|s| s.score),
            readiness_scaled_low: scaled.as_ref().map(|s| s.low),
            readiness_scaled_high: scaled.as_ref().map(|s| s.high),
            performance_time_penalty_applied: performance_avg_seconds
                > crate::practice::performance::PERFORMANCE_TARGET_SECONDS,
            projected: Some(projected),
            memory_breakdown: Some(memory_breakdown),
            performance_breakdown: Some(performance_breakdown),
            readiness_breakdown: Some(readiness_breakdown),
        })
    }

    fn reset_speedycat_review_session(&mut self) -> Result<pb::SpeedycatGamingStatus> {
        self.speedycat_reset_review_session()
    }

    fn record_speedycat_honest_review(&mut self) -> Result<pb::SpeedycatGamingStatus> {
        self.speedycat_record_honest_review()
    }

    fn record_speedycat_gamed_lapse(
        &mut self,
        input: pb::RecordSpeedycatGamedLapseRequest,
    ) -> Result<pb::SpeedycatGamingStatus> {
        self.speedycat_record_gamed_lapse(input.idk)
    }

    fn get_speedycat_gaming_status(&mut self) -> Result<pb::SpeedycatGamingStatus> {
        self.speedycat_gaming_status()
    }
}

/// Aggregate full-length scaled-score ESTIMATE (472–528) with an explicit range,
/// summed from the per-section scaled scores + their Wilson bounds.
struct ScaledEstimate {
    score: u32,
    low: u32,
    high: u32,
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
/// Minimum answered practice questions per MCAT section before the projected
/// score includes that section (all four sections are required).
const MIN_PROJECTED_SECTION_ATTEMPTS: u32 = 5;

/// Canonical MCAT sections in display order.
const PROJECTED_SECTIONS: [&str; 4] = ["CPBS", "CARS", "BBLS", "PSBB"];

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
        let lifetime_studied = memory_lifetime_studied_count(&graphs);
        if let Some(message) = self.speedycat_memory_suppression_message()? {
            return Ok(give_up_pillar(
                SOURCE,
                METHOD,
                message,
                lifetime_studied,
            ));
        }
        if !graphs.fsrs {
            return Ok(give_up_pillar(
                SOURCE,
                METHOD,
                "Turn on FSRS to unlock your Memory score.".to_string(),
                lifetime_studied,
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

    /// Pillar 2 — Performance: time-weighted accuracy (EWMA, 7-day half-life)
    /// over answered practice questions, with a Wilson 95% interval on effective
    /// sample size. Also returns the EWMA average seconds per question.
    fn readiness_performance(&self) -> Result<(pb::ReadinessPillar, f64)> {
        const SOURCE: &str = "SpeedyCAT practice sessions (practice_attempts store)";
        const METHOD: &str = crate::practice::performance::PERFORMANCE_METHOD;
        let rows = self.storage.practice_performance_observations()?;
        let answered = rows.len() as u32;
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
        let now = TimestampSecs::now().0;
        let credit_obs: Vec<(f64, i64)> = rows.iter().map(|(c, t, _)| (*c, *t)).collect();
        let time_obs: Vec<(u32, i64)> = rows.iter().map(|(_, t, s)| (*s, *t)).collect();
        let agg = ewma::ewma_aggregate(&credit_obs, ewma::PERFORMANCE_HALF_LIFE_DAYS, now);
        let avg_seconds = ewma::ewma_weighted_seconds(
            &time_obs,
            ewma::PERFORMANCE_HALF_LIFE_DAYS,
            now,
        );
        let n_eff = effective_sample_size_for_wilson(agg.effective_n);
        let (value, low, high) =
            wilson_interval_fraction(agg.mean.clamp(0.0, 1.0), n_eff, READINESS_Z);
        let (value, low, high) =
            crate::practice::performance::apply_performance_time_penalty(value, low, high, avg_seconds);
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

    /// Pillar 3 — Readiness: time-weighted raw score (EWMA, 30-day half-life)
    /// across COMPLETED full-length test answers, with a Wilson 95% interval on
    /// effective sample size and a per-section breakdown. Deliberately excludes
    /// timing/pacing, breaks, question distribution and everything outside the
    /// full-length tests.
    fn readiness_full_length(
        &self,
    ) -> Result<(
        pb::ReadinessPillar,
        Vec<pb::ReadinessSectionScore>,
        Option<ScaledEstimate>,
    )> {
        const SOURCE: &str = "SpeedyCAT full-length tests (completed attempts, raw score)";
        const METHOD: &str = "time-weighted raw correct ÷ total (EWMA, 30-day half-life) \
             across completed full-length answers, with a Wilson 95% interval on \
             effective sample size";
        let obs = self.storage.full_length_readiness_observations()?;
        let total = obs.len() as u32;
        if total == 0 {
            return Ok((
                give_up_pillar(
                    SOURCE,
                    METHOD,
                    "Finish a full-length test to unlock your Readiness score.".to_string(),
                    0,
                ),
                Vec::new(),
                None,
            ));
        }
        let now = TimestampSecs::now().0;
        let agg = ewma::ewma_aggregate(&obs, ewma::READINESS_HALF_LIFE_DAYS, now);
        let n_eff = effective_sample_size_for_wilson(agg.effective_n);
        let (value, low, high) =
            wilson_interval_fraction(agg.mean.clamp(0.0, 1.0), n_eff, READINESS_Z);

        // Per-section raw score + averaged scaled ESTIMATE. The overall scaled
        // estimate sums the per-section scaled scores; its range sums the scaled
        // scores at each section's Wilson bounds (a conservative aggregate).
        let mut section_scores = Vec::new();
        let mut scaled_point = 0u32;
        let mut scaled_low = 0u32;
        let mut scaled_high = 0u32;
        let mut scaled_sections = 0u32;
        for (section, s_correct, s_total) in self.storage.full_length_section_scores()? {
            let scaled_score = scoring::section_scaled_score(s_correct, s_total);
            if scaled_score.is_some() {
                let (p, plo, phi) = wilson_interval(s_correct, s_total, READINESS_Z);
                scaled_point += scoring::scaled_from_fraction(p);
                scaled_low += scoring::scaled_from_fraction(plo);
                scaled_high += scoring::scaled_from_fraction(phi);
                scaled_sections += 1;
            }
            section_scores.push(pb::ReadinessSectionScore {
                section: section_from_db(&section),
                correct: s_correct,
                total: s_total,
                scaled_score,
            });
        }
        let scaled = (scaled_sections > 0).then(|| ScaledEstimate {
            score: scoring::clamp_total_for_sections(scaled_point, scaled_sections),
            low: scoring::clamp_total_for_sections(scaled_low, scaled_sections),
            high: scoring::clamp_total_for_sections(scaled_high, scaled_sections),
        });

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
            scaled,
        ))
    }

    /// Projected MCAT score (472–528): per-section blend of Performance
    /// (practice-session EWMA accuracy) and Readiness (completed full-length
    /// EWMA accuracy), mapped through the representative AAMC anchor curve in
    /// [`scoring`]. Gives up unless the Performance pillar is available and
    /// every section has at least [`MIN_PROJECTED_SECTION_ATTEMPTS`] practice
    /// answers. When a section has no full-length data the practice signal
    /// alone drives that section's estimate.
    fn readiness_projected_mcat(
        &self,
        performance: Option<&pb::ReadinessPillar>,
        performance_avg_seconds: f64,
    ) -> Result<pb::ProjectedMcatScore> {
        const SOURCE: &str =
            "SpeedyCAT practice sessions + full-length tests (per-section blend)";
        const METHOD: &str = "per-section average of Performance (practice EWMA, 7-day \
             half-life) and Readiness (full-length EWMA, 30-day half-life) fractions, \
             mapped to scaled scores via AAMC representative anchors; 95% range from \
             Wilson intervals on each signal; total = sum of four sections (472–528)";

        match performance {
            Some(p) if p.available => {}
            _ => {
                return Ok(give_up_projected(
                    SOURCE,
                    METHOD,
                    format!(
                        "Answer more practice questions to unlock your projected MCAT score \
                         (need ≥{MIN_PERFORMANCE_ATTEMPTS} answered overall)."
                    ),
                ));
            }
        }

        let now = TimestampSecs::now().0;
        let practice_by_section = self.storage.practice_performance_observations_by_section()?;
        let fl_by_section = self.storage.full_length_readiness_observations_by_section()?;

        let mut section_scores = Vec::new();
        let mut scaled_point = 0u32;
        let mut scaled_low = 0u32;
        let mut scaled_high = 0u32;
        let mut missing_sections = Vec::new();

        for section_db in PROJECTED_SECTIONS {
            let section_q = scoring::section_question_count(section_db);
            let practice_obs = practice_by_section
                .get(section_db)
                .map(|v| v.as_slice())
                .unwrap_or(&[]);
            let practice_n = practice_obs.len() as u32;
            if practice_n < MIN_PROJECTED_SECTION_ATTEMPTS {
                missing_sections.push(section_db);
                continue;
            }

            let perf_agg =
                ewma::ewma_aggregate(practice_obs, ewma::PERFORMANCE_HALF_LIFE_DAYS, now);
            let perf_n_eff = effective_sample_size_for_wilson(perf_agg.effective_n);
            let (perf_p, perf_lo, perf_hi) = wilson_interval_fraction(
                perf_agg.mean.clamp(0.0, 1.0),
                perf_n_eff,
                READINESS_Z,
            );
            let (perf_p, perf_lo, perf_hi) = crate::practice::performance::apply_performance_time_penalty(
                perf_p,
                perf_lo,
                perf_hi,
                performance_avg_seconds,
            );

            let fl_obs = fl_by_section
                .get(section_db)
                .map(|v| v.as_slice())
                .unwrap_or(&[]);
            let fl_n = fl_obs.len() as u32;
            let (blend_p, blend_lo, blend_hi) = if fl_obs.is_empty() {
                (perf_p, perf_lo, perf_hi)
            } else {
                let fl_agg = ewma::ewma_aggregate(fl_obs, ewma::READINESS_HALF_LIFE_DAYS, now);
                let fl_n_eff = effective_sample_size_for_wilson(fl_agg.effective_n);
                let (ready_p, ready_lo, ready_hi) = wilson_interval_fraction(
                    fl_agg.mean.clamp(0.0, 1.0),
                    fl_n_eff,
                    READINESS_Z,
                );
                (
                    (perf_p + ready_p) / 2.0,
                    (perf_lo + ready_lo) / 2.0,
                    (perf_hi + ready_hi) / 2.0,
                )
            };

            let raw_correct = (blend_p * section_q as f64).round() as u32;
            let scaled = scoring::scaled_from_fraction(blend_p);
            let section_low = scoring::scaled_from_fraction(blend_lo);
            let section_high = scoring::scaled_from_fraction(blend_hi);

            scaled_point += scaled;
            scaled_low += section_low;
            scaled_high += section_high;

            section_scores.push(pb::ProjectedSectionScore {
                section: section_from_db(section_db),
                scaled_score: Some(scaled),
                scaled_low: Some(section_low),
                scaled_high: Some(section_high),
                raw_correct,
                raw_total: section_q,
                practice_attempts: practice_n,
                full_length_attempts: fl_n,
            });
        }

        if !missing_sections.is_empty() {
            return Ok(give_up_projected(
                SOURCE,
                METHOD,
                format!(
                    "Answer more practice questions per section to unlock your projected MCAT \
                     score (need ≥{MIN_PROJECTED_SECTION_ATTEMPTS} per section; need more data \
                     for: {}).",
                    missing_sections.join(", ")
                ),
            ));
        }

        Ok(pb::ProjectedMcatScore {
            available: true,
            message: String::new(),
            source: SOURCE.to_string(),
            method: METHOD.to_string(),
            total: Some(scoring::clamp_total_for_sections(scaled_point, 4)),
            total_low: Some(scoring::clamp_total_for_sections(scaled_low, 4)),
            total_high: Some(scoring::clamp_total_for_sections(scaled_high, 4)),
            sections: section_scores,
        })
    }
}

/// Build a pillar in its "gave up" state (a prerequisite is off, or there is
/// not enough data): no value/range, just the sample size and an explanation.
/// Build section results + totals from per-section attempt aggregates.
fn section_results_from_counts(
    counts: &[(String, u32, u32, u32)],
) -> (Vec<pb::SectionResult>, u32, u32, Option<u32>) {
    let mut section_results = Vec::new();
    let mut total_correct = 0u32;
    let mut total_questions = 0u32;
    let mut scaled_sum = 0u32;
    let mut scaled_sections = 0u32;
    for (section_db, correct, total, time) in counts {
        total_correct += correct;
        total_questions += total;
        let scaled_score = scoring::section_scaled_score(*correct, *total);
        if let Some(s) = scaled_score {
            scaled_sum += s;
            scaled_sections += 1;
        }
        section_results.push(pb::SectionResult {
            section: section_from_db(section_db),
            correct: *correct,
            total: *total,
            time_seconds: *time,
            scaled_score,
        });
    }
    let overall_scaled_score = (scaled_sections > 0)
        .then(|| scoring::clamp_total_for_sections(scaled_sum, scaled_sections));
    (
        section_results,
        total_correct,
        total_questions,
        overall_scaled_score,
    )
}

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

fn give_up_projected(source: &str, method: &str, message: String) -> pb::ProjectedMcatScore {
    pb::ProjectedMcatScore {
        available: false,
        message,
        source: source.to_string(),
        method: method.to_string(),
        total: None,
        total_low: None,
        total_high: None,
        sections: Vec::new(),
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
    wilson_interval_fraction(correct as f64 / total as f64, total, z)
}

/// Wilson interval when the point estimate is already a fraction in [0, 1]
/// (e.g. a weighted-mean performance credit).
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

/// Map Kish effective sample size to a Wilson `n` (at least 1 when positive).
fn effective_sample_size_for_wilson(effective_n: f64) -> u32 {
    if effective_n <= 0.0 {
        return 0;
    }
    effective_n.round().max(1.0) as u32
}

/// Estimate the mean and standard error of per-card FSRS retrievability from the
/// stock stats retrievability histogram (bin = floor-percent → card count) plus
/// the exact mean percentage reported alongside it. Returns (mean, se, n) as
/// fractions in [0, 1], or `None` when no card has a retrievability. The mean
/// comes straight from the exact `average`; the SE is estimated from the 1%-wide
/// histogram bins, so no FSRS math is re-implemented here.
/// Non-new cards in the collection search scope: lifetime cards studied at least once.
fn memory_lifetime_studied_count(graphs: &anki_proto::stats::GraphsResponse) -> u32 {
    let Some(counts) = graphs
        .card_counts
        .as_ref()
        .and_then(|c| c.including_inactive.as_ref())
    else {
        return 0;
    };
    counts.learn + counts.relearn + counts.young + counts.mature
}

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
    use crate::practice::ewma;
    use crate::services::PracticeService;
    use crate::storage::practice::NewAttempt;
    use crate::timestamp::TimestampSecs;

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
                hint_level_used: 0,
                assisted: false,
                main_wrong_first: false,
            })
            .unwrap();
    }

    /// Record an attempt that used the hint ladder up to `hint_level` (0..3).
    fn record_hinted(
        col: &mut Collection,
        session_id: &str,
        question_id: &str,
        correct: bool,
        hint_level: u32,
    ) {
        record_hinted_with_main_wrong(col, session_id, question_id, correct, hint_level, false);
    }

    fn record_hinted_with_main_wrong(
        col: &mut Collection,
        session_id: &str,
        question_id: &str,
        correct: bool,
        hint_level: u32,
        main_wrong_first: bool,
    ) {
        let _ = col
            .record_practice_attempt(pb::RecordPracticeAttemptRequest {
                session_id: session_id.to_string(),
                question_id: question_id.to_string(),
                selected_answer: if correct { "A".into() } else { "B".into() },
                correct,
                time_on_question_seconds: 30,
                section: cpbs(),
                topic: "kinetics".to_string(),
                hint_level_used: hint_level,
                assisted: hint_level >= 3,
                main_wrong_first,
            })
            .unwrap();
    }

    fn topic<'a>(
        resp: &'a pb::GetTopicStatsResponse,
        section: i32,
        name: &str,
    ) -> &'a pb::TopicStat {
        let want = crate::practice::topic_display::format_topic_display(name);
        resp.topics
            .iter()
            .find(|t| t.section == section && t.topic == want)
            .unwrap_or_else(|| panic!("missing topic stat {want}"))
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
            first_attempt_no_hint_only: false,
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
            hint_level_used: 0,
            assisted: false,
            main_wrong_first: false,
            first_try_no_hint: Some(1),
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
            hint_level_used: 0,
            assisted: false,
            main_wrong_first: false,
            first_try_no_hint: None,
        })?;

        // ALL: both attempts combine.
        let all = col.get_topic_stats(pb::GetTopicStatsRequest {
            section: None,
            source: pb::AttemptSource::All as i32,
            first_attempt_no_hint_only: false,
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
            first_attempt_no_hint_only: false,
        })?;
        let ps_topic = topic(&ps, cpbs(), "thermodynamics");
        assert_eq!(ps_topic.attempts, 1);
        assert_eq!(ps_topic.correct, 1);
        assert_eq!(ps_topic.accuracy, 1.0);

        // Full-length only.
        let fl = col.get_topic_stats(pb::GetTopicStatsRequest {
            section: None,
            source: pb::AttemptSource::FullLength as i32,
            first_attempt_no_hint_only: false,
        })?;
        let fl_topic = topic(&fl, cpbs(), "thermodynamics");
        assert_eq!(fl_topic.attempts, 1);
        assert_eq!(fl_topic.correct, 0);
        assert_eq!(fl_topic.accuracy, 0.0);

        // Section filter excludes unrelated sections.
        let cars_only = col.get_topic_stats(pb::GetTopicStatsRequest {
            section: Some(cars()),
            source: pb::AttemptSource::All as i32,
            first_attempt_no_hint_only: false,
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

        // Averaged scaled-score estimate: a perfect 1/1 section maps to the 132
        // ceiling, a 0/1 section to the 118 floor, and the overall is their sum
        // (clamped to the two-section range 236–264).
        assert_eq!(cpbs_result.scaled_score, Some(132));
        let cars_result = report
            .section_results
            .iter()
            .find(|r| r.section == cars())
            .unwrap();
        assert_eq!(cars_result.scaled_score, Some(118));
        assert_eq!(report.overall_scaled_score, Some(250));

        // The completed attempt shows up in the Full-Length Tests listing,
        // joined to its test title. This exercises the full_length_attempts ->
        // full_length_tests join (regression: the join must key on t.id).
        let listing = col.list_full_length_attempts(pb::ListFullLengthAttemptsRequest {})?;
        assert_eq!(listing.attempts.len(), 1);
        let summary = &listing.attempts[0];
        assert_eq!(summary.test_id, "fl-test");
        assert_eq!(summary.test_title, "FL");
        assert_eq!(summary.total_questions, 2);
        assert_eq!(summary.total_correct, 1);

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
            hint_level_used: 0,
                assisted: false,
                main_wrong_first: false,
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
            hint_level_used: 0,
                assisted: false,
                main_wrong_first: false,
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
            hint_level_used: 0,
                assisted: false,
                main_wrong_first: false,
            });
        assert!(after_end.is_err());
        Ok(())
    }

    /// The question-bank filters — difficulty, limit, and the free-standing
    /// vs full-length split — each narrow the result set.
    #[test]
    fn question_filters_difficulty_limit_and_full_length() -> Result<()> {
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
            hints: vec![],
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

    /// With excessive gamed lapses the Memory pillar gives up with an anti-gaming message.
    #[test]
    fn readiness_memory_suppressed_by_gaming() -> Result<()> {
        let mut col = Collection::new();
        for _ in 0..4 {
            let _ = col.speedycat_record_gamed_lapse(false)?;
        }
        let r = col.get_readiness(pb::GetReadinessRequest {
            deck_search: String::new(),
        })?;
        let memory = r.memory.as_ref().unwrap();
        assert!(!memory.available);
        assert!(memory.message.contains("Excessive guessing"));
        Ok(())
    }

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
        // No completed full-length -> no scaled estimate either.
        assert!(r.readiness_scaled_score.is_none());
        assert!(r.readiness_scaled_low.is_none());
        assert!(r.readiness_scaled_high.is_none());
        let projected = r.projected.as_ref().unwrap();
        assert!(!projected.available);
        assert!(!projected.message.is_empty());
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
                hint_level_used: 0,
                assisted: false,
                main_wrong_first: false,
                first_try_no_hint: Some(if i < 24 { 1 } else { 0 }),
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
            hint_level_used: 0,
            assisted: false,
            main_wrong_first: false,
            first_try_no_hint: None,
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
                hint_level_used: 0,
                assisted: false,
                main_wrong_first: false,
                first_try_no_hint: None,
            })?;
        }
        col.storage
            .complete_full_length_attempt("fla1", "[]", None, 100, true)?;

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

        // Averaged scaled-score estimate rides along the Readiness pillar: each
        // section carries its scaled score, and the overall estimate (472–528
        // clamped per section-count) is bracketed by its range. 2/2 -> 132,
        // 1/2 (50%) -> 122; overall point 254 for the two scored sections.
        assert_eq!(cpbs_score.scaled_score, Some(132));
        assert_eq!(cars_score.scaled_score, Some(122));
        assert_eq!(r.readiness_scaled_score, Some(254));
        let scaled_low = r.readiness_scaled_low.unwrap();
        let scaled_high = r.readiness_scaled_high.unwrap();
        assert!(
            scaled_low <= 254 && 254 <= scaled_high,
            "range [{scaled_low}, {scaled_high}] must bracket 254"
        );

        // Performance breakdown: CPBS section has 30 practice attempts.
        let perf_bd = r.performance_breakdown.as_ref().unwrap();
        let cpbs_bd = perf_bd
            .sections
            .iter()
            .find(|s| s.section == cpbs())
            .unwrap();
        assert!(cpbs_bd.available);
        assert_eq!(cpbs_bd.sample_size, 30);
        assert_eq!(perf_bd.topics.len(), 1);
        assert_eq!(perf_bd.topics[0].topic, "Kinetics");
        assert!(perf_bd.topics[0].available);

        // Readiness breakdown: section rows exist; CPBS has 2 FL answers (< 10 min).
        let ready_bd = r.readiness_breakdown.as_ref().unwrap();
        let cpbs_ready = ready_bd
            .sections
            .iter()
            .find(|s| s.section == cpbs())
            .unwrap();
        assert!(!cpbs_ready.available);
        assert_eq!(cpbs_ready.sample_size, 2);
        assert!(ready_bd.topics.is_empty());
        Ok(())
    }

    /// Projected MCAT score requires practice data in every section and returns
    /// a 472–528 total with an explicit per-section breakdown and range.
    #[test]
    fn readiness_projected_mcat_available() -> Result<()> {
        let mut col = Collection::new();
        let sections = [("CPBS", 8), ("CARS", 8), ("BBLS", 7), ("PSBB", 7)];
        let mut i = 0;
        for (section, count) in sections {
            for _ in 0..count {
                col.storage.add_practice_attempt(&NewAttempt {
                    id: &format!("proj:{i}"),
                    session_id: Some("s-proj"),
                    full_length_attempt_id: None,
                    question_id: &format!("q{i}"),
                    selected_answer: "A",
                    correct: i % 5 != 0,
                    time_on_question_seconds: 60,
                    section_db: section,
                    topic: "t",
                    answered_at: 0,
                    hint_level_used: 0,
                    assisted: false,
                    main_wrong_first: false,
                    first_try_no_hint: Some(1),
                })?;
                i += 1;
            }
        }

        let r = col.get_readiness(pb::GetReadinessRequest {
            deck_search: String::new(),
        })?;
        let projected = r.projected.as_ref().unwrap();
        assert!(projected.available, "{}", projected.message);
        assert_eq!(projected.sections.len(), 4);
        let total = projected.total.unwrap();
        let low = projected.total_low.unwrap();
        let high = projected.total_high.unwrap();
        assert!((472..=528).contains(&total));
        assert!(low <= total && total <= high);
        assert!(low >= 472 && high <= 528);
        for section in projected.sections.iter() {
            let scaled = section.scaled_score.unwrap();
            assert!((118..=132).contains(&scaled));
            assert!(section.raw_total > 0);
        }
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
            hint_level_used: 0,
            assisted: false,
            main_wrong_first: false,
            first_try_no_hint: None,
        })?;

        let r = col.get_readiness(pb::GetReadinessRequest {
            deck_search: String::new(),
        })?;
        let readiness = r.readiness.unwrap();
        assert!(!readiness.available);
        assert_eq!(readiness.sample_size, 0);
        assert!(r.section_scores.is_empty());
        assert!(r.readiness_scaled_score.is_none());
        Ok(())
    }

    /// Completing a full-length while another attempt is still in progress
    /// shows results but excludes the score from the Readiness pillar.
    #[test]
    fn readiness_excludes_score_with_prior_incomplete_attempt() -> Result<()> {
        let mut col = Collection::new();
        col.storage
            .add_full_length_attempt("fla-open", "fl-test", None, 0)?;
        col.storage
            .add_full_length_attempt("fla-done", "fl-test", None, 1)?;
        col.storage.add_practice_attempt(&NewAttempt {
            id: "fla-done:0",
            session_id: None,
            full_length_attempt_id: Some("fla-done"),
            question_id: "f0",
            selected_answer: "A",
            correct: true,
            time_on_question_seconds: 60,
            section_db: "CPBS",
            topic: "t",
            answered_at: 1,
            hint_level_used: 0,
            assisted: false,
            main_wrong_first: false,
            first_try_no_hint: None,
        })?;

        let report = col.submit_full_length_attempt(pb::SubmitFullLengthAttemptRequest {
            attempt_id: "fla-done".into(),
        })?;
        assert!(!report.counts_for_readiness);
        assert_eq!(report.total_correct, 1);

        let r = col.get_readiness(pb::GetReadinessRequest {
            deck_search: String::new(),
        })?;
        let readiness = r.readiness.unwrap();
        assert!(!readiness.available);
        assert_eq!(readiness.sample_size, 0);
        Ok(())
    }

    /// After the unfinished attempt is abandoned, a later completion counts.
    #[test]
    fn readiness_counts_after_prior_incomplete_abandoned() -> Result<()> {
        let mut col = Collection::new();
        col.storage
            .add_full_length_attempt("fla-open", "fl-test", None, 0)?;
        col.storage
            .add_full_length_attempt("fla-done", "fl-test", None, 1)?;
        col.abandon_full_length_attempt(pb::AbandonFullLengthAttemptRequest {
            attempt_id: "fla-open".into(),
        })?;
        col.storage.add_practice_attempt(&NewAttempt {
            id: "fla-done:0",
            session_id: None,
            full_length_attempt_id: Some("fla-done"),
            question_id: "f0",
            selected_answer: "A",
            correct: true,
            time_on_question_seconds: 60,
            section_db: "CPBS",
            topic: "t",
            answered_at: 1,
            hint_level_used: 0,
            assisted: false,
            main_wrong_first: false,
            first_try_no_hint: None,
        })?;

        let report = col.submit_full_length_attempt(pb::SubmitFullLengthAttemptRequest {
            attempt_id: "fla-done".into(),
        })?;
        assert!(report.counts_for_readiness);

        let r = col.get_readiness(pb::GetReadinessRequest {
            deck_search: String::new(),
        })?;
        let readiness = r.readiness.unwrap();
        assert!(readiness.available);
        assert_eq!(readiness.sample_size, 1);
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

    // ---- Graduated hint ladder --------------------------------------------

    /// The loader parses a 3-subquestion hint ladder (order + levels preserved,
    /// each a 4-choice MCQ), and DEFENSIVELY drops malformed tiers (wrong choice
    /// count, a `correctAnswer` that matches no choice, an empty prompt) while
    /// keeping the well-formed ones — so a question can load with fewer than 3
    /// (or zero) hints while content generation is still in progress.
    #[test]
    fn loads_hint_ladder_defensively() -> Result<()> {
        let mut col = Collection::new();
        let json = r#"{
            "questions": [
                {"id": "h-full", "section": "CPBS", "stem": "q?",
                 "choices": [{"label":"A","text":"x"},{"label":"B","text":"y"}],
                 "correctAnswer": "A", "explanation": "e", "topicTags": ["kinetics"],
                 "difficulty": "easy", "sourceName": "OpenStax", "sourceLicense": "CC BY 4.0",
                 "hints": [
                    {"level":1,"prompt":"concept?","choices":[
                       {"label":"A","text":"a"},{"label":"B","text":"b"},
                       {"label":"C","text":"c"},{"label":"D","text":"d"}],
                     "correctAnswer":"B","rationale":"because"},
                    {"level":2,"prompt":"process?","choices":[
                       {"label":"A","text":"a"},{"label":"B","text":"b"},
                       {"label":"C","text":"c"},{"label":"D","text":"d"}],
                     "correctAnswer":"C","rationale":"steps"},
                    {"level":3,"prompt":"eliminate two?","choices":[
                       {"label":"A","text":"a"},{"label":"B","text":"b"},
                       {"label":"C","text":"c"},{"label":"D","text":"d"}],
                     "correctAnswer":"D","rationale":"narrow"}
                 ]},
                {"id": "h-partial", "section": "CPBS", "stem": "q?",
                 "choices": [{"label":"A","text":"x"},{"label":"B","text":"y"}],
                 "correctAnswer": "A", "explanation": "e", "topicTags": ["kinetics"],
                 "difficulty": "easy", "sourceName": "OpenStax", "sourceLicense": "CC BY 4.0",
                 "hints": [
                    {"level":1,"prompt":"ok?","choices":[
                       {"label":"A","text":"a"},{"label":"B","text":"b"},
                       {"label":"C","text":"c"},{"label":"D","text":"d"}],
                     "correctAnswer":"A"},
                    {"level":2,"prompt":"too few choices","choices":[
                       {"label":"A","text":"a"},{"label":"B","text":"b"}],
                     "correctAnswer":"A"},
                    {"level":3,"prompt":"bad correct","choices":[
                       {"label":"A","text":"a"},{"label":"B","text":"b"},
                       {"label":"C","text":"c"},{"label":"D","text":"d"}],
                     "correctAnswer":"Z"},
                    {"level":3,"prompt":"","choices":[
                       {"label":"A","text":"a"},{"label":"B","text":"b"},
                       {"label":"C","text":"c"},{"label":"D","text":"d"}],
                     "correctAnswer":"A"}
                 ]},
                {"id": "h-none", "section": "CPBS", "stem": "q?",
                 "choices": [{"label":"A","text":"x"},{"label":"B","text":"y"}],
                 "correctAnswer": "A", "explanation": "e", "topicTags": ["kinetics"],
                 "difficulty": "easy", "sourceName": "OpenStax", "sourceLicense": "CC BY 4.0"}
            ]
        }"#;
        let _ = col.load_practice_question_bundle(pb::LoadBundleRequest {
            path: String::new(),
            json: json.to_string(),
            replace: true,
        })?;

        let qs = col
            .get_practice_questions(pb::GetPracticeQuestionsRequest {
                filter: Some(pb::QuestionFilter {
                    sections: vec![cpbs()],
                    ..Default::default()
                }),
            })?
            .questions;
        let by_id = |id: &str| qs.iter().find(|q| q.id == id).unwrap();

        // Full ladder: 3 tiers, in order, each a 4-choice MCQ, round-tripped
        // through the DB (parse -> store -> read).
        let full = by_id("h-full");
        assert_eq!(full.hints.len(), 3);
        assert_eq!(
            full.hints.iter().map(|h| h.level).collect::<Vec<_>>(),
            vec![1, 2, 3]
        );
        assert_eq!(full.hints[0].choices.len(), 4);
        assert_eq!(full.hints[0].correct_answer, "B");
        assert_eq!(full.hints[2].rationale, "narrow");

        // Partial: only the single well-formed tier survives; the 2-choice tier,
        // the bad-correctAnswer tier and the empty-prompt tier are all dropped.
        let partial = by_id("h-partial");
        assert_eq!(partial.hints.len(), 1);
        assert_eq!(partial.hints[0].prompt, "ok?");

        // No hints -> empty ladder (the UI disables/hides "Request hint").
        assert!(by_id("h-none").hints.is_empty());
        Ok(())
    }

    /// SpeedyCAT progressive hint penalties in the Performance pillar.
    #[test]
    fn readiness_performance_progressive_hint_penalties() -> Result<()> {
        let mut col = Collection::new();
        let session = col
            .start_practice_session(pb::StartPracticeSessionRequest {
                filter: None,
                time_limit_seconds: 0,
            })?
            .session_id;

        // 10 L0 + 10 L1 + 10 L3 correct -> credit = 10 + 6 + 1 = 17 / 30
        for i in 0..10 {
            record_hinted(&mut col, &session, &format!("l0{i}"), true, 0);
        }
        for i in 0..10 {
            record_hinted(&mut col, &session, &format!("l1{i}"), true, 1);
        }
        for i in 0..10 {
            record_hinted(&mut col, &session, &format!("l3{i}"), true, 3);
        }

        let r = col.get_readiness(pb::GetReadinessRequest {
            deck_search: String::new(),
        })?;
        let perf = r.performance.unwrap();
        assert!(perf.available);
        assert_eq!(perf.sample_size, 30);
        assert!(
            (perf.value - 17.0 / 30.0).abs() < 1e-9,
            "progressive credits expected {:.4}, got {}",
            17.0 / 30.0,
            perf.value
        );
        assert!(perf.method.contains("L1=0.60"), "penalty documented in method");
        Ok(())
    }

    /// Wrong main-question escalation zeroes Performance credit even when the
    /// final recorded answer is correct via the hint ladder.
    #[test]
    fn readiness_performance_main_wrong_first_is_zero_credit() -> Result<()> {
        let mut col = Collection::new();
        let session = col
            .start_practice_session(pb::StartPracticeSessionRequest {
                filter: None,
                time_limit_seconds: 0,
            })?
            .session_id;

        for i in 0..29 {
            record_hinted(&mut col, &session, &format!("ok{i}"), true, 0);
        }
        record_hinted_with_main_wrong(&mut col, &session, "bad", true, 2, true);

        let r = col.get_readiness(pb::GetReadinessRequest {
            deck_search: String::new(),
        })?;
        let perf = r.performance.unwrap();
        assert!((perf.value - 29.0 / 30.0).abs() < 1e-9);
        Ok(())
    }

    /// Dashboard stats count only first-ever no-hint attempts; retries and
    /// hint-assisted first encounters are excluded.
    #[test]
    fn topic_stats_first_attempt_no_hint_only() -> Result<()> {
        let mut col = Collection::new();
        let session = col
            .start_practice_session(pb::StartPracticeSessionRequest {
                filter: None,
                time_limit_seconds: 0,
            })?
            .session_id;

        record(&mut col, &session, "q1", true, 10, cpbs(), "kinetics");
        record(&mut col, &session, "q2", false, 10, cpbs(), "kinetics");
        record_hinted(&mut col, &session, "q3", true, 2);

        // Retry q1 in a new session — must not count toward dashboard stats.
        let session2 = col
            .start_practice_session(pb::StartPracticeSessionRequest {
                filter: None,
                time_limit_seconds: 0,
            })?
            .session_id;
        record(&mut col, &session2, "q1", true, 10, cpbs(), "kinetics");

        let dash = col.get_topic_stats(pb::GetTopicStatsRequest {
            section: None,
            source: pb::AttemptSource::PracticeSession as i32,
            first_attempt_no_hint_only: true,
        })?;
        let cpbs_section = section(&dash, cpbs());
        assert_eq!(cpbs_section.attempts, 2, "q1 retry + q3 hint excluded");
        assert_eq!(cpbs_section.correct, 1, "only q1 first try counts");
        assert!((cpbs_section.accuracy - 0.5).abs() < 1e-9);
        Ok(())
    }

    /// Legacy test name kept: L3 correct earns heavily penalized credit (0.1).
    #[test]
    fn readiness_performance_penalizes_assisted_correct() -> Result<()> {
        let mut col = Collection::new();
        let session = col
            .start_practice_session(pb::StartPracticeSessionRequest {
                filter: None,
                time_limit_seconds: 0,
            })?
            .session_id;

        for i in 0..15 {
            record_hinted(&mut col, &session, &format!("u{i}"), true, 0);
        }
        for i in 0..15 {
            record_hinted(&mut col, &session, &format!("a{i}"), true, 3);
        }

        let r = col.get_readiness(pb::GetReadinessRequest {
            deck_search: String::new(),
        })?;
        let perf = r.performance.unwrap();
        assert!(perf.available);
        assert_eq!(perf.sample_size, 30);
        // 15 * 1.0 + 15 * 0.1 = 16.5 / 30
        assert!(
            (perf.value - 16.5 / 30.0).abs() < 1e-9,
            "L3-assisted correct earns 0.1 credit (got {})",
            perf.value
        );
        Ok(())
    }

    /// Average pace over 98s/question scales the accuracy-based Performance score.
    #[test]
    fn readiness_performance_time_penalty_slow_pace() -> Result<()> {
        let mut col = Collection::new();
        // 27/30 = 90% accuracy at 147s/question → 90% × (98/147) = 60%.
        for i in 0..30 {
            col.storage.add_practice_attempt(&NewAttempt {
                id: &format!("ps:{i}"),
                session_id: Some("s1"),
                full_length_attempt_id: None,
                question_id: &format!("q{i}"),
                selected_answer: "A",
                correct: i < 27,
                time_on_question_seconds: 147,
                section_db: "CPBS",
                topic: "kinetics",
                answered_at: 0,
                hint_level_used: 0,
                assisted: false,
                main_wrong_first: false,
                first_try_no_hint: Some(if i < 27 { 1 } else { 0 }),
            })?;
        }

        let r = col.get_readiness(pb::GetReadinessRequest {
            deck_search: String::new(),
        })?;
        let perf = r.performance.unwrap();
        assert!(perf.available);
        assert!((r.performance_avg_seconds - 147.0).abs() < 1e-9);
        assert!(r.performance_time_penalty_applied);
        assert!((perf.value - 0.6).abs() < 1e-9, "got {}", perf.value);
        Ok(())
    }

    /// At or under 98s/question the Performance score is unchanged.
    #[test]
    fn readiness_performance_no_time_penalty_at_target_pace() -> Result<()> {
        let mut col = Collection::new();
        for i in 0..30 {
            col.storage.add_practice_attempt(&NewAttempt {
                id: &format!("ps:{i}"),
                session_id: Some("s1"),
                full_length_attempt_id: None,
                question_id: &format!("q{i}"),
                selected_answer: "A",
                correct: i < 27,
                time_on_question_seconds: if i == 0 { 98 } else { 97 },
                section_db: "CPBS",
                topic: "kinetics",
                answered_at: 0,
                hint_level_used: 0,
                assisted: false,
                main_wrong_first: false,
                first_try_no_hint: Some(if i < 27 { 1 } else { 0 }),
            })?;
        }

        let r = col.get_readiness(pb::GetReadinessRequest {
            deck_search: String::new(),
        })?;
        let perf = r.performance.unwrap();
        assert!(!r.performance_time_penalty_applied);
        assert!((perf.value - 0.9).abs() < 1e-9, "got {}", perf.value);
        Ok(())
    }

    /// EWMA with one observation at age 0 and one at exactly the 7-day half-life
    /// should weight the older one at half — here 1.0 vs 0.0 credit → 2/3 mean.
    #[test]
    fn readiness_performance_ewma_half_life() -> Result<()> {
        let mut col = Collection::new();
        let now = TimestampSecs::now().0;
        let age_secs = (ewma::PERFORMANCE_HALF_LIFE_DAYS * 86_400.0) as i64;
        col.storage.add_practice_attempt(&NewAttempt {
            id: "ps:recent",
            session_id: Some("s1"),
            full_length_attempt_id: None,
            question_id: "q-recent",
            selected_answer: "A",
            correct: true,
            time_on_question_seconds: 60,
            section_db: "CPBS",
            topic: "kinetics",
            answered_at: now,
            hint_level_used: 0,
            assisted: false,
            main_wrong_first: false,
            first_try_no_hint: Some(1),
        })?;
        col.storage.add_practice_attempt(&NewAttempt {
            id: "ps:old",
            session_id: Some("s1"),
            full_length_attempt_id: None,
            question_id: "q-old",
            selected_answer: "B",
            correct: false,
            time_on_question_seconds: 60,
            section_db: "CPBS",
            topic: "kinetics",
            answered_at: now - age_secs,
            hint_level_used: 0,
            assisted: false,
            main_wrong_first: false,
            first_try_no_hint: Some(0),
        })?;
        // Pad to the minimum answered threshold with equal-weight rows at `now`.
        for i in 0..28 {
            col.storage.add_practice_attempt(&NewAttempt {
                id: &format!("ps:pad{i}"),
                session_id: Some("s1"),
                full_length_attempt_id: None,
                question_id: &format!("q-pad{i}"),
                selected_answer: "A",
                correct: true,
                time_on_question_seconds: 60,
                section_db: "CPBS",
                topic: "kinetics",
                answered_at: now,
                hint_level_used: 0,
                assisted: false,
                main_wrong_first: false,
                first_try_no_hint: Some(1),
            })?;
        }

        let r = col.get_readiness(pb::GetReadinessRequest {
            deck_search: String::new(),
        })?;
        let perf = r.performance.unwrap();
        assert!(perf.available);
        // 29×1.0 + 0.5×0.0 at `now` vs one half-weight 0 at half-life → mean > 0.9
        assert!(perf.value > 0.9, "got {}", perf.value);
        Ok(())
    }

    /// Readiness EWMA: one correct answer now and one wrong answer one half-life ago.
    #[test]
    fn readiness_full_length_ewma_half_life() -> Result<()> {
        let mut col = Collection::new();
        let now = TimestampSecs::now().0;
        let age_secs = (ewma::READINESS_HALF_LIFE_DAYS * 86_400.0) as i64;
        col.storage
            .add_full_length_attempt("fla1", "fl-test", None, 0)?;
        col.storage.add_practice_attempt(&NewAttempt {
            id: "fla1:recent",
            session_id: None,
            full_length_attempt_id: Some("fla1"),
            question_id: "f-recent",
            selected_answer: "A",
            correct: true,
            time_on_question_seconds: 90,
            section_db: "CPBS",
            topic: "t",
            answered_at: now,
            hint_level_used: 0,
            assisted: false,
            main_wrong_first: false,
            first_try_no_hint: None,
        })?;
        col.storage.add_practice_attempt(&NewAttempt {
            id: "fla1:old",
            session_id: None,
            full_length_attempt_id: Some("fla1"),
            question_id: "f-old",
            selected_answer: "B",
            correct: false,
            time_on_question_seconds: 90,
            section_db: "CPBS",
            topic: "t",
            answered_at: now - age_secs,
            hint_level_used: 0,
            assisted: false,
            main_wrong_first: false,
            first_try_no_hint: None,
        })?;
        col.storage
            .complete_full_length_attempt("fla1", "[]", None, 100, true)?;

        let r = col.get_readiness(pb::GetReadinessRequest {
            deck_search: String::new(),
        })?;
        let readiness = r.readiness.unwrap();
        assert!(readiness.available);
        assert!((readiness.value - 2.0 / 3.0).abs() < 1e-9, "got {}", readiness.value);
        Ok(())
    }
}
