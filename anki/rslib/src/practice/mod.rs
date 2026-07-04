// Copyright: Ankitects Pty Ltd and contributors
// License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

//! SpeedyCAT: backend for the Practice Question Bank and Full-Length Practice
//! Tests, plus per-topic time/accuracy tracking.
//!
//! Content is loaded from the JSON bundles under `content/practice-questions/`
//! and `content/full-length-tests/` into local collection tables (schema 19,
//! see `storage/upgrades/schema19_upgrade.sql`). Sessions and attempts are
//! user-generated. The RPC surface is defined in `proto/anki/practice.proto`
//! and implemented in [`service`].

pub(crate) mod ewma;
pub(crate) mod loader;
pub(crate) mod performance;
pub(crate) mod scoring;
pub(crate) mod service;

use anki_proto::practice::AttemptSource;
use anki_proto::practice::Difficulty;
use anki_proto::practice::McatSection;
use serde::Deserialize;
use serde::Serialize;

/// A stored answer choice (serialized as JSON in `practice_questions.choices`).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub(crate) struct StoredChoice {
    pub label: String,
    pub text: String,
}

/// SpeedyCAT graduated hint ladder — a stored hint answer choice.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub(crate) struct StoredHintChoice {
    pub label: String,
    pub text: String,
}

/// SpeedyCAT graduated hint ladder — one stored hint subquestion (serialized as
/// JSON in the `practice_questions.hints` column).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub(crate) struct StoredHint {
    pub level: u32,
    pub prompt: String,
    pub choices: Vec<StoredHintChoice>,
    pub correct_answer: String,
    #[serde(default)]
    pub rationale: String,
}

/// A full-length section as serialized into `full_length_tests.sections`.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub(crate) struct StoredSection {
    /// Canonical DB section string (e.g. "CPBS").
    pub section: String,
    pub order: u32,
    pub duration_seconds: u32,
    pub question_count: u32,
}

/// A scheduled break as serialized into `full_length_tests.breaks`.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub(crate) struct StoredBreak {
    pub after_section: u32,
    pub duration_seconds: u32,
    pub optional: bool,
    pub label: String,
}

/// A per-section result as serialized into `full_length_attempts.section_results`.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub(crate) struct StoredSectionResult {
    pub section: String,
    pub correct: u32,
    pub total: u32,
    pub time_seconds: u32,
    pub scaled_score: Option<u32>,
}

/// Prefixes keep generated ids self-describing in the DB.
pub(crate) fn new_session_id() -> String {
    format!("ps-{}", crate::notes::base91_u64())
}

pub(crate) fn new_full_length_attempt_id() -> String {
    format!("fla-{}", crate::notes::base91_u64())
}

/// Derive a stable u64 RNG seed from a string (FNV-1a). Deterministic and
/// portable, so a session's shuffles are reproducible within the session (same
/// key -> same seed) while differing across sessions (session ids are random).
pub(crate) fn seed_from_str(s: &str) -> u64 {
    let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
    for b in s.as_bytes() {
        hash ^= *b as u64;
        hash = hash.wrapping_mul(0x0000_0100_0000_01b3);
    }
    hash
}

// ---- Section (proto enum <-> canonical DB string) --------------------------

/// Canonical DB representation of a section proto enum value.
pub(crate) fn section_to_db(section: i32) -> &'static str {
    match McatSection::try_from(section).unwrap_or(McatSection::Unspecified) {
        McatSection::Cpbs => "CPBS",
        McatSection::Cars => "CARS",
        McatSection::Bbls => "BBLS",
        McatSection::Psbb => "PSBB",
        McatSection::Unspecified => "",
    }
}

/// Proto enum value (as i32) for a DB section string.
pub(crate) fn section_from_db(s: &str) -> i32 {
    let section = match s {
        "CPBS" => McatSection::Cpbs,
        "CARS" => McatSection::Cars,
        "BBLS" => McatSection::Bbls,
        "PSBB" => McatSection::Psbb,
        _ => McatSection::Unspecified,
    };
    section as i32
}

/// Normalize a section string from a content bundle into the canonical DB form.
pub(crate) fn normalize_section(s: &str) -> String {
    s.trim().to_uppercase()
}

// ---- Difficulty (proto enum <-> canonical DB string) -----------------------

pub(crate) fn difficulty_to_db(difficulty: i32) -> &'static str {
    match Difficulty::try_from(difficulty).unwrap_or(Difficulty::Unspecified) {
        Difficulty::Easy => "easy",
        Difficulty::Medium => "medium",
        Difficulty::Hard => "hard",
        Difficulty::Unspecified => "",
    }
}

pub(crate) fn difficulty_from_db(s: &str) -> i32 {
    let difficulty = match s {
        "easy" => Difficulty::Easy,
        "medium" => Difficulty::Medium,
        "hard" => Difficulty::Hard,
        _ => Difficulty::Unspecified,
    };
    difficulty as i32
}

pub(crate) fn normalize_difficulty(s: &str) -> String {
    s.trim().to_lowercase()
}

// ---- Attempt-source filter -------------------------------------------------

/// SQL predicate (against the `practice_attempts` table) selecting attempts of
/// the requested source. Returns `None` when no restriction applies (ALL).
pub(crate) fn attempt_source_clause(source: i32) -> Option<&'static str> {
    match AttemptSource::try_from(source).unwrap_or(AttemptSource::All) {
        AttemptSource::All => None,
        AttemptSource::PracticeSession => Some("session_id is not null"),
        AttemptSource::FullLength => Some("full_length_attempt_id is not null"),
    }
}
