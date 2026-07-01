# Copyright: Ankitects Pty Ltd and contributors
# License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

"""SpeedyCAT built-in MCAT deck.

A fresh SpeedyCAT install ships with a ready-to-study MCAT flashcard set and
imports it automatically on first run - no prompt, no download. The set is the
bundled, text-only ``cards.json`` (under the app data folder), derived from two
free, redistributable community MCAT Anki decks:

* MileDown MCAT (~2,888 cards) - science sections (CPBS/BBLS/PSBB) + equations.
  Author: u/MileDown.
* Mr. Pankow P/S (~2,254 cards) - Psychology/Sociology. Author: Mr. Pankow.

Cards are imported under a single "SpeedyCAT MCAT" parent deck (split by
source/topic) via the rslib ``import_builtin_deck`` backend method, which makes
the operation idempotent (a per-collection config marker, so it never
re-imports or duplicates on later startups). CARS is intentionally not covered
by flashcards.

Attribution for the source decks is preserved here and in
``docs/speedycat-mcat-decks.md``; SpeedyCAT itself remains AGPL-3.0-or-later and
credits Anki (created by Damien Elmes and the Anki contributors).

Asset resolution order (see ``_bundled_deck_path``):

1. a directory given by the ``SPEEDYCAT_DECK_DIR`` environment variable (lets a
   developer or installer point at an alternative ``cards.json``), then
2. the bundled copy shipped in the app data folder
   (``<data>/speedycat/cards.json``).
"""

from __future__ import annotations

import os

import aqt
import aqt.gui_hooks
import aqt.main
from anki.collection import Collection, ImportBuiltinDeckResponse
from aqt.operations import QueryOp
from aqt.qt import QAction, qconnect
from aqt.speedycat_themes import reorganize_into_themes
from aqt.utils import aqt_data_folder, showWarning, tooltip

PARENT_DECK = "SpeedyCAT MCAT"

# Stable idempotency key (recorded in the collection config as
# ``speedycat_builtin_deck_<key>`` by the backend).
_DECK_KEY = "speedycat"
_CONFIG_MARKER = f"speedycat_builtin_deck_{_DECK_KEY}"
_BUNDLED_FILENAME = "cards.json"
_DECK_DIR_ENV = "SPEEDYCAT_DECK_DIR"

# Source decks bundled into cards.json (for attribution; shown in docs/About).
_ATTRIBUTION = (
    "MileDown MCAT (u/MileDown) and Mr. Pankow P/S (Mr. Pankow) - free, "
    "redistributable community decks."
)


def setup(mw: aqt.main.AnkiQt) -> None:
    """Register the first-run auto-import and a manual re-add action. Call once."""
    action = QAction("Add SpeedyCAT MCAT Deck", mw)
    qconnect(action.triggered, lambda: import_deck(mw, force=True, manual=True))
    mw.form.menuTools.addAction(action)
    aqt.gui_hooks.profile_did_open.append(_auto_import_on_first_run)


def _auto_import_on_first_run() -> None:
    """Silently import the bundled deck the first time a collection is opened."""
    mw = aqt.mw
    if mw is None or mw.col is None:
        return
    # idempotent: the backend marker is set after a successful import and syncs
    # with the collection, so this only ever runs once per collection.
    if mw.col.get_config(_CONFIG_MARKER, False):
        return
    import_deck(mw)


def import_deck(
    mw: aqt.main.AnkiQt, *, force: bool = False, manual: bool = False
) -> None:
    """Import the bundled SpeedyCAT deck in the background.

    ``force`` re-imports even if the marker is set (updates in place, no dupes).
    ``manual`` surfaces warnings/tooltips for the Tools-menu trigger; the
    automatic first-run path stays silent on a no-op.
    """
    if mw.col is None:
        if manual:
            showWarning("Please open a profile first.")
        return

    path = _bundled_deck_path()
    if path is None:
        if manual:
            showWarning(
                "The bundled SpeedyCAT MCAT deck could not be found. Expected "
                f"{_BUNDLED_FILENAME!r} in the app data folder or in a directory "
                f"named by the {_DECK_DIR_ENV} environment variable."
            )
        return

    def op(col: Collection) -> ImportBuiltinDeckResponse:
        res = col.import_builtin_deck(
            package_path=path,
            deck_key=_DECK_KEY,
            parent_deck=PARENT_DECK,
            force=force,
        )
        # Collapse the source/unit subdeck structure into flat thematic topics
        # so the deck tree never reveals where a card came from. Idempotent, so
        # re-running from the Tools menu also repairs an already-imported install.
        reorganize_into_themes(col)
        return res

    def on_success(res: ImportBuiltinDeckResponse) -> None:
        mw.reset()
        if res.imported:
            tooltip(f"SpeedyCAT MCAT deck added ({res.note_count} notes).")
        elif manual:
            tooltip("SpeedyCAT MCAT deck is already installed.")

    QueryOp(parent=mw, op=op, success=on_success).with_progress(
        "Adding SpeedyCAT MCAT deck"
    ).run_in_background()


def _bundled_deck_path() -> str | None:
    """Return the path to the bundled cards.json, or None if it is missing."""
    for directory in _candidate_dirs():
        candidate = os.path.join(directory, _BUNDLED_FILENAME)
        if os.path.isfile(candidate):
            return candidate
    return None


def _candidate_dirs() -> list[str]:
    dirs: list[str] = []
    env = os.environ.get(_DECK_DIR_ENV)
    if env:
        dirs.append(env)
    dirs.append(os.path.join(aqt_data_folder(), "speedycat"))
    return dirs
