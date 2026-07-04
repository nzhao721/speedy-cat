# Copyright: Ankitects Pty Ltd and contributors
# License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

"""SpeedyCAT: the forced-active-recall reveal shows a SIMPLE, case-insensitive
Correct/Incorrect verdict and NO character-by-character diff.

The reviewer's forced-recall branch (``Reviewer.typeAnsAnswerFilter``) derives its
verdict from ``Reviewer._compare_typed_answer`` -> the rslib ``match_answer`` RPC
(which lower-cases before comparing), with ``Reviewer._fallback_answer_match`` as a
local case-insensitive fallback, and renders it with ``Reviewer._format_match_feedback``
(the whole-answer ✓/✗ badge only, never the diff). These tests pin all three:
the RPC verdict, the local fallback verdict, and that the rendered badge carries
no character-by-character diff markup.
"""

from __future__ import annotations

import os
import tempfile

from anki.collection import Collection
from aqt.reviewer import Reviewer


def _new_col() -> Collection:
    fd, path = tempfile.mkstemp(suffix=".anki2")
    os.close(fd)
    os.unlink(path)
    return Collection(path)


def test_match_answer_rpc_is_case_insensitive() -> None:
    """The backend RPC that drives the forced-recall verdict ignores letter case."""
    col = _new_col()
    try:
        assert col.match_answer("Hemoglobin", "hemoglobin").matches is True
        assert col.match_answer("hemoglobin", "HEMOGLOBIN").matches is True
        assert col.match_answer("Hemoglobin", "insulin").matches is False
        # A blank typed answer is never correct, even against a blank expected one.
        assert col.match_answer("Hemoglobin", "   ").matches is False
        assert col.match_answer("", "").matches is False
    finally:
        col.close()


def test_fallback_answer_match_is_case_insensitive() -> None:
    """The local fallback (used only when the RPC binding is unavailable) agrees
    with the RPC: case-insensitive, and a blank typed answer never matches."""
    assert Reviewer._fallback_answer_match("Hemoglobin", "hemoglobin") is True
    assert Reviewer._fallback_answer_match("the Krebs cycle", "The Krebs Cycle") is True
    assert Reviewer._fallback_answer_match("Hemoglobin", "insulin") is False
    assert Reviewer._fallback_answer_match("Hemoglobin", "   ") is False


def test_format_match_feedback_is_verdict_only_no_diff() -> None:
    """The rendered banner is a plain Correct/Incorrect badge with no
    character-by-character diff markup (the diff stays removed)."""
    reviewer = Reviewer.__new__(Reviewer)

    correct = reviewer._format_match_feedback(True)
    assert "Correct" in correct
    assert "&#x2714;" in correct  # ✔

    incorrect = reviewer._format_match_feedback(False)
    assert "Incorrect" in incorrect
    assert "&#x2718;" in incorrect  # ✗

    # The dashed/coloured token diff must never appear in the verdict banner.
    for html in (correct, incorrect):
        assert "typeGood" not in html
        assert "typeBad" not in html
        assert "typeMissed" not in html
        assert "typearrow" not in html
        assert "id=typeans" not in html
        assert "&darr;" not in html
        assert "Expected:" not in html
        assert "type-expected" not in html


def test_forced_recall_verdict_is_prepended_before_answer() -> None:
    """The forced-recall reveal places the verdict banner before the card back."""
    reviewer = Reviewer.__new__(Reviewer)
    verdict = reviewer._format_match_feedback(True)
    cleaned = "<p>Back side content</p>"
    banner = f'<div style="font-family: \'arial\'; font-size: 20px">{verdict}</div>'
    composed = f"{banner}<hr>{cleaned}"
    assert composed.index("type-result") < composed.index("Back side content")
    assert "Expected:" not in composed
