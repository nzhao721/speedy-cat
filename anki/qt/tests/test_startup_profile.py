# Copyright: Ankitects Pty Ltd and contributors
# License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

"""Regression tests for SpeedyCAT's boot-time profile auto-open.

Upstream Anki shows the profile-selection window ("choose the user") at
startup whenever it can't unambiguously pick a profile. SpeedyCAT is a
single-user desktop app, so it boots straight into a profile instead:

* ``ProfileManager.profile_to_auto_open()`` (qt/aqt/profiles.py) resolves which
  profile to open — the most-recently-loaded one, else the branded default
  ("SpeedyCAT"), else the first profile.
* ``AnkiQt.setupProfile()`` (qt/aqt/main.py) loads that profile directly and
  only falls back to the chooser for the explicit ``-p <bad-name>`` error path.

Switching accounts after launch is unaffected: the account toolbar's
"Switch Profile" action calls ``unloadProfileAndShowProfileManager()``, which
still opens the chooser on demand.
"""

from __future__ import annotations

from pathlib import Path
from unittest import mock

import pytest

import anki.lang
from aqt.main import AnkiQt
from aqt.profiles import ProfileManager


@pytest.fixture(autouse=True)
def _english_i18n() -> None:
    # profiles() auto-creates the default profile via _ensureProfile(), which
    # writes a localized README through ``tr``; the bare harness has no
    # collection, so initialise the global translation backend first.
    anki.lang.set_lang("en")


def _pm(tmp_path: Path) -> ProfileManager:
    pm = ProfileManager(tmp_path)
    # opens prefs21.db and creates the "profiles" table + the _global row.
    pm.setupMeta()
    # simulate an existing install (firstRun would otherwise load profiles()[0]
    # unconditionally and bypass the auto-open decision under test).
    pm.meta["firstRun"] = False
    return pm


def _setup_profile(pm: ProfileManager) -> mock.Mock:
    """Run the real AnkiQt.setupProfile against a minimal fake window.

    ``setupProfile`` only touches ``self.pm`` plus a couple of scratch
    attributes and the terminal ``loadProfile`` / ``showProfileManager``
    branches; a Mock provides those without constructing any Qt objects.
    """
    fake = mock.Mock()
    fake.pm = pm
    AnkiQt.setupProfile(fake)
    return fake


# --- profile_to_auto_open(): which profile boots -----------------------------


def test_auto_open_prefers_last_loaded(tmp_path: Path) -> None:
    pm = _pm(tmp_path)
    pm.create("SpeedyCAT")
    pm.create("Bob")
    pm.set_last_loaded_profile_name("Bob")

    assert pm.profile_to_auto_open() == "Bob"


def test_auto_open_falls_back_to_branded_default(tmp_path: Path) -> None:
    pm = _pm(tmp_path)
    pm.create("SpeedyCAT")
    pm.create("Bob")
    # nothing loaded yet -> no recorded last profile
    assert pm.last_loaded_profile_name() is None

    assert pm.profile_to_auto_open() == "SpeedyCAT"


def test_auto_open_ignores_stale_last_loaded(tmp_path: Path) -> None:
    pm = _pm(tmp_path)
    pm.create("SpeedyCAT")
    # e.g. the recorded profile was since deleted
    pm.set_last_loaded_profile_name("Ghost")

    assert pm.profile_to_auto_open() == "SpeedyCAT"


def test_auto_open_single_profile(tmp_path: Path) -> None:
    pm = _pm(tmp_path)
    # profiles() auto-creates the branded default when the DB has none.
    assert pm.profile_to_auto_open() == "SpeedyCAT"


def test_auto_open_falls_back_to_first_when_no_default(tmp_path: Path) -> None:
    pm = _pm(tmp_path)
    pm.create("Alice")
    pm.create("Bob")
    # no SpeedyCAT and nothing loaded -> deterministic first profile
    assert pm.last_loaded_profile_name() is None
    assert pm.profile_to_auto_open() == pm.profiles()[0]


# --- setupProfile(): boots straight in, no chooser ---------------------------


def test_setup_profile_auto_opens_last_loaded(tmp_path: Path) -> None:
    pm = _pm(tmp_path)
    pm.create("SpeedyCAT")
    pm.create("Bob")
    pm.load("Bob")  # records last_loaded = "Bob"
    pm.name = None  # simulate a fresh boot (no profile chosen yet)

    fake = _setup_profile(pm)

    assert pm.name == "Bob"
    fake.loadProfile.assert_called_once()
    fake.showProfileManager.assert_not_called()


def test_setup_profile_falls_back_to_default_without_chooser(
    tmp_path: Path,
) -> None:
    pm = _pm(tmp_path)
    pm.create("SpeedyCAT")
    pm.create("Bob")  # >1 profile, but no last-loaded record

    fake = _setup_profile(pm)

    # Would have shown the chooser upstream; SpeedyCAT opens the default.
    assert pm.name == "SpeedyCAT"
    fake.loadProfile.assert_called_once()
    fake.showProfileManager.assert_not_called()


def test_setup_profile_first_run_boots_default(tmp_path: Path) -> None:
    pm = ProfileManager(tmp_path)
    pm.setupMeta()
    assert pm.meta["firstRun"] is True  # fresh install

    fake = _setup_profile(pm)

    assert pm.name == "SpeedyCAT"
    fake.loadProfile.assert_called_once()
    fake.showProfileManager.assert_not_called()


def test_setup_profile_invalid_commandline_still_shows_chooser(
    tmp_path: Path,
) -> None:
    # The one boot path that still shows the picker: an explicit `-p <bad-name>`
    # was requested and could not be loaded. This is an explicit user error, not
    # the normal launch, so we keep surfacing the chooser here.
    pm = _pm(tmp_path)
    pm.create("SpeedyCAT")
    pm.invalid_profile_provided_on_commandline = True

    fake = _setup_profile(pm)

    assert pm.name is None
    fake.showProfileManager.assert_called_once()
    fake.loadProfile.assert_not_called()


# --- switching accounts after launch still works -----------------------------


def test_switch_profile_reopens_chooser_on_demand() -> None:
    # In-app "Switch Profile" (account toolbar) must still open the manager,
    # independent of the boot-time auto-open change.
    fake = mock.Mock()

    AnkiQt.unloadProfileAndShowProfileManager(fake)

    fake.unloadProfile.assert_called_once_with(fake.showProfileManager)
