# Copyright: Ankitects Pty Ltd and contributors
# License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

"""Regression tests for the SpeedyCAT account toolbar (qt/aqt/main.py).

SpeedyCAT removed the native menu bar and moved AnkiWeb login/logout onto a
small view QToolBar built in ``_setup_view_toolbar()``. That toolbar is
constructed during ``setupMenus()`` -> ``__init__`` -> ``setupUI()``, which runs
*before* any profile is opened (the profile loads later, on a deferred timer).

``ProfileManager.profile`` is ``None`` until ``pm.load()`` runs, so calling
``pm.sync_auth()`` at toolbar-build time raised
``AttributeError: 'NoneType' object has no attribute 'get'`` and crashed startup.
``_refresh_account_menu()`` now guards against a not-yet-loaded profile.
"""

from __future__ import annotations

from pathlib import Path
from unittest import mock

import pytest

import aqt.main
from aqt.main import AnkiQt
from aqt.profiles import ProfileManager

LOGIN_LABEL = "<login>"
LOGOUT_LABEL = "<logout>"


def _pm(tmp_path: Path) -> ProfileManager:
    # ProfileManager.__init__ is cheap: it only sets attributes (profile=None,
    # db=None) and does not touch the database, so it is safe to build directly.
    return ProfileManager(tmp_path)


def _refresh(pm: ProfileManager) -> mock.Mock:
    """Run the real AnkiQt._refresh_account_menu against a minimal fake window.

    Only the attributes the method touches are provided. ``tr`` is patched so
    the label branch can be asserted without a live translation backend (this
    bare harness has no collection, so the real ``tr`` cannot translate).
    Returns the stub QAction so callers can inspect setText().
    """
    fake = mock.Mock()
    fake.pm = pm
    fake_tr = mock.Mock()
    fake_tr.sync_log_in_button.return_value = LOGIN_LABEL
    fake_tr.sync_log_out_button.return_value = LOGOUT_LABEL
    with mock.patch.object(aqt.main, "tr", fake_tr):
        AnkiQt._refresh_account_menu(fake)
    return fake.accountAuthAction


def test_sync_auth_raises_without_profile(tmp_path: Path) -> None:
    """The underlying crash: sync_auth() dereferences a None profile.

    This is intentionally left as-is in profiles.py; the fix lives at the aqt
    call site, so this test documents *why* the guard is required.
    """
    pm = _pm(tmp_path)
    assert pm.profile is None
    with pytest.raises(AttributeError):
        pm.sync_auth()


def test_refresh_account_menu_no_profile_does_not_crash(tmp_path: Path) -> None:
    """Regression: building the toolbar before a profile is open must not raise."""
    pm = _pm(tmp_path)
    assert pm.profile is None

    action = _refresh(pm)  # would raise AttributeError before the fix

    # No profile -> treated as signed out -> shows the "log in" label.
    action.setText.assert_called_once_with(LOGIN_LABEL)


def test_refresh_account_menu_profile_without_sync_key_is_signed_out(
    tmp_path: Path,
) -> None:
    pm = _pm(tmp_path)
    pm.profile = {}

    action = _refresh(pm)

    action.setText.assert_called_once_with(LOGIN_LABEL)


def test_refresh_account_menu_signed_in_when_profile_has_sync_key(
    tmp_path: Path,
) -> None:
    pm = _pm(tmp_path)
    pm.profile = {"syncKey": "deadbeef"}

    action = _refresh(pm)

    action.setText.assert_called_once_with(LOGOUT_LABEL)
