# Copyright: Ankitects Pty Ltd and contributors
# License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

"""SpeedyCAT: desktop exam lockdown during an active full-length attempt."""

from __future__ import annotations

import aqt
from aqt.utils import askUser

_ABANDON_WARN = (
    "You have a full-length test in progress.\n\n"
    "Leaving now will abandon your attempt. Progress will be lost and "
    "this score will not count toward Readiness.\n\n"
    "Are you sure you want to leave?"
)

_CLOSE_WARN = (
    "You have a full-length test in progress.\n\n"
    "Closing SpeedyCAT now will abandon your attempt. Progress will be lost "
    "and this score will not count toward Readiness.\n\n"
    "Are you sure you want to quit?"
)


def is_active(mw: aqt.main.AnkiQt) -> bool:
    return bool(getattr(mw, "_full_length_lockdown", False))


def set_active(mw: aqt.main.AnkiQt, active: bool, attempt_id: str | None = None) -> None:
    mw._full_length_lockdown = active
    mw._full_length_attempt_id = attempt_id if active else None
    _sync_toolbar(mw)


def confirm_leave(mw: aqt.main.AnkiQt) -> bool:
    """Ask before navigating away; abandons the attempt when confirmed."""
    if not is_active(mw):
        return True
    if not askUser(_ABANDON_WARN, parent=mw, defaultno=True):
        return False
    abandon_current(mw)
    return True


def confirm_close(mw: aqt.main.AnkiQt) -> bool:
    """Ask before quitting the app; abandons the attempt when confirmed."""
    if not is_active(mw):
        return True
    if not askUser(_CLOSE_WARN, parent=mw, defaultno=True):
        return False
    abandon_current(mw)
    return True


def abandon_current(mw: aqt.main.AnkiQt) -> None:
    attempt_id = getattr(mw, "_full_length_attempt_id", None)
    if attempt_id and mw.col:
        try:
            mw.col.abandon_full_length_attempt(attempt_id=attempt_id)
        except Exception as exc:
            print(f"SpeedyCAT: abandon full-length failed: {exc}")
    set_active(mw, False)
    if mw.state == "speedycat":
        mw.speedycatWeb.load_sveltekit_page("full-length")


def _sync_toolbar(mw: aqt.main.AnkiQt) -> None:
    if is_active(mw):
        mw.toolbarWeb.collapse_for_lockdown()
    elif mw.state == "speedycat":
        mw.toolbarWeb.restore_from_lockdown()
