# Copyright: Ankitects Pty Ltd and contributors
# License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

"""SpeedyCAT: the two MCAT study-mode windows that sit alongside flashcards.

* **Practice Questions** - a UWorld-like multiple-choice question bank, with
  discrete items and CARS passage-sets (a reading passage shown alongside all
  of its questions), section/topic/missed-only filters, an optional session
  timer, post-submit explanations, and a per-topic post-session summary.
* **Full-Length Tests** - AAMC-style four-section practice exams with enforced
  per-section countdowns, scheduled breaks, and a per-section score report.

Both windows are Anki web pages (SvelteKit routes ``practice`` / ``full-length``
served by mediasrv) hosted in a plain ``QDialog``; they talk to the Rust
``PracticeService`` through the ``/_anki`` POST endpoints exposed in
``aqt/mediasrv.py``.

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
from collections.abc import Callable

import aqt
import aqt.gui_hooks
import aqt.main
from anki.collection import Collection
from aqt.operations import QueryOp
from aqt.qt import (
    QAction,
    QDialog,
    Qt,
    QVBoxLayout,
    qconnect,
)
from aqt.utils import (
    aqt_data_folder,
    disable_help_button,
    restoreGeom,
    saveGeom,
    showWarning,
    tooltip,
)
from aqt.webview import AnkiWebView, AnkiWebViewKind

_CONTENT_DIR_ENV = "SPEEDYCAT_CONTENT_DIR"
_PRACTICE_SUBDIR = "practice-questions"
_FULL_LENGTH_SUBDIR = "full-length-tests"

# Bump the version suffix whenever the shipped content changes so a collection
# imported by an earlier build re-imports the new bundles on next open. The
# marker is a collection config value, so it syncs with the collection and the
# import only ever runs once per (collection, content version).
_CONTENT_MARKER = "speedycat_practice_content_v1"

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


# Windows
######################################################################


class _PracticePageDialog(QDialog):
    """Base class for the two SvelteKit-backed study-mode windows."""

    #: subclasses set these
    _kind: AnkiWebViewKind
    _page: str
    _geom_key: str
    _title: str
    _registry_name: str
    #: allow aqt.dialogs.closeAll() to close us immediately
    silentlyClose = True

    def __init__(self, mw: aqt.main.AnkiQt) -> None:
        QDialog.__init__(self, mw, Qt.WindowType.Window)
        self.mw = mw
        mw.garbage_collect_on_dialog_finish(self)
        # ensure content is present even if the profile hook was skipped (e.g.
        # a dev profile opened before this build shipped).
        ensure_content_loaded(mw)
        self.setMinimumSize(800, 600)
        disable_help_button(self)
        restoreGeom(self, self._geom_key, default_size=(1000, 800))
        self.setWindowTitle(self._title)

        self.web = AnkiWebView(mw, kind=self._kind)
        self.web.load_sveltekit_page(self._page)
        layout = QVBoxLayout()
        layout.setContentsMargins(0, 0, 0, 0)
        layout.addWidget(self.web)
        self.setLayout(layout)
        self.show()
        self.web.hide_while_preserving_layout()

    def reopen(self, mw: aqt.main.AnkiQt) -> None:
        self.show()
        self.raise_()
        self.activateWindow()

    def _cleanup(self) -> None:
        if self.web is not None:
            self.web.cleanup()
            self.web = None  # type: ignore[assignment]
        saveGeom(self, self._geom_key)

    def reject(self) -> None:
        self._cleanup()
        aqt.dialogs.markClosed(self._registry_name)
        QDialog.reject(self)

    def closeWithCallback(self, callback: Callable[[], None]) -> None:
        self.reject()
        callback()


class PracticeQuestionsDialog(_PracticePageDialog):
    _kind = AnkiWebViewKind.PRACTICE_QUESTIONS
    _page = "practice"
    _geom_key = "speedycatPractice"
    _title = "Practice Questions"
    _registry_name = "PracticeQuestions"


class FullLengthTestsDialog(_PracticePageDialog):
    _kind = AnkiWebViewKind.FULL_LENGTH_TESTS
    _page = "full-length"
    _geom_key = "speedycatFullLength"
    _title = "Full-Length Tests"
    _registry_name = "FullLengthTests"


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
