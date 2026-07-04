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

        // SpeedyCAT graduated hint ladder: additive columns on the schema-19
        // practice tables. Added idempotently (no schema-version bump) so a
        // collection already sitting at schema 19 — created before the hint
        // ladder existed — gains them on open instead of erroring on the new
        // `hints`/`assisted` columns. A no-op once the columns are present.
        self.add_speedycat_hint_columns_if_missing()?;

        // in some future schema upgrade, we may want to change
        // _collapsed to _expanded in DeckCommon and invert existing values, so
        // that we can avoid serializing the values in the default case, and use
        // DeckCommon::default() in new_normal() and new_filtered()

        Ok(())
    }

    /// SpeedyCAT graduated hint ladder: add its additive columns to the
    /// (local-only) schema-19 practice tables when they are missing, without a
    /// schema-version bump. Safe on a fresh DB (columns already created by
    /// `schema19_upgrade.sql`) and on a pre-hint-ladder schema-19 DB (columns
    /// added here). No-op when the practice tables don't exist yet.
    ///
    /// Called both from `upgrade_to_latest_schema` (the create / version-bump
    /// path) and, for a collection already sitting at `SCHEMA_MAX_VERSION`
    /// before the hint ladder existed, directly from `open_or_create` on every
    /// open (that steady-state path never runs the schema upgrade).
    pub(super) fn add_speedycat_hint_columns_if_missing(&self) -> Result<()> {
        self.add_column_if_missing("practice_questions", "hints", "text")?;
        self.add_column_if_missing(
            "practice_attempts",
            "hint_level_used",
            "integer not null default 0",
        )?;
        self.add_column_if_missing("practice_attempts", "assisted", "integer not null default 0")?;
        self.add_column_if_missing(
            "practice_attempts",
            "main_wrong_first",
            "integer not null default 0",
        )?;
        self.add_column_if_missing("practice_attempts", "first_try_no_hint", "integer")?;
        self.add_speedycat_full_length_columns_if_missing()?;
        Ok(())
    }

    /// SpeedyCAT full-length attempt flags: readiness exclusion + abandoned state.
    pub(super) fn add_speedycat_full_length_columns_if_missing(&self) -> Result<()> {
        self.add_column_if_missing(
            "full_length_attempts",
            "counts_for_readiness",
            "integer not null default 1",
        )?;
        self.add_column_if_missing(
            "full_length_attempts",
            "abandoned",
            "integer not null default 0",
        )?;
        Ok(())
    }

    /// Add `column` (`decl` = its SQL type/constraints) to `table` when the table
    /// exists but lacks the column. `table`/`column`/`decl` are trusted internal
    /// constants (never user input).
    fn add_column_if_missing(&self, table: &str, column: &str, decl: &str) -> Result<()> {
        let table_exists: bool = self
            .db
            .prepare("select 1 from sqlite_master where type = 'table' and name = ?")?
            .exists([table])?;
        if !table_exists {
            return Ok(());
        }
        let has_column: bool = self
            .db
            .prepare(&format!(
                "select count(*) from pragma_table_info('{table}') where name = ?"
            ))?
            .query_row([column], |r| r.get::<_, i64>(0))?
            > 0;
        if !has_column {
            self.db
                .execute_batch(&format!("alter table {table} add column {column} {decl}"))?;
        }
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

    /// SpeedyCAT graduated hint ladder: a collection created at schema 19
    /// *before* the hint ladder existed is already at SCHEMA_MAX_VERSION, so it
    /// never runs the schema upgrade. The additive hint columns must still be
    /// backfilled on open (see `add_speedycat_hint_columns_if_missing` and its
    /// unconditional call in `SqliteStorage::open_or_create`) — otherwise the
    /// results-sync read of `hint_level_used` fails with "no such column".
    #[test]
    fn speedycat_hint_columns_backfilled_on_open_when_already_v19() -> Result<()> {
        let tempfile = new_tempfile()?;
        // A fresh build lands at schema 19 with the hint columns present.
        {
            let col = CollectionBuilder::default()
                .set_collection_path(tempfile.path())
                .build()?;
            assert_eq!(col.storage.db_scalar::<u8>("select ver from col")?, 19);
            // Simulate the pre-hint-ladder schema-19 shape by recreating the two
            // practice tables WITHOUT the additive hint columns, while leaving
            // ver = 19 so the reopen takes the steady-state path (no create, no
            // upgrade). (Recreate rather than DROP COLUMN: SQLite's DROP COLUMN
            // rejects the commented CREATE statement with "incomplete input".)
            col.storage.db.execute_batch(
                "drop table practice_attempts;
                 create table practice_attempts (
                   id text not null primary key,
                   session_id text,
                   full_length_attempt_id text,
                   question_id text not null,
                   selected_answer text not null default '',
                   correct integer not null,
                   time_on_question_seconds integer not null,
                   section text not null,
                   topic text not null,
                   answered_at integer not null
                 ) without rowid;
                 drop table practice_questions;
                 create table practice_questions (
                   id text not null primary key,
                   section text not null,
                   passage_id text,
                   test_id text,
                   stem text not null,
                   choices text not null,
                   correct_answer text not null,
                   explanation text not null,
                   question_type text,
                   topic_tags text not null,
                   difficulty text not null,
                   source_name text not null,
                   source_license text not null,
                   source_url text,
                   answer_provenance text,
                   notes text
                 ) without rowid;",
            )?;
            col.close(None)?;
        }

        // Reopening must re-add the columns even though ver is already 19.
        let col = CollectionBuilder::default()
            .set_collection_path(tempfile.path())
            .build()?;
        assert_eq!(col.storage.db_scalar::<u8>("select ver from col")?, 19);
        for (table, column) in [
            ("practice_attempts", "hint_level_used"),
            ("practice_attempts", "assisted"),
            ("practice_attempts", "main_wrong_first"),
            ("practice_attempts", "first_try_no_hint"),
            ("practice_questions", "hints"),
        ] {
            let present: i64 = col.storage.db.query_row(
                &format!("select count(*) from pragma_table_info('{table}') where name = ?"),
                [column],
                |r| r.get(0),
            )?;
            assert_eq!(present, 1, "{table}.{column} must be backfilled on open");
        }
        // The exact results-sync read that crashed must now succeed.
        col.storage.db.execute_batch(
            "select id, hint_level_used, assisted from practice_attempts \
             where session_id is not null",
        )?;
        Ok(())
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
