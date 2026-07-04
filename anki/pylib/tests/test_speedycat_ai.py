# Copyright: Ankitects Pty Ltd and contributors
# License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

"""SpeedyCAT AI answer checker — unit tests for the pure, Qt-free core.

Covers the four behaviours the project spec calls out — honesty-gate parsing,
the AI-off fallback, the "I don't know" decision, and the FSRS *Again* lock —
plus key loading, prompt/schema construction, and the deterministic helpers.
None of these touch the network: :func:`run_check` is only exercised for its
no-key short-circuit.
"""

from __future__ import annotations

import json

import pytest

from anki import speedycat_ai as ai

# --- Structured-output parsing (the honesty gate) ---------------------------


def test_parse_valid_honest_correct() -> None:
    result = ai.parse_checker_response(
        json.dumps({"honest_attempt": True, "verdict": "correct", "reason": "matches"})
    )
    assert result is not None
    assert result.honest_attempt is True
    assert result.verdict == "correct"
    assert result.correct is True
    assert result.reason == "matches"


def test_parse_valid_honest_incorrect() -> None:
    result = ai.parse_checker_response(
        json.dumps({"honest_attempt": True, "verdict": "incorrect", "reason": "no"})
    )
    assert result is not None
    assert result.honest_attempt is True
    assert result.correct is False


def test_parse_dishonest_is_forced_incorrect() -> None:
    # A dishonest attempt is never scored correct, even if the model says so.
    result = ai.parse_checker_response(
        json.dumps({"honest_attempt": False, "verdict": "correct", "reason": "mash"})
    )
    assert result is not None
    assert result.honest_attempt is False
    assert result.verdict == "incorrect"


@pytest.mark.parametrize(
    "raw",
    [
        "not json",
        "[]",
        "null",
        json.dumps({"verdict": "correct", "reason": "x"}),  # missing honest_attempt
        json.dumps({"honest_attempt": "yes", "verdict": "correct", "reason": "x"}),
        json.dumps({"honest_attempt": True, "verdict": "maybe", "reason": "x"}),
        json.dumps({"honest_attempt": True, "reason": "x"}),  # missing verdict
    ],
)
def test_parse_malformed_returns_none(raw: str) -> None:
    assert ai.parse_checker_response(raw) is None


def test_extract_output_text_prefers_convenience_field() -> None:
    assert ai._extract_output_text({"output_text": "hi"}) == "hi"


def test_extract_output_text_walks_output_array_and_skips_reasoning() -> None:
    payload = {
        "output": [
            {"type": "reasoning", "content": [{"type": "reasoning", "text": "think"}]},
            {
                "type": "message",
                "content": [
                    {"type": "output_text", "text": '{"honest_attempt":'},
                    {"type": "output_text", "text": " true}"},
                ],
            },
        ]
    }
    assert ai._extract_output_text(payload) == '{"honest_attempt": true}'


def test_extract_output_text_empty_when_missing() -> None:
    assert ai._extract_output_text({}) == ""


# --- Prompt + request body (named source + strict schema) -------------------


def test_prompt_contains_front_typed_expected() -> None:
    prompt = ai.build_check_prompt(
        "What is the powerhouse?", "mitochondria", "Mitochondria"
    )
    assert "What is the powerhouse?" in prompt
    assert "mitochondria" in prompt
    assert "Mitochondria" in prompt


def test_request_body_matches_cloud_proxy_setup() -> None:
    body = ai.build_request_body("f", "t", "e", model=ai.MODEL)
    assert body["model"] == "gpt-5.4-mini"
    assert body["max_output_tokens"] == ai.MAX_OUTPUT_TOKENS
    assert body["reasoning"] == {"effort": "low"}
    fmt = body["text"]["format"]
    assert fmt["type"] == "json_schema"
    assert fmt["strict"] is True
    assert fmt["schema"]["required"] == ["honest_attempt", "verdict", "reason"]
    assert fmt["schema"]["additionalProperties"] is False


def test_source_traces_to_named_model() -> None:
    assert ai.SOURCE_AI == "openai:gpt-5.4-mini"


# --- Deterministic AI-off verdict -------------------------------------------


@pytest.mark.parametrize(
    "typed,expected,match",
    [
        ("Hemoglobin", "hemoglobin", True),
        ("the Krebs cycle", "The Krebs Cycle", True),
        ("aorta", "<b>aorta</b>", True),
        ("new york", "new\u00a0york", True),
        ("Hemoglobin", "insulin", False),
        ("   ", "hemoglobin", False),
        ("", "", False),
    ],
)
def test_deterministic_correct(typed: str, expected: str, match: bool) -> None:
    assert ai.deterministic_correct(typed, expected) is match


# --- Conservative gaming heuristic (drives the AI-off FSRS lock) ------------


@pytest.mark.parametrize(
    "typed",
    [
        "",
        "   ",
        "....",
        "!!!",
        "-----",
        "aaaa",
        "zzzzz",
        "asdf",
        "qwerty",
        "zxcvbn",
        "hjkl",
        "idk",
        "dunno",
    ],
)
def test_heuristic_flags_non_attempts(typed: str) -> None:
    assert ai.heuristic_is_honest_attempt(typed) is False


@pytest.mark.parametrize(
    "typed",
    [
        "C",  # a legitimate single-character answer (e.g. an element)
        "K+",
        "pH",
        "DNA",
        "mitochondria",
        "mitochondira",  # misspelled but a real attempt
        "the krebs cycle",
        "action potential",
    ],
)
def test_heuristic_keeps_real_attempts(typed: str) -> None:
    assert ai.heuristic_is_honest_attempt(typed) is True


# --- Static-first AI gate (plan_reveal) -------------------------------------


def test_plan_reveal_static_correct_skips_ai() -> None:
    plan = ai.plan_reveal("Mitochondria", "mitochondria", ai_on=True)
    assert plan.needs_ai is False
    assert plan.decision is not None
    assert plan.decision.verdict == "correct"
    assert plan.decision.source == ai.SOURCE_BASELINE
    assert plan.decision.force_again is False


def test_plan_reveal_static_incorrect_with_ai_on_needs_ai() -> None:
    plan = ai.plan_reveal("mitochondira", "mitochondria", ai_on=True)
    assert plan.needs_ai is True
    assert plan.decision is None


def test_plan_reveal_static_incorrect_ai_off_uses_baseline() -> None:
    plan = ai.plan_reveal("insulin", "mitochondria", ai_on=False)
    assert plan.needs_ai is False
    assert plan.decision is not None
    assert plan.decision.verdict == "incorrect"
    assert plan.decision.source == ai.SOURCE_BASELINE


def test_run_check_only_after_static_incorrect(monkeypatch: pytest.MonkeyPatch) -> None:
    calls: list[tuple[str, ...]] = []

    def fake_proxy(*a: object, **k: object) -> ai.CheckerResult:
        calls.append(("proxy",) + tuple(str(x) for x in a))
        return ai.CheckerResult(True, "correct", "synonym")

    monkeypatch.setattr(ai, "run_check_via_proxy", fake_proxy)
    monkeypatch.setattr(ai, "run_check_direct", lambda *a, **k: None)

    # Static correct: run_check should never be reached via plan_reveal.
    plan = ai.plan_reveal("mitochondria", "Mitochondria", ai_on=True)
    assert not plan.needs_ai
    assert calls == []

    # Static incorrect: caller invokes run_check.
    plan = ai.plan_reveal("mitochondira", "Mitochondria", ai_on=True)
    assert plan.needs_ai
    result, source = ai.run_check("front", "mitochondira", "Mitochondria")
    assert result is not None
    assert source == ai.SOURCE_AI_PROXY
    assert len(calls) == 1


# --- Shared decision logic --------------------------------------------------


def test_decide_idk_forces_again_and_locks() -> None:
    decision = ai.decide_idk()
    assert decision.reveal is True
    assert decision.verdict is None
    assert decision.force_again is True
    assert decision.lock_ratings is True
    assert decision.source == ai.SOURCE_IDK


def test_decide_reveal_ai_on_honest_reveals_with_model_verdict() -> None:
    result = ai.CheckerResult(honest_attempt=True, verdict="correct", reason="ok")
    decision = ai.decide_reveal(
        "mitochondria", "Mitochondria", ai_on=True, ai_result=result
    )
    assert decision.reveal is True
    assert decision.verdict == "correct"
    assert decision.force_again is False
    assert decision.lock_ratings is False
    assert decision.source == ai.SOURCE_AI


def test_decide_reveal_ai_on_dishonest_blocks_reveal_and_locks() -> None:
    result = ai.CheckerResult(honest_attempt=False, verdict="incorrect", reason="mash")
    decision = ai.decide_reveal("asdf", "Mitochondria", ai_on=True, ai_result=result)
    assert decision.reveal is False
    assert decision.force_again is True
    assert decision.lock_ratings is True
    assert decision.message == ai.HONESTY_PROMPT
    assert decision.source == ai.SOURCE_AI


def test_decide_reveal_ai_off_always_reveals_with_string_match() -> None:
    # AI off => always reveal + deterministic verdict, exactly like today.
    honest = ai.decide_reveal(
        "Mitochondria", "mitochondria", ai_on=False, ai_result=None
    )
    assert honest.reveal is True
    assert honest.verdict == "correct"
    assert honest.force_again is False
    assert honest.lock_ratings is False
    assert honest.source == ai.SOURCE_BASELINE

    wrong = ai.decide_reveal("insulin", "mitochondria", ai_on=False, ai_result=None)
    assert wrong.reveal is True
    assert wrong.verdict == "incorrect"
    assert wrong.force_again is False  # wrong but honest -> normal ratings


def test_decide_reveal_ai_off_heuristic_locks_on_gaming() -> None:
    # AI off but a clear non-attempt still forces Again + lock (reveal stays true).
    decision = ai.decide_reveal("asdf", "mitochondria", ai_on=False, ai_result=None)
    assert decision.reveal is True
    assert decision.force_again is True
    assert decision.lock_ratings is True
    assert decision.source == ai.SOURCE_BASELINE


def test_decide_reveal_ai_on_but_no_result_falls_back() -> None:
    # AI enabled but the call failed (ai_result None) -> deterministic fallback.
    decision = ai.decide_reveal(
        "Mitochondria", "mitochondria", ai_on=True, ai_result=None
    )
    assert decision.reveal is True
    assert decision.source == ai.SOURCE_BASELINE


def test_idk_delay_is_five_seconds() -> None:
    assert ai.IDK_DELAY_MS == 5000


# --- Key loading (env var OR gitignored file; never hardcoded) --------------


def test_key_from_env_takes_precedence(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(ai.ENV_KEY, "  sk-from-env  ")
    assert ai.load_openai_key() == "sk-from-env"
    assert ai.key_present() is True


def test_key_from_file_when_env_absent(
    monkeypatch: pytest.MonkeyPatch, tmp_path
) -> None:
    monkeypatch.delenv(ai.ENV_KEY, raising=False)
    key_file = tmp_path / "openai.key"
    key_file.write_text("sk-from-file\n", encoding="utf-8")
    monkeypatch.setenv(ai.ENV_KEY_FILE, str(key_file))
    assert ai.load_openai_key() == "sk-from-file"


def test_key_absent_means_ai_off(monkeypatch: pytest.MonkeyPatch, tmp_path) -> None:
    monkeypatch.delenv(ai.ENV_KEY, raising=False)
    monkeypatch.setenv(ai.ENV_KEY_FILE, str(tmp_path / "does-not-exist.key"))
    # No repo-root key file is guaranteed in CI, but an explicit empty override
    # plus an unset env var must resolve to "off" here.
    monkeypatch.setattr(ai, "_candidate_key_files", lambda: [tmp_path / "nope.key"])
    assert ai.load_openai_key() is None
    assert ai.key_present() is False


def test_run_check_without_key_or_proxy_returns_baseline(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(ai, "load_openai_key", lambda: None)
    monkeypatch.setattr(ai, "resolve_proxy_url", lambda: None)
    result, source = ai.run_check("front", "typed", "expected", key=None)
    assert result is None
    assert source == ai.SOURCE_BASELINE


def test_resolve_proxy_url_default() -> None:
    assert ai.resolve_proxy_url() == (
        "https://us-central1-speedycat-mcat.cloudfunctions.net/checkSpeedycatAnswer"
    )
    assert ai.resolve_proxy_url() == ai.DEFAULT_PROXY_URL


def test_resolve_proxy_url_empty_default_disables(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv(ai.ENV_PROXY_URL, raising=False)
    monkeypatch.setattr(ai, "DEFAULT_PROXY_URL", "")
    monkeypatch.setattr(ai, "load_openai_key", lambda: None)
    assert ai.resolve_proxy_url() is None
    assert ai.ai_checker_available() is False


def test_resolve_proxy_url_env_override(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(ai.ENV_PROXY_URL, "https://example.test/proxy")
    assert ai.resolve_proxy_url() == "https://example.test/proxy"


def test_resolve_proxy_url_empty_env_disables(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(ai.ENV_PROXY_URL, "  ")
    assert ai.resolve_proxy_url() is None


def test_ai_checker_available_with_proxy_only(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(ai, "load_openai_key", lambda: None)
    monkeypatch.setattr(ai, "resolve_proxy_url", lambda: "https://example.test/proxy")
    assert ai.ai_checker_available() is True
    assert ai.key_present() is False


def test_ai_checker_available_with_local_key_only(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(ai, "load_openai_key", lambda: "sk-local")
    monkeypatch.setattr(ai, "resolve_proxy_url", lambda: None)
    assert ai.ai_checker_available() is True


def test_run_check_prefers_proxy_over_local_key(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        ai,
        "run_check_via_proxy",
        lambda *a, **k: ai.CheckerResult(True, "correct", "proxy"),
    )
    monkeypatch.setattr(ai, "run_check_direct", lambda *a, **k: None)
    result, source = ai.run_check("f", "t", "e", key="sk-local")
    assert result is not None
    assert source == ai.SOURCE_AI_PROXY


def test_run_check_falls_back_to_local_key(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(ai, "run_check_via_proxy", lambda *a, **k: None)
    monkeypatch.setattr(
        ai,
        "run_check_direct",
        lambda *a, **k: ai.CheckerResult(True, "incorrect", "direct"),
    )
    result, source = ai.run_check("f", "t", "e", key="sk-local")
    assert result is not None
    assert source == ai.SOURCE_AI


def test_run_check_falls_back_to_proxy(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(ai, "load_openai_key", lambda: None)
    monkeypatch.setattr(
        ai,
        "run_check_via_proxy",
        lambda *a, **k: ai.CheckerResult(True, "incorrect", "proxy"),
    )
    result, source = ai.run_check("f", "t", "e")
    assert result is not None
    assert source == ai.SOURCE_AI_PROXY


def test_is_ai_source() -> None:
    assert ai.is_ai_source(ai.SOURCE_AI) is True
    assert ai.is_ai_source(ai.SOURCE_AI_PROXY) is True
    assert ai.is_ai_source(ai.SOURCE_BASELINE) is False


def test_source_proxy_traces_to_named_model() -> None:
    assert ai.SOURCE_AI_PROXY == "openai/gpt-5.4-mini via speedycat-proxy"


# --- Field stripping: expected-answer source cleanup (CSS/HTML/breadcrumb/footer)


def test_extract_cloze_answer_from_markers() -> None:
    field = (
        "{{c1::rotational equilibrium}} occurs in the absence of any net "
        "torques acting on an object"
    )
    assert ai.extract_cloze_answer(field, 1) == "rotational equilibrium"


def test_extract_cloze_from_prerendered_rotational_equilibrium() -> None:
    front = "___ occurs in the absence of any net torques acting on an object"
    back = (
        "rotational equilibrium occurs in the absence of any net torques "
        "acting on an object"
    )
    assert ai.extract_cloze_from_prerendered(front, back) == "rotational equilibrium"


def test_extract_cloze_from_prerendered_bracket_blank() -> None:
    front = "[...] occurs in the absence of any net torques acting on an object"
    back = (
        "rotational equilibrium occurs in the absence of any net torques "
        "acting on an object"
    )
    assert ai.extract_cloze_from_prerendered(front, back) == "rotational equilibrium"


def test_extract_cloze_from_prerendered_translational_equilibrium() -> None:
    front = (
        "___ equilibrium occurs in the absence of any net forces acting on an object"
    )
    back = (
        "Translational equilibrium occurs in the absence of any net forces "
        "acting on an object"
    )
    assert ai.extract_cloze_from_prerendered(front, back) == "Translational"


def test_extract_cloze_answer_skips_front_placeholder_span() -> None:
    front = (
        '<span class="cloze" data-ordinal="1">[...] equilibrium</span> '
        "occurs in the absence of any net forces acting on an object"
    )
    assert ai.extract_cloze_answer(front, 1) is None
    back = (
        '<span class="cloze" data-ordinal="1">Translational</span> '
        "equilibrium occurs in the absence of any net forces acting on an object"
    )
    assert ai.extract_cloze_answer(back, 1) == "Translational"


def test_extract_cloze_answer_from_rendered_span() -> None:
    back = (
        '<span class="cloze" data-ordinal="1">rotational equilibrium</span> '
        "occurs in the absence of any net torques acting on an object"
    )
    assert ai.extract_cloze_answer(back, 1) == "rotational equilibrium"


def test_strip_expected_leaves_bare_cloze_untouched() -> None:
    # The real fix derives the expected answer from the cloze deletion; stripping
    # a bare deletion is a no-op.
    assert ai.strip_expected("direction") == "direction"


def test_strip_expected_removes_style_breadcrumb_html_and_link_footer() -> None:
    rendered = (
        "<style>#kard { color: red; }</style>"
        '<div class="tags">Physics::Kinematics</div>'
        '<div>change the <span class="cloze">direction</span>&nbsp;of the vector</div>'
        '<a href="https://khanacademy.org">Khan Academy Link</a>'
    )
    cleaned = ai.strip_expected(rendered)
    assert "<" not in cleaned
    assert "#kard" not in cleaned
    assert "Physics::Kinematics" not in cleaned
    assert "Khan Academy Link" not in cleaned
    assert "change the direction of the vector" in cleaned


def test_strip_expected_multicloze_html_and_entities() -> None:
    # extract_cloze_for_typing joins multiple deletions with ", " and keeps HTML.
    raw = "n, <i>energy level</i> or&nbsp;<i>shell number</i>"
    assert ai.strip_expected(raw) == "n, energy level or shell number"


def test_strip_expected_multilevel_breadcrumb() -> None:
    assert (
        ai.strip_expected("General_Chemistry::Atomic_Structure::Quantum_Numbers n")
        == "n"
    )


def test_strip_front_removes_breadcrumb_and_keeps_cloze_blank() -> None:
    front = (
        "<style>x{}</style>"
        '<div class="tags">Physics::Kinematics</div>'
        '<div>change the <span class="cloze">[...]</span> of the vector</div>'
    )
    assert ai.strip_front(front) == "change the [...] of the vector"


# --- Multi-cloze flexible-separator matching (IS the AI-off fallback compare) ---


@pytest.mark.parametrize(
    "typed",
    ["a b", "a, b", "a; b", "a  b", "a | b", "a / b", "a : b", "A B"],
)
def test_multicloze_separators_all_match(typed: str) -> None:
    assert ai.deterministic_correct(typed, "a, b") is True


def test_multicloze_order_matters() -> None:
    assert ai.deterministic_correct("b a", "a, b") is False


def test_multicloze_multiword_answers_not_split() -> None:
    assert (
        ai.deterministic_correct(
            "acrylic acid sodium hydroxide", "acrylic acid, sodium hydroxide"
        )
        is True
    )
    assert ai.deterministic_correct("acrylic acid", "acrylic acid") is True


def test_multicloze_quantum_example_end_to_end() -> None:
    expected = ai.strip_expected("n, <i>energy level</i> or&nbsp;<i>shell number</i>")
    assert ai.deterministic_correct("n energy level or shell number", expected) is True
    assert ai.deterministic_correct("n, energy level or shell number", expected) is True
    assert ai.deterministic_correct("energy level or shell number n", expected) is False


def test_idk_delay_escalates() -> None:
    assert ai.idk_delay_ms(0) == 5000
    assert ai.idk_delay_ms(1) == 10_000
    assert ai.idk_delay_ms(2) == 15_000
    assert ai.idk_delay_ms(99) == 15_000


def test_is_gamed_decision() -> None:
    assert ai.is_gamed_decision(ai.decide_idk()) is True
    honest = ai.decide_reveal("mitochondria", "mitochondria", ai_on=False, ai_result=None)
    assert ai.is_gamed_decision(honest) is False
    gamed = ai.decide_reveal("asdf", "mitochondria", ai_on=False, ai_result=None)
    assert ai.is_gamed_decision(gamed) is True


def test_is_idk_decision() -> None:
    assert ai.is_idk_decision(ai.decide_idk()) is True
    assert not ai.is_idk_decision(
        ai.decide_reveal("asdf", "x", ai_on=False, ai_result=None)
    )
