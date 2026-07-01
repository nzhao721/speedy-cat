# Copyright: Ankitects Pty Ltd and contributors
# License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

"""Regression tests for the SpeedyCAT deck-tree theme reorg + startup repair.

The Browse "Topic" column shows a card's *top-level* deck. SpeedyCAT wants those
top-level decks to be the thematic topics (Biochemistry, Physics and Math, ...),
so the importer flattens the ``SpeedyCAT MCAT::<theme>`` tree it produces into
bare top-level ``<theme>`` decks and deletes the parent.

The bug these tests guard: a collection imported by an *older* build keeps its
cards nested under the ``SpeedyCAT MCAT`` parent, so every card's top-level deck
-- and therefore its Browse Topic -- is ``SpeedyCAT MCAT``. The import is gated
behind a one-shot config marker, so it never re-ran to repair such collections.

``reorganize_into_themes`` migrates that layout up to the top level (tested here
directly, since it is what the repair runs), and ``_auto_import_on_first_run``
now invokes ``_repair_themes_on_open`` on every open so already-imported
collections get fixed.
"""

from __future__ import annotations

import os
import tempfile
from unittest import mock

from anki.collection import Collection
from aqt.speedycat_themes import PARENT_DECK, reorganize_into_themes


def _new_col() -> Collection:
    fd, path = tempfile.mkstemp(suffix=".anki2")
    os.close(fd)
    os.unlink(path)
    return Collection(path)


def _add_card(col: Collection, deck_name: str, front: str) -> int:
    """Add a Basic note and move its card into ``deck_name``; return the cid."""
    model = col.models.by_name("Basic") or col.models.current()
    note = col.new_note(model)
    note["Front"] = front
    note["Back"] = "a"
    col.add_note(note, col.decks.id("Default"))
    cid = note.card_ids()[0]
    col.set_deck([cid], col.decks.id(deck_name, create=True))
    return cid


def _top_level_counts(col: Collection) -> dict[str, int]:
    """Simulate the Browse Topic column: count cards by their top-level deck."""
    counts: dict[str, int] = {}
    for cid in col.find_cards(""):
        deck = col.decks.name(col.get_card(cid).current_deck_id())
        topic = deck.split("::", 1)[0]
        counts[topic] = counts.get(topic, 0) + 1
    return counts


def test_stale_nested_tree_reads_as_parent_topic_then_is_repaired() -> None:
    """The reported bug + its fix, end to end on a real collection."""
    col = _new_col()
    try:
        # Simulate an OLD import: thematic decks nested under the parent.
        _add_card(col, f"{PARENT_DECK}::Biochemistry", "q1")
        _add_card(col, f"{PARENT_DECK}::Biochemistry", "q2")
        _add_card(col, f"{PARENT_DECK}::Physics and Math", "q3")
        _add_card(col, f"{PARENT_DECK}::Behavioral Sciences", "q4")

        # Bug: every card's Topic (top-level deck) is the parent.
        before = _top_level_counts(col)
        assert before == {PARENT_DECK: 4}

        res = reorganize_into_themes(col)

        # Lossless: same number of cards, all re-homed.
        assert res.moved == 4
        assert res.removed == [PARENT_DECK]
        # Parent (and any nested theme decks) are gone.
        names = [d.name for d in col.decks.all_names_and_ids()]
        assert not any(
            n == PARENT_DECK or n.startswith(f"{PARENT_DECK}::") for n in names
        )
        # Fixed: Topic now reflects the real per-card theme.
        after = _top_level_counts(col)
        assert after == {
            "Biochemistry": 2,
            "Physics and Math": 1,
            "Behavioral Sciences": 1,
        }
    finally:
        col.close()


def test_reorg_is_idempotent_and_lossless_on_rerun() -> None:
    col = _new_col()
    try:
        _add_card(col, f"{PARENT_DECK}::Biology", "q1")
        _add_card(col, f"{PARENT_DECK}::Biology", "q2")
        total = len(col.find_cards(""))

        first = reorganize_into_themes(col)
        assert first.moved == 2
        # Second run is a pure no-op (parent already gone).
        second = reorganize_into_themes(col)
        assert second.moved == 0
        assert second.removed == []
        # No cards lost across repairs.
        assert len(col.find_cards("")) == total
        assert _top_level_counts(col) == {"Biology": 2}
    finally:
        col.close()


def test_reorg_noop_when_no_parent_deck() -> None:
    """A healthy (already-flat) collection is left untouched."""
    col = _new_col()
    try:
        _add_card(col, "Biochemistry", "q1")
        res = reorganize_into_themes(col)
        assert res == type(res)(0, [], [])
        assert _top_level_counts(col) == {"Biochemistry": 1}
    finally:
        col.close()


def test_reorg_maps_source_layout_and_keeps_stray_cards() -> None:
    """Source containers collapse to themes; unmapped/parent cards are kept."""
    col = _new_col()
    try:
        # .apkg source-deck layout (MileDown) -> theme, source label stripped.
        mile = _add_card(
            col, f"{PARENT_DECK}::MileDown's MCAT Decks::Biochemistry", "q1"
        )
        # A card sitting directly on the parent must not be dropped.
        stray = _add_card(col, PARENT_DECK, "q2")

        reorganize_into_themes(col)

        assert col.decks.name(col.get_card(mile).current_deck_id()) == "Biochemistry"
        # Stray card is preserved under the documented fallback theme.
        assert col.get_card(stray).current_deck_id() != 0
        names = [d.name for d in col.decks.all_names_and_ids()]
        assert not any(n.startswith(PARENT_DECK) for n in names)
    finally:
        col.close()


# --- wiring: the repair actually runs on collection open -------------------


def _fake_mw(marker: bool, parent_present: bool = True) -> mock.Mock:
    mw = mock.Mock()
    mw.col.get_config.return_value = marker
    mw.col.decks.id_for_name.return_value = 123 if parent_present else None
    return mw


def test_auto_import_repairs_when_already_imported() -> None:
    """Regression: an already-imported collection is repaired on open."""
    import aqt.speedycat as sc

    mw = _fake_mw(marker=True)
    with (
        mock.patch.object(sc.aqt, "mw", mw),
        mock.patch.object(sc, "_repair_themes_on_open") as repair,
        mock.patch.object(sc, "import_deck") as import_deck,
    ):
        sc._auto_import_on_first_run()

    repair.assert_called_once_with(mw)
    import_deck.assert_not_called()


def test_auto_import_imports_on_first_run() -> None:
    import aqt.speedycat as sc

    mw = _fake_mw(marker=False)
    with (
        mock.patch.object(sc.aqt, "mw", mw),
        mock.patch.object(sc, "_repair_themes_on_open") as repair,
        mock.patch.object(sc, "import_deck") as import_deck,
    ):
        sc._auto_import_on_first_run()

    import_deck.assert_called_once_with(mw)
    repair.assert_not_called()


def test_repair_skips_background_op_when_tree_already_flat() -> None:
    import aqt.speedycat as sc

    mw = _fake_mw(marker=True, parent_present=False)
    with mock.patch.object(sc, "QueryOp") as query_op:
        sc._repair_themes_on_open(mw)
    query_op.assert_not_called()


def test_repair_launches_background_op_when_parent_present() -> None:
    import aqt.speedycat as sc

    mw = _fake_mw(marker=True, parent_present=True)
    with mock.patch.object(sc, "QueryOp") as query_op:
        sc._repair_themes_on_open(mw)
    query_op.assert_called_once()
