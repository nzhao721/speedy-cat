# Copyright: Ankitects Pty Ltd and contributors
# License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

"""Tests for SpeedyCAT cross-device results sync (media-channel transport)."""

from __future__ import annotations

import json
import os

from anki import speedycat_sync
from tests.shared import getEmptyCol


def _add_session(col, session_id, started_at=1000):
    col.db.execute(
        "insert or replace into practice_sessions "
        "(id, filter, time_limit_seconds, started_at, completed_at) "
        "values (?, '{}', 0, ?, ?)",
        session_id,
        started_at,
        started_at,
    )


def _add_attempt(
    col,
    *,
    session_id,
    question_id,
    correct=True,
    selected="A",
    time_seconds=30,
    section="CPBS",
    topic="kinetics",
    answered_at=1000,
    full_length_attempt_id=None,
    hint_level_used=0,
    assisted=False,
):
    attempt_id = (
        f"{session_id}:{question_id}"
        if session_id
        else f"{full_length_attempt_id}:{question_id}"
    )
    col.db.execute(
        "insert or replace into practice_attempts "
        "(id, session_id, full_length_attempt_id, question_id, selected_answer, "
        " correct, time_on_question_seconds, section, topic, answered_at, "
        " hint_level_used, assisted) "
        "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        attempt_id,
        session_id,
        full_length_attempt_id,
        question_id,
        selected,
        1 if correct else 0,
        time_seconds,
        section,
        topic,
        answered_at,
        hint_level_used,
        1 if assisted else 0,
    )
    return attempt_id


def _add_completed_full_length(col, attempt_id, test_id="fl-1"):
    col.db.execute(
        "insert or replace into full_length_tests "
        "(id, title, source, format, disclaimer, total_questions, "
        " total_testing_seconds, sections, breaks) "
        "values (?, ?, '', '', '', ?, ?, '[]', '[]')",
        test_id,
        "Full Length 1",
        118,
        22500,
    )
    section_results = json.dumps(
        [
            {"section": "CPBS", "correct": 40, "total": 59, "time_seconds": 5100,
             "scaled_score": None},
            {"section": "CARS", "correct": 30, "total": 53, "time_seconds": 5000,
             "scaled_score": None},
        ]
    )
    col.db.execute(
        "insert or replace into full_length_attempts "
        "(id, test_id, aamc_exam_id, started_at, completed_at, section_results, "
        " overall_scaled_score) values (?, ?, NULL, ?, ?, ?, NULL)",
        attempt_id,
        test_id,
        1000,
        2000,
        section_results,
    )


# ---- serialize / parse -----------------------------------------------------


def test_serialize_reads_attempts_and_full_length():
    col = getEmptyCol()
    _add_session(col, "ps-a")
    _add_attempt(col, session_id="ps-a", question_id="q1", correct=True)
    _add_attempt(
        col,
        session_id="ps-a",
        question_id="q2",
        correct=False,
        selected="",
    )
    # An assisted-correct attempt (hint ladder reached level 3) carries its
    # tracking so the other device applies the same anti-gaming penalty.
    _add_attempt(
        col,
        session_id="ps-a",
        question_id="q4",
        correct=True,
        hint_level_used=3,
        assisted=True,
    )
    # A full-length per-question answer must NOT appear in attempts[] (it is
    # session_id NULL); only the aggregate summary is exported.
    _add_attempt(col, session_id=None, full_length_attempt_id="fla-1", question_id="q3")
    _add_completed_full_length(col, "fla-1")

    payload = speedycat_sync.serialize_results(col, "dev-desktop", now=12345)
    assert payload["schema"] == speedycat_sync.RESULTS_SCHEMA_VERSION
    assert payload["deviceId"] == "dev-desktop"
    assert payload["updatedAt"] == 12345

    attempts = {a["id"]: a for a in payload["attempts"]}
    assert set(attempts) == {"ps-a:q1", "ps-a:q2", "ps-a:q4"}
    assert attempts["ps-a:q1"]["correct"] is True
    assert attempts["ps-a:q1"]["section"] == "CPBS"
    assert attempts["ps-a:q2"]["selectedAnswer"] == ""
    # Graduated hint ladder fields ride along.
    assert attempts["ps-a:q1"]["hintLevelUsed"] == 0
    assert attempts["ps-a:q1"]["assisted"] is False
    assert attempts["ps-a:q4"]["hintLevelUsed"] == 3
    assert attempts["ps-a:q4"]["assisted"] is True

    assert len(payload["fullLength"]) == 1
    fl = payload["fullLength"][0]
    assert fl["attemptId"] == "fla-1"
    assert fl["totalCorrect"] == 70
    assert fl["totalQuestions"] == 112
    assert [s["section"] for s in fl["sections"]] == ["CPBS", "CARS"]
    assert fl["sections"][0]["timeSeconds"] == 5100


def test_parse_roundtrip_and_rejects_garbage():
    col = getEmptyCol()
    _add_session(col, "ps-a")
    _add_attempt(col, session_id="ps-a", question_id="q1")
    payload = speedycat_sync.serialize_results(col, "dev-1", now=1)
    text = json.dumps(payload)
    parsed = speedycat_sync.parse_results(text)
    assert parsed is not None
    assert parsed["deviceId"] == "dev-1"
    assert len(parsed["attempts"]) == 1

    assert speedycat_sync.parse_results("not json") is None
    assert speedycat_sync.parse_results("[1,2,3]") is None
    # Missing arrays normalize to empty lists.
    empty = speedycat_sync.parse_results('{"deviceId":"x"}')
    assert empty["attempts"] == [] and empty["fullLength"] == []


# ---- filename helpers ------------------------------------------------------


def test_filename_helpers_roundtrip_and_sanitize():
    assert speedycat_sync.results_filename("abc123") == "_speedycat_results_abc123.json"
    # Unsafe chars are stripped so the media filename stays normalized.
    assert speedycat_sync.results_filename("a/b .c") == "_speedycat_results_abc.json"
    assert (
        speedycat_sync.device_id_from_filename("_speedycat_results_abc123.json")
        == "abc123"
    )
    assert speedycat_sync.device_id_from_filename("random.jpg") is None
    assert speedycat_sync.device_id_from_filename("_speedycat_results_.json") is None


# ---- merge / dedup ---------------------------------------------------------


def test_merge_attempts_dedups_and_prefers_latest():
    a_old = {"id": "s:q", "questionId": "q", "answeredAt": 100, "correct": False}
    a_new = {"id": "s:q", "questionId": "q", "answeredAt": 200, "correct": True}
    other = {"id": "s:q2", "questionId": "q2", "answeredAt": 50, "correct": True}
    merged = speedycat_sync.merge_attempts([[a_old, other], [a_new]])
    by_id = {a["id"]: a for a in merged}
    assert set(by_id) == {"s:q", "s:q2"}
    # Later answeredAt wins on id collision.
    assert by_id["s:q"]["correct"] is True
    # Output is sorted by id for stability.
    assert [a["id"] for a in merged] == ["s:q", "s:q2"]

    # Invalid rows are dropped.
    assert speedycat_sync.merge_attempts([[{"no": "id"}, {"id": ""}]]) == []


# ---- publish / ingest ------------------------------------------------------


def test_publish_writes_stable_media_file():
    col = getEmptyCol()
    _add_session(col, "ps-a")
    _add_attempt(col, session_id="ps-a", question_id="q1")

    fname = speedycat_sync.publish_results(col, "dev-1")
    assert fname == "_speedycat_results_dev-1.json"
    path = os.path.join(col.media.dir(), fname)
    assert os.path.exists(path)
    with open(path, encoding="utf-8") as f:
        parsed = speedycat_sync.parse_results(f.read())
    assert parsed["deviceId"] == "dev-1"
    assert len(parsed["attempts"]) == 1

    # Re-publishing with new data OVERWRITES the same file (stable name).
    _add_attempt(col, session_id="ps-a", question_id="q2")
    fname2 = speedycat_sync.publish_results(col, "dev-1")
    assert fname2 == fname
    files = [x for x in os.listdir(col.media.dir()) if x.startswith("_speedycat")]
    assert files == [fname]
    with open(path, encoding="utf-8") as f:
        assert len(speedycat_sync.parse_results(f.read())["attempts"]) == 2


def _write_remote_file(col, device_id, payload):
    with open(
        os.path.join(col.media.dir(), speedycat_sync.results_filename(device_id)),
        "w",
        encoding="utf-8",
    ) as f:
        json.dump(payload, f)


def test_ingest_unions_other_devices_and_is_idempotent():
    col = getEmptyCol()
    # Local (this device = "desktop") has its own attempts + published file.
    _add_session(col, "ps-local")
    _add_attempt(col, session_id="ps-local", question_id="q1", answered_at=1000)
    speedycat_sync.publish_results(col, "desktop")

    # A remote "mobile" device file with two fresh attempts.
    remote = {
        "schema": 1,
        "deviceId": "mobile",
        "updatedAt": 5,
        "attempts": [
            {
                "id": "ps-m:q10",
                "sessionId": "ps-m",
                "questionId": "q10",
                "selectedAnswer": "B",
                "correct": True,
                "timeSeconds": 20,
                "section": "CARS",
                "topic": "philosophy",
                "answeredAt": 2000,
            },
            {
                "id": "ps-m:q11",
                "sessionId": "ps-m",
                "questionId": "q11",
                "selectedAnswer": "C",
                "correct": False,
                "timeSeconds": 40,
                "section": "CARS",
                "topic": "ethics",
                "answeredAt": 2001,
            },
        ],
        "fullLength": [],
    }
    _write_remote_file(col, "mobile", remote)

    written = speedycat_sync.ingest_results(col, "desktop")
    assert written == 2
    ids = set(col.db.list("select id from practice_attempts"))
    assert ids == {"ps-local:q1", "ps-m:q10", "ps-m:q11"}

    # Idempotent: re-ingesting writes the same rows, no duplicates.
    speedycat_sync.ingest_results(col, "desktop")
    assert col.db.scalar("select count(*) from practice_attempts") == 3

    # Our own published file is skipped (never re-ingested as "remote").
    from anki.collection import McatSection

    stats = col.get_topic_stats(source=0)  # ALL
    cars = [s for s in stats.sections if s.section == McatSection.MCAT_SECTION_CARS]
    assert cars and cars[0].attempts == 2  # both mobile CARS attempts counted


def test_ingest_feeds_existing_performance_pillar():
    col = getEmptyCol()
    # No local attempts. A remote device supplies 30 answered practice attempts.
    remote_attempts = [
        {
            "id": f"ps-m:q{i}",
            "sessionId": "ps-m",
            "questionId": f"q{i}",
            "selectedAnswer": "A",
            "correct": i < 24,
            "timeSeconds": 60,
            "section": "CPBS",
            "topic": "kinetics",
            "answeredAt": 1000 + i,
        }
        for i in range(30)
    ]
    _write_remote_file(
        col,
        "mobile",
        {"schema": 1, "deviceId": "mobile", "attempts": remote_attempts,
         "fullLength": []},
    )

    # Before ingest the Performance pillar gives up (0 local attempts).
    before = col.get_readiness(deck_search="")
    assert not before.performance.available

    speedycat_sync.ingest_results(col, "desktop")

    after = col.get_readiness(deck_search="")
    assert after.performance.available
    assert after.performance.sample_size == 30
    assert abs(after.performance.value - 0.8) < 1e-9


def test_ingest_assisted_correct_is_penalized_in_performance():
    col = getEmptyCol()
    # 30 answered attempts synced from mobile, ALL correct — but 15 of them are
    # assisted-correct (hint ladder reached level 3). The anti-gaming penalty
    # credits only unassisted-correct, so the Performance value is 15/30 = 0.5,
    # not a gamed 30/30 = 1.0, while all 30 stay in the denominator.
    remote_attempts = [
        {
            "id": f"ps-m:q{i}",
            "sessionId": "ps-m",
            "questionId": f"q{i}",
            "selectedAnswer": "A",
            "correct": True,
            "timeSeconds": 30,
            "section": "CPBS",
            "topic": "kinetics",
            "answeredAt": 1000 + i,
            "hintLevelUsed": 3 if i < 15 else 0,
            "assisted": i < 15,
        }
        for i in range(30)
    ]
    _write_remote_file(
        col,
        "mobile",
        {"schema": 1, "deviceId": "mobile", "attempts": remote_attempts,
         "fullLength": []},
    )
    speedycat_sync.ingest_results(col, "desktop")

    after = col.get_readiness(deck_search="")
    assert after.performance.available
    assert after.performance.sample_size == 30
    # 15 * 1.0 + 15 * 0.10 = 16.5 / 30 (progressive L3 penalty)
    assert abs(after.performance.value - 16.5 / 30.0) < 1e-9


def test_ingest_with_no_remote_files_is_a_noop():
    col = getEmptyCol()
    # With no other-device files present, ingest must no-op (return 0) rather
    # than raise; robustness is asserted by simply not throwing here.
    assert speedycat_sync.ingest_results(col, "desktop") == 0
