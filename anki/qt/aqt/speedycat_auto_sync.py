# Copyright: SpeedyCAT contributors
# License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

"""Always-on AnkiWeb sync for SpeedyCAT (30s throttle + startup/close)."""

from __future__ import annotations

import time
from typing import TYPE_CHECKING

import aqt.gui_hooks
from anki.collection import OpChanges
from anki.speedycat_auto_sync import should_throttle

if TYPE_CHECKING:
    import aqt.main


class SpeedyCatAutoSyncController:
    def __init__(self, mw: aqt.main.AnkiQt) -> None:
        self._mw = mw
        self._last_attempt = 0.0
        self._periodic_timer = mw.progress.timer(
            30_000,
            lambda: self._request_sync(force=False),
            repeat=True,
            parent=mw,
        )
        self._periodic_timer.stop()

    def on_profile_open(self) -> None:
        self._periodic_timer.start()

    def on_profile_close(self) -> None:
        self._periodic_timer.stop()

    def on_collection_change(self, changes: OpChanges, handler: object | None) -> None:
        if handler is self:
            return
        if not self._changes_need_sync(changes):
            return
        self._request_sync(force=False)

    def _request_sync(self, *, force: bool) -> None:
        mw = self._mw
        if mw.col is None:
            return
        now = time.time()
        if should_throttle(force=force, last_attempt_secs=self._last_attempt, now_secs=now):
            return
        if not mw.can_auto_sync():
            return
        self._last_attempt = now
        mw._sync_collection_and_media(mw._refresh_after_sync, silent=True)

    @staticmethod
    def _changes_need_sync(changes: OpChanges) -> bool:
        return bool(
            changes.card
            or changes.note
            or changes.deck
            or changes.tag
            or changes.notetype
            or changes.config
            or changes.deck_config
            or changes.mtime
            or changes.study_queues
            or changes.browser_table
            or changes.browser_sidebar
            or changes.note_text
        )


_controller: SpeedyCatAutoSyncController | None = None


def request_data_change_sync() -> None:
    if _controller is not None:
        _controller._request_sync(force=False)


def setup(mw: aqt.main.AnkiQt) -> None:
    global _controller
    controller = SpeedyCatAutoSyncController(mw)
    _controller = controller

    def on_open() -> None:
        controller.on_profile_open()

    def on_close() -> None:
        controller.on_profile_close()

    def on_change(changes: OpChanges, handler: object | None) -> None:
        controller.on_collection_change(changes, handler)

    aqt.gui_hooks.profile_did_open.append(on_open)
    aqt.gui_hooks.profile_will_close.append(on_close)
    aqt.gui_hooks.operation_did_execute.append(on_change)
