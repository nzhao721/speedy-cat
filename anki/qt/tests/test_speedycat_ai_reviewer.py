# Copyright: Ankitects Pty Ltd and contributors
# License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

"""SpeedyCAT: reviewer-level wiring for the AI answer checker.

These pin the behaviour that lives on ``Reviewer`` rather than in the pure
``anki.speedycat_ai`` core (which is unit-tested separately): the AI on/off gate
(opt-in AND key required) and the per-card state reset. They construct a bare
``Reviewer`` via ``__new__`` with a tiny stub ``mw`` so no collection/Qt event
loop is needed, matching ``test_forced_recall_verdict.py``.
"""

from __future__ import annotations

import os
import tempfile
from types import SimpleNamespace

import pytest

from anki import speedycat_ai
from anki.collection import Collection
from anki.decks import DeckId
from aqt.reviewer import Reviewer


def _new_col() -> Collection:
    fd, path = tempfile.mkstemp(suffix=".anki2")
    os.close(fd)
    os.unlink(path)
    return Collection(path)


def _cloze_reviewer(col: Collection, text: str) -> Reviewer:
    """A bare Reviewer whose current card is a Cloze note with the given Text."""
    notetype = col.models.by_name("Cloze")
    assert notetype is not None
    note = col.new_note(notetype)
    note["Text"] = text
    col.add_note(note, DeckId(1))
    reviewer = Reviewer.__new__(Reviewer)
    reviewer.mw = SimpleNamespace(col=col)
    reviewer.card = note.cards()[0]
    return reviewer


def _reviewer_with_config(config: dict) -> Reviewer:
    reviewer = Reviewer.__new__(Reviewer)
    reviewer.mw = SimpleNamespace(
        col=SimpleNamespace(
            get_config=lambda key, default=None: config.get(key, default)
        )
    )
    return reviewer


def test_ai_enabled_requires_optin_and_key(monkeypatch: pytest.MonkeyPatch) -> None:
    # Opt-in ON + key present -> AI on.
    reviewer = _reviewer_with_config({speedycat_ai.AI_CONFIG_KEY: True})
    monkeypatch.setattr(speedycat_ai, "key_present", lambda: True)
    assert reviewer._speedycat_ai_enabled() is True

    # Opt-in ON but no key -> AI off (deterministic fallback).
    monkeypatch.setattr(speedycat_ai, "key_present", lambda: False)
    assert reviewer._speedycat_ai_enabled() is False

    # Opt-in OFF (default) -> AI off even if a key is present.
    reviewer_off = _reviewer_with_config({speedycat_ai.AI_CONFIG_KEY: False})
    monkeypatch.setattr(speedycat_ai, "key_present", lambda: True)
    assert reviewer_off._speedycat_ai_enabled() is False


def test_ai_disabled_by_default(monkeypatch: pytest.MonkeyPatch) -> None:
    # No config entry at all -> default OFF (the project requirement).
    reviewer = _reviewer_with_config({})
    monkeypatch.setattr(speedycat_ai, "key_present", lambda: True)
    assert reviewer._speedycat_ai_enabled() is False


def test_reset_card_state_clears_flags_and_timer() -> None:
    reviewer = Reviewer.__new__(Reviewer)
    reviewer._speedycat_force_again = True
    reviewer._speedycat_ai_decision = speedycat_ai.decide_idk()
    reviewer._speedycat_checking = True
    reviewer._speedycat_idk_timer = None  # clear path is a no-op when unset

    reviewer._speedycat_reset_card_state()

    assert reviewer._speedycat_force_again is False
    assert reviewer._speedycat_ai_decision is None
    assert reviewer._speedycat_checking is False
    assert reviewer._speedycat_idk_timer is None


# --- Expected answer derived from the cloze deletion (the core fix) ----------


def test_forced_recall_expected_uses_cloze_deletion_not_rendered_field() -> None:
    col = _new_col()
    try:
        reviewer = _cloze_reviewer(
            col,
            "For vector subtraction, you must change the "
            "{{c1::direction}} of the subtracted vector",
        )
        # The real answer is the cloze deletion — exactly "direction", NOT the
        # rendered field (which would carry CSS / breadcrumb / source footer).
        assert reviewer._forced_recall_cloze_answer() == "direction"
        assert reviewer._forced_recall_expected() == "direction"
    finally:
        col.close()


def test_forced_recall_expected_multi_cloze_ordered() -> None:
    col = _new_col()
    try:
        reviewer = _cloze_reviewer(
            col,
            "Quantum number {{c1::n}} is the principal quantum number and "
            "gives the electron {{c1::energy level}}",
        )
        expected = reviewer._forced_recall_expected()
        assert expected == "n, energy level"
        # The multi-cloze verdict accepts any separator, in order.
        assert speedycat_ai.deterministic_correct("n energy level", expected) is True
        assert speedycat_ai.deterministic_correct("n, energy level", expected) is True
        assert speedycat_ai.deterministic_correct("n; energy level", expected) is True
        assert speedycat_ai.deterministic_correct("energy level n", expected) is False
    finally:
        col.close()


def test_forced_recall_cloze_answer_none_for_basic_notetype() -> None:
    col = _new_col()
    try:
        basic = col.models.by_name("Basic")
        assert basic is not None
        note = col.new_note(basic)
        note["Front"] = "Q"
        note["Back"] = "A"
        col.add_note(note, DeckId(1))
        reviewer = Reviewer.__new__(Reviewer)
        reviewer.mw = SimpleNamespace(col=col)
        reviewer.card = note.cards()[0]
        assert reviewer._forced_recall_cloze_answer() is None
    finally:
        col.close()
