// Copyright: Ankitects Pty Ltd and contributors
// License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

//! SpeedyCAT: storage layer for the practice-question / full-length-test tables
//! (schema 19). These tables are local to the collection (no usn/sync columns).

use anki_proto::practice::AnswerChoice;
use anki_proto::practice::FullLengthBreak;
use anki_proto::practice::FullLengthSection;
use anki_proto::practice::FullLengthTest;
use anki_proto::practice::FullLengthTestSummary;
use anki_proto::practice::HintChoice;
use anki_proto::practice::HintSubquestion;
use anki_proto::practice::Passage;
use anki_proto::practice::PassageSummary;
use anki_proto::practice::PracticeQuestion;
use anki_proto::practice::SectionStat;
use anki_proto::practice::TopicStat;
use rusqlite::params;
use rusqlite::params_from_iter;
use rusqlite::OptionalExtension;
use rusqlite::types::Value;
use rusqlite::Row;

use super::SqliteStorage;
use crate::error::Result;
use crate::practice::difficulty_from_db;
use crate::practice::difficulty_to_db;
use crate::practice::section_from_db;
use crate::practice::section_to_db;
use crate::practice::StoredBreak;
use crate::practice::StoredChoice;
use crate::practice::StoredHint;
use crate::practice::StoredHintChoice;
use crate::practice::StoredSection;

const QUESTION_COLS: &str = "select id, section, passage_id, test_id, stem, choices, \
    correct_answer, explanation, question_type, topic_tags, difficulty, source_name, \
    source_license, source_url, answer_provenance, notes, hints from practice_questions";

const PASSAGE_COLS: &str = "select passage_id, section, test_id, title, passage, discipline, \
    word_count, topic_tags, difficulty, source_name, source_license from practice_passages";

/// Fields for inserting a new attempt row. Exactly one of `session_id` /
/// `full_length_attempt_id` is expected to be set.
pub(crate) struct NewAttempt<'a> {
    pub id: &'a str,
    pub session_id: Option<&'a str>,
    pub full_length_attempt_id: Option<&'a str>,
    pub question_id: &'a str,
    pub selected_answer: &'a str,
    pub correct: bool,
    pub time_on_question_seconds: u32,
    pub section_db: &'a str,
    pub topic: &'a str,
    pub answered_at: i64,
    /// SpeedyCAT graduated hint ladder: highest hint tier reached before the
    /// answer was locked (0..3). Always 0 for full-length answers (no ladder).
    pub hint_level_used: u32,
    /// True when the learner reached level 3 of the ladder; penalized in the
    /// readiness Performance pillar (an assisted-correct is not a full correct).
    pub assisted: bool,
    /// True when the learner submitted a wrong main-question answer before
    /// finalizing (wrong-answer escalation into the hint ladder).
    pub main_wrong_first: bool,
    /// Dashboard first-try eligibility: `Some(0/1)` when this row is the
    /// learner's first-ever no-hint attempt on the question; `None` otherwise.
    pub first_try_no_hint: Option<i32>,
}

pub(crate) struct CompletedFullLengthAttempt {
    pub attempt_id: String,
    pub test_id: String,
    pub test_title: String,
    pub started_at: i64,
    pub completed_at: i64,
    pub overall_scaled_score: Option<u32>,
    pub total_correct: u32,
    pub total_questions: u32,
    pub counts_for_readiness: bool,
}

pub(crate) struct FullLengthAttemptMeta {
    pub test_id: String,
    pub completed_at: Option<i64>,
    pub abandoned: bool,
    pub counts_for_readiness: bool,
}

fn row_to_question(row: &Row) -> Result<PracticeQuestion> {
    let section: String = row.get(1)?;
    let choices_json: String = row.get(5)?;
    let tags_json: String = row.get(9)?;
    let difficulty: String = row.get(10)?;
    let choices: Vec<StoredChoice> = serde_json::from_str(&choices_json)?;
    let topic_tags: Vec<String> = serde_json::from_str(&tags_json)?;
    // SpeedyCAT graduated hint ladder: the column is nullable and may hold "" for
    // questions with no hints yet; treat both as an empty ladder.
    let hints_json: Option<String> = row.get(16)?;
    let hints = parse_stored_hints(hints_json.as_deref());
    Ok(PracticeQuestion {
        id: row.get(0)?,
        section: section_from_db(&section),
        passage_id: row.get(2)?,
        test_id: row.get(3)?,
        stem: row.get(4)?,
        choices: choices
            .into_iter()
            .map(|c| AnswerChoice {
                label: c.label,
                text: c.text,
            })
            .collect(),
        correct_answer: row.get(6)?,
        explanation: row.get(7)?,
        question_type: row.get(8)?,
        topic_tags,
        difficulty: difficulty_from_db(&difficulty),
        source_name: row.get(11)?,
        source_license: row.get(12)?,
        source_url: row.get(13)?,
        answer_provenance: row.get(14)?,
        notes: row.get(15)?,
        hints,
    })
}

/// SpeedyCAT graduated hint ladder: deserialize the stored hints JSON into proto
/// subquestions. Missing/empty/invalid JSON yields an empty ladder so a question
/// still loads (the UI then just offers no hints for it).
fn parse_stored_hints(json: Option<&str>) -> Vec<HintSubquestion> {
    let Some(text) = json else {
        return Vec::new();
    };
    if text.trim().is_empty() {
        return Vec::new();
    }
    let stored: Vec<StoredHint> = serde_json::from_str(text).unwrap_or_default();
    stored
        .into_iter()
        .map(|h| HintSubquestion {
            level: h.level,
            prompt: h.prompt,
            choices: h
                .choices
                .into_iter()
                .map(|c| HintChoice {
                    label: c.label,
                    text: c.text,
                })
                .collect(),
            correct_answer: h.correct_answer,
            rationale: h.rationale,
        })
        .collect()
}

fn row_to_passage(row: &Row) -> Result<Passage> {
    let section: String = row.get(1)?;
    let tags_json: String = row.get(7)?;
    let difficulty: String = row.get(8)?;
    Ok(Passage {
        passage_id: row.get(0)?,
        section: section_from_db(&section),
        test_id: row.get(2)?,
        title: row.get(3)?,
        passage: row.get(4)?,
        discipline: row.get(5)?,
        word_count: row.get::<_, Option<i64>>(6)?.map(|v| v as u32),
        topic_tags: serde_json::from_str(&tags_json)?,
        difficulty: difficulty_from_db(&difficulty),
        source_name: row.get(9)?,
        source_license: row.get(10)?,
    })
}

fn row_to_full_length_test(row: &Row) -> Result<FullLengthTest> {
    let sections_json: String = row.get(7)?;
    let breaks_json: String = row.get(8)?;
    let stored_sections: Vec<StoredSection> = serde_json::from_str(&sections_json)?;
    let stored_breaks: Vec<StoredBreak> = serde_json::from_str(&breaks_json)?;
    let sections: Vec<FullLengthSection> = stored_sections
        .iter()
        .map(|s| FullLengthSection {
            section: section_from_db(&s.section),
            order: s.order,
            duration_seconds: s.duration_seconds,
            question_count: s.question_count,
        })
        .collect();
    let breaks: Vec<FullLengthBreak> = stored_breaks
        .iter()
        .map(|b| FullLengthBreak {
            after_section: b.after_section,
            duration_seconds: b.duration_seconds,
            optional: b.optional,
            label: b.label.clone(),
        })
        .collect();
    let total_break_seconds: u32 = breaks.iter().map(|b| b.duration_seconds).sum();
    Ok(FullLengthTest {
        test_id: row.get(0)?,
        title: row.get(1)?,
        source: row.get(2)?,
        format: row.get(3)?,
        disclaimer: row.get(4)?,
        total_questions: row.get::<_, i64>(5)? as u32,
        total_testing_seconds: row.get::<_, i64>(6)? as u32,
        sections,
        breaks,
        total_break_seconds,
    })
}

impl SqliteStorage {
    // ---- Questions --------------------------------------------------------

    pub(crate) fn upsert_practice_question(&self, q: &PracticeQuestion) -> Result<()> {
        let choices: Vec<StoredChoice> = q
            .choices
            .iter()
            .map(|c| StoredChoice {
                label: c.label.clone(),
                text: c.text.clone(),
            })
            .collect();
        let choices_json = serde_json::to_string(&choices)?;
        let tags_json = serde_json::to_string(&q.topic_tags)?;
        // SpeedyCAT graduated hint ladder: store the subquestions as JSON. None
        // when the question has no hints, so the column stays NULL for those.
        let hints_json: Option<String> = if q.hints.is_empty() {
            None
        } else {
            let stored: Vec<StoredHint> = q
                .hints
                .iter()
                .map(|h| StoredHint {
                    level: h.level,
                    prompt: h.prompt.clone(),
                    choices: h
                        .choices
                        .iter()
                        .map(|c| StoredHintChoice {
                            label: c.label.clone(),
                            text: c.text.clone(),
                        })
                        .collect(),
                    correct_answer: h.correct_answer.clone(),
                    rationale: h.rationale.clone(),
                })
                .collect();
            Some(serde_json::to_string(&stored)?)
        };
        self.db
            .prepare_cached(
                "insert or replace into practice_questions (id, section, passage_id, test_id, \
                 stem, choices, correct_answer, explanation, question_type, topic_tags, \
                 difficulty, source_name, source_license, source_url, answer_provenance, notes, \
                 hints) \
                 values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            )?
            .execute(params![
                q.id,
                section_to_db(q.section),
                q.passage_id,
                q.test_id,
                q.stem,
                choices_json,
                q.correct_answer,
                q.explanation,
                q.question_type,
                tags_json,
                difficulty_to_db(q.difficulty),
                q.source_name,
                q.source_license,
                q.source_url,
                q.answer_provenance,
                q.notes,
                hints_json,
            ])?;
        Ok(())
    }

    /// Questions matching the structural filters (topic filtering, passage-set
    /// grouping and limit are applied by the caller). Ordered by id for stable
    /// serving order. `sections_db` matches ANY of the given sections; an empty
    /// slice means all sections.
    pub(crate) fn get_questions_filtered(
        &self,
        sections_db: &[String],
        difficulty_db: Option<&str>,
        passage_id: Option<&str>,
        include_full_length: bool,
        missed_only: bool,
    ) -> Result<Vec<PracticeQuestion>> {
        let mut sql = String::from(QUESTION_COLS);
        sql.push_str(" where 1=1");
        let mut args: Vec<Value> = Vec::new();
        if !sections_db.is_empty() {
            let placeholders = vec!["?"; sections_db.len()].join(",");
            sql.push_str(&format!(" and section in ({placeholders})"));
            for s in sections_db {
                args.push(Value::Text(s.clone()));
            }
        }
        if let Some(d) = difficulty_db {
            sql.push_str(" and difficulty = ?");
            args.push(Value::Text(d.to_string()));
        }
        if let Some(p) = passage_id {
            sql.push_str(" and passage_id = ?");
            args.push(Value::Text(p.to_string()));
        }
        if !include_full_length {
            sql.push_str(" and test_id is null");
        }
        if missed_only {
            sql.push_str(
                " and id in (select question_id from practice_attempts where correct = 0)",
            );
        }
        sql.push_str(" order by id");
        let mut stmt = self.db.prepare(&sql)?;
        let rows = stmt.query_and_then(params_from_iter(args), row_to_question)?;
        rows.collect()
    }

    pub(crate) fn questions_for_passage(&self, passage_id: &str) -> Result<Vec<PracticeQuestion>> {
        let mut stmt = self
            .db
            .prepare_cached(&format!("{QUESTION_COLS} where passage_id = ? order by id"))?;
        let rows = stmt.query_and_then([passage_id], row_to_question)?;
        rows.collect()
    }

    pub(crate) fn questions_for_test(&self, test_id: &str) -> Result<Vec<PracticeQuestion>> {
        let mut stmt = self
            .db
            .prepare_cached(&format!("{QUESTION_COLS} where test_id = ? order by id"))?;
        let rows = stmt.query_and_then([test_id], row_to_question)?;
        rows.collect()
    }

    // ---- Passages ---------------------------------------------------------

    pub(crate) fn upsert_practice_passage(&self, p: &Passage) -> Result<()> {
        let tags_json = serde_json::to_string(&p.topic_tags)?;
        self.db
            .prepare_cached(
                "insert or replace into practice_passages (passage_id, section, test_id, title, \
                 passage, discipline, word_count, topic_tags, difficulty, source_name, \
                 source_license) values (?,?,?,?,?,?,?,?,?,?,?)",
            )?
            .execute(params![
                p.passage_id,
                section_to_db(p.section),
                p.test_id,
                p.title,
                p.passage,
                p.discipline,
                p.word_count,
                tags_json,
                difficulty_to_db(p.difficulty),
                p.source_name,
                p.source_license,
            ])?;
        Ok(())
    }

    pub(crate) fn get_practice_passage(&self, passage_id: &str) -> Result<Option<Passage>> {
        self.db
            .prepare_cached(&format!("{PASSAGE_COLS} where passage_id = ?"))?
            .query_and_then([passage_id], row_to_passage)?
            .next()
            .transpose()
    }

    pub(crate) fn list_passages(
        &self,
        section_db: Option<&str>,
        test_id: Option<&str>,
    ) -> Result<Vec<PassageSummary>> {
        let mut sql = String::from(
            "select p.passage_id, p.section, p.title, p.discipline, p.difficulty, \
             (select count(*) from practice_questions q where q.passage_id = p.passage_id), \
             p.test_id from practice_passages p where 1=1",
        );
        let mut args: Vec<Value> = Vec::new();
        if let Some(s) = section_db {
            sql.push_str(" and p.section = ?");
            args.push(Value::Text(s.to_string()));
        }
        if let Some(t) = test_id {
            sql.push_str(" and p.test_id = ?");
            args.push(Value::Text(t.to_string()));
        }
        sql.push_str(" order by p.passage_id");
        let mut stmt = self.db.prepare(&sql)?;
        let rows = stmt.query_and_then(params_from_iter(args), |row| -> Result<PassageSummary> {
            let section: String = row.get(1)?;
            let difficulty: String = row.get(4)?;
            Ok(PassageSummary {
                passage_id: row.get(0)?,
                section: section_from_db(&section),
                title: row.get(2)?,
                discipline: row.get(3)?,
                difficulty: difficulty_from_db(&difficulty),
                question_count: row.get::<_, i64>(5)? as u32,
                test_id: row.get(6)?,
            })
        })?;
        rows.collect()
    }

    // ---- Practice sessions ------------------------------------------------

    pub(crate) fn add_practice_session(
        &self,
        id: &str,
        filter_json: &str,
        time_limit_seconds: u32,
        started_at: i64,
    ) -> Result<()> {
        self.db
            .prepare_cached(
                "insert into practice_sessions (id, filter, time_limit_seconds, started_at, \
                 completed_at) values (?,?,?,?,null)",
            )?
            .execute(params![id, filter_json, time_limit_seconds, started_at])?;
        Ok(())
    }

    /// Returns (started_at, completed_at) if the session exists.
    pub(crate) fn get_practice_session(&self, id: &str) -> Result<Option<(i64, Option<i64>)>> {
        self.db
            .prepare_cached("select started_at, completed_at from practice_sessions where id = ?")?
            .query_and_then([id], |r| {
                Ok((r.get::<_, i64>(0)?, r.get::<_, Option<i64>>(1)?))
            })?
            .next()
            .transpose()
    }

    pub(crate) fn complete_practice_session(&self, id: &str, completed_at: i64) -> Result<()> {
        self.db
            .prepare_cached("update practice_sessions set completed_at = ? where id = ?")?
            .execute(params![completed_at, id])?;
        Ok(())
    }

    // ---- Attempts ---------------------------------------------------------

    pub(crate) fn add_practice_attempt(&self, a: &NewAttempt) -> Result<()> {
        self.db
            .prepare_cached(
                "insert or replace into practice_attempts (id, session_id, \
                 full_length_attempt_id, question_id, selected_answer, correct, \
                 time_on_question_seconds, section, topic, answered_at, hint_level_used, \
                 assisted, main_wrong_first, first_try_no_hint) \
                 values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            )?
            .execute(params![
                a.id,
                a.session_id,
                a.full_length_attempt_id,
                a.question_id,
                a.selected_answer,
                a.correct,
                a.time_on_question_seconds,
                a.section_db,
                a.topic,
                a.answered_at,
                a.hint_level_used,
                a.assisted,
                a.main_wrong_first,
                a.first_try_no_hint,
            ])?;
        Ok(())
    }

    /// Whether any attempt row exists for `question_id` other than `exclude_id`.
    pub(crate) fn question_attempted_before(
        &self,
        question_id: &str,
        exclude_id: &str,
    ) -> Result<bool> {
        self.db
            .prepare_cached(
                "select 1 from practice_attempts where question_id = ? and id <> ? limit 1",
            )?
            .exists(params![question_id, exclude_id])
            .map_err(Into::into)
    }

    /// Prior `first_try_no_hint` for an attempt row, if it exists.
    pub(crate) fn attempt_first_try_no_hint(&self, id: &str) -> Result<Option<Option<i32>>> {
        self.db
            .prepare_cached("select first_try_no_hint from practice_attempts where id = ?")?
            .query_row([id], |r| r.get(0))
            .optional()
            .map_err(Into::into)
    }

    /// (correct, time_seconds, section_db, selected_answer) for each attempt in
    /// a practice session.
    pub(crate) fn practice_session_attempts(
        &self,
        session_id: &str,
    ) -> Result<Vec<(bool, u32, String, String)>> {
        let mut stmt = self.db.prepare_cached(
            "select correct, time_on_question_seconds, section, selected_answer \
             from practice_attempts where session_id = ?",
        )?;
        let rows = stmt.query_and_then([session_id], |r| {
            Ok((
                r.get::<_, bool>(0)?,
                r.get::<_, i64>(1)? as u32,
                r.get::<_, String>(2)?,
                r.get::<_, String>(3)?,
            ))
        })?;
        rows.collect()
    }

    // ---- Full-length tests ------------------------------------------------

    pub(crate) fn upsert_full_length_test(&self, t: &FullLengthTest) -> Result<()> {
        let sections: Vec<StoredSection> = t
            .sections
            .iter()
            .map(|s| StoredSection {
                section: section_to_db(s.section).to_string(),
                order: s.order,
                duration_seconds: s.duration_seconds,
                question_count: s.question_count,
            })
            .collect();
        let breaks: Vec<StoredBreak> = t
            .breaks
            .iter()
            .map(|b| StoredBreak {
                after_section: b.after_section,
                duration_seconds: b.duration_seconds,
                optional: b.optional,
                label: b.label.clone(),
            })
            .collect();
        let sections_json = serde_json::to_string(&sections)?;
        let breaks_json = serde_json::to_string(&breaks)?;
        self.db
            .prepare_cached(
                "insert or replace into full_length_tests (id, title, source, format, disclaimer, \
                 total_questions, total_testing_seconds, sections, breaks) \
                 values (?,?,?,?,?,?,?,?,?)",
            )?
            .execute(params![
                t.test_id,
                t.title,
                t.source,
                t.format,
                t.disclaimer,
                t.total_questions,
                t.total_testing_seconds,
                sections_json,
                breaks_json,
            ])?;
        Ok(())
    }

    pub(crate) fn get_full_length_test(&self, id: &str) -> Result<Option<FullLengthTest>> {
        self.db
            .prepare_cached(
                "select id, title, source, format, disclaimer, total_questions, \
                 total_testing_seconds, sections, breaks from full_length_tests where id = ?",
            )?
            .query_and_then([id], row_to_full_length_test)?
            .next()
            .transpose()
    }

    pub(crate) fn list_full_length_tests(&self) -> Result<Vec<FullLengthTestSummary>> {
        let mut stmt = self.db.prepare_cached(
            "select id, title, total_questions, total_testing_seconds, breaks \
             from full_length_tests order by id",
        )?;
        let rows = stmt.query_and_then([], |row| -> Result<FullLengthTestSummary> {
            let breaks_json: String = row.get(4)?;
            let stored_breaks: Vec<StoredBreak> = serde_json::from_str(&breaks_json)?;
            let total_break_seconds: u32 = stored_breaks.iter().map(|b| b.duration_seconds).sum();
            Ok(FullLengthTestSummary {
                test_id: row.get(0)?,
                title: row.get(1)?,
                total_questions: row.get::<_, i64>(2)? as u32,
                total_testing_seconds: row.get::<_, i64>(3)? as u32,
                total_break_seconds,
            })
        })?;
        rows.collect()
    }

    // ---- Full-length attempts ---------------------------------------------

    pub(crate) fn add_full_length_attempt(
        &self,
        id: &str,
        test_id: &str,
        aamc_exam_id: Option<&str>,
        started_at: i64,
    ) -> Result<()> {
        self.db
            .prepare_cached(
                "insert into full_length_attempts (id, test_id, aamc_exam_id, started_at, \
                 completed_at, section_results, overall_scaled_score) \
                 values (?,?,?,?,null,null,null)",
            )?
            .execute(params![id, test_id, aamc_exam_id, started_at])?;
        Ok(())
    }

    /// Returns (test_id, completed_at) if the attempt exists.
    pub(crate) fn get_full_length_attempt(
        &self,
        id: &str,
    ) -> Result<Option<(String, Option<i64>)>> {
        self.db
            .prepare_cached("select test_id, completed_at from full_length_attempts where id = ?")?
            .query_and_then([id], |r| {
                Ok((r.get::<_, String>(0)?, r.get::<_, Option<i64>>(1)?))
            })?
            .next()
            .transpose()
    }

    pub(crate) fn complete_full_length_attempt(
        &self,
        id: &str,
        section_results_json: &str,
        overall_scaled_score: Option<u32>,
        completed_at: i64,
        counts_for_readiness: bool,
    ) -> Result<()> {
        self.db
            .prepare_cached(
                "update full_length_attempts set section_results = ?, overall_scaled_score = ?, \
                 completed_at = ?, counts_for_readiness = ?, abandoned = 0 where id = ?",
            )?
            .execute(params![
                section_results_json,
                overall_scaled_score,
                completed_at,
                counts_for_readiness as i32,
                id
            ])?;
        Ok(())
    }

    /// Mark an in-progress attempt as abandoned (user left mid-exam).
    pub(crate) fn abandon_full_length_attempt(&self, id: &str, completed_at: i64) -> Result<()> {
        self.db
            .prepare_cached(
                "update full_length_attempts set completed_at = ?, section_results = '[]', \
                 overall_scaled_score = null, counts_for_readiness = 0, abandoned = 1 \
                 where id = ? and completed_at is null",
            )?
            .execute(params![completed_at, id])?;
        Ok(())
    }

    /// In-progress attempts (not submitted, not abandoned), optionally excluding one id.
    pub(crate) fn count_incomplete_full_length_attempts(
        &self,
        exclude_id: Option<&str>,
    ) -> Result<u32> {
        let sql = if exclude_id.is_some() {
            "select count(*) from full_length_attempts \
             where completed_at is null and abandoned = 0 and id != ?"
        } else {
            "select count(*) from full_length_attempts \
             where completed_at is null and abandoned = 0"
        };
        let count = if let Some(id) = exclude_id {
            self.db
                .prepare_cached(sql)?
                .query_row([id], |r| r.get::<_, i64>(0))?
        } else {
            self.db.prepare_cached(sql)?.query_row([], |r| r.get::<_, i64>(0))?
        };
        Ok(count as u32)
    }

    /// (section_db, correct, total, time_seconds) grouped by section for a
    /// full-length attempt.
    pub(crate) fn full_length_section_counts(
        &self,
        attempt_id: &str,
    ) -> Result<Vec<(String, u32, u32, u32)>> {
        let mut stmt = self.db.prepare_cached(
            "select section, sum(correct), count(*), sum(time_on_question_seconds) \
             from practice_attempts where full_length_attempt_id = ? group by section order by section",
        )?;
        let rows = stmt.query_and_then([attempt_id], |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, i64>(1)? as u32,
                r.get::<_, i64>(2)? as u32,
                r.get::<_, i64>(3)? as u32,
            ))
        })?;
        rows.collect()
    }

    // ---- Tracking / aggregation -------------------------------------------

    /// Time-spent + accuracy aggregated by (section, topic).
    pub(crate) fn topic_stats(
        &self,
        section_db: Option<&str>,
        source_clause: Option<&str>,
        first_attempt_no_hint_only: bool,
    ) -> Result<Vec<TopicStat>> {
        let mut sql = String::from(
            "select section, topic, count(*), sum(",
        );
        if first_attempt_no_hint_only {
            sql.push_str("first_try_no_hint");
        } else {
            sql.push_str("correct");
        }
        sql.push_str("), sum(time_on_question_seconds) \
             from practice_attempts where 1=1");
        if first_attempt_no_hint_only {
            sql.push_str(" and first_try_no_hint is not null");
        }
        let mut args: Vec<Value> = Vec::new();
        if let Some(s) = section_db {
            sql.push_str(" and section = ?");
            args.push(Value::Text(s.to_string()));
        }
        if let Some(clause) = source_clause {
            sql.push_str(" and ");
            sql.push_str(clause);
        }
        sql.push_str(" group by section, topic order by section, topic");
        let mut stmt = self.db.prepare(&sql)?;
        let rows = stmt.query_and_then(params_from_iter(args), |row| -> Result<TopicStat> {
            let section: String = row.get(0)?;
            let attempts = row.get::<_, i64>(2)? as u32;
            let correct = row.get::<_, i64>(3)? as u32;
            let total_time = row.get::<_, i64>(4)? as u32;
            Ok(TopicStat {
                topic: row.get(1)?,
                section: section_from_db(&section),
                attempts,
                correct,
                total_time_seconds: total_time,
                accuracy: ratio(correct, attempts),
                avg_time_seconds: ratio(total_time, attempts),
            })
        })?;
        rows.collect()
    }

    /// (weighted_credit_sum, answered, total_time_seconds) over answered practice-session
    /// attempts (a session attempt with a non-empty selected answer). Feeds the
    /// readiness Performance pillar; skipped/unanswered questions are excluded
    /// so accuracy is over questions the learner actually answered.
    ///
    /// SpeedyCAT progressive hint penalties: credit is summed per attempt (L0=1.0,
    /// L1=0.60, L2=0.30, L3=0.10; main-wrong-first or incorrect=0). The
    /// denominator is every answered attempt; timing is unaffected.
    ///
    /// Prefer [`Self::practice_performance_observations`] for EWMA aggregation.
    pub(crate) fn practice_accuracy_totals(&self) -> Result<(f64, u32, u32)> {
        self.db
            .prepare_cached(
                "select coalesce(sum(
                    case
                        when main_wrong_first = 1 or correct = 0 then 0.0
                        when hint_level_used = 0 then 1.0
                        when hint_level_used = 1 then 0.6
                        when hint_level_used = 2 then 0.3
                        else 0.1
                    end
                 ), 0.0), count(*), \
                 coalesce(sum(time_on_question_seconds), 0) from practice_attempts \
                 where session_id is not null and selected_answer <> ''",
            )?
            .query_row([], |r| {
                Ok((
                    r.get::<_, f64>(0)?,
                    r.get::<_, i64>(1)? as u32,
                    r.get::<_, i64>(2)? as u32,
                ))
            })
            .map_err(Into::into)
    }

    /// Per-answered practice-session attempt for EWMA Performance aggregation:
    /// (fractional credit, answered_at, time_on_question_seconds).
    pub(crate) fn practice_performance_observations(
        &self,
    ) -> Result<Vec<(f64, i64, u32)>> {
        let mut stmt = self.db.prepare_cached(
            "select
                case
                    when main_wrong_first = 1 or correct = 0 then 0.0
                    when hint_level_used = 0 then 1.0
                    when hint_level_used = 1 then 0.6
                    when hint_level_used = 2 then 0.3
                    else 0.1
                end,
                answered_at,
                time_on_question_seconds
             from practice_attempts
             where session_id is not null and selected_answer <> ''",
        )?;
        let rows = stmt.query_and_then([], |r| {
            Ok((
                r.get::<_, f64>(0)?,
                r.get::<_, i64>(1)?,
                r.get::<_, i64>(2)? as u32,
            ))
        })?;
        rows.collect()
    }

    /// Per-answer rows for EWMA Readiness aggregation over completed full-length
    /// tests: (correct as 0/1, answered_at).
    pub(crate) fn full_length_readiness_observations(&self) -> Result<Vec<(f64, i64)>> {
        let mut stmt = self.db.prepare_cached(
            "select a.correct, a.answered_at from practice_attempts a \
             join full_length_attempts f on a.full_length_attempt_id = f.id \
             where f.completed_at is not null and f.abandoned = 0 \
             and f.counts_for_readiness = 1",
        )?;
        let rows = stmt.query_and_then([], |r| {
            let correct: i64 = r.get(0)?;
            Ok((if correct != 0 { 1.0 } else { 0.0 }, r.get::<_, i64>(1)?))
        })?;
        rows.collect()
    }

    /// (correct, total) over answers recorded for COMPLETED full-length attempts
    /// that count toward readiness (excludes abandoned and scores earned while
    /// another attempt was left unfinished).
    pub(crate) fn full_length_score_totals(&self) -> Result<(u32, u32)> {
        self.db
            .prepare_cached(
                "select coalesce(sum(a.correct), 0), count(*) from practice_attempts a \
                 join full_length_attempts f on a.full_length_attempt_id = f.id \
                 where f.completed_at is not null and f.abandoned = 0 \
                 and f.counts_for_readiness = 1",
            )?
            .query_row([], |r| {
                Ok((r.get::<_, i64>(0)? as u32, r.get::<_, i64>(1)? as u32))
            })
            .map_err(Into::into)
    }

    /// Per-section (section_db, correct, total) over answers recorded for
    /// completed full-length attempts that count toward readiness.
    pub(crate) fn full_length_section_scores(&self) -> Result<Vec<(String, u32, u32)>> {
        let mut stmt = self.db.prepare_cached(
            "select a.section, coalesce(sum(a.correct), 0), count(*) from practice_attempts a \
             join full_length_attempts f on a.full_length_attempt_id = f.id \
             where f.completed_at is not null and f.abandoned = 0 \
             and f.counts_for_readiness = 1 group by a.section order by a.section",
        )?;
        let rows = stmt.query_and_then([], |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, i64>(1)? as u32,
                r.get::<_, i64>(2)? as u32,
            ))
        })?;
        rows.collect()
    }

    /// Completed, non-abandoned attempts with aggregate scores (newest first).
    pub(crate) fn list_completed_full_length_attempts(
        &self,
    ) -> Result<Vec<CompletedFullLengthAttempt>> {
        let mut stmt = self.db.prepare_cached(
            "select f.id, f.test_id, t.title, f.started_at, f.completed_at, \
             f.overall_scaled_score, f.counts_for_readiness, \
             coalesce((select sum(a.correct) from practice_attempts a \
               where a.full_length_attempt_id = f.id), 0), \
             coalesce((select count(*) from practice_attempts a \
               where a.full_length_attempt_id = f.id), 0) \
             from full_length_attempts f \
             join full_length_tests t on f.test_id = t.id \
             where f.completed_at is not null and f.abandoned = 0 \
             order by f.completed_at desc",
        )?;
        let rows = stmt.query_and_then([], |r| {
            Ok(CompletedFullLengthAttempt {
                attempt_id: r.get(0)?,
                test_id: r.get(1)?,
                test_title: r.get(2)?,
                started_at: r.get(3)?,
                completed_at: r.get(4)?,
                overall_scaled_score: r.get::<_, Option<i64>>(5)?.map(|s| s as u32),
                total_correct: r.get::<_, i64>(7)? as u32,
                total_questions: r.get::<_, i64>(8)? as u32,
                counts_for_readiness: r.get::<_, i64>(6)? != 0,
            })
        })?;
        rows.collect()
    }

    /// (section_db, topic, correct, total) for one completed attempt.
    pub(crate) fn full_length_topic_scores(
        &self,
        attempt_id: &str,
    ) -> Result<Vec<(String, String, u32, u32)>> {
        let mut stmt = self.db.prepare_cached(
            "select section, topic, coalesce(sum(correct), 0), count(*) \
             from practice_attempts where full_length_attempt_id = ? \
             group by section, topic order by section, topic",
        )?;
        let rows = stmt.query_and_then([attempt_id], |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, i64>(2)? as u32,
                r.get::<_, i64>(3)? as u32,
            ))
        })?;
        rows.collect()
    }

    /// Stored answers for a full-length attempt: (question_id, selected_answer, correct, section_db).
    pub(crate) fn full_length_attempt_answers(
        &self,
        attempt_id: &str,
    ) -> Result<Vec<(String, String, bool, String)>> {
        let mut stmt = self.db.prepare_cached(
            "select question_id, selected_answer, correct, section \
             from practice_attempts where full_length_attempt_id = ? \
             order by answered_at",
        )?;
        let rows = stmt.query_and_then([attempt_id], |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, i64>(2)? != 0,
                r.get::<_, String>(3)?,
            ))
        })?;
        rows.collect()
    }

    /// Returns (test_id, completed_at, abandoned, counts_for_readiness) if the attempt exists.
    pub(crate) fn get_full_length_attempt_meta(
        &self,
        id: &str,
    ) -> Result<Option<FullLengthAttemptMeta>> {
        self.db
            .prepare_cached(
                "select test_id, completed_at, abandoned, counts_for_readiness \
                 from full_length_attempts where id = ?",
            )?
            .query_and_then([id], |r| {
                Ok(FullLengthAttemptMeta {
                    test_id: r.get(0)?,
                    completed_at: r.get(1)?,
                    abandoned: r.get::<_, i64>(2)? != 0,
                    counts_for_readiness: r.get::<_, i64>(3)? != 0,
                })
            })?
            .next()
            .transpose()
    }

    /// Time-spent + accuracy aggregated by section.
    pub(crate) fn section_stats(
        &self,
        section_db: Option<&str>,
        source_clause: Option<&str>,
        first_attempt_no_hint_only: bool,
    ) -> Result<Vec<SectionStat>> {
        let mut sql = String::from("select section, count(*), sum(");
        if first_attempt_no_hint_only {
            sql.push_str("first_try_no_hint");
        } else {
            sql.push_str("correct");
        }
        sql.push_str("), sum(time_on_question_seconds) \
             from practice_attempts where 1=1");
        if first_attempt_no_hint_only {
            sql.push_str(" and first_try_no_hint is not null");
        }
        let mut args: Vec<Value> = Vec::new();
        if let Some(s) = section_db {
            sql.push_str(" and section = ?");
            args.push(Value::Text(s.to_string()));
        }
        if let Some(clause) = source_clause {
            sql.push_str(" and ");
            sql.push_str(clause);
        }
        sql.push_str(" group by section order by section");
        let mut stmt = self.db.prepare(&sql)?;
        let rows = stmt.query_and_then(params_from_iter(args), |row| -> Result<SectionStat> {
            let section: String = row.get(0)?;
            let attempts = row.get::<_, i64>(1)? as u32;
            let correct = row.get::<_, i64>(2)? as u32;
            let total_time = row.get::<_, i64>(3)? as u32;
            Ok(SectionStat {
                section: section_from_db(&section),
                attempts,
                correct,
                total_time_seconds: total_time,
                accuracy: ratio(correct, attempts),
                avg_time_seconds: ratio(total_time, attempts),
            })
        })?;
        rows.collect()
    }
}

fn ratio(numerator: u32, denominator: u32) -> f64 {
    if denominator > 0 {
        numerator as f64 / denominator as f64
    } else {
        0.0
    }
}
