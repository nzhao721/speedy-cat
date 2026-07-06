# Copyright: Ankitects Pty Ltd and contributors
# License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

from anki import speedycat_explanation as expl


def test_requires_explanation_gate_is_deterministic_and_about_one_in_five() -> None:
    session = "ps-test"
    flags = [expl.requires_explanation_gate(session, f"q-{i}") for i in range(100)]
    rate = sum(flags) / len(flags)
    assert 0.10 < rate < 0.35
    assert expl.requires_explanation_gate(session, "q-42") == expl.requires_explanation_gate(
        session, "q-42"
    )


def test_should_use_explanation_gate_skips_when_ai_off() -> None:
    assert not expl.should_use_explanation_gate(
        "ps-1", "q-1", ai_on=False, ai_available=True
    )
    assert not expl.should_use_explanation_gate(
        "ps-1", "q-1", ai_on=True, ai_available=False
    )


def test_build_evaluator_prompt_has_stem_not_choices() -> None:
    prompt = expl.build_evaluator_prompt(
        "Which enzyme limits glycolysis?",
        "PFK-1 is the rate-limiting enzyme.",
        "Phosphofructokinase-1",
    )
    assert "Which enzyme limits glycolysis?" in prompt
    assert "PFK-1 is the rate-limiting enzyme." in prompt
    assert "Phosphofructokinase-1" in prompt
    assert "Hexokinase" not in prompt


def test_build_user_visible_prompt_is_instruction_only() -> None:
    prompt = expl.build_user_visible_prompt("What is the powerhouse of the cell?")
    assert prompt == (
        "You answered correctly. Before moving on, explain your reasoning in a few "
        "sentences."
    )
    assert "powerhouse" not in prompt
    assert "mitochondria" not in prompt.lower()


def test_explanation_failure_hint_escalates() -> None:
    h1 = expl.explanation_failure_hint(1)
    h2 = expl.explanation_failure_hint(2)
    # Custom prompts kick in only after the generic hints are exhausted (there
    # are 3 generic hints, so level 4 is the first custom one) — mirrors the TS
    # twin test `explanationFailureHint(4, ...)`.
    h3 = expl.explanation_failure_hint(4, ["What regulates this step?"])
    assert h1 != h2
    assert "What regulates this step?" in h3


def test_heuristic_passes_explanation() -> None:
    short = "because yes"
    long_enough = (
        "The rate-limiting step is controlled by energy charge. "
        "High ATP inhibits the enzyme through allosteric feedback."
    )
    assert not expl.heuristic_passes_explanation(short)
    assert expl.heuristic_passes_explanation(long_enough)


def test_parse_explanation_response() -> None:
    ok = expl.parse_explanation_response('{"pass": true, "feedback": "nice"}')
    assert ok is not None
    assert ok.passed is True
    assert ok.feedback == "nice"
    assert expl.parse_explanation_response("not json") is None


def test_run_check_reports_unavailable_when_no_proxy_and_no_key(monkeypatch) -> None:
    # Proxy disabled (empty env override) AND no local key → run_check performs
    # no network call and reports "unavailable" as (None, baseline). The desktop
    # bridge maps this None to the error → advance fallback, so a learner is
    # never trapped by a transient/technical AI failure after they submit.
    monkeypatch.setenv(expl.ENV_PROXY_URL, "")
    monkeypatch.setattr(expl.speedycat_ai, "load_openai_key", lambda: None)
    result, source = expl.run_check("stem", "a genuine explanation", "answer")
    assert result is None
    assert source == expl.SOURCE_BASELINE
