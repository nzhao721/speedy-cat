// Copyright: Ankitects Pty Ltd and contributors
// License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

//! SpeedyCAT: storage layer for the practice-question / full-length-test tables
//! (schema 19). These tables are local to the collection (no usn/sync columns).

use anki_proto::practice::AnswerChoice;
use anki_proto::practice::FullLengthBreak;
use anki_proto::practice::FullLengthSection;
use anki_proto::practice::FullLengthTest;
use anki_proto::practice::FullLengthTestSummary;
use anki_proto::practice::Passage;
use anki_proto::practice::PassageSummary;
use anki_proto::practice::PracticeQuestion;
use anki_proto::practice::SectionStat;
use anki_proto::practice::TopicStat;
use rusqlite::params;
use rusqlite::params_from_iter;
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
use crate::practice::StoredSection;

const QUESTION_COLS: &str = "select id, section, passage_id, test_id, stem, choices, \
    correct_answer, explanation, question_type, topic_tags, difficulty, source_name, \
    source_license, source_url, answer_provenance, notes from practice_questions";

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
}

fn row_to_question(row: &Row) -> Result<PracticeQuestion> {
    let section: String = row.get(1)?;
    let choices_json: String = row.get(5)?;
    let tags_json: String = row.get(9)?;
    let difficulty: String = row.get(10)?;
    let choices: Vec<StoredChoice> = serde_json::from_str(&choices_json)?;
    let topic_tags: Vec<String> = serde_json::from_str(&tags_json)?;
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
    })
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
        self.db
            .prepare_cached(
                "insert or replace into practice_questions (id, section, passage_id, test_id, \
                 stem, choices, correct_answer, explanation, question_type, topic_tags, \
                 difficulty, source_name, source_license, source_url, answer_provenance, notes) \
                 values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
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
            ])?;
        Ok(())
    }

    /// Questions matching the structural filters (topic filtering + limit are
    /// applied by the caller). Ordered by id for stable serving order.
    pub(crate) fn get_questions_filtered(
        &self,
        section_db: Option<&str>,
        difficulty_db: Option<&str>,
        passage_id: Option<&str>,
        include_full_length: bool,
        missed_only: bool,
    ) -> Result<Vec<PracticeQuestion>> {
        let mut sql = String::from(QUESTION_COLS);
        sql.push_str(" where 1=1");
        let mut args: Vec<Value> = Vec::new();
        if let Some(s) = section_db {
            sql.push_str(" and section = ?");
            args.push(Value::Text(s.to_string()));
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
                 time_on_question_seconds, section, topic, answered_at) \
                 values (?,?,?,?,?,?,?,?,?,?)",
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
            ])?;
        Ok(())
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
    ) -> Result<()> {
        self.db
            .prepare_cached(
                "update full_length_attempts set section_results = ?, overall_scaled_score = ?, \
                 completed_at = ? where id = ?",
            )?
            .execute(params![
                section_results_json,
                overall_scaled_score,
                completed_at,
                id
            ])?;
        Ok(())
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
    ) -> Result<Vec<TopicStat>> {
        let mut sql = String::from(
            "select section, topic, count(*), sum(correct), sum(time_on_question_seconds) \
             from practice_attempts where 1=1",
        );
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

    /// Time-spent + accuracy aggregated by section.
    pub(crate) fn section_stats(
        &self,
        section_db: Option<&str>,
        source_clause: Option<&str>,
    ) -> Result<Vec<SectionStat>> {
        let mut sql = String::from(
            "select section, count(*), sum(correct), sum(time_on_question_seconds) \
             from practice_attempts where 1=1",
        );
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
