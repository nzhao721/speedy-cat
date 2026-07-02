# Copyright: Ankitects Pty Ltd and contributors
# License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

"""SpeedyCAT: cross-device sync of practice + full-length RESULTS.

The practice/full-length *results* live in schema-19 collection tables
(``practice_attempts``, ``full_length_attempts``) that are **deliberately
stripped** from the AnkiWeb upload (stock AnkiWeb only understands schema <=18;
see ``rslib/src/sync/collection/upload.rs`` +
``storage/upgrades/schema19_downgrade.sql``). So those tables never reach the
other device.

To move results across devices over *stock* AnkiWeb sync, we ride the **media
channel**: each device writes ONE JSON file into ``collection.media`` named
``_speedycat_results_<deviceId>.json``. Media files:

* survive the schema-19 strip (they are not in the ``.anki2`` at all);
* round-trip stock AnkiWeb (schema-agnostic media protocol);
* merge **per file** bidirectionally with no clobber, because each device owns a
  distinct filename (see ``rslib/src/sync/media/syncer.rs`` +
  ``changetracker.rs``);
* are readable+writable natively on mobile via the stock backend
  (``col.media`` in AnkiDroid);
* are preserved by Check Media because the name starts with ``_``
  (``rslib/src/media/check.rs`` never lists ``_``-prefixed files as unused).

Each device **publishes** its own file (its practice-session attempts, plus —
desktop only — its completed full-length summaries) and **ingests** the *other*
devices' files, upserting attempts into the local ``practice_attempts`` table by
their stable, globally-unique id (``ps-<rand>:<qid>``). Because every readiness /
tracking query aggregates ``count()``/``sum()`` over distinct primary keys, the
union is exact and never double-counts.

This module is pure-ish plumbing over a :class:`anki.collection.Collection`
(``col.db`` + ``col.media.dir()``); it has no Qt dependency and is unit-tested in
``pylib/tests/test_speedycat_sync.py``. The Qt layer (``aqt/practice.py``) owns
the device id (stored device-locally in the profile manager, NOT in the synced
config) and calls :func:`publish_results` / :func:`ingest_results` from the
profile-open and sync hooks.
"""

from __future__ import annotations

import json
import os
import re
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:
    from anki.collection import Collection

# Bump when the on-disk JSON shape changes in a non-backward-compatible way.
RESULTS_SCHEMA_VERSION = 1

# Media filename: one stable file per device. The leading underscore keeps it
# out of Check Media's "unused" list; the id is sanitized to a safe token.
_FILENAME_PREFIX = "_speedycat_results_"
_FILENAME_SUFFIX = ".json"
_FILENAME_RE = re.compile(r"^_speedycat_results_([A-Za-z0-9_-]+)\.json$")

# Only [a-z0-9_-] survive so the filename is always a normalized media name.
_DEVICE_ID_SANITIZE_RE = re.compile(r"[^A-Za-z0-9_-]")


def sanitize_device_id(device_id: str) -> str:
    """Reduce an arbitrary device id to a safe media-filename token."""
    cleaned = _DEVICE_ID_SANITIZE_RE.sub("", device_id or "")
    return cleaned or "unknown"


def results_filename(device_id: str) -> str:
    return f"{_FILENAME_PREFIX}{sanitize_device_id(device_id)}{_FILENAME_SUFFIX}"


def device_id_from_filename(fname: str) -> str | None:
    m = _FILENAME_RE.match(fname)
    return m.group(1) if m else None


# ---- Serialize (local store -> channel) ------------------------------------


def _read_local_attempts(col: Collection) -> list[dict[str, Any]]:
    """Practice-session attempts from the local schema-19 table.

    Only ``session_id is not null`` rows are exported: those are the attempts
    the Performance pillar + per-topic tracking aggregate on BOTH platforms.
    Full-length per-question answers stay local (mobile consumes the aggregate
    full-length summary instead)."""
    rows = col.db.all(
        """
        select id, session_id, question_id, selected_answer, correct,
               time_on_question_seconds, section, topic, answered_at
        from practice_attempts
        where session_id is not null
        """
    )
    out: list[dict[str, Any]] = []
    for (
        id_,
        session_id,
        question_id,
        selected_answer,
        correct,
        time_seconds,
        section,
        topic,
        answered_at,
    ) in rows:
        out.append(
            {
                "id": id_,
                "sessionId": session_id,
                "questionId": question_id,
                "selectedAnswer": selected_answer or "",
                "correct": bool(correct),
                "timeSeconds": int(time_seconds or 0),
                "section": section or "",
                "topic": topic or "",
                "answeredAt": int(answered_at or 0),
            }
        )
    return out


def _read_local_full_length(col: Collection) -> list[dict[str, Any]]:
    """Completed full-length attempts as compact per-test summaries.

    Desktop is the only producer (full-length was removed from mobile); mobile
    renders these read-only in its 3rd Readiness pillar."""
    rows = col.db.all(
        """
        select fa.id, fa.test_id, fa.started_at, fa.completed_at,
               fa.section_results, fa.overall_scaled_score, ft.title
        from full_length_attempts fa
        left join full_length_tests ft on ft.id = fa.test_id
        where fa.completed_at is not null
        """
    )
    out: list[dict[str, Any]] = []
    for (
        attempt_id,
        test_id,
        started_at,
        completed_at,
        section_results_json,
        overall_scaled_score,
        title,
    ) in rows:
        sections: list[dict[str, Any]] = []
        total_correct = 0
        total_questions = 0
        try:
            stored = json.loads(section_results_json) if section_results_json else []
        except (ValueError, TypeError):
            stored = []
        for sr in stored:
            if not isinstance(sr, dict):
                continue
            correct = int(sr.get("correct", 0) or 0)
            total = int(sr.get("total", 0) or 0)
            total_correct += correct
            total_questions += total
            sections.append(
                {
                    "section": sr.get("section", "") or "",
                    "correct": correct,
                    "total": total,
                    "timeSeconds": int(sr.get("time_seconds", 0) or 0),
                    "scaledScore": sr.get("scaled_score"),
                }
            )
        out.append(
            {
                "attemptId": attempt_id,
                "testId": test_id or "",
                "title": title or "",
                "startedAt": int(started_at or 0),
                "completedAt": int(completed_at or 0),
                "totalCorrect": total_correct,
                "totalQuestions": total_questions,
                "overallScaledScore": overall_scaled_score,
                "sections": sections,
            }
        )
    return out


def serialize_results(
    col: Collection, device_id: str, *, now: int | None = None
) -> dict[str, Any]:
    """Build this device's results payload (attempts + full-length summaries)."""
    from anki.utils import int_time

    return {
        "schema": RESULTS_SCHEMA_VERSION,
        "deviceId": device_id,
        "updatedAt": now if now is not None else int_time(),
        "attempts": _read_local_attempts(col),
        "fullLength": _read_local_full_length(col),
    }


# ---- Publish (channel write) -----------------------------------------------


def publish_results(col: Collection, device_id: str) -> str | None:
    """Write this device's results file into the media folder.

    Returns the filename on success, or None if there is no media folder (e.g.
    an in-memory collection). Writing directly (rather than
    ``media.add_media_file``) keeps the filename STABLE across updates — the
    media change-tracker picks up the overwrite and syncs it on the next media
    sync. Never raises: results sync is best-effort telemetry."""
    try:
        media_dir = col.media.dir()
    except Exception:
        return None
    if not media_dir:
        return None
    payload = serialize_results(col, device_id)
    fname = results_filename(device_id)
    data = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode(
        "utf-8"
    )
    path = os.path.join(media_dir, fname)
    tmp = f"{path}.tmp-{os.getpid()}"
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, path)
    return fname


# ---- Parse + merge (channel read) ------------------------------------------


def parse_results(data: str | bytes) -> dict[str, Any] | None:
    """Parse a results file, returning a normalized dict or None if invalid."""
    try:
        parsed = json.loads(data)
    except (ValueError, TypeError):
        return None
    if not isinstance(parsed, dict):
        return None
    attempts = parsed.get("attempts")
    full_length = parsed.get("fullLength")
    return {
        "schema": parsed.get("schema"),
        "deviceId": parsed.get("deviceId"),
        "updatedAt": parsed.get("updatedAt"),
        "attempts": attempts if isinstance(attempts, list) else [],
        "fullLength": full_length if isinstance(full_length, list) else [],
    }


def _valid_attempt(a: Any) -> bool:
    return (
        isinstance(a, dict)
        and isinstance(a.get("id"), str)
        and a["id"] != ""
        and isinstance(a.get("questionId"), str)
    )


def merge_attempts(attempt_lists: list[list[dict[str, Any]]]) -> list[dict[str, Any]]:
    """Union + dedup attempts by stable id across any number of source files.

    On an id collision the row with the greater ``answeredAt`` wins (a later
    re-answer supersedes), so the merge is deterministic regardless of file
    order. Returns rows sorted by id for stable output."""
    by_id: dict[str, dict[str, Any]] = {}
    for attempts in attempt_lists:
        for a in attempts:
            if not _valid_attempt(a):
                continue
            existing = by_id.get(a["id"])
            if existing is None or int(a.get("answeredAt", 0) or 0) >= int(
                existing.get("answeredAt", 0) or 0
            ):
                by_id[a["id"]] = a
    return [by_id[k] for k in sorted(by_id)]


# ---- Ingest (channel -> local store) ---------------------------------------


def read_other_device_files(col: Collection, device_id: str) -> list[dict[str, Any]]:
    """Parse every ``_speedycat_results_*.json`` media file that is NOT ours."""
    try:
        media_dir = col.media.dir()
    except Exception:
        return []
    if not media_dir or not os.path.isdir(media_dir):
        return []
    my_id = sanitize_device_id(device_id)
    out: list[dict[str, Any]] = []
    for fname in os.listdir(media_dir):
        file_device = device_id_from_filename(fname)
        if file_device is None or file_device == my_id:
            continue
        try:
            with open(os.path.join(media_dir, fname), "rb") as f:
                raw = f.read()
        except OSError:
            continue
        parsed = parse_results(raw)
        if parsed is not None:
            out.append(parsed)
    return out


def ingest_results(col: Collection, device_id: str) -> int:
    """Upsert remote devices' practice attempts into the local schema-19 table.

    Returns the number of attempt rows written. Deduped by primary key, so
    re-running is idempotent and existing readiness/tracking queries transparently
    aggregate the union. Never raises."""
    files = read_other_device_files(col, device_id)
    if not files:
        return 0
    merged = merge_attempts([f["attempts"] for f in files])
    if not merged:
        return 0
    params = [
        (
            a["id"],
            a.get("sessionId"),
            a["questionId"],
            a.get("selectedAnswer", "") or "",
            1 if a.get("correct") else 0,
            int(a.get("timeSeconds", 0) or 0),
            a.get("section", "") or "",
            a.get("topic", "") or "",
            int(a.get("answeredAt", 0) or 0),
        )
        for a in merged
    ]
    col.db.executemany(
        """
        insert or replace into practice_attempts
        (id, session_id, full_length_attempt_id, question_id, selected_answer,
         correct, time_on_question_seconds, section, topic, answered_at)
        values (?, ?, NULL, ?, ?, ?, ?, ?, ?, ?)
        """,
        params,
    )
    return len(params)


def remote_full_length_summaries(
    col: Collection, device_id: str
) -> list[dict[str, Any]]:
    """Completed full-length summaries published by OTHER devices, deduped by
    attemptId. Desktop already has its own locally; this is provided for
    completeness / multi-desktop setups and is not required by the primary
    desktop->mobile flow."""
    files = read_other_device_files(col, device_id)
    by_id: dict[str, dict[str, Any]] = {}
    for f in files:
        for fl in f["fullLength"]:
            if isinstance(fl, dict) and isinstance(fl.get("attemptId"), str):
                by_id[fl["attemptId"]] = fl
    return [by_id[k] for k in sorted(by_id)]
