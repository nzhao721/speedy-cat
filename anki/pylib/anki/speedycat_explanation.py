# Copyright: Ankitects Pty Ltd and contributors
# License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

"""SpeedyCAT: AI explanation verifier for practice multiple-choice questions.

Pure helpers (selection, prompt construction, progressive coaching hints) are
unit-tested without network access. :func:`run_check` / :func:`run_check_via_proxy`
perform the HTTPS call after a correct MCQ answer when the explanation gate is
active. When AI is off or unavailable, callers skip the gate entirely.
"""

from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass
from typing import Any

from anki import speedycat_ai

MODEL = speedycat_ai.MODEL
SOURCE_AI = speedycat_ai.SOURCE_AI
SOURCE_AI_PROXY = speedycat_ai.SOURCE_AI_PROXY
SOURCE_BASELINE = speedycat_ai.SOURCE_BASELINE

DEFAULT_PROXY_URL = (
    "https://us-central1-speedycat-mcat.cloudfunctions.net/checkPracticeExplanation"
)
ENV_PROXY_URL = "SPEEDYCAT_EXPLANATION_PROXY_URL"

EXPLANATION_GATE_MODULO = 5
EXPLANATION_GATE_SUFFIX = ":explanation-gate"

MIN_EXPLANATION_SENTENCES = 2
MIN_EXPLANATION_WORDS = 12

GENERIC_FAIL_HINTS: tuple[str, ...] = (
    "Walk through your reasoning step by step. What concept does this question test, "
    "and how does it support your conclusion?",
    "Be more specific about the underlying principle. How does it apply to the "
    "scenario in the stem?",
    "Connect the stem's details to the science. Explain why your answer follows "
    "from that principle — without just restating the question.",
)

SYSTEM_INSTRUCTION = (
    "You are SpeedyCAT's practice-question explanation evaluator for MCAT study. "
    "The learner answered a multiple-choice question correctly and must explain WHY.\n"
    "You receive:\n"
    "- QUESTION STEM (no answer choices shown to the learner)\n"
    "- LEARNER'S WRITTEN EXPLANATION\n"
    "- CORRECT ANSWER (internal reference ONLY — use it to judge correctness; "
    "NEVER quote it, name its letter, or paraphrase it closely in feedback)\n\n"
    "Judge whether the explanation:\n"
    "1. Is substantive: at least 2–3 sentences with genuine reasoning (not filler, "
    "gaming, or a single vague phrase).\n"
    "2. Demonstrates understanding of WHY the correct answer is correct for this "
    "specific question.\n\n"
    "Return JSON with:\n"
    "- pass: true only when BOTH criteria are met.\n"
    "- feedback: brief coaching for the learner (max ~30 words). When pass is false, "
    "nudge them toward deeper reasoning WITHOUT revealing or closely restating the "
    "correct answer.\n"
    "Answer with ONLY the JSON object."
)

JSON_SCHEMA: dict[str, Any] = {
    "type": "object",
    "additionalProperties": False,
    "properties": {
        "pass": {
            "type": "boolean",
            "description": (
                "true when the explanation is substantive and shows why the "
                "correct answer is correct."
            ),
        },
        "feedback": {
            "type": "string",
            "description": (
                "Brief coaching message; must not reveal the correct answer."
            ),
        },
    },
    "required": ["pass", "feedback"],
}


@dataclass(frozen=True)
class ExplanationResult:
    passed: bool
    feedback: str


def seed_from_str(s: str) -> int:
    """FNV-1a 64-bit hash (mirrors rslib ``seed_from_str`` / mobile Kotlin)."""
    hash_val = 0xCBF29CE484222325
    for b in s.encode("utf-8"):
        hash_val ^= b
        hash_val = (hash_val * 0x100000001B3) & 0xFFFFFFFFFFFFFFFF
    return hash_val


def requires_explanation_gate(session_id: str, question_id: str) -> bool:
    """Deterministic ~1-in-5 gate per session+question (stable on revisit)."""
    key = f"{session_id}:{question_id}{EXPLANATION_GATE_SUFFIX}"
    return seed_from_str(key) % EXPLANATION_GATE_MODULO == 0


def should_use_explanation_gate(
    session_id: str,
    question_id: str,
    *,
    ai_on: bool,
    ai_available: bool,
) -> bool:
    """Gate only when AI can evaluate; skip entirely when AI is off/unavailable."""
    if not ai_on or not ai_available:
        return False
    return requires_explanation_gate(session_id, question_id)


def correct_answer_text(
    stem: str,
    choices: list[dict[str, str]],
    correct_label: str,
) -> str:
    """Resolve the correct choice text from label; fall back to the label."""
    for choice in choices:
        if choice.get("label") == correct_label:
            text = (choice.get("text") or "").strip()
            if text:
                return text
    return correct_label.strip()


def build_evaluator_prompt(
    stem: str,
    user_explanation: str,
    correct_answer: str,
) -> str:
    """Prompt sent to the model. Stem only — no MCQ choices."""
    return "\n".join(
        [
            f"QUESTION STEM: {stem.strip() or '(empty)'}",
            f"LEARNER'S WRITTEN EXPLANATION: {user_explanation.strip() or '(blank)'}",
            (
                "CORRECT ANSWER (internal reference — judge only; do not reveal): "
                f"{correct_answer.strip() or '(empty)'}"
            ),
            "",
            "Return the JSON verdict now.",
        ]
    )


def build_user_visible_prompt(stem: str) -> str:
    """Chatbot opener shown to the learner (instruction only — no question quote)."""
    del stem  # stem kept for API parity with TS/Kotlin twins
    return (
        "You answered correctly. Before moving on, explain your reasoning in a few "
        "sentences."
    )


def explanation_failure_hint(
    fail_count: int,
    hint_prompts: list[str] | None = None,
) -> str:
    """Progressive coaching after a failed explanation (never the full answer)."""
    level = max(1, fail_count)
    prompts = hint_prompts or []
    if level <= len(GENERIC_FAIL_HINTS):
        return GENERIC_FAIL_HINTS[level - 1]
    hint_idx = level - len(GENERIC_FAIL_HINTS) - 1
    if 0 <= hint_idx < len(prompts):
        return f"Consider: {prompts[hint_idx]}"
    return GENERIC_FAIL_HINTS[-1]


_SENTENCE_SPLIT = re.compile(r"[.!?]+")


def heuristic_passes_explanation(text: str) -> bool:
    """Conservative AI-off fallback: substantive multi-sentence attempt."""
    collapsed = re.sub(r"\s+", " ", (text or "")).strip()
    if not collapsed:
        return False
    words = [w for w in collapsed.split(" ") if w]
    if len(words) < MIN_EXPLANATION_WORDS:
        return False
    sentences = [s.strip() for s in _SENTENCE_SPLIT.split(collapsed) if s.strip()]
    return len(sentences) >= MIN_EXPLANATION_SENTENCES


def parse_explanation_response(raw_text: str) -> ExplanationResult | None:
    try:
        parsed = json.loads(raw_text)
    except (ValueError, TypeError):
        return None
    if not isinstance(parsed, dict):
        return None
    passed = parsed.get("pass")
    feedback = parsed.get("feedback")
    if not isinstance(passed, bool):
        return None
    feedback_text = feedback.strip() if isinstance(feedback, str) else ""
    return ExplanationResult(passed=passed, feedback=feedback_text)


def build_request_body(stem: str, user_explanation: str, correct_answer: str) -> dict[str, Any]:
    return {
        "model": MODEL,
        "instructions": SYSTEM_INSTRUCTION,
        "input": build_evaluator_prompt(stem, user_explanation, correct_answer),
        "max_output_tokens": speedycat_ai.MAX_OUTPUT_TOKENS,
        "reasoning": {"effort": speedycat_ai.REASONING_EFFORT},
        "text": {
            "format": {
                "type": "json_schema",
                "name": "speedycat_explanation_check",
                "schema": JSON_SCHEMA,
                "strict": True,
            }
        },
    }


def resolve_proxy_url() -> str | None:
    env_value = os.environ.get(ENV_PROXY_URL)
    if env_value is not None:
        stripped = env_value.strip()
        return stripped if stripped else None
    return DEFAULT_PROXY_URL or None


def explanation_ai_available() -> bool:
    return speedycat_ai.ai_checker_available() or resolve_proxy_url() is not None


def run_check_via_proxy(
    stem: str,
    user_explanation: str,
    correct_answer: str,
    *,
    proxy_url: str | None = None,
    timeout: int = speedycat_ai.REQUEST_TIMEOUT,
) -> ExplanationResult | None:
    url = proxy_url if proxy_url is not None else resolve_proxy_url()
    if not url:
        return None
    try:
        import requests

        response = requests.post(
            url,
            headers={"Content-Type": "application/json"},
            data=json.dumps(
                {
                    "stem": stem,
                    "userExplanation": user_explanation,
                    "correctAnswer": correct_answer,
                }
            ),
            timeout=timeout,
        )
        response.raise_for_status()
        payload = response.json()
    except Exception:
        return None
    if not isinstance(payload, dict):
        return None
    passed = payload.get("pass")
    feedback = payload.get("feedback")
    if not isinstance(passed, bool):
        return None
    feedback_text = feedback.strip() if isinstance(feedback, str) else ""
    return ExplanationResult(passed=passed, feedback=feedback_text)


def run_check_direct(
    stem: str,
    user_explanation: str,
    correct_answer: str,
    *,
    key: str,
    timeout: int = speedycat_ai.REQUEST_TIMEOUT,
) -> ExplanationResult | None:
    try:
        import requests

        response = requests.post(
            speedycat_ai.OPENAI_RESPONSES_URL,
            headers={
                "Authorization": f"Bearer {key}",
                "Content-Type": "application/json",
            },
            data=json.dumps(
                build_request_body(stem, user_explanation, correct_answer)
            ),
            timeout=timeout,
        )
        response.raise_for_status()
        payload = response.json()
    except Exception:
        return None
    return parse_explanation_response(
        speedycat_ai._extract_output_text(payload)
    )


def run_check(
    stem: str,
    user_explanation: str,
    correct_answer: str,
    *,
    key: str | None = None,
    timeout: int = speedycat_ai.REQUEST_TIMEOUT,
) -> tuple[ExplanationResult | None, str]:
    result = run_check_via_proxy(
        stem, user_explanation, correct_answer, timeout=timeout
    )
    if result is not None:
        return result, SOURCE_AI_PROXY
    api_key = key if key is not None else speedycat_ai.load_openai_key()
    if api_key:
        result = run_check_direct(
            stem, user_explanation, correct_answer, key=api_key, timeout=timeout
        )
        return result, SOURCE_AI if result is not None else SOURCE_BASELINE
    return None, SOURCE_BASELINE
