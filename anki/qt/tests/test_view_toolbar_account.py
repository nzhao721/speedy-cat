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

The account UI also shows the *signed-in identity* (the AnkiWeb email stored as
``syncUser``) instead of the internal profile name ("User 1"): a neutral label
when signed out, the email when signed in, and a graceful fallback when signed
in without a stored email. These tests cover that display logic and the
window-title helper (``_update_window_title``) that shares the same source.
"""

from __future__ import annotations

from pathlib import Path
from unittest import mock

import pytest

import aqt.main
from aqt.main import (
    ACCOUNT_SIGNED_IN_FALLBACK,
    ACCOUNT_SIGNED_OUT_LABEL,
    AnkiQt,
    _signed_in_account_name,
)
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
    Returns the fake window so callers can inspect the QAction/button stubs
    (``accountAuthAction``, ``accountIdentityAction``, ``accountButton``).
    """
    fake = mock.Mock()
    fake.pm = pm
    fake_tr = mock.Mock()
    fake_tr.sync_log_in_button.return_value = LOGIN_LABEL
    fake_tr.sync_log_out_button.return_value = LOGOUT_LABEL
    with mock.patch.object(aqt.main, "tr", fake_tr):
        AnkiQt._refresh_account_menu(fake)
    return fake


# --- _signed_in_account_name: the single source of the displayed identity ----


def test_signed_in_account_name_no_profile(tmp_path: Path) -> None:
    pm = _pm(tmp_path)
    assert pm.profile is None
    # Must short-circuit before sync_auth() (which would deref the None profile).
    assert _signed_in_account_name(pm) is None


def test_signed_in_account_name_signed_out(tmp_path: Path) -> None:
    pm = _pm(tmp_path)
    pm.profile = {}
    assert _signed_in_account_name(pm) is None


def test_signed_in_account_name_signed_in_without_email(tmp_path: Path) -> None:
    pm = _pm(tmp_path)
    pm.profile = {"syncKey": "deadbeef"}
    # Signed in, but no username was recorded -> no identity to display.
    assert _signed_in_account_name(pm) is None


def test_signed_in_account_name_signed_in_with_email(tmp_path: Path) -> None:
    pm = _pm(tmp_path)
    pm.profile = {"syncKey": "deadbeef", "syncUser": "me@example.com"}
    assert _signed_in_account_name(pm) == "me@example.com"


# --- the crash guard (unchanged behaviour) -----------------------------------


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

    fake = _refresh(pm)  # would raise AttributeError before the fix

    # No profile -> treated as signed out -> shows the "log in" label and the
    # neutral identity, never "User 1".
    fake.accountAuthAction.setText.assert_called_once_with(LOGIN_LABEL)
    fake.accountIdentityAction.setText.assert_called_once_with(ACCOUNT_SIGNED_OUT_LABEL)
    fake.accountButton.setToolTip.assert_called_once_with(ACCOUNT_SIGNED_OUT_LABEL)


def test_refresh_account_menu_profile_without_sync_key_is_signed_out(
    tmp_path: Path,
) -> None:
    pm = _pm(tmp_path)
    pm.profile = {}

    fake = _refresh(pm)

    fake.accountAuthAction.setText.assert_called_once_with(LOGIN_LABEL)
    fake.accountIdentityAction.setText.assert_called_once_with(ACCOUNT_SIGNED_OUT_LABEL)


# --- signed-in identity display ----------------------------------------------


def test_refresh_account_menu_signed_in_when_profile_has_sync_key(
    tmp_path: Path,
) -> None:
    pm = _pm(tmp_path)
    pm.profile = {"syncKey": "deadbeef"}

    fake = _refresh(pm)

    fake.accountAuthAction.setText.assert_called_once_with(LOGOUT_LABEL)


def test_refresh_account_menu_signed_in_shows_email(tmp_path: Path) -> None:
    pm = _pm(tmp_path)
    pm.profile = {"syncKey": "deadbeef", "syncUser": "me@example.com"}

    fake = _refresh(pm)

    fake.accountAuthAction.setText.assert_called_once_with(LOGOUT_LABEL)
    # The account email is shown in the menu header and the button tooltip.
    fake.accountIdentityAction.setText.assert_called_once_with("me@example.com")
    fake.accountButton.setToolTip.assert_called_once_with("Signed in as me@example.com")


def test_refresh_account_menu_signed_in_without_email_falls_back(
    tmp_path: Path,
) -> None:
    """Residual case: signed in but no stored email -> neutral 'Signed in'."""
    pm = _pm(tmp_path)
    pm.profile = {"syncKey": "deadbeef"}

    fake = _refresh(pm)

    fake.accountIdentityAction.setText.assert_called_once_with(
        ACCOUNT_SIGNED_IN_FALLBACK
    )
    fake.accountButton.setToolTip.assert_called_once_with(ACCOUNT_SIGNED_IN_FALLBACK)


# --- window title (shares _signed_in_account_name) ---------------------------


def _title(pm: ProfileManager) -> mock.Mock:
    fake = mock.Mock()
    fake.pm = pm
    AnkiQt._update_window_title(fake)
    return fake.setWindowTitle


def test_window_title_signed_in_shows_account(tmp_path: Path) -> None:
    pm = _pm(tmp_path)
    pm.profile = {"syncKey": "deadbeef", "syncUser": "me@example.com"}

    _title(pm).assert_called_once_with("me@example.com - SpeedyCAT")


def test_window_title_signed_out_is_neutral(tmp_path: Path) -> None:
    pm = _pm(tmp_path)
    pm.profile = {}

    # Never "User 1 - SpeedyCAT"; just the neutral branding.
    _title(pm).assert_called_once_with("SpeedyCAT")


def test_window_title_signed_in_without_email_is_neutral(tmp_path: Path) -> None:
    pm = _pm(tmp_path)
    pm.profile = {"syncKey": "deadbeef"}

    _title(pm).assert_called_once_with("SpeedyCAT")


def test_window_title_no_profile_is_neutral(tmp_path: Path) -> None:
    pm = _pm(tmp_path)
    assert pm.profile is None

    _title(pm).assert_called_once_with("SpeedyCAT")
