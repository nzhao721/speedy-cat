// Copyright: Ankitects Pty Ltd and contributors
// License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

//! SpeedyCAT: [`PracticeService`](crate::services::PracticeService)
//! implementation — content import, question/passage queries, practice
//! sessions, full-length attempts, and per-topic tracking.

use std::collections::BTreeMap;

use anki_proto::practice as pb;

use crate::practice::attempt_source_clause;
use crate::practice::difficulty_to_db;
use crate::practice::new_full_length_attempt_id;
use crate::practice::new_session_id;
use crate::practice::section_from_db;
use crate::practice::section_to_db;
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
        let questions = self.matching_questions(&filter)?;
        let question_ids: Vec<String> = questions.into_iter().map(|q| q.id).collect();
        let session_id = new_session_id();
        let filter_json = serde_json::json!({
            "section": filter.section,
            "topics": filter.topics,
            "difficulty": filter.difficulty,
            "passage_id": filter.passage_id,
            "missed_only": filter.missed_only,
            "include_full_length": filter.include_full_length,
            "limit": filter.limit,
            "time_limit_seconds": input.time_limit_seconds,
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
            question_ids,
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
}

impl Collection {
    /// Apply a [`pb::QuestionFilter`]: structural filters run in SQL, topic
    /// matching (ANY, case-insensitive) and the limit are applied here.
    fn matching_questions(&self, filter: &pb::QuestionFilter) -> Result<Vec<pb::PracticeQuestion>> {
        let section_db = filter.section.map(|s| section_to_db(s).to_string());
        let difficulty_db = filter.difficulty.map(|d| difficulty_to_db(d).to_string());
        let mut questions = self.storage.get_questions_filtered(
            section_db.as_deref(),
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
        if filter.limit > 0 {
            questions.truncate(filter.limit as usize);
        }
        Ok(questions)
    }
}

#[cfg(test)]
mod test {
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
                section: Some(cpbs()),
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
                section: Some(cpbs()),
                ..Default::default()
            },
        );
        assert_eq!(free, vec!["cpbs-a", "cpbs-b", "cpbs-c"]);

        // Including full-length adds the fl-q1 item.
        let with_fl = ids(
            &mut col,
            pb::QuestionFilter {
                section: Some(cpbs()),
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
                section: Some(cpbs()),
                difficulty: Some(pb::Difficulty::Hard as i32),
                ..Default::default()
            },
        );
        assert_eq!(hard, vec!["cpbs-c"]);

        // Limit truncates (ordered by id).
        let limited = ids(
            &mut col,
            pb::QuestionFilter {
                section: Some(cpbs()),
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
                section: Some(cpbs()),
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
        assert_eq!(test.breaks[1].after_section, 2);
        assert_eq!(test.breaks[1].label, "Mid-exam break");
        assert_eq!(test.breaks[2].after_section, 3);
        assert_eq!(test.breaks[2].label, "Break");
        assert_eq!(test.total_break_seconds, 1800);
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
}
