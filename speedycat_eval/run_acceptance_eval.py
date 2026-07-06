#!/usr/bin/env python3
# Copyright: Ankitects Pty Ltd and contributors
# License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

"""Measure how many distinct alternative answers the AI checker accepts per card.

Picks flashcards from ``acceptance_cards.json``, generates plausible variants
(synonyms, paraphrases, misspellings, abbreviations, extra words — not case-only
changes), and calls the live checker (cloud proxy or local key). Reports per-card
and aggregate stats to stdout and ``ACCEPTANCE_RESULTS.md``.

    python speedycat_eval/run_acceptance_eval.py
    python speedycat_eval/run_acceptance_eval.py --no-write   # stdout only
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
import unicodedata
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent
CARDS_PATH = HERE / "acceptance_cards.json"
RESULTS_PATH = HERE / "ACCEPTANCE_RESULTS.md"

# Reuse the shared checker loader from the main eval harness.
sys.path.insert(0, str(HERE))
from run_eval import load_checker  # noqa: E402

_WHITESPACE_RE = re.compile(r"\s+")


def normalize_answer(text: str) -> str:
    """Case-insensitive key for deduplicating accepted alternatives."""
    collapsed = _WHITESPACE_RE.sub(" ", (text or "").strip())
    return unicodedata.normalize("NFC", collapsed).lower()


def is_case_only_variant(a: str, b: str) -> bool:
    return normalize_answer(a) == normalize_answer(b) and a.strip() != b.strip()


# --- Variant generation -------------------------------------------------------

# Curated extras per card id (synonyms, paraphrases, plausible typos, expansions).
CURATED: dict[str, list[tuple[str, str]]] = {
    "mitochondria": [
        ("synonym", "mitochondrion"),
        ("synonym", "mitochondria organelle"),
        ("paraphrase", "powerhouse of the cell"),
        ("paraphrase", "cell powerhouse"),
        ("extra_words", "the mitochondria"),
        ("extra_words", "mitochondria - powerhouse of the cell"),
        ("typo", "mitochondira"),
        ("typo", "mitocondria"),
        ("typo", "mitochondrea"),
        ("typo", "mitchondria"),
        ("typo", "mitocondrea"),
        ("wrong", "chloroplast"),
        ("wrong", "nucleus"),
        ("wrong", "ribosome"),
    ],
    "na_k_pump": [
        ("synonym", "Na+/K+ ATPase"),
        ("synonym", "sodium potassium pump"),
        ("synonym", "Na-K pump"),
        ("synonym", "sodium/potassium pump"),
        ("abbrev", "Na/K ATPase"),
        ("typo", "sodum-potassium pump"),
        ("typo", "sodium potasium pump"),
        ("typo", "sodium-potassium pmp"),
        ("wrong", "calcium pump"),
        ("wrong", "proton pump"),
    ],
    "depolarization": [
        ("synonym", "goes up"),
        ("synonym", "rises"),
        ("synonym", "becomes more positive"),
        ("synonym", "less negative"),
        ("paraphrase", "membrane potential increases"),
        ("paraphrase", "gets more positive"),
        ("typo", "increaes"),
        ("typo", "incrases"),
        ("typo", "increse"),
        ("wrong", "decreases"),
        ("wrong", "goes down"),
        ("wrong", "hyperpolarizes"),
    ],
    "atp": [
        ("abbrev", "ATP"),
        ("synonym", "adenosine triphosphate molecule"),
        ("extra_words", "ATP (adenosine triphosphate)"),
        ("typo", "adenosine triphospate"),
        ("typo", "adenosene triphosphate"),
        ("typo", "adenosine triphosphat"),
        ("wrong", "ADP"),
        ("wrong", "NADH"),
        ("wrong", "glucose"),
    ],
    "krebs": [
        ("synonym", "citric acid cycle"),
        ("synonym", "TCA cycle"),
        ("synonym", "tricarboxylic acid cycle"),
        ("abbrev", "CAC"),
        ("extra_words", "the citric acid cycle"),
        ("typo", "citric acid cyle"),
        ("typo", "citrc acid cycle"),
        ("typo", "krebs cycle"),
        ("wrong", "glycolysis"),
        ("wrong", "electron transport chain"),
    ],
    "nephron": [
        ("synonym", "renal corpuscle and tubule"),
        ("extra_words", "the nephron"),
        ("extra_words", "a nephron"),
        ("typo", "nephronn"),
        ("typo", "neprhon"),
        ("typo", "nephron unit"),
        ("wrong", "glomerulus only"),
        ("wrong", "ureter"),
        ("wrong", "collecting duct"),
    ],
}


def _adjacent_swap(word: str, i: int) -> str | None:
    if i < 0 or i >= len(word) - 1:
        return None
    chars = list(word)
    chars[i], chars[i + 1] = chars[i + 1], chars[i]
    return "".join(chars)


def _drop_char(word: str, i: int) -> str | None:
    if i < 0 or i >= len(word):
        return None
    return word[:i] + word[i + 1 :]


def _double_char(word: str, i: int) -> str | None:
    if i < 0 or i >= len(word):
        return None
    return word[: i + 1] + word[i] + word[i + 1 :]


def programmatic_typos(expected: str, *, max_per_word: int = 3) -> list[tuple[str, str]]:
    """Lightweight typo generator for each word in the expected answer."""
    out: list[tuple[str, str]] = []
    words = expected.split()
    for wi, word in enumerate(words):
        if len(word) < 4:
            continue
        candidates: list[str] = []
        for i in range(min(len(word) - 1, max_per_word)):
            for fn in (_adjacent_swap, _drop_char):
                variant_word = fn(word, i)
                if variant_word and variant_word != word:
                    new_words = words.copy()
                    new_words[wi] = variant_word
                    candidates.append(" ".join(new_words))
        if wi == 0 and len(word) >= 5:
            doubled = _double_char(word, len(word) // 2)
            if doubled:
                new_words = words.copy()
                new_words[wi] = doubled
                candidates.append(" ".join(new_words))
        for c in candidates:
            out.append(("typo_auto", c))
    return out


def punctuation_variants(expected: str) -> list[tuple[str, str]]:
    """Spacing/punctuation tweaks (not case)."""
    variants: list[tuple[str, str]] = []
    if "-" in expected:
        variants.append(("punct", expected.replace("-", " ")))
    if "/" in expected:
        variants.append(("punct", expected.replace("/", " ")))
    if "(" in expected:
        variants.append(("punct", re.sub(r"\s*\([^)]*\)", "", expected).strip()))
    extra = re.sub(r"\s+", "  ", expected.strip())
    if extra != expected:
        variants.append(("punct", extra))
    return variants


@dataclass
class Variant:
    text: str
    kind: str


@dataclass
class CardResult:
    card_id: str
    front: str
    expected: str
    variants_tested: int = 0
    accepted: list[Variant] = field(default_factory=list)
    rejected: list[Variant] = field(default_factory=list)
    errors: int = 0
    source: str = ""

    @property
    def distinct_accepted(self) -> int:
        seen: set[str] = set()
        count = 0
        expected_norm = normalize_answer(self.expected)
        for v in self.accepted:
            norm = normalize_answer(v.text)
            if norm == expected_norm:
                continue  # exact match (case-insensitive) — not an "alternative"
            if norm in seen:
                continue
            seen.add(norm)
            count += 1
        return count

    def accepted_alternatives(self) -> list[Variant]:
        """Accepted variants excluding case-only changes vs expected."""
        expected_norm = normalize_answer(self.expected)
        seen: set[str] = set()
        out: list[Variant] = []
        for v in self.accepted:
            norm = normalize_answer(v.text)
            if norm == expected_norm:
                continue
            if norm in seen:
                continue
            seen.add(norm)
            out.append(v)
        return out


def build_variants(card_id: str, expected: str) -> list[Variant]:
    raw: list[tuple[str, str]] = []
    raw.extend(CURATED.get(card_id, []))
    raw.extend(programmatic_typos(expected))
    raw.extend(punctuation_variants(expected))

    # Deduplicate by normalized text; skip case-only duplicates of expected.
    seen: set[str] = set()
    expected_norm = normalize_answer(expected)
    variants: list[Variant] = []
    for kind, text in raw:
        norm = normalize_answer(text)
        if not norm or norm == expected_norm:
            continue
        if norm in seen:
            continue
        seen.add(norm)
        variants.append(Variant(text=text, kind=kind))
    return variants


def run_card(
    ai: Any,
    card: dict[str, str],
    *,
    delay_s: float,
) -> CardResult:
    card_id = card["id"]
    front = card["front"]
    expected = card["expected"]
    result = CardResult(card_id=card_id, front=front, expected=expected)
    variants = build_variants(card_id, expected)

    for variant in variants:
        check_result, source = ai.run_check(front, variant.text, expected)
        result.variants_tested += 1
        if not result.source and source != ai.SOURCE_BASELINE:
            result.source = source
        if check_result is None:
            result.errors += 1
            # Failed call falls back to deterministic in the app; record that.
            accepted = ai.deterministic_correct(variant.text, expected)
        else:
            accepted = check_result.correct and check_result.honest_attempt
        if accepted:
            result.accepted.append(variant)
        else:
            result.rejected.append(variant)
        if delay_s > 0:
            time.sleep(delay_s)

    return result


def aggregate(results: list[CardResult]) -> dict[str, Any]:
    total_variants = sum(r.variants_tested for r in results)
    total_accepted_calls = sum(len(r.accepted) for r in results)
    total_distinct = sum(r.distinct_accepted for r in results)
    total_errors = sum(r.errors for r in results)
    wrong_kinds = ("wrong",)
    wrong_tested = sum(
        1
        for r in results
        for v in r.accepted + r.rejected
        if v.kind in wrong_kinds
    )
    wrong_accepted = sum(
        1 for r in results for v in r.accepted if v.kind in wrong_kinds
    )
    return {
        "cards": len(results),
        "variants_tested": total_variants,
        "accepted_calls": total_accepted_calls,
        "distinct_alternatives": total_distinct,
        "acceptance_rate": total_accepted_calls / total_variants if total_variants else 0,
        "distinct_per_card_mean": total_distinct / len(results) if results else 0,
        "errors": total_errors,
        "wrong_controls_tested": wrong_tested,
        "wrong_controls_accepted": wrong_accepted,
    }


def render_markdown(ai: Any, results: list[CardResult], agg: dict[str, Any]) -> str:
    lines: list[str] = [
        "# SpeedyCAT AI checker — alternative-answer acceptance eval",
        "",
        "How many **distinct** alternative answers (excluding case-only changes) does the",
        "AI checker accept for the same flashcard question?",
        "",
        "## Setup",
        "",
        f"- **Named source:** `{ai.SOURCE_AI_PROXY}` or `{ai.SOURCE_AI}` (via `run_check`)",
        f"- **Cards:** {agg['cards']} flashcards from `acceptance_cards.json`",
        f"- **Variants generated:** synonyms, paraphrases, abbreviations, typos,",
        "  punctuation/spacing tweaks, and wrong-answer controls",
        f"- **Variants tested:** {agg['variants_tested']} (case-only duplicates excluded)",
        "",
        "## Aggregate stats",
        "",
        "| Metric | Value |",
        "| --- | --- |",
        f"| Variants tested | {agg['variants_tested']} |",
        f"| Accepted (any call) | {agg['accepted_calls']} ({100 * agg['acceptance_rate']:.1f}%) |",
        f"| **Distinct alternatives accepted** (excl. case) | **{agg['distinct_alternatives']}** |",
        f"| Mean distinct alternatives per card | {agg['distinct_per_card_mean']:.1f} |",
        f"| API errors (fell back to baseline) | {agg['errors']} |",
        f"| Wrong-answer controls tested | {agg['wrong_controls_tested']} |",
        f"| Wrong-answer controls incorrectly accepted | {agg['wrong_controls_accepted']} |",
        "",
        "## Per-card results",
        "",
    ]

    for r in results:
        alts = r.accepted_alternatives()
        lines.extend(
            [
                f"### {r.card_id}",
                "",
                f"- **Front:** {r.front}",
                f"- **Expected:** {r.expected}",
                f"- Variants tested: {r.variants_tested}",
                f"- **Distinct alternatives accepted:** {r.distinct_accepted}",
                f"- Accepted (all calls): {len(r.accepted)}",
                "",
            ]
        )
        if alts:
            lines.append("**Accepted alternatives:**")
            lines.append("")
            by_kind: dict[str, list[str]] = defaultdict(list)
            for v in alts:
                by_kind[v.kind].append(v.text)
            for kind in sorted(by_kind):
                samples = by_kind[kind]
                lines.append(f"- *{kind}:* " + "; ".join(f"`{s}`" for s in samples))
            lines.append("")
        rejected_wrong = [v for v in r.rejected if v.kind == "wrong"]
        wrongly_accepted = [v for v in r.accepted if v.kind == "wrong"]
        if wrongly_accepted:
            lines.append(
                "**⚠ Wrong answers incorrectly accepted:** "
                + ", ".join(f"`{v.text}`" for v in wrongly_accepted)
            )
            lines.append("")
        if rejected_wrong:
            lines.append(
                f"*Wrong controls correctly rejected:* {len(rejected_wrong)}"
            )
            lines.append("")

    lines.extend(
        [
            "## Method notes",
            "",
            "- Variants that normalize to the same text as the expected answer (case-insensitive)",
            "  are not counted as alternatives.",
            "- Case-only variants are never generated; spacing/punctuation/synonym/typo variants are.",
            "- Re-run: `python speedycat_eval/run_acceptance_eval.py`",
            "",
        ]
    )
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description="SpeedyCAT alternative-answer acceptance eval")
    parser.add_argument(
        "--no-write", action="store_true", help="do not write ACCEPTANCE_RESULTS.md"
    )
    parser.add_argument(
        "--delay",
        type=float,
        default=0.25,
        help="seconds between API calls (default 0.25)",
    )
    args = parser.parse_args()

    ai = load_checker()
    if not ai.ai_checker_available():
        print(
            "ERROR: No cloud proxy and no OpenAI key — cannot run live acceptance eval.",
            file=sys.stderr,
        )
        return 1

    cards = json.loads(CARDS_PATH.read_text(encoding="utf-8"))["cards"]
    results: list[CardResult] = []
    for card in cards:
        print(f"Testing {card['id']}...", flush=True)
        results.append(run_card(ai, card, delay_s=args.delay))

    agg = aggregate(results)
    md = render_markdown(ai, results, agg)
    print()
    print(md)

    if not args.no_write:
        RESULTS_PATH.write_text(md, encoding="utf-8")
        print(f"\nWrote {RESULTS_PATH}", flush=True)

    return 0


if __name__ == "__main__":
    sys.exit(main())
