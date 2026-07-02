# Copyright: Ankitects Pty Ltd and contributors
# License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

"""SpeedyCAT: the two MCAT study modes that sit alongside flashcards.

* **Practice Questions** - a UWorld-like multiple-choice question bank, with
  discrete items and CARS passage-sets (a reading passage shown alongside all
  of its questions), section/topic/missed-only filters, an optional session
  timer, post-submit explanations, and a per-topic post-session summary.
* **Full-Length Tests** - AAMC-style four-section practice exams with enforced
  per-section countdowns, scheduled breaks, and a per-section score report.

Both modes are Anki web pages (SvelteKit routes ``practice`` / ``full-length``
served by mediasrv) rendered *inside the main application window*: the toolbar
tabs switch the main window into the ``speedycat`` state, which swaps the
central content from the deck browser to a dedicated, API-enabled webview
navigated to the mode's route (see ``aqt/main.py``). One webview is reused for
both modes, so switching modes just navigates it and no separate window is ever
opened. They talk to the Rust ``PracticeService`` through the ``/_anki`` POST
endpoints exposed in ``aqt/mediasrv.py``.

Content (questions, passages, full-length definitions) ships as structured JSON
bundles inside the app data folder (``<data>/speedycat/practice-questions/*.json``
and ``<data>/speedycat/full-length-tests/*.json``) and is imported into the
collection DB by the Rust loader on first run - idempotently, gated by a
per-collection config marker, so it never re-imports on later startups. A
``SPEEDYCAT_CONTENT_DIR`` environment variable overrides the location (points at
a directory holding ``practice-questions``/``full-length-tests`` subfolders),
which lets a developer or installer swap in an alternate content set.
"""

from __future__ import annotations

import os
import traceback
import uuid

import aqt
import aqt.gui_hooks
import aqt.main
from anki import speedycat_sync
from anki.collection import Collection
from aqt.operations import QueryOp
from aqt.qt import QAction, qconnect
from aqt.utils import aqt_data_folder, showWarning, tooltip

_CONTENT_DIR_ENV = "SPEEDYCAT_CONTENT_DIR"
_PRACTICE_SUBDIR = "practice-questions"
_FULL_LENGTH_SUBDIR = "full-length-tests"

# Bump the version suffix whenever the shipped content changes so a collection
# imported by an earlier build re-imports the new bundles on next open. The
# marker is a collection config value, so it syncs with the collection and the
# import only ever runs once per (collection, content version).
# v2: adds the AI-generated practice-question expansion (3,974 items; 5,174 total).
_CONTENT_MARKER = "speedycat_practice_content_v2"

# Guards against queuing the import twice (e.g. the profile hook and a page
# opening before the first import finishes). Collection background ops are
# serialized on a single worker, so this only avoids redundant work.
_content_loading = False


# Content resolution + import
######################################################################


def _content_root() -> str | None:
    """Return the directory holding the practice/full-length JSON subfolders."""
    candidates: list[str] = []
    if env := os.environ.get(_CONTENT_DIR_ENV):
        candidates.append(env)
    candidates.append(os.path.join(aqt_data_folder(), "speedycat"))
    for directory in candidates:
        if os.path.isdir(os.path.join(directory, _PRACTICE_SUBDIR)) or os.path.isdir(
            os.path.join(directory, _FULL_LENGTH_SUBDIR)
        ):
            return directory
    return None


def _bundle_files(root: str, subdir: str) -> list[str]:
    """JSON bundles in ``root/subdir`` (skipping ``_``-prefixed helper files)."""
    directory = os.path.join(root, subdir)
    if not os.path.isdir(directory):
        return []
    return sorted(
        os.path.join(directory, name)
        for name in os.listdir(directory)
        if name.endswith(".json") and not name.startswith("_")
    )


def _load_all_bundles(col: Collection, root: str) -> tuple[int, int, int]:
    """Import every practice/full-length bundle; return (questions, passages,
    tests) imported. Loading is an upsert, so this is safe to re-run."""
    questions = passages = tests = 0
    for path in _bundle_files(root, _PRACTICE_SUBDIR):
        res = col.load_practice_question_bundle(path=path)
        questions += res.questions_imported
        passages += res.passages_imported
    for path in _bundle_files(root, _FULL_LENGTH_SUBDIR):
        res = col.load_full_length_test_bundle(path=path)
        questions += res.questions_imported
        passages += res.passages_imported
        tests += res.tests_imported
    return questions, passages, tests


def ensure_content_loaded(
    mw: aqt.main.AnkiQt, *, force: bool = False, manual: bool = False
) -> None:
    """Import the bundled MCAT practice content in the background (idempotent).

    ``force`` re-imports even if the marker is set; ``manual`` surfaces
    warnings/tooltips for the Tools-menu trigger (the automatic path stays
    silent on a no-op)."""
    global _content_loading
    if mw.col is None:
        if manual:
            showWarning("Please open a profile first.")
        return
    if not force and mw.col.get_config(_CONTENT_MARKER, False):
        return
    if _content_loading:
        return

    root = _content_root()
    if root is None:
        if manual:
            showWarning(
                "The bundled MCAT practice content could not be found. Expected "
                f"{_PRACTICE_SUBDIR!r}/{_FULL_LENGTH_SUBDIR!r} folders in the app "
                f"data folder or in a directory named by the {_CONTENT_DIR_ENV} "
                "environment variable."
            )
        return

    def op(col: Collection) -> tuple[int, int, int]:
        counts = _load_all_bundles(col, root)
        col.set_config(_CONTENT_MARKER, True)
        return counts

    def on_success(counts: tuple[int, int, int]) -> None:
        global _content_loading
        _content_loading = False
        questions, _passages, tests = counts
        if manual:
            tooltip(
                f"MCAT practice content ready ({questions} questions, "
                f"{tests} full-length tests)."
            )

    def on_failure(exc: Exception) -> None:
        global _content_loading
        _content_loading = False
        if manual:
            showWarning(f"Could not load MCAT practice content: {exc}")

    _content_loading = True
    QueryOp(parent=mw, op=op, success=on_success).failure(on_failure).with_progress(
        "Loading MCAT practice content"
    ).run_in_background()


def _auto_load_on_open() -> None:
    """Silently import the bundled content the first time a collection opens."""
    if aqt.mw is not None:
        ensure_content_loaded(aqt.mw)


# Cross-device results sync (over stock AnkiWeb, via the media channel)
######################################################################
#
# Practice + full-length RESULTS live in schema-19 tables that are stripped from
# the AnkiWeb upload, so they never sync directly. Instead each device publishes
# a per-device JSON file into ``collection.media`` and ingests the OTHER devices'
# files, unioning attempts into the local ``practice_attempts`` table (deduped by
# stable id). The desktop's existing Performance/Readiness/tracking queries then
# compute over the merged store unchanged. See ``anki/speedycat_sync.py`` for the
# transport rationale and the exact merge/dedup contract.

# Device id is stored in the (local, NEVER-synced) profile-manager meta so each
# device keeps a distinct identity; storing it in the synced collection config
# would clobber it across devices and collapse everyone onto one media file.
_DEVICE_ID_META_KEY = "speedycatDeviceId"


def _device_id(mw: aqt.main.AnkiQt) -> str:
    """This device's stable, local-only id (minted + persisted on first use)."""
    existing = mw.pm.meta.get(_DEVICE_ID_META_KEY)
    if isinstance(existing, str) and existing:
        return existing
    new_id = uuid.uuid4().hex
    mw.pm.meta[_DEVICE_ID_META_KEY] = new_id
    mw.pm.save()
    return new_id


def _publish_results_now() -> None:
    """Write this device's results file so the next media sync uploads it.

    Runs synchronously on ``sync_will_start`` (before media sync's change
    scan). Best-effort: a failure here must never block a sync."""
    mw = aqt.mw
    if mw is None or mw.col is None:
        return
    try:
        speedycat_sync.publish_results(mw.col, _device_id(mw))
    except Exception:
        traceback.print_exc()


def _ingest_results_now() -> None:
    """Union other devices' published attempts into the local store.

    Runs on ``profile_did_open`` and ``sync_did_finish``. Executed as a
    background ``QueryOp`` so the ``practice_attempts`` writes are serialized on
    the collection worker thread (never racing the first-run content import or a
    sync). Best-effort and idempotent (upsert by id); newly-ingested rows are
    reflected the next time the dashboard recomputes readiness."""
    mw = aqt.mw
    if mw is None or mw.col is None:
        return
    device_id = _device_id(mw)
    QueryOp(
        parent=mw,
        op=lambda col: speedycat_sync.ingest_results(col, device_id),
        success=lambda _count: None,
    ).failure(
        lambda exc: print(f"SpeedyCAT: results ingest failed: {exc}")
    ).run_in_background()


# Embedded study modes
######################################################################
#
# The two MCAT study modes render *inside* the main application window (the same
# window that shows the deck browser): the toolbar tabs switch the main window
# into the ``speedycat`` state, which hides the normal deck-browser content and
# reveals a dedicated, API-enabled webview (``mw.speedycatWeb``) navigated to
# the route below -- so no separate window is ever opened. The main-window
# wiring lives in ``aqt/main.py`` (``setupMainWindow`` builds ``speedycatWeb``;
# ``_speedycatState`` / ``_speedycatCleanup`` swap the central content). This
# module just owns the mode -> route mapping and the content import.

#: study mode key -> SvelteKit route served by mediasrv
STUDY_ROUTES: dict[str, str] = {
    "practice": "practice",
    "full-length": "full-length",
    # SpeedyCAT: the Dashboard is embedded through the same state (it is also
    # the startup landing page), so the toolbar's Dashboard tab swaps the main
    # window content instead of opening the old separate stats dialog.
    "dashboard": "graphs",
}
DEFAULT_STUDY_MODE = "practice"


# Registration
######################################################################


def setup(mw: aqt.main.AnkiQt) -> None:
    """Register the first-run content import and a manual reload action. Call
    once at startup (from ``aqt/main.py``)."""
    action = QAction("Reload MCAT Practice Content", mw)
    qconnect(
        action.triggered, lambda: ensure_content_loaded(mw, force=True, manual=True)
    )
    mw.form.menuTools.addAction(action)
    aqt.gui_hooks.profile_did_open.append(_auto_load_on_open)
    # Cross-device results sync (media channel): ingest others' results on open
    # and after each sync; publish ours right before a sync uploads media.
    aqt.gui_hooks.profile_did_open.append(_ingest_results_now)
    aqt.gui_hooks.sync_will_start.append(_publish_results_now)
    aqt.gui_hooks.sync_did_finish.append(_ingest_results_now)
