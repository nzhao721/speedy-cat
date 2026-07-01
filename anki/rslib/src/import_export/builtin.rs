// Copyright: Ankitects Pty Ltd and contributors
// License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

//! SpeedyCAT: import a bundled MCAT deck into the collection so a fresh install
//! already ships with study material, without prompting the user.
//!
//! Two input formats are supported, chosen by the file extension of
//! `package_path`:
//!
//! * `.json` - the bundled, redistributable SpeedyCAT flashcard set
//!   (`qt/aqt/data/speedycat/cards.json`): pre-rendered, text-only cards
//!   derived from the free community MileDown and Mr. Pankow MCAT decks. Each
//!   card becomes a note under `SpeedyCAT MCAT::<source>::<topic>` using the
//!   collection's native `ForeignData` importer.
//! * anything else (`.apkg`/`.colpkg`/`.zip`) - an Anki package, imported and
//!   reparented under the shared parent deck (kept for flexibility/tests).
//!
//! In both cases the import is made idempotent via a collection config marker
//! keyed on `deck_key`, so repeated runs (e.g. every profile open) are no-ops.

use std::collections::BTreeSet;
use std::collections::HashSet;

use anki_io::read_file;
use anki_proto::import_export::ImportBuiltinDeckResponse;
use serde::Deserialize;

use crate::import_export::package::ImportAnkiPackageOptions;
use crate::import_export::text::ForeignData;
use crate::import_export::text::ForeignNote;
use crate::import_export::text::ForeignNotetype;
use crate::import_export::text::ForeignTemplate;
use crate::import_export::text::NameOrId;
use crate::prelude::*;

/// Default parent deck used when the caller does not supply one.
const DEFAULT_PARENT_DECK: &str = "SpeedyCAT MCAT";
/// Name of the simple front/back notetype the bundled cards are imported under.
const SPEEDYCAT_NOTETYPE: &str = "SpeedyCAT";

impl Collection {
    /// Import a bundled deck `package_path`, placing new top-level decks under
    /// `parent_deck` (when non-empty) while preserving subdeck structure.
    ///
    /// The import is idempotent: a collection config marker keyed on `deck_key`
    /// short-circuits subsequent calls unless `force` is set. Cards are imported
    /// without scheduling history, matching a fresh study deck.
    pub fn add_builtin_deck(
        &mut self,
        package_path: &str,
        deck_key: &str,
        parent_deck: &str,
        force: bool,
    ) -> Result<ImportBuiltinDeckResponse> {
        let marker = builtin_deck_marker(deck_key);
        if !force
            && self
                .get_config_optional::<bool, _>(marker.as_str())
                .unwrap_or(false)
        {
            return Ok(ImportBuiltinDeckResponse {
                imported: false,
                note_count: 0,
                deck_names: Vec::new(),
            });
        }

        let parent_deck = parent_deck.trim();
        let response = if is_json_path(package_path) {
            self.import_builtin_json_deck(package_path, parent_deck)?
        } else {
            self.import_builtin_apkg_deck(package_path, parent_deck)?
        };

        self.set_config_json(marker.as_str(), &true, false)?;
        Ok(response)
    }

    /// Import the bundled, text-only SpeedyCAT flashcard set from a JSON file
    /// (see module docs) using the native `ForeignData` importer. Each card is
    /// stored as a note whose `Front`/`Back` fields hold the pre-rendered
    /// question/answer HTML, filed under `parent::<source>::<topic>`.
    fn import_builtin_json_deck(
        &mut self,
        path: &str,
        parent_deck: &str,
    ) -> Result<ImportBuiltinDeckResponse> {
        let parent = if parent_deck.is_empty() {
            DEFAULT_PARENT_DECK
        } else {
            parent_deck
        };

        let slice = read_file(path)?;
        let file: SpeedycatDeckFile = serde_json::from_slice(&slice)?;

        // A minimal front/back notetype: the cards already embed their own
        // styling in the field HTML, so the templates just echo the fields.
        let mut data = ForeignData {
            default_notetype: NameOrId::Name(SPEEDYCAT_NOTETYPE.to_string()),
            default_deck: NameOrId::Name(parent.to_string()),
            notetypes: vec![ForeignNotetype {
                name: SPEEDYCAT_NOTETYPE.to_string(),
                fields: vec!["Front".to_string(), "Back".to_string()],
                templates: vec![ForeignTemplate {
                    name: "Card 1".to_string(),
                    qfmt: "{{Front}}".to_string(),
                    afmt: "{{Back}}".to_string(),
                }],
                is_cloze: false,
            }],
            ..Default::default()
        };

        let mut deck_names: BTreeSet<String> = BTreeSet::new();
        let mut notes = Vec::with_capacity(file.cards.len());
        for card in file.cards {
            let deck_name = speedycat_deck_name(parent, &card.source, &card.topic);
            deck_names.insert(deck_name.clone());
            notes.push(ForeignNote {
                // Stable guid so a forced re-import updates in place instead of
                // duplicating (matched by guid in the importer).
                guid: format!("speedycat-{}", card.card_id),
                fields: vec![Some(card.question_html), Some(card.answer_html)],
                tags: Some(speedycat_note_tags(&card.tags)),
                notetype: NameOrId::Name(SPEEDYCAT_NOTETYPE.to_string()),
                deck: NameOrId::Name(deck_name),
                cards: Vec::new(),
            });
        }
        let note_count = notes.len() as u32;
        data.notes = notes;

        let progress = self.new_progress_handler();
        data.import(self, progress)?;

        Ok(ImportBuiltinDeckResponse {
            imported: true,
            note_count,
            deck_names: deck_names.into_iter().collect(),
        })
    }

    /// Import an Anki `.apkg`/`.colpkg` package, moving any newly created
    /// top-level decks under `parent_deck` (when non-empty); subdecks keep
    /// their original structure underneath.
    fn import_builtin_apkg_deck(
        &mut self,
        package_path: &str,
        parent_deck: &str,
    ) -> Result<ImportBuiltinDeckResponse> {
        // Record which decks already exist so we can identify the ones the
        // package creates.
        let before: HashSet<String> = self
            .get_all_deck_names(false)?
            .into_iter()
            .map(|(_, name)| name)
            .collect();

        let options = ImportAnkiPackageOptions {
            with_scheduling: false,
            ..Default::default()
        };
        let note_count = self.import_apkg(package_path, options)?.output.found_notes;

        let mut deck_names = Vec::new();
        for (did, name) in self.get_all_deck_names(false)? {
            // Only act on freshly created top-level decks; renaming a parent
            // carries its children along (see Collection::update_deck_inner).
            if name == "Default" || name.contains("::") || before.contains(&name) {
                continue;
            }
            if parent_deck.is_empty() {
                deck_names.push(name);
            } else {
                let nested = format!("{parent_deck}::{name}");
                self.rename_deck(did, &nested)?;
                deck_names.push(nested);
            }
        }
        deck_names.sort();

        Ok(ImportBuiltinDeckResponse {
            imported: true,
            note_count,
            deck_names,
        })
    }
}

/// Bundled SpeedyCAT flashcard set: a `meta` block (ignored here) plus a flat
/// list of pre-rendered cards. Unknown fields are ignored by serde.
#[derive(Debug, Default, Deserialize)]
struct SpeedycatDeckFile {
    #[serde(default)]
    cards: Vec<SpeedycatCard>,
}

#[derive(Debug, Default, Deserialize)]
struct SpeedycatCard {
    #[serde(default)]
    card_id: i64,
    #[serde(default)]
    source: String,
    #[serde(default)]
    topic: String,
    #[serde(default)]
    question_html: String,
    #[serde(default)]
    answer_html: String,
    #[serde(default)]
    tags: Vec<String>,
}

fn is_json_path(path: &str) -> bool {
    std::path::Path::new(path)
        .extension()
        .map(|ext| ext.eq_ignore_ascii_case("json"))
        .unwrap_or(false)
}

/// Build the deck name `parent::source::topic` for a card, keeping the source
/// label as its own middle level. The Python `reorganize_into_themes` step
/// (`qt/aqt/speedycat_themes.py`) strips that source level afterwards so the
/// final tree is purely thematic; emitting it here keeps that mapping
/// unambiguous. Empty components are skipped and any embedded `::` is
/// neutralised so each value stays a single level.
fn speedycat_deck_name(parent: &str, source: &str, topic: &str) -> String {
    let mut name = String::new();
    for component in [parent, source, topic] {
        let component = sanitize_deck_component(component);
        if component.is_empty() {
            continue;
        }
        if name.is_empty() {
            name = component;
        } else {
            name = format!("{name}::{component}");
        }
    }
    if name.is_empty() {
        DEFAULT_PARENT_DECK.to_string()
    } else {
        name
    }
}

fn sanitize_deck_component(component: &str) -> String {
    component.replace("::", ":").trim().to_string()
}

/// Tags for a bundled note: a source-neutral `SpeedyCAT` marker plus the card's
/// own (topic-only) tags, so users can filter the bundled set without exposing
/// where a card came from.
fn speedycat_note_tags(card_tags: &[String]) -> Vec<String> {
    let mut tags = vec!["SpeedyCAT".to_string()];
    for tag in card_tags {
        let tag = tag.trim();
        if !tag.is_empty() {
            tags.push(tag.to_string());
        }
    }
    tags
}

/// Config key recording that a built-in deck with the given key was imported.
fn builtin_deck_marker(deck_key: &str) -> String {
    format!("speedycat_builtin_deck_{deck_key}")
}

#[cfg(test)]
mod tests {
    use super::SPEEDYCAT_NOTETYPE;
    use crate::import_export::package::ExportAnkiPackageOptions;
    use crate::search::SearchNode;
    use crate::tests::open_fs_test_collection;
    use crate::tests::DeckAdder;
    use crate::tests::NoteAdder;

    const PARENT: &str = "SpeedyCAT MCAT";

    const SAMPLE_JSON: &str = r#"{
        "meta": {"title": "sample"},
        "cards": [
            {
                "card_id": 1,
                "note_id": 1,
                "ord": 0,
                "source": "MileDown MCAT",
                "topic": "General Chemistry",
                "notetype": "Cloze-b279e",
                "question_html": "<b>Q1</b> is [...]",
                "answer_html": "<b>Q1</b> is the answer",
                "tags": ["General_Chemistry::Atomic_Structure"]
            },
            {
                "card_id": 2,
                "note_id": 2,
                "ord": 0,
                "source": "Mr. Pankow P/S",
                "topic": "Sensation & Perception",
                "notetype": "AACloze",
                "question_html": "Q2 is [...]",
                "answer_html": "Q2 is the answer",
                "tags": []
            }
        ]
    }"#;

    /// Export a tiny package containing a top-level deck with one subdeck and a
    /// single note, mimicking the structure of the real community decks.
    fn build_sample_apkg(path: &std::path::Path) {
        let (mut src, _dir) = open_fs_test_collection("builtin_src");
        let sub = DeckAdder::new("Imported Deck::Sub").add(&mut src);
        NoteAdder::basic(&mut src).deck(sub.id).add(&mut src);
        src.export_apkg(
            path,
            ExportAnkiPackageOptions {
                with_scheduling: false,
                with_deck_configs: false,
                with_media: false,
                legacy: false,
            },
            SearchNode::from_deck_name("Imported Deck"),
            None,
        )
        .unwrap();
    }

    #[test]
    fn imports_reparents_and_is_idempotent() {
        let dir = tempfile::tempdir().unwrap();
        let apkg = dir.path().join("sample.apkg");
        build_sample_apkg(&apkg);
        let apkg = apkg.to_str().unwrap();

        let (mut col, _dir) = open_fs_test_collection("builtin_target");

        // first import: nested under the parent, note counted
        let first = col.add_builtin_deck(apkg, "sample", PARENT, false).unwrap();
        assert!(first.imported);
        assert_eq!(first.note_count, 1);
        assert_eq!(first.deck_names, vec![format!("{PARENT}::Imported Deck")]);

        // the subdeck was carried along under the new parent
        let names: Vec<String> = col
            .get_all_deck_names(false)
            .unwrap()
            .into_iter()
            .map(|(_, name)| name)
            .collect();
        assert!(names.contains(&format!("{PARENT}::Imported Deck::Sub")));

        // second call without force is a no-op
        let second = col.add_builtin_deck(apkg, "sample", PARENT, false).unwrap();
        assert!(!second.imported);
    }

    #[test]
    fn force_bypasses_the_idempotency_marker() {
        let dir = tempfile::tempdir().unwrap();
        let apkg = dir.path().join("sample.apkg");
        build_sample_apkg(&apkg);
        let apkg = apkg.to_str().unwrap();

        let (mut col, _dir) = open_fs_test_collection("builtin_force");
        // simulate a previous import
        col.set_config_json("speedycat_builtin_deck_sample", &true, false)
            .unwrap();

        // marker blocks a normal import
        assert!(
            !col.add_builtin_deck(apkg, "sample", PARENT, false)
                .unwrap()
                .imported
        );
        // force imports anyway
        assert!(
            col.add_builtin_deck(apkg, "sample", PARENT, true)
                .unwrap()
                .imported
        );
    }

    #[test]
    fn imports_bundled_json_deck_and_is_idempotent() {
        let dir = tempfile::tempdir().unwrap();
        let json_path = dir.path().join("cards.json");
        std::fs::write(&json_path, SAMPLE_JSON).unwrap();
        let json = json_path.to_str().unwrap();

        let (mut col, _dir) = open_fs_test_collection("builtin_json");

        let res = col
            .add_builtin_deck(json, "speedycat", PARENT, false)
            .unwrap();
        assert!(res.imported);
        assert_eq!(res.note_count, 2);

        // cards are filed under parent::<source>::<topic>; the Python
        // reorganize_into_themes step later strips the source level. Here we
        // assert the backend produced that documented interface structure.
        let names: Vec<String> = col
            .get_all_deck_names(false)
            .unwrap()
            .into_iter()
            .map(|(_, name)| name)
            .collect();
        assert!(names.contains(&format!("{PARENT}::MileDown MCAT::General Chemistry")));
        assert!(names.contains(&format!("{PARENT}::Mr. Pankow P/S::Sensation & Perception")));

        // the front/back notetype was created
        assert!(col.get_notetype_by_name(SPEEDYCAT_NOTETYPE).unwrap().is_some());

        // both notes were added
        assert_eq!(col.storage.get_all_notes().len(), 2);

        // a second call without force is a no-op (idempotency marker)
        let again = col
            .add_builtin_deck(json, "speedycat", PARENT, false)
            .unwrap();
        assert!(!again.imported);
        assert_eq!(col.storage.get_all_notes().len(), 2);

        // forcing re-imports but updates in place (stable guids) - no dupes
        let forced = col
            .add_builtin_deck(json, "speedycat", PARENT, true)
            .unwrap();
        assert!(forced.imported);
        assert_eq!(col.storage.get_all_notes().len(), 2);
    }
}
