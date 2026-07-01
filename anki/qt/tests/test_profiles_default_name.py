# Copyright: Ankitects Pty Ltd and contributors
# License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

"""Regression tests for SpeedyCAT's branded default profile (qt/aqt/profiles.py).

Upstream Anki names the first, auto-created profile "User 1" (from
``tr.profiles_user_1()``), which then shows up in the profile chooser. SpeedyCAT
brands it instead:

* fresh installs create ``ProfileManager.DEFAULT_PROFILE_NAME`` ("SpeedyCAT");
* an existing install whose *sole* profile is still the untouched upstream
  default ("User 1") is migrated once at startup via
  ``maybe_rebrand_default_profile()``, reusing ``rename()`` so the profile
  folder + metadata DB stay consistent and the collection is preserved.

The migration is guarded so it never touches a user-chosen name or a
multi-profile install, and is idempotent (a no-op after the first run).
"""

from __future__ import annotations

import os
from pathlib import Path

import pytest

import anki.lang
from aqt.profiles import ProfileManager


@pytest.fixture(autouse=True)
def _english_i18n() -> None:
    # _ensureProfile() writes a localized README via ``tr``; the bare harness has
    # no collection, so initialise the global translation backend first.
    anki.lang.set_lang("en")


def _pm(base: Path) -> ProfileManager:
    pm = ProfileManager(base)
    # opens prefs21.db and creates the "profiles" table + the _global row.
    pm.setupMeta()
    return pm


def test_fresh_install_default_profile_is_branded(tmp_path: Path) -> None:
    pm = _pm(tmp_path)
    # profiles() auto-creates the default profile when the DB has none.
    assert pm.profiles() == [ProfileManager.DEFAULT_PROFILE_NAME]
    assert pm.profiles() == ["SpeedyCAT"]
    assert "User 1" not in pm.profiles()


def test_migrates_lone_legacy_default(tmp_path: Path) -> None:
    pm = _pm(tmp_path)
    pm.create(ProfileManager.LEGACY_DEFAULT_PROFILE_NAME)  # simulate old install
    assert pm.profiles() == ["User 1"]

    assert pm.maybe_rebrand_default_profile() is True

    assert pm.profiles() == ["SpeedyCAT"]
    # rename() kept the folder and DB consistent: new folder exists, old is gone.
    assert os.path.isdir(os.path.join(pm.base, "SpeedyCAT"))
    assert not os.path.isdir(os.path.join(pm.base, "User 1"))


def test_migration_is_idempotent(tmp_path: Path) -> None:
    pm = _pm(tmp_path)
    pm.create("User 1")

    assert pm.maybe_rebrand_default_profile() is True
    # Nothing named "User 1" remains, so a second call must do nothing.
    assert pm.maybe_rebrand_default_profile() is False
    assert pm.profiles() == ["SpeedyCAT"]


def test_does_not_touch_user_chosen_name(tmp_path: Path) -> None:
    pm = _pm(tmp_path)
    pm.create("Alice")

    assert pm.maybe_rebrand_default_profile() is False
    assert pm.profiles() == ["Alice"]


def test_does_not_touch_when_multiple_profiles(tmp_path: Path) -> None:
    pm = _pm(tmp_path)
    pm.create("User 1")
    pm.create("Bob")

    assert pm.maybe_rebrand_default_profile() is False
    assert sorted(pm.profiles()) == ["Bob", "User 1"]


def test_skips_when_target_folder_already_exists(tmp_path: Path) -> None:
    # Defensive: a stray folder colliding with the brand name (case-insensitively
    # on Windows/macOS) must not trigger rename()'s "folder already exists"
    # warning dialog at startup — the migration quietly skips instead.
    pm = _pm(tmp_path)
    pm.create("User 1")
    os.makedirs(os.path.join(pm.base, "SpeedyCAT"))

    assert pm.maybe_rebrand_default_profile() is False
    assert pm.profiles() == ["User 1"]
