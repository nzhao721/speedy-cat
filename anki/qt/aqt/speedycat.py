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
import traceback

import aqt
import aqt.gui_hooks
import aqt.main
from anki.collection import Collection, ImportBuiltinDeckResponse
from anki.decks import DEFAULT_DECK_ID, UpdateDeckConfigs, UpdateDeckConfigsMode
from aqt.operations import QueryOp
from aqt.qt import QAction, qconnect
from aqt.speedycat_themes import (
    TAG_BREADCRUMB_SEARCH,
    reorganize_into_themes,
    strip_tag_breadcrumbs,
)
from aqt.utils import aqt_data_folder, showWarning, tooltip

PARENT_DECK = "SpeedyCAT MCAT"

# Stable idempotency key (recorded in the collection config as
# ``speedycat_builtin_deck_<key>`` by the backend).
_DECK_KEY = "speedycat"
_CONFIG_MARKER = f"speedycat_builtin_deck_{_DECK_KEY}"
_BUNDLED_FILENAME = "cards.json"
_DECK_DIR_ENV = "SPEEDYCAT_DECK_DIR"

# One-time marker recording that the FSRS memory-state backfill has run for this
# collection (see :func:`_backfill_fsrs_memory_state`). Stored in the collection
# config so it syncs and never re-runs the heavy recompute on later opens.
_FSRS_BACKFILL_MARKER = "speedycat_fsrs_backfilled"

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
    """First-run import of the bundled deck, plus idempotent repairs on reopen.

    On the very first open (marker unset) the bundled deck is imported, which
    also flattens the tree into top-level themes and strips the tag breadcrumb.
    On every later open (marker set) we re-run those same repairs against an
    already-imported collection (see :func:`_repair_on_open`); both are
    idempotent and lossless, so they are no-ops once the collection is clean.
    """
    mw = aqt.mw
    if mw is None or mw.col is None:
        return
    # SpeedyCAT keeps FSRS always on (the readiness Memory pillar is built on
    # FSRS retrievability and gives up when FSRS is off). Force it here, before
    # the first-run/repair split, so both fresh and already-imported
    # collections are covered on every profile open. Idempotent (no-op once on).
    _force_fsrs_on(mw.col)
    # One-time: cards reviewed while FSRS was off have no memory state (and thus
    # no retrievability), so the Memory pillar would see 0 scored cards. Backfill
    # memory states once from each card's review history. Runs before the
    # first-run/repair split so both fresh and already-imported collections are
    # covered; guarded by a config marker so the heavy recompute runs at most
    # once. Fresh collections have no review history, so this is a no-op.
    _backfill_fsrs_memory_state(mw)
    # idempotent: the backend marker is set after a successful import and syncs
    # with the collection, so the import itself only ever runs once per
    # collection. Already-imported collections still get the repairs below.
    if mw.col.get_config(_CONFIG_MARKER, False):
        _repair_on_open(mw)
        return
    import_deck(mw)


def _repair_on_open(mw: aqt.main.AnkiQt) -> None:
    """Idempotent, no-op-when-clean repairs for an already-imported collection.

    Two independent fixes, both safe to re-run on every profile open and each
    guarded so a healthy collection does no work:

    * deck tree: a collection imported under an older build keeps every card
      nested beneath the ``SpeedyCAT MCAT`` parent, so the Browse Topic column
      (the card's top-level deck) reads ``SpeedyCAT MCAT`` for all of them.
      Re-running the reorg flattens the tree and deletes the parent, restoring
      per-card topics. Guarded on the legacy parent still being present.
    * tag breadcrumb: cards imported from the pre-rendered dataset carry a
      baked-in ``<div class="tags">…</div>`` header (the source templates'
      rendered ``{{Tags}}``) that shows as a leading breadcrumb in the Browse
      Front/Back columns. Stripping it leaves only the real Q/A. Guarded on at
      least one bundled note still containing the wrapper.

    Both run inside a single background op, so the collection is never touched
    by two ops concurrently.
    """
    col = mw.col
    if col is None:
        return
    needs_reorg = col.decks.id_for_name(PARENT_DECK) is not None
    needs_breadcrumb = bool(col.find_notes(TAG_BREADCRUMB_SEARCH))
    if not needs_reorg and not needs_breadcrumb:
        return

    def op(col: Collection) -> int:
        changed = 0
        if needs_reorg:
            changed += reorganize_into_themes(col).moved
        if needs_breadcrumb:
            changed += strip_tag_breadcrumbs(col)
        return changed

    def on_success(changed: int) -> None:
        # only refresh the UI when a repair actually changed something
        if changed:
            mw.reset()

    QueryOp(parent=mw, op=op, success=on_success).with_progress(
        "Updating flashcards"
    ).run_in_background()


def _force_fsrs_on(col: Collection) -> None:
    """Force FSRS scheduling on for this collection, idempotently.

    SpeedyCAT's readiness "Memory" pillar is mean FSRS retrievability and
    deliberately gives up when FSRS is off, so the app keeps FSRS always on: the
    deck-options enable toggle is disabled in the UI, and this guarantees the
    stored collection config agrees, on every profile open, for both fresh and
    pre-existing collections. Every write is guarded, so a collection that is
    already correct does no work.

    FSRS requires the v3 scheduler, so that is ensured first (fresh SpeedyCAT
    collections already ship on v3, making this a no-op in practice). The enable
    flag itself is the ``fsrs`` collection config - ``BoolKey::Fsrs`` in rslib,
    the same key the deck-options screen writes through ``update_deck_configs``.
    We set it through the backend JSON config API (``set_config``), never via
    raw SQL.
    """
    # FSRS needs the v3 scheduler; upgrade defensively only if an older
    # collection is somehow opened without it.
    if not col.v3_scheduler():
        if col.sched_ver() == 1:
            col.upgrade_to_v2_scheduler()
        col.set_v3_scheduler(True)
    # Enable FSRS itself (rslib reads this under the "fsrs" key).
    if not col.get_config("fsrs", False):
        col.set_config("fsrs", True)


def _backfill_fsrs_memory_state(mw: aqt.main.AnkiQt) -> None:
    """Recompute FSRS memory states for every card, once per collection.

    Cards studied while FSRS was off carry no FSRS memory state (stability /
    difficulty), so they have no retrievability and the readiness "Memory"
    pillar sees zero scored cards ("study to unlock") even once FSRS is forced
    on. This performs the exact same whole-collection recompute the deck-options
    "Save" performs when a user enables FSRS - :meth:`Collection.decks
    .update_deck_configs`, which drives rslib's ``update_deck_configs`` ->
    ``update_memory_state`` and backfills memory states from each card's review
    history using each preset's (default) FSRS params.

    Guarded by :data:`_FSRS_BACKFILL_MARKER`: the heavy recompute runs at most
    once per collection. It runs on the serialized background collection thread
    (so it never blocks the UI and never overlaps the import/repair ops), and is
    wrapped so a failure can never crash startup - the marker is only set on
    success, so a failed run is retried on the next open.
    """
    col = mw.col
    if col is None or col.get_config(_FSRS_BACKFILL_MARKER, False):
        return

    def op(col: Collection) -> None:
        try:
            _recompute_all_memory_states(col)
        except Exception:
            # Never let a backfill failure crash profile open. Keep FSRS on and
            # leave the marker unset so the next open retries the recompute.
            traceback.print_exc()
            if not col.get_config("fsrs", False):
                col.set_config("fsrs", True)
            return
        col.set_config(_FSRS_BACKFILL_MARKER, True)

    QueryOp(parent=mw, op=op, success=lambda _: mw.reset()).with_progress(
        "Preparing readiness data"
    ).run_in_background()


def _recompute_all_memory_states(col: Collection) -> None:
    """Replay the deck-options "enable FSRS" save to recompute memory states.

    Mirrors what the deck-options screen sends when FSRS is turned on: it fetches
    every preset via ``get_deck_configs_for_update`` and passes them back
    unchanged through ``update_deck_configs`` with ``fsrs=True``. rslib recomputes
    memory state for a deck when FSRS is *toggled* on (stored ``fsrs`` flag
    differs from the request), so we flip the stored flag off immediately before
    the save to guarantee the toggle fires for every deck - otherwise the flag is
    already on (see :func:`_force_fsrs_on`) and nothing would recompute.

    ``fsrs_reschedule=False`` matches the deck-options default: memory states are
    computed but due dates are left untouched, so the existing schedule is not
    disrupted. The selected preset is placed last (the entry ``update_deck_configs``
    reassigns the target deck to) and the Default deck's own preset/limits are
    reused, so no deck's config assignment or limits change.
    """
    dcfu = col.decks.get_deck_configs_for_update(DEFAULT_DECK_ID)
    selected_config_id = dcfu.current_deck.config_id
    configs = [entry.config for entry in dcfu.all_config]
    # Keep the target deck's current preset last so update_deck_configs reassigns
    # the Default deck to its own preset (a no-op) rather than a different one.
    configs.sort(key=lambda config: config.id == selected_config_id)
    request = UpdateDeckConfigs(
        target_deck_id=DEFAULT_DECK_ID,
        configs=configs,
        removed_config_ids=[],
        mode=UpdateDeckConfigsMode.UPDATE_DECK_CONFIGS_MODE_NORMAL,
        card_state_customizer=dcfu.card_state_customizer,
        limits=dcfu.current_deck.limits,
        new_cards_ignore_review_limit=dcfu.new_cards_ignore_review_limit,
        apply_all_parent_limits=dcfu.apply_all_parent_limits,
        fsrs=True,
        fsrs_reschedule=False,
        fsrs_health_check=dcfu.fsrs_health_check,
    )
    # Force rslib's fsrs-toggled detection so memory state is recomputed for
    # every deck (it only recomputes when the stored flag flips false -> true).
    col.set_config("fsrs", False)
    col.decks.update_deck_configs(request)


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
        # so the deck tree never reveals where a card came from, then strip the
        # baked-in tag breadcrumb from the card HTML so Browse shows only the
        # real Q/A. Both are idempotent, so re-running from the Tools menu (or a
        # forced re-import) also repairs an already-imported install.
        reorganize_into_themes(col)
        strip_tag_breadcrumbs(col)
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
