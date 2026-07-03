// Copyright: Ankitects Pty Ltd and contributors
// License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

//! SpeedyCAT: parse the structured content bundles under
//! `content/practice-questions/*.json` and `content/full-length-tests/*.json`
//! and load them into the collection DB.
//!
//! Two question-bundle shapes are supported:
//! - discrete items: `{ "meta": ..., "questions": [ ... ] }` (may contain
//!   passage-linked CARS items with an inline `passage`/`passageId`)
//! - CARS passage sets: `{ "meta": ..., "passageSets": [ { passageId, passage,
//!   discipline, questions: [ ... ] } ] }`
//!
//! Full-length definitions: `{ testId, title, ..., sections: [ { sectionId,
//! order, durationSeconds, questionCount, passages: [...], questions: [...] } ]
//! }`. Scheduled MCAT breaks are synthesized when the bundle omits them.

use std::collections::HashSet;

use anki_proto::practice::AnswerChoice;
use anki_proto::practice::FullLengthBreak;
use anki_proto::practice::FullLengthSection;
use anki_proto::practice::FullLengthTest;
use anki_proto::practice::HintChoice;
use anki_proto::practice::HintSubquestion;
use anki_proto::practice::LoadBundleResponse;
use anki_proto::practice::Passage;
use anki_proto::practice::PracticeQuestion;
use serde::Deserialize;

use crate::practice::difficulty_from_db;
use crate::practice::normalize_difficulty;
use crate::practice::normalize_section;
use crate::practice::section_from_db;
use crate::prelude::*;

/// A regular ~10-minute break (seconds), matching the real AAMC MCAT breaks
/// after sections 1 (Chem/Phys) and 3 (Bio/Biochem).
const BREAK_SECONDS: u32 = 600;

/// The 30-minute mid-exam break (seconds) after section 2 (CARS) on the real
/// AAMC MCAT.
const MID_EXAM_BREAK_SECONDS: u32 = 1800;

// ---- Raw (JSON) representations -------------------------------------------

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RawChoice {
    label: String,
    text: String,
}

// SpeedyCAT graduated hint ladder — raw (JSON) shapes. The content generators
// own the hint content in the question bundles; we only parse it, defensively.
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RawHintChoice {
    #[serde(default)]
    label: String,
    #[serde(default)]
    text: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RawHint {
    #[serde(default)]
    level: u32,
    #[serde(default)]
    prompt: String,
    #[serde(default)]
    choices: Vec<RawHintChoice>,
    #[serde(default)]
    correct_answer: String,
    #[serde(default)]
    rationale: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RawQuestion {
    id: String,
    #[serde(default)]
    section: String,
    #[serde(default)]
    passage_id: Option<String>,
    #[serde(default)]
    passage: Option<String>,
    stem: String,
    choices: Vec<RawChoice>,
    correct_answer: String,
    #[serde(default)]
    explanation: String,
    #[serde(default)]
    question_type: Option<String>,
    #[serde(default)]
    topic_tags: Vec<String>,
    #[serde(default)]
    difficulty: String,
    #[serde(default)]
    source_name: String,
    #[serde(default)]
    source_license: String,
    #[serde(default)]
    source_url: Option<String>,
    #[serde(default)]
    answer_provenance: Option<String>,
    #[serde(default)]
    notes: Option<String>,
    // SpeedyCAT graduated hint ladder (generated separately into the bundle).
    #[serde(default)]
    hints: Vec<RawHint>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RawPassageSet {
    passage_id: String,
    #[serde(default)]
    section: String,
    #[serde(default)]
    title: String,
    passage: String,
    #[serde(default)]
    discipline: Option<String>,
    #[serde(default)]
    word_count: Option<u32>,
    #[serde(default)]
    topic_tags: Vec<String>,
    #[serde(default)]
    difficulty: String,
    #[serde(default)]
    source_name: Option<String>,
    #[serde(default)]
    source_license: Option<String>,
    #[serde(default)]
    questions: Vec<RawQuestion>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct QuestionBundle {
    #[serde(default)]
    questions: Vec<RawQuestion>,
    #[serde(default)]
    passage_sets: Vec<RawPassageSet>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RawFullLengthPassage {
    passage_id: String,
    #[serde(default)]
    title: String,
    passage: String,
    #[serde(default)]
    discipline: Option<String>,
    #[serde(default)]
    word_count: Option<u32>,
    #[serde(default)]
    topic_tags: Vec<String>,
    #[serde(default)]
    difficulty: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RawFullLengthSection {
    section_id: String,
    order: u32,
    duration_seconds: u32,
    question_count: u32,
    #[serde(default)]
    passages: Vec<RawFullLengthPassage>,
    #[serde(default)]
    questions: Vec<RawQuestion>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RawBreak {
    after_section: u32,
    #[serde(default = "default_break_seconds")]
    duration_seconds: u32,
    #[serde(default = "default_true")]
    optional: bool,
    #[serde(default)]
    label: String,
}

fn default_break_seconds() -> u32 {
    BREAK_SECONDS
}

fn default_true() -> bool {
    true
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct FullLengthBundle {
    test_id: String,
    #[serde(default)]
    title: String,
    #[serde(default)]
    source: String,
    #[serde(default)]
    format: String,
    #[serde(default)]
    disclaimer: String,
    #[serde(default)]
    total_questions: u32,
    #[serde(default)]
    total_testing_seconds: u32,
    sections: Vec<RawFullLengthSection>,
    #[serde(default)]
    breaks: Vec<RawBreak>,
}

// ---- Helpers --------------------------------------------------------------

/// SpeedyCAT graduated hint ladder: convert the raw hint subquestions into
/// validated proto messages, DEFENSIVELY. Content generation is asynchronous, so
/// a question may temporarily carry malformed/partial hints; we keep only the
/// well-formed tiers (a non-empty prompt, exactly 4 choices, and a
/// `correctAnswer` that matches one of those choice labels) and drop the rest,
/// so the UI simply shows the tiers that exist (or none). Levels are normalized
/// to their 1-based position when missing/out of range so the ladder still
/// escalates 1→N. The order in the bundle is preserved.
fn build_hints(raw_hints: &[RawHint]) -> Vec<HintSubquestion> {
    let mut out = Vec::new();
    for raw in raw_hints {
        if raw.prompt.trim().is_empty() || raw.choices.len() != 4 {
            continue;
        }
        let choices: Vec<HintChoice> = raw
            .choices
            .iter()
            .map(|c| HintChoice {
                label: c.label.clone(),
                text: c.text.clone(),
            })
            .collect();
        let correct = raw.correct_answer.trim();
        if correct.is_empty() || !choices.iter().any(|c| c.label == correct) {
            continue;
        }
        let position = out.len() as u32 + 1;
        let level = if (1..=3).contains(&raw.level) {
            raw.level
        } else {
            position
        };
        out.push(HintSubquestion {
            level,
            prompt: raw.prompt.clone(),
            choices,
            correct_answer: correct.to_string(),
            rationale: raw.rationale.clone(),
        });
    }
    out
}

fn build_question(
    raw: &RawQuestion,
    section_db: &str,
    passage_id: Option<String>,
    test_id: Option<String>,
) -> PracticeQuestion {
    PracticeQuestion {
        id: raw.id.clone(),
        section: section_from_db(section_db),
        passage_id,
        stem: raw.stem.clone(),
        choices: raw
            .choices
            .iter()
            .map(|c| AnswerChoice {
                label: c.label.clone(),
                text: c.text.clone(),
            })
            .collect(),
        correct_answer: raw.correct_answer.clone(),
        explanation: raw.explanation.clone(),
        question_type: raw.question_type.clone(),
        topic_tags: raw.topic_tags.clone(),
        difficulty: difficulty_from_db(&normalize_difficulty(&raw.difficulty)),
        source_name: raw.source_name.clone(),
        source_license: raw.source_license.clone(),
        source_url: raw.source_url.clone(),
        answer_provenance: raw.answer_provenance.clone(),
        notes: raw.notes.clone(),
        test_id,
        hints: build_hints(&raw.hints),
    }
}

/// PRD guardrail: reject question-bank imports missing source/license metadata.
fn validate_question_source(raw: &RawQuestion) -> Result<()> {
    require!(
        !raw.source_name.trim().is_empty(),
        "question {} is missing sourceName",
        raw.id
    );
    require!(
        !raw.source_license.trim().is_empty(),
        "question {} is missing sourceLicense",
        raw.id
    );
    Ok(())
}

/// Synthesize the standard AAMC MCAT scheduled breaks for a test with
/// `num_sections` sections: optional breaks after every section except the last.
/// The break after section 2 (CARS) is the 30-minute mid-exam break; the others
/// are regular 10-minute breaks.
fn synthesize_breaks(num_sections: u32) -> Vec<FullLengthBreak> {
    (1..num_sections)
        .map(|after_section| FullLengthBreak {
            after_section,
            duration_seconds: if after_section == 2 {
                MID_EXAM_BREAK_SECONDS
            } else {
                BREAK_SECONDS
            },
            optional: true,
            label: if after_section == 2 {
                "Mid-exam break".to_string()
            } else {
                "Break".to_string()
            },
        })
        .collect()
}

impl Collection {
    /// Parse and upsert a Practice Question Bank bundle (discrete-item or
    /// passage-set format). Caller is responsible for the surrounding
    /// transaction.
    pub(crate) fn load_practice_question_bundle_json(
        &mut self,
        json: &str,
        _replace: bool,
    ) -> Result<LoadBundleResponse> {
        let bundle: QuestionBundle = serde_json::from_str(json)?;
        let mut questions_imported = 0u32;
        let mut passages_imported = 0u32;
        let mut seen_passages: HashSet<String> = HashSet::new();

        // CARS passage-set format.
        for set in &bundle.passage_sets {
            let section_db = {
                let s = normalize_section(&set.section);
                if s.is_empty() {
                    "CARS".to_string()
                } else {
                    s
                }
            };
            let passage = Passage {
                passage_id: set.passage_id.clone(),
                section: section_from_db(&section_db),
                title: set.title.clone(),
                passage: set.passage.clone(),
                discipline: set.discipline.clone(),
                word_count: set.word_count,
                topic_tags: set.topic_tags.clone(),
                difficulty: difficulty_from_db(&normalize_difficulty(&set.difficulty)),
                source_name: set.source_name.clone(),
                source_license: set.source_license.clone(),
                test_id: None,
            };
            self.storage.upsert_practice_passage(&passage)?;
            if seen_passages.insert(set.passage_id.clone()) {
                passages_imported += 1;
            }
            for raw in &set.questions {
                validate_question_source(raw)?;
                let q = build_question(raw, &section_db, Some(set.passage_id.clone()), None);
                self.storage.upsert_practice_question(&q)?;
                questions_imported += 1;
            }
        }

        // Discrete / inline-passage format.
        for raw in &bundle.questions {
            validate_question_source(raw)?;
            let section_db = normalize_section(&raw.section);
            // A denormalized passage-linked item inlines its passage text.
            if let (Some(passage_id), Some(passage_text)) = (&raw.passage_id, &raw.passage) {
                if seen_passages.insert(passage_id.clone()) {
                    let passage = Passage {
                        passage_id: passage_id.clone(),
                        section: section_from_db(&section_db),
                        title: passage_id.clone(),
                        passage: passage_text.clone(),
                        discipline: None,
                        word_count: None,
                        topic_tags: raw.topic_tags.clone(),
                        difficulty: difficulty_from_db(&normalize_difficulty(&raw.difficulty)),
                        source_name: Some(raw.source_name.clone()),
                        source_license: Some(raw.source_license.clone()),
                        test_id: None,
                    };
                    self.storage.upsert_practice_passage(&passage)?;
                    passages_imported += 1;
                }
                let q = build_question(raw, &section_db, Some(passage_id.clone()), None);
                self.storage.upsert_practice_question(&q)?;
            } else {
                let q = build_question(raw, &section_db, raw.passage_id.clone(), None);
                self.storage.upsert_practice_question(&q)?;
            }
            questions_imported += 1;
        }

        Ok(LoadBundleResponse {
            questions_imported,
            passages_imported,
            tests_imported: 0,
            warnings: vec![],
        })
    }

    /// Parse and upsert a Full-Length Test definition bundle. Caller is
    /// responsible for the surrounding transaction.
    pub(crate) fn load_full_length_bundle_json(
        &mut self,
        json: &str,
        _replace: bool,
    ) -> Result<LoadBundleResponse> {
        let bundle: FullLengthBundle = serde_json::from_str(json)?;
        let mut questions_imported = 0u32;
        let mut passages_imported = 0u32;

        let sections: Vec<FullLengthSection> = bundle
            .sections
            .iter()
            .map(|s| FullLengthSection {
                section: section_from_db(&normalize_section(&s.section_id)),
                order: s.order,
                duration_seconds: s.duration_seconds,
                question_count: s.question_count,
            })
            .collect();
        let breaks: Vec<FullLengthBreak> = if bundle.breaks.is_empty() {
            synthesize_breaks(bundle.sections.len() as u32)
        } else {
            bundle
                .breaks
                .iter()
                .map(|b| FullLengthBreak {
                    after_section: b.after_section,
                    duration_seconds: b.duration_seconds,
                    optional: b.optional,
                    label: if b.label.is_empty() {
                        "Break".to_string()
                    } else {
                        b.label.clone()
                    },
                })
                .collect()
        };
        let total_testing_seconds = if bundle.total_testing_seconds > 0 {
            bundle.total_testing_seconds
        } else {
            sections.iter().map(|s| s.duration_seconds).sum()
        };
        let total_questions = if bundle.total_questions > 0 {
            bundle.total_questions
        } else {
            sections.iter().map(|s| s.question_count).sum()
        };
        let total_break_seconds = breaks.iter().map(|b| b.duration_seconds).sum();
        let test = FullLengthTest {
            test_id: bundle.test_id.clone(),
            title: bundle.title.clone(),
            source: bundle.source.clone(),
            format: bundle.format.clone(),
            disclaimer: bundle.disclaimer.clone(),
            total_questions,
            total_testing_seconds,
            sections,
            breaks,
            total_break_seconds,
        };
        self.storage.upsert_full_length_test(&test)?;

        for section in &bundle.sections {
            let section_db = normalize_section(&section.section_id);
            for p in &section.passages {
                let passage = Passage {
                    passage_id: p.passage_id.clone(),
                    section: section_from_db(&section_db),
                    title: p.title.clone(),
                    passage: p.passage.clone(),
                    discipline: p.discipline.clone(),
                    word_count: p.word_count,
                    topic_tags: p.topic_tags.clone(),
                    difficulty: difficulty_from_db(&normalize_difficulty(&p.difficulty)),
                    source_name: None,
                    source_license: None,
                    test_id: Some(bundle.test_id.clone()),
                };
                self.storage.upsert_practice_passage(&passage)?;
                passages_imported += 1;
            }
            for raw in &section.questions {
                validate_question_source(raw)?;
                let q = build_question(
                    raw,
                    &section_db,
                    raw.passage_id.clone(),
                    Some(bundle.test_id.clone()),
                );
                self.storage.upsert_practice_question(&q)?;
                questions_imported += 1;
            }
        }

        Ok(LoadBundleResponse {
            questions_imported,
            passages_imported,
            tests_imported: 1,
            warnings: vec![],
        })
    }
}
