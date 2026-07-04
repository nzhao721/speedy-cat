# Copyright: SpeedyCAT contributors
# License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

"""SpeedyCAT always-on sync throttle (30s minimum between periodic/data triggers)."""

from __future__ import annotations

MIN_INTERVAL_SECS = 30


def should_throttle(
    *,
    force: bool,
    last_attempt_secs: float,
    now_secs: float,
) -> bool:
    """Return True when a non-forced sync should be skipped (within 30s of last attempt)."""
    if force:
        return False
    return (now_secs - last_attempt_secs) < MIN_INTERVAL_SECS
