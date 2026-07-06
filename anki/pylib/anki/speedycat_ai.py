# Copyright: Ankitects Pty Ltd and contributors
# License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

"""SpeedyCAT: AI answer checker for forced-active-recall flashcards (desktop).

This module is the **pure, Qt-free** core of SpeedyCAT's AI answer checker. It
owns everything that can be reasoned about (and unit-tested) without a running
reviewer or a network connection:

* loading the OpenAI key (env var or a gitignored local file) — never hardcoded;
* calling the SpeedyCAT cloud proxy first, then a local OpenAI key when the proxy
  is unavailable (fresh installs use the proxy by default);
* the model id (the *named source* every AI verdict traces to), the prompt, and
  the strict ``json_schema`` structured-output contract;
* parsing / validating the model's JSON reply into a :class:`CheckerResult`;
* the deterministic **AI-off** fallbacks — a case-insensitive string match
  (verdict) and a conservative "is this a genuine attempt?" heuristic (used to
  drive the FSRS *Again* lock without the model);
* the shared **decision** logic (:func:`decide_reveal` / :func:`decide_idk` /
  :func:`plan_reveal`) that both the desktop reviewer and the offline eval harness
  consume.

The impure helpers :func:`run_check` / :func:`run_check_via_proxy` perform the
HTTPS calls **only after** :func:`plan_reveal` finds a static *incorrect*
verdict. :func:`run_check` tries the SpeedyCAT cloud proxy first, then the
OpenAI *responses* API with a local key (same model + schema as
``functions/src/speedycatChecker.ts``). ``requests`` is imported lazily so the rest of
the module — and therefore the eval harness and the unit tests — import with
only the stdlib.

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

# --- Named source / provider settings ---------------------------------------

#: The model id. Every AI verdict traces to this *named source*; it is shown to
#: the learner (labelled model-generated) and recorded by the eval harness.
MODEL = "gpt-5.4-mini"

#: OpenAI *responses* endpoint (same API surface the cloud proxy calls via SDK).
OPENAI_RESPONSES_URL = "https://api.openai.com/v1/responses"

#: Bounded output budget: the reply is a tiny JSON object, but low-effort
#: reasoning shares this budget, so keep enough headroom that it can't starve
#: the message (same rationale as the cloud proxy's MAX_OUTPUT_TOKENS).
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

# --- Cloud proxy (fresh installs; key stays server-side) ---------------------

#: Default proxy URL (SpeedyCAT Firebase project ``speedycat-mcat``, us-central1).
#: Override with ``SPEEDYCAT_AI_PROXY_URL`` for staging/emulator; set to empty to disable.
#: Deploy steps: ``docs/speedycat-ai-proxy.md``.
DEFAULT_PROXY_URL = (
    "https://us-central1-speedycat-mcat.cloudfunctions.net/checkSpeedycatAnswer"
)

#: Environment variable overriding :data:`DEFAULT_PROXY_URL`.
ENV_PROXY_URL = "SPEEDYCAT_AI_PROXY_URL"


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


def resolve_proxy_url() -> str | None:
    """Return the configured cloud-proxy URL, or None when proxy is disabled.

    Precedence: ``SPEEDYCAT_AI_PROXY_URL`` env var (empty string disables),
    then :data:`DEFAULT_PROXY_URL`. Whitespace-only values are treated as absent.
    """
    env_value = os.environ.get(ENV_PROXY_URL)
    if env_value is not None:
        stripped = env_value.strip()
        return stripped if stripped else None
    return DEFAULT_PROXY_URL or None


def key_present() -> bool:
    """True when a non-empty local OpenAI key is available (env var or file)."""
    return load_openai_key() is not None


def ai_checker_available() -> bool:
    """True when AI checking can run: a local key OR the cloud proxy is configured."""
    return key_present() or resolve_proxy_url() is not None


# --- Structured-output contract ---------------------------------------------

#: Collection-config key for the user-facing AI on/off toggle.
AI_CONFIG_KEY = "speedycatAiChecker"
#: Default when the key is absent from collection config (AI on for fresh installs).
AI_CONFIG_DEFAULT = True

#: Delay before the "I don't know" affordance appears (base; escalates with bypasses).
IDK_DELAY_MS = 5000
IDK_DELAYS_MS = (5000, 10_000, 15_000)

#: Anti-gaming thresholds (mirrors rslib speedycat::gaming).
DAILY_GAMED_RATE = 0.10
MEMORY_SUPPRESSION_MSG = (
    "Memory Score Unavailable: Excessive guessing detected. "
    "Focus on genuine retrieval practice to restore your score."
)

#: Where a verdict came from (recorded by the eval harness + shown to the user).
SOURCE_AI = f"openai:{MODEL}"
SOURCE_AI_PROXY = f"openai/{MODEL} via speedycat-proxy"
SOURCE_BASELINE = "baseline:string-match+heuristic"
SOURCE_IDK = "user:i-dont-know"


def is_ai_source(source: str) -> bool:
    """True when *source* names a model-generated verdict (direct or via proxy)."""
    return source in (SOURCE_AI, SOURCE_AI_PROXY)

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
    "MEANING (ignore case, spacing, punctuation, word order, reasonable "
    "synonyms/abbreviations, and minor misspellings/typos that preserve the "
    "intended meaning), otherwise 'incorrect'. If honest_attempt is false, "
    "set verdict to 'incorrect'.\n"
    "Also give a brief (max ~15 words) reason. Answer with ONLY the JSON object."
)

#: Strict JSON schema (every property required, no extras) — guarantees a
#: parseable reply, mirroring the cloud proxy's strict structured output.
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
                "meaning (case/format/synonym/typo insensitive), else 'incorrect'."
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
    """The ``responses.create`` payload (same shape the cloud proxy sends)."""
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


def run_check_via_proxy(
    front: str,
    typed: str,
    expected: str,
    *,
    proxy_url: str | None = None,
    timeout: int = REQUEST_TIMEOUT,
) -> CheckerResult | None:
    """Call the SpeedyCAT cloud proxy and return a :class:`CheckerResult`.

    Returns None on ANY problem (no proxy URL, network/HTTP error, unparseable
    reply) so callers fall back to the deterministic path.
    """
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
                    "front": strip_front(front),
                    "typed": typed,
                    "expected": strip_expected(expected),
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
    honest = payload.get("honest_attempt")
    verdict = payload.get("verdict")
    if not isinstance(honest, bool):
        return None
    if verdict not in ("correct", "incorrect"):
        return None
    reason = payload.get("reason")
    reason_text = reason.strip() if isinstance(reason, str) else ""
    if not honest:
        verdict = "incorrect"
    return CheckerResult(honest_attempt=honest, verdict=verdict, reason=reason_text)


def run_check_direct(
    front: str,
    typed: str,
    expected: str,
    *,
    key: str,
    model: str = MODEL,
    timeout: int = REQUEST_TIMEOUT,
) -> CheckerResult | None:
    """Call the OpenAI *responses* API directly with a local key."""
    try:
        import requests

        response = requests.post(
            OPENAI_RESPONSES_URL,
            headers={
                "Authorization": f"Bearer {key}",
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


def run_check(
    front: str,
    typed: str,
    expected: str,
    *,
    key: str | None = None,
    model: str = MODEL,
    timeout: int = REQUEST_TIMEOUT,
) -> tuple[CheckerResult | None, str]:
    """Run the AI answer check and return ``(result, source)``.

    Precedence: cloud proxy > local key (direct OpenAI) > unavailable.
    ``source`` is :data:`SOURCE_AI_PROXY`, :data:`SOURCE_AI`, or
    :data:`SOURCE_BASELINE` when nothing could be checked. Returns
    ``(None, SOURCE_BASELINE)`` on failure so callers fall back to the
    deterministic path.
    """
    result = run_check_via_proxy(front, typed, expected, timeout=timeout)
    if result is not None:
        return result, SOURCE_AI_PROXY
    api_key = key if key is not None else load_openai_key()
    if api_key:
        result = run_check_direct(front, typed, expected, key=api_key, model=model, timeout=timeout)
        return result, SOURCE_AI if result is not None else SOURCE_BASELINE
    return None, SOURCE_BASELINE


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

# Cloze deletion markers in note fields (`{{c1::answer}}` / `{{c1::answer::hint}}`).
_CLOZE_MARKER_RE = re.compile(r"\{\{c(\d+)::(.+?)\}\}", re.DOTALL)
# Rendered active cloze on the answer side (`<span class="cloze" data-ordinal="1">…</span>`).
_CLOZE_SPAN_RE = re.compile(
    r'<span\b[^>]*\bclass="[^"]*\bcloze\b[^"]*"[^>]*\bdata-ordinal="([^"]*)"[^>]*>(.*?)</span>',
    re.DOTALL | re.IGNORECASE,
)
# Placeholders baked into pre-rendered SpeedyCAT Front/Back fields (`[...]`, `___`, hints).
_BLANK_PLACEHOLDER_RE = re.compile(r"\[\.\.\.\]|\[[^\]]{1,40}\]|_{3,}|…+")

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


def _cloze_body_without_hint(body: str) -> str:
    hint = body.find("::")
    return body[:hint] if hint > -1 else body


def _ordinal_matches(data_ordinal: str, ordinal: int) -> bool:
    return str(ordinal) in (part.strip() for part in data_ordinal.split(","))


def _is_cloze_placeholder(text: str) -> bool:
    """True when *text* is a front-side blank hint, not the real deletion."""
    t = text.strip()
    if not t or t in ("...", "[...]"):
        return True
    if _BLANK_PLACEHOLDER_RE.fullmatch(t):
        return True
    # Rendered front blanks often show "[...] remainder" where only the blank is hidden.
    if t.startswith("[...]"):
        return True
    if re.fullmatch(r"_+", t):
        return True
    return False


def extract_cloze_answer(text: str | None, ordinal: int) -> str | None:
    """Return the cloze deletion text(s) for *ordinal* from a note field or rendered HTML.

    Handles ``{{cN::answer}}`` / ``{{cN::answer::hint}}`` markers and answer-side
    ``<span class="cloze" data-ordinal="N">…</span>`` elements. Multiple deletions
    for the same ordinal are joined with ``, `` (order preserved); identical deletions
    collapse to one — mirroring Anki's ``extract_cloze_for_typing`` / mobile
    ``clozeAnswerForOrd``. Returns ``None`` when nothing matches."""
    if not text or ordinal < 1:
        return None
    parts: list[str] = []
    for match in _CLOZE_MARKER_RE.finditer(text):
        if int(match.group(1)) == ordinal:
            parts.append(_cloze_body_without_hint(match.group(2)))
    for match in _CLOZE_SPAN_RE.finditer(text):
        if not _ordinal_matches(match.group(1), ordinal):
            continue
        inner = strip_display(match.group(2))
        if inner and not _is_cloze_placeholder(inner):
            parts.append(inner)
    if not parts:
        return None
    if len(set(parts)) == 1:
        return parts[0]
    return ", ".join(parts)


def extract_cloze_from_prerendered(
    front: str | None, back: str | None, ordinal: int = 1
) -> str | None:
    """Derive the cloze answer from a pre-rendered Front/Back field pair.

    SpeedyCAT's bundled import stores rendered HTML in ``Front`` / ``Back`` without
    ``{{cN::}}`` markers — the front carries a ``[...]`` / ``___`` blank and the back
    carries the filled sentence. Align prefix/suffix around the blank to isolate the
    deletion text only (e.g. ``rotational equilibrium``, not the whole back field)."""
    if not front or not back or ordinal < 1:
        return None
    front_plain = strip_front(front)
    back_plain = strip_expected(back)
    blanks = list(_BLANK_PLACEHOLDER_RE.finditer(front_plain))
    if not blanks:
        return None
    blank = blanks[min(ordinal - 1, len(blanks) - 1)]
    prefix = front_plain[: blank.start()]
    suffix = front_plain[blank.end() :]
    if suffix and back_plain.startswith(prefix) and back_plain.endswith(suffix):
        middle = back_plain[len(prefix) : len(back_plain) - len(suffix)].strip()
        return middle or None
    if not suffix and back_plain.startswith(prefix):
        middle = back_plain[len(prefix) :].strip()
        return middle or None
    if suffix:
        suffix_at = back_plain.find(suffix)
        if suffix_at >= len(prefix):
            middle = back_plain[len(prefix) : suffix_at].strip()
            return middle or None
    return None


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


@dataclass(frozen=True)
class RevealPlan:
    """Static-first gate before any AI call.

    * ``decision`` is set when the answer can be decided without AI (static
      correct, or AI off / unavailable).
    * ``needs_ai`` is True only when the static checker said *incorrect* and AI
      checking is enabled — callers must invoke :func:`run_check` and then
      :func:`decide_reveal`.
    """

    decision: Decision | None
    needs_ai: bool


def plan_reveal(typed: str, expected: str, *, ai_on: bool) -> RevealPlan:
    """Static-first reveal plan: skip AI when the deterministic match is correct.

    1. Static correct -> immediate correct verdict, ``needs_ai=False``.
    2. Static incorrect + AI off -> deterministic fallback, ``needs_ai=False``.
    3. Static incorrect + AI on -> ``needs_ai=True`` (caller runs :func:`run_check`).
    """
    if deterministic_correct(typed, expected):
        return RevealPlan(
            decision=Decision(
                reveal=True,
                verdict="correct",
                force_again=False,
                lock_ratings=False,
                message=None,
                honest=True,
                source=SOURCE_BASELINE,
            ),
            needs_ai=False,
        )
    if not ai_on:
        return RevealPlan(
            decision=decide_reveal(typed, expected, ai_on=False, ai_result=None),
            needs_ai=False,
        )
    return RevealPlan(decision=None, needs_ai=True)


def decide_reveal(
    typed: str,
    expected: str,
    *,
    ai_on: bool,
    ai_result: CheckerResult | None,
    ai_source: str = SOURCE_AI,
) -> Decision:
    """Decision when the learner submits a typed answer to reveal the card.

    Callers should use :func:`plan_reveal` first so AI runs only after a static
    *incorrect* verdict. This function applies the AI result (or the full
    deterministic fallback when ``ai_on`` is False or ``ai_result`` is None).

    AI ON with a usable result (static incorrect path):
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
                source=ai_source,
                reason=ai_result.reason,
            )
        return Decision(
            reveal=True,
            verdict=ai_result.verdict,
            force_again=False,
            lock_ratings=False,
            message=None,
            honest=True,
            source=ai_source,
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


def idk_delay_ms(idk_bypass_count: int = 0) -> int:
    """Escalating wait gate before the 'I don't know' affordance (5s → 10s → 15s)."""
    idx = min(idk_bypass_count, len(IDK_DELAYS_MS) - 1)
    return IDK_DELAYS_MS[idx]


def is_gamed_decision(decision: Decision) -> bool:
    """True when the decision forces Again due to gaming or I don't know."""
    return decision.force_again


def is_idk_decision(decision: Decision) -> bool:
    return decision.source == SOURCE_IDK
