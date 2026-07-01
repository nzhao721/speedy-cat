// Copyright: Ankitects Pty Ltd and contributors
// License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

/// The minimum schema version we can open.
pub(super) const SCHEMA_MIN_VERSION: u8 = 11;
/// The version new files are initially created with.
pub(super) const SCHEMA_STARTING_VERSION: u8 = 11;
/// The maximum schema version we can open.
/// SpeedyCAT: bumped to 19 to add the practice-question / full-length-test
/// tables (see schema19_upgrade.sql and rslib/src/storage/practice/).
pub(super) const SCHEMA_MAX_VERSION: u8 = 19;

use super::SchemaVersion;
use super::SqliteStorage;
use crate::error::Result;

impl SqliteStorage {
    pub(super) fn upgrade_to_latest_schema(&self, ver: u8, server: bool) -> Result<()> {
        if ver < 14 {
            self.db
                .execute_batch(include_str!("schema14_upgrade.sql"))?;
            self.upgrade_deck_conf_to_schema14()?;
            self.upgrade_tags_to_schema14()?;
            self.upgrade_config_to_schema14()?;
        }
        if ver < 15 {
            self.db
                .execute_batch(include_str!("schema15_upgrade.sql"))?;
            self.upgrade_notetypes_to_schema15()?;
            self.upgrade_decks_to_schema15(server)?;
            self.upgrade_deck_conf_to_schema15()?;
        }
        if ver < 16 {
            self.upgrade_deck_conf_to_schema16(server)?;
            self.db.execute_batch("update col set ver = 16")?;
        }
        if ver < 17 {
            self.upgrade_tags_to_schema17()?;
            self.db.execute_batch("update col set ver = 17")?;
        }
        if ver < 18 {
            self.db
                .execute_batch(include_str!("schema18_upgrade.sql"))?;
        }
        if ver < 19 {
            // SpeedyCAT: create the practice-question / full-length-test tables.
            self.db
                .execute_batch(include_str!("schema19_upgrade.sql"))?;
        }

        // in some future schema upgrade, we may want to change
        // _collapsed to _expanded in DeckCommon and invert existing values, so
        // that we can avoid serializing the values in the default case, and use
        // DeckCommon::default() in new_normal() and new_filtered()

        Ok(())
    }

    pub(super) fn downgrade_to(&self, ver: SchemaVersion) -> Result<()> {
        match ver {
            SchemaVersion::V11 => self.downgrade_to_schema_11(),
            SchemaVersion::V18 => Ok(()),
        }
    }

    fn downgrade_to_schema_11(&self) -> Result<()> {
        self.begin_trx()?;

        // SpeedyCAT: drop the additive, local-only practice tables first.
        self.db
            .execute_batch(include_str!("schema19_downgrade.sql"))?;
        self.db
            .execute_batch(include_str!("schema18_downgrade.sql"))?;
        self.downgrade_deck_conf_from_schema16()?;
        self.downgrade_decks_from_schema15()?;
        self.downgrade_notetypes_from_schema15()?;
        self.downgrade_config_from_schema14()?;
        self.downgrade_tags_from_schema14()?;
        self.db
            .execute_batch(include_str!("schema11_downgrade.sql"))?;

        self.commit_trx()?;

        Ok(())
    }

    /// SpeedyCAT: reduce the collection to the stock schema 18 by dropping the
    /// additive, local-only practice tables (schema 19) and stamping `col.ver`
    /// back to 18.
    ///
    /// Upstream Anki (and therefore AnkiWeb) only understands schema <= 18, so a
    /// verbatim upload/open of our schema-19 collection is rejected as corrupt.
    /// The full-sync upload path runs this on a throwaway COPY of the collection
    /// (see `sync::collection::upload`), leaving the live collection at schema
    /// 19 with its practice questions/attempts intact. Practice data is
    /// local-only and intentionally not synced.
    ///
    /// This deliberately does not route through `downgrade_to`, so colpkg/apkg
    /// export behaviour (which downgrades to V18 as well) is left unchanged.
    pub(crate) fn strip_practice_tables_for_upload(&self) -> Result<()> {
        self.begin_trx()?;
        self.db
            .execute_batch(include_str!("schema19_downgrade.sql"))?;
        self.commit_trx()?;

        Ok(())
    }
}

#[cfg(test)]
mod test {
    use anki_io::new_tempfile;

    use super::*;
    use crate::collection::CollectionBuilder;
    use crate::prelude::*;

    #[test]
    #[allow(clippy::assertions_on_constants)]
    fn assert_19_is_latest_schema_version() {
        // SpeedyCAT: schema 19 adds additive, local-only practice tables on top
        // of the modern (V18) format. Downgrade-to-V11 drops them
        // (schema19_downgrade.sql). The V18 downgrade target still keeps them,
        // so the full-sync upload strips them from a throwaway copy via
        // SqliteStorage::strip_practice_tables_for_upload (see
        // sync::collection::upload) to stay compatible with stock AnkiWeb.
        assert_eq!(
            19, SCHEMA_MAX_VERSION,
            "must implement SqliteStorage::downgrade_to(SchemaVersion::V11) drop for new tables"
        );
    }

    #[test]
    fn valid_ease_factor_survives_upgrade_roundtrip() -> Result<()> {
        let tempfile = new_tempfile()?;
        let mut col = CollectionBuilder::default()
            .set_collection_path(tempfile.path())
            .build()?;
        let nt = col.get_notetype_by_name("Basic")?.unwrap();
        let mut note = nt.new_note();
        col.add_note(&mut note, DeckId(1))?;
        col.storage
            .db
            .execute("update cards set factor = 1400", [])?;
        col.close(Some(SchemaVersion::V11))?;
        let col = CollectionBuilder::default()
            .set_collection_path(tempfile.path())
            .build()?;
        let card = &col.storage.get_all_cards()[0];
        assert_eq!(card.ease_factor, 1400);
        Ok(())
    }

    /// The full-sync upload uploads a copy that has been reduced to the stock
    /// schema 18 (openable by AnkiWeb), while the live collection keeps its
    /// schema-19 practice data. See `sync::collection::upload`.
    #[test]
    fn upload_copy_is_stock_v18_and_live_practice_is_preserved() -> Result<()> {
        use rusqlite::Connection;

        // Build a collection through the normal pipeline: it lands at the latest
        // schema (19) with the practice tables, plus a practice attempt standing
        // in for real user history.
        let live = new_tempfile()?;
        {
            let col = CollectionBuilder::default()
                .set_collection_path(live.path())
                .build()?;
            assert_eq!(col.storage.db_scalar::<u8>("select ver from col")?, 19);
            col.storage.db.execute_batch(
                "insert into practice_attempts \
                 (id, question_id, selected_answer, correct, time_on_question_seconds, \
                  section, topic, answered_at) \
                 values ('a1', 'q1', 'A', 1, 12, 'CP', 'thermo', 1000)",
            )?;
            col.close(None)?;
        }

        // Reproduce what the full-sync upload does: copy the live file, then
        // reduce the copy to the stock schema 18.
        let upload = new_tempfile()?;
        std::fs::copy(live.path(), upload.path())?;
        {
            let stock_copy = CollectionBuilder::default()
                .set_collection_path(upload.path())
                .build()?;
            stock_copy.storage.strip_practice_tables_for_upload()?;
            stock_copy.close(None)?;
        }

        // The uploaded copy must look like a stock collection: schema 18, no
        // practice/full-length tables, passes an integrity check, keeps core
        // tables. Read it raw so no schema upgrade is applied.
        let reader = Connection::open(upload.path())?;
        // Opened raw so no schema upgrade runs; register the `unicase` collation
        // that Anki's schema declares on several columns (notetypes/decks/tags/…)
        // so PRAGMA integrity_check can verify their indexes, just like a real
        // Anki connection does (see storage::sqlite::open_or_create).
        reader.create_collation("unicase", |s1: &str, s2: &str| {
            unicase::UniCase::new(s1).cmp(&unicase::UniCase::new(s2))
        })?;
        let ver: u8 = reader.query_row("select ver from col", [], |r| r.get(0))?;
        assert_eq!(ver, 18, "upload must be stamped at the stock schema version");
        let custom_tables: i64 = reader.query_row(
            "select count(*) from sqlite_master where type = 'table' \
             and (name like 'practice_%' or name like 'full_length_%')",
            [],
            |r| r.get(0),
        )?;
        assert_eq!(custom_tables, 0, "custom tables must be stripped from upload");
        let integrity: String =
            reader.pragma_query_value(None, "integrity_check", |r| r.get(0))?;
        assert_eq!(integrity, "ok", "upload must pass integrity_check");
        let has_cards: bool = reader
            .prepare("select 1 from sqlite_master where type='table' and name='cards'")?
            .exists([])?;
        assert!(has_cards, "core stock tables must survive the downgrade");
        drop(reader);

        // The live collection must be untouched: still schema 19 with the
        // practice attempt intact.
        let col = CollectionBuilder::default()
            .set_collection_path(live.path())
            .build()?;
        assert_eq!(col.storage.db_scalar::<u8>("select ver from col")?, 19);
        assert_eq!(
            col.storage
                .db_scalar::<i64>("select count(*) from practice_attempts")?,
            1,
            "live practice history must be preserved by the upload"
        );
        Ok(())
    }
}
