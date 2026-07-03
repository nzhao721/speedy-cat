# Copyright: Ankitects Pty Ltd and contributors
# License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

"""SpeedyCAT: AI answer checker for forced-active-recall flashcards (desktop).

This module is the **pure, Qt-free** core of SpeedyCAT's AI answer checker. It
owns everything that can be reasoned about (and unit-tested) without a running
reviewer or a network connection:

* loading the OpenAI key (env var or a gitignored local file) — never hardcoded;
* the model id (the *named source* every AI verdict traces to), the prompt, and
  the strict ``json_schema`` structured-output contract;
* parsing / validating the model's JSON reply into a :class:`CheckerResult`;
* the deterministic **AI-off** fallbacks — a case-insensitive string match
  (verdict) and a conservative "is this a genuine attempt?" heuristic (used to
  drive the FSRS *Again* lock without the model);
* the shared **decision** logic (:func:`decide_reveal` / :func:`decide_idk`)
  that both the desktop reviewer and the offline eval harness consume.

The one impure helper, :func:`run_check`, performs the actual HTTPS call to the
OpenAI *responses* API (mirroring ``brilliant-clone``'s setup: model
``gpt-5.4-mini``, ``responses.create`` with a strict ``json_schema`` and low
reasoning effort). It imports ``requests`` lazily so the rest of the module —
and therefore the eval harness and the unit tests — import with only the stdlib.

Hard project rule: the app MUST work fully with AI **off**. When the key is
absent, the toggle is off, or the call fails, callers fall back to the
deterministic path and the reviewer reveals + shows a verdict exactly as before.
"""

from __future__ import annotations

import html
import json
import os
import re
import unicodedata
from dataclasses import dataclass
from pathlib import Path
from typing import Any

# --- Named source / provider settings (mirrors brilliant-clone) -------------

#: The model id. Every AI verdict traces to this *named source*; it is shown to
#: the learner (labelled model-generated) and recorded by the eval harness.
MODEL = "gpt-5.4-mini"

#: OpenAI *responses* endpoint (same API surface brilliant-clone calls via SDK).
OPENAI_RESPONSES_URL = "https://api.openai.com/v1/responses"

#: Bounded output budget: the reply is a tiny JSON object, but low-effort
#: reasoning shares this budget, so keep enough headroom that it can't starve
#: the message (same rationale as brilliant-clone's MAX_OUTPUT_TOKENS).
MAX_OUTPUT_TOKENS = 600

#: 'minimal' isn't valid for gpt-5.4-mini; 'low' keeps the short reply fast.
REASONING_EFFORT = "low"

#: Network timeout (seconds) for the check. The reviewer runs it on a background
#: thread and falls back to the deterministic path if it errors or times out.
REQUEST_TIMEOUT = 20

# --- Key loading (env var OR gitignored file; NEVER hardcoded) --------------

#: Environment variable holding the key (highest precedence).
ENV_KEY = "SPEEDYCAT_OPENAI_API_KEY"

#: Optional environment variable pointing at an alternative key *file*.
ENV_KEY_FILE = "SPEEDYCAT_OPENAI_KEY_FILE"

#: The gitignored key filename the user populates themselves.
KEY_FILENAME = ".speedycat-openai.key"


def _candidate_key_files() -> list[Path]:
    """Locations searched for the key file, in priority order.

    Covers an explicit ``SPEEDYCAT_OPENAI_KEY_FILE`` override, the speedrun repo
    root (this file lives at ``<root>/anki/pylib/anki/speedycat_ai.py``), and the
    current working directory — so it resolves whether Anki is run from source or
    the eval harness is run from the repo root.
    """
    candidates: list[Path] = []
    explicit = os.environ.get(ENV_KEY_FILE, "").strip()
    if explicit:
        candidates.append(Path(explicit))
    try:
        repo_root = Path(__file__).resolve().parents[3]
        candidates.append(repo_root / KEY_FILENAME)
    except IndexError:
        pass
    candidates.append(Path.cwd() / KEY_FILENAME)
    # De-duplicate while preserving order.
    seen: set[str] = set()
    unique: list[Path] = []
    for path in candidates:
        key = str(path)
        if key not in seen:
            seen.add(key)
            unique.append(path)
    return unique


def load_openai_key() -> str | None:
    """Return the OpenAI key from the env var or the gitignored file, else None.

    Precedence: ``SPEEDYCAT_OPENAI_API_KEY`` env var, then the first readable
    key file (see :func:`_candidate_key_files`). Whitespace is stripped; an
    empty value is treated as absent. Never raises — a missing/unreadable key
    simply means **AI is off**.
    """
    env_value = os.environ.get(ENV_KEY, "").strip()
    if env_value:
        return env_value
    for path in _candidate_key_files():
        try:
            if path.is_file():
                contents = path.read_text(encoding="utf-8").strip()
                if contents:
                    return contents
        except OSError:
            continue
    return None


def key_present() -> bool:
    """True when a non-empty OpenAI key is available (env var or file)."""
    return load_openai_key() is not None


# --- Structured-output contract ---------------------------------------------

#: Collection-config key for the user-facing AI on/off opt-in (default OFF).
AI_CONFIG_KEY = "speedycatAiChecker"

#: Delay before the "I don't know" affordance appears (project spec: 5 seconds).
IDK_DELAY_MS = 5000

#: Where a verdict came from (recorded by the eval harness + shown to the user).
SOURCE_AI = f"openai:{MODEL}"
SOURCE_BASELINE = "baseline:string-match+heuristic"
SOURCE_IDK = "user:i-dont-know"

#: Shown (instead of revealing the back) when the model judges the input is not
#: a genuine recall attempt.
HONESTY_PROMPT = (
    "That doesn't look like a genuine attempt to recall the answer. "
    "Give it your best honest try, then reveal."
)

#: Short note shown when the card is forced to "Again" (I don't know / gaming).
IDK_NOTE = "Marked \u201cAgain\u201d \u2014 you'll see this card again soon."
FORCED_AGAIN_NOTE = "Marked \u201cAgain\u201d for an honest re-try."

SYSTEM_INSTRUCTION = (
    "You are SpeedyCAT's answer checker for a spaced-repetition flashcard app "
    "used to study for the MCAT. The learner is forced to type an answer from "
    "memory (active recall) before the back of the card is revealed. You are "
    "given the flashcard FRONT (the prompt), the learner's TYPED answer, and the "
    "EXPECTED answer (the back of the card).\n"
    "Decide two things and return them as JSON:\n"
    "1. honest_attempt: true if the typed answer is a GENUINE, good-faith attempt "
    "to recall THIS card's answer (even if wrong or partial). Set it false only "
    "for clear non-attempts: blank/whitespace, random keyboard mashing, gibberish, "
    "a single unrelated character, filler like 'idk'/'i don't know'/'dunno', or "
    "text unrelated to the question that looks like gaming the reveal.\n"
    "2. verdict: 'correct' if the typed answer matches the expected answer in "
    "MEANING (ignore case, spacing, punctuation, word order, and reasonable "
    "synonyms/abbreviations), otherwise 'incorrect'. If honest_attempt is false, "
    "set verdict to 'incorrect'.\n"
    "Also give a brief (max ~15 words) reason. Answer with ONLY the JSON object."
)

#: Strict JSON schema (every property required, no extras) — guarantees a
#: parseable reply, mirroring brilliant-clone's strict structured output.
JSON_SCHEMA: dict[str, Any] = {
    "type": "object",
    "additionalProperties": False,
    "properties": {
        "honest_attempt": {
            "type": "boolean",
            "description": (
                "true if the typed answer is a genuine good-faith recall attempt; "
                "false for blank/gibberish/keyboard-mash/unrelated/gaming input."
            ),
        },
        "verdict": {
            "type": "string",
            "enum": ["correct", "incorrect"],
            "description": (
                "'correct' if the typed answer matches the expected answer in "
                "meaning (case/format/synonym insensitive), else 'incorrect'."
            ),
        },
        "reason": {
            "type": "string",
            "description": "Brief explanation of the judgement (max ~15 words).",
        },
    },
    "required": ["honest_attempt", "verdict", "reason"],
}


@dataclass(frozen=True)
class CheckerResult:
    """A parsed, validated verdict from the AI checker."""

    honest_attempt: bool
    verdict: str  # "correct" | "incorrect"
    reason: str

    @property
    def correct(self) -> bool:
        return self.verdict == "correct"


def build_check_prompt(front: str, typed: str, expected: str) -> str:
    """Build the user prompt from the flashcard FRONT, TYPED, and EXPECTED text."""
    return "\n".join(
        [
            f"FRONT (the prompt shown to the learner): {strip_front(front) or '(empty)'}",
            f"TYPED answer (what the learner recalled): {typed.strip() or '(blank)'}",
            f"EXPECTED answer (the back of the card): {strip_expected(expected) or '(empty)'}",
            "",
            "Return the JSON verdict now.",
        ]
    )


def build_request_body(
    front: str, typed: str, expected: str, *, model: str
) -> dict[str, Any]:
    """The ``responses.create`` payload (same shape brilliant-clone sends)."""
    return {
        "model": model,
        "instructions": SYSTEM_INSTRUCTION,
        "input": build_check_prompt(front, typed, expected),
        "max_output_tokens": MAX_OUTPUT_TOKENS,
        "reasoning": {"effort": REASONING_EFFORT},
        "text": {
            "format": {
                "type": "json_schema",
                "name": "speedycat_answer_check",
                "schema": JSON_SCHEMA,
                "strict": True,
            }
        },
    }


def _extract_output_text(response_json: dict[str, Any]) -> str:
    """Pull the assistant's text out of an OpenAI *responses* API reply.

    Prefers the ``output_text`` convenience field when present, otherwise
    concatenates every ``output_text`` content part from the ``output`` array
    (skipping reasoning items). Returns "" when nothing usable is found.
    """
    convenience = response_json.get("output_text")
    if isinstance(convenience, str) and convenience.strip():
        return convenience
    parts: list[str] = []
    output = response_json.get("output")
    if isinstance(output, list):
        for item in output:
            if not isinstance(item, dict):
                continue
            for part in item.get("content", []) or []:
                if (
                    isinstance(part, dict)
                    and part.get("type") == "output_text"
                    and isinstance(part.get("text"), str)
                ):
                    parts.append(part["text"])
    return "".join(parts)


def parse_checker_response(raw_text: str) -> CheckerResult | None:
    """Parse the model's JSON reply into a :class:`CheckerResult`, or None.

    Returns None for anything that isn't a well-formed object with a boolean
    ``honest_attempt`` and a ``verdict`` of ``correct``/``incorrect`` — so a
    malformed reply cleanly triggers the deterministic fallback.
    """
    try:
        parsed = json.loads(raw_text)
    except (ValueError, TypeError):
        return None
    if not isinstance(parsed, dict):
        return None
    honest = parsed.get("honest_attempt")
    verdict = parsed.get("verdict")
    if not isinstance(honest, bool):
        return None
    if verdict not in ("correct", "incorrect"):
        return None
    reason = parsed.get("reason")
    reason_text = reason.strip() if isinstance(reason, str) else ""
    # A dishonest attempt is never scored correct, regardless of the raw verdict.
    if not honest:
        verdict = "incorrect"
    return CheckerResult(honest_attempt=honest, verdict=verdict, reason=reason_text)


def run_check(
    front: str,
    typed: str,
    expected: str,
    *,
    key: str | None = None,
    model: str = MODEL,
    timeout: int = REQUEST_TIMEOUT,
) -> CheckerResult | None:
    """Call the OpenAI *responses* API and return a :class:`CheckerResult`.

    Returns None on ANY problem (no key, network/HTTP error, unparseable reply)
    so callers fall back to the deterministic path. ``requests`` is imported
    lazily to keep the rest of the module dependency-free.
    """
    api_key = key if key is not None else load_openai_key()
    if not api_key:
        return None
    try:
        import requests

        response = requests.post(
            OPENAI_RESPONSES_URL,
            headers={
                "Authorization": f"Bearer {api_key}",
                "Content-Type": "application/json",
            },
            data=json.dumps(build_request_body(front, typed, expected, model=model)),
            timeout=timeout,
        )
        response.raise_for_status()
        payload = response.json()
    except Exception:
        return None
    return parse_checker_response(_extract_output_text(payload))


# --- Deterministic (AI-off) fallbacks ---------------------------------------

_AV_MEDIA_RE = re.compile(r"\[(?:sound|anki):[^\]]*\]")
_HTML_TAG_RE = re.compile(r"<[^>]+>")
_WHITESPACE_RE = re.compile(r"\s+")

# SpeedyCAT: the bundled cloze notetype bakes extras into the RENDERED field that
# must never pollute the expected answer / prompt / displayed lines — a `<style>`
# CSS block, a leading ``Subject::Subtopic`` tag breadcrumb, and a trailing
# source footer (rendered as an ``<a>…Khan Academy Link</a>`` element). These are
# stripped deterministically; the real expected answer comes from the card's
# cloze deletion (see the reviewer), not the rendered field.
_STYLE_RE = re.compile(r"<style[^>]*>.*?</style>", re.DOTALL | re.IGNORECASE)
_ANCHOR_RE = re.compile(r"<a\b[^>]*>.*?</a>", re.DOTALL | re.IGNORECASE)
_BREADCRUMB_RE = re.compile(r"^\s*\w+(?:::\w+)+\s*")
# A trailing "…Link" source footer (belt-and-suspenders behind the anchor strip):
# a short run of capitalised source words ending in "Link" (e.g. "Khan Academy
# Link"). Capitalisation keeps it from eating ordinary trailing prose.
_FOOTER_RE = re.compile(r"\s*(?:[A-Z][A-Za-z0-9.'&-]*\s+){1,3}Link\s*$")
# Separator punctuation treated as a single space so multi-cloze answers match
# regardless of the user's separator (", " / "; " / " : " / " / " / "|").
_SEPARATOR_RE = re.compile(r"[,;:/|\\]+")

#: Small set of unmistakable keyboard-mash / filler tokens. Kept deliberately
#: tiny and obvious so the heuristic never punishes a real (if short) answer.
_MASH_TOKENS = frozenset(
    {
        "asdf",
        "asdf",
        "asdfg",
        "asdfgh",
        "asdfghjkl",
        "asdfjkl",
        "qwer",
        "qwert",
        "qwerty",
        "qwertyuiop",
        "zxcv",
        "zxcvbn",
        "zxcvbnm",
        "jkl",
        "hjkl",
        "fjfj",
        "jfjf",
        "sdf",
        "lkj",
        "lkjh",
        "idk",
        "dunno",
        "idfk",
        "nfi",
    }
)


def strip_display(text: str | None) -> str:
    """Strip AV/media refs + HTML tags and collapse whitespace (case preserved).

    Used for building the prompt and for the labelled "Expected" display; it
    mirrors the normalization the deterministic matcher does, minus lower-casing.
    """
    if not text:
        return ""
    stripped = _AV_MEDIA_RE.sub(" ", text)
    stripped = _HTML_TAG_RE.sub(" ", stripped)
    stripped = html.unescape(stripped)
    return _WHITESPACE_RE.sub(" ", stripped).strip()


def _strip_field(text: str | None, *, strip_footer: bool) -> str:
    """Deterministically clean a rendered field: drop the ``<style>`` block and
    any source-link ``<a>`` element, strip AV/media + all HTML tags, unescape
    entities, collapse whitespace, then remove a leading ``Subject::Subtopic``
    breadcrumb (and, when ``strip_footer``, a trailing ``…Link`` footer)."""
    if not text:
        return ""
    t = _STYLE_RE.sub(" ", text)
    t = _ANCHOR_RE.sub(" ", t)
    t = _AV_MEDIA_RE.sub(" ", t)
    t = _HTML_TAG_RE.sub(" ", t)
    t = html.unescape(t)
    t = _WHITESPACE_RE.sub(" ", t).strip()
    t = _BREADCRUMB_RE.sub("", t).strip()
    if strip_footer:
        t = _FOOTER_RE.sub("", t).strip()
    return t


def strip_expected(text: str | None) -> str:
    """Clean an expected answer before it is used (prompt + fallback) or shown
    (the expected-answer line): strips a ``<style>`` block, the source/``…Link``
    footer, all HTML, a leading ``Subject::Subtopic`` breadcrumb, and a trailing
    ``…Link`` footer. A bare cloze deletion (e.g. ``direction``) is unchanged."""
    return _strip_field(text, strip_footer=True)


def strip_front(text: str | None) -> str:
    """Clean the question/front for display and the AI prompt: strips a
    ``<style>`` block, all HTML, and the leading ``Subject::Subtopic`` breadcrumb
    (the cloze ``[...]`` blank is preserved)."""
    return _strip_field(text, strip_footer=False)


def _normalize_for_match(text: str) -> str:
    """Normalize for comparison: strip media/HTML, treat separator punctuation
    (``, ; : / |``) as whitespace so multi-cloze answers match regardless of the
    learner's separator, collapse whitespace, NFC, lower-case."""
    separated = _SEPARATOR_RE.sub(" ", strip_display(text))
    collapsed = _WHITESPACE_RE.sub(" ", separated).strip()
    return unicodedata.normalize("NFC", collapsed).lower()


def deterministic_correct(typed: str, expected: str) -> bool:
    """Case-insensitive whole-answer string match (the AI-off verdict + baseline).

    An empty typed answer never matches — mirroring
    ``Reviewer._fallback_answer_match`` and ``ForcedRecall.matches`` so the
    baseline and the live reviewer agree.
    """
    normalized_typed = _normalize_for_match(typed)
    if not normalized_typed:
        return False
    return _normalize_for_match(expected) == normalized_typed


def heuristic_is_honest_attempt(typed: str) -> bool:
    """Conservative AI-off "genuine attempt?" gate that drives the FSRS lock.

    Returns False only for clear non-attempts — blank/whitespace, punctuation- or
    symbol-only input, a single character repeated, or an obvious
    keyboard-mash/filler token — and True otherwise. It is intentionally
    permissive so a real (even short or misspelled) answer is never punished.
    """
    collapsed = _WHITESPACE_RE.sub(" ", (typed or "")).strip()
    if not collapsed:
        return False
    # Only punctuation / symbols (no letters or digits) -> not an attempt.
    if not any(ch.isalnum() for ch in collapsed):
        return False
    compact = collapsed.replace(" ", "").lower()
    # A single character repeated (e.g. "aaaa", "....", "-----").
    if len(compact) >= 2 and len(set(compact)) == 1:
        return False
    # Unmistakable keyboard-mash / filler tokens.
    if compact in _MASH_TOKENS:
        return False
    return True


# --- Shared decision logic (reviewer + eval consume this) -------------------


@dataclass(frozen=True)
class Decision:
    """What the reviewer should do after a reveal / "I don't know" action.

    * ``reveal``       - show the back of the card.
    * ``verdict``      - "correct"/"incorrect" to badge, or None (no grade).
    * ``force_again``  - auto-send the *Again* rating to the scheduler.
    * ``lock_ratings`` - hide/disable Hard/Good/Easy (forced to *Again*).
    * ``message``      - honesty prompt / forced-Again note to show, or None.
    * ``honest``       - whether the input was judged a genuine attempt.
    * ``source``       - the named source of the judgement (model id / baseline).
    * ``reason``       - the model's short reason (empty for the baseline).
    """

    reveal: bool
    verdict: str | None
    force_again: bool
    lock_ratings: bool
    message: str | None
    honest: bool
    source: str
    reason: str = ""


def decide_idk() -> Decision:
    """Decision for the "I don't know" button: reveal, but force *Again* + lock."""
    return Decision(
        reveal=True,
        verdict=None,
        force_again=True,
        lock_ratings=True,
        message=IDK_NOTE,
        honest=False,
        source=SOURCE_IDK,
    )


def decide_reveal(
    typed: str,
    expected: str,
    *,
    ai_on: bool,
    ai_result: CheckerResult | None,
) -> Decision:
    """Decision when the learner submits a typed answer to reveal the card.

    AI ON with a usable result:
      * dishonest -> DO NOT reveal; show the honesty prompt; force *Again* + lock.
      * honest    -> reveal; badge the model's verdict; keep normal ratings.

    AI OFF (or the call failed / returned nothing) -> deterministic fallback:
      * ALWAYS reveal with the case-insensitive string-match verdict (as today);
      * the conservative heuristic still forces *Again* + lock on a clear
        non-attempt, so anti-gaming works without the model.
    """
    if ai_on and ai_result is not None:
        if not ai_result.honest_attempt:
            return Decision(
                reveal=False,
                verdict=None,
                force_again=True,
                lock_ratings=True,
                message=HONESTY_PROMPT,
                honest=False,
                source=SOURCE_AI,
                reason=ai_result.reason,
            )
        return Decision(
            reveal=True,
            verdict=ai_result.verdict,
            force_again=False,
            lock_ratings=False,
            message=None,
            honest=True,
            source=SOURCE_AI,
            reason=ai_result.reason,
        )
    honest = heuristic_is_honest_attempt(typed)
    return Decision(
        reveal=True,
        verdict="correct" if deterministic_correct(typed, expected) else "incorrect",
        force_again=not honest,
        lock_ratings=not honest,
        message=None if honest else FORCED_AGAIN_NOTE,
        honest=honest,
        source=SOURCE_BASELINE,
    )
