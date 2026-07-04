# Copyright: SpeedyCAT contributors
# License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

from anki.speedycat_auto_sync import should_throttle


def test_force_bypasses_throttle() -> None:
    assert should_throttle(force=True, last_attempt_secs=100.0, now_secs=100.5) is False


def test_within_interval_is_throttled() -> None:
    assert should_throttle(force=False, last_attempt_secs=100.0, now_secs=115.0) is True


def test_after_interval_is_not_throttled() -> None:
    assert should_throttle(force=False, last_attempt_secs=100.0, now_secs=130.0) is False
