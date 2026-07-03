#!/usr/bin/env python3
# Copyright: Ankitects Pty Ltd and contributors
# License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

"""SpeedyCAT AI answer-checker evaluation harness.

Spec compliance for the AI feature: every AI output must trace to a NAMED SOURCE
(the model id ``openai:gpt-5.4-mini``), be checked against a HELD-OUT TEST SET,
and beat a simpler BASELINE. This script does exactly that on ``fixtures.json``:

* BASELINE (always runs, no key, no network): predicts ``honest`` with the
  conservative heuristic and ``correct`` with the case-insensitive string match
  — the same deterministic logic the apps use when AI is OFF.
* AI (runs only when a key is present): calls the OpenAI *responses* API through
  the shared ``speedycat_ai`` module and uses the model's structured verdict.

It loads the pure checker module straight from the desktop source tree by path,
so it needs no build and no ``anki`` install. Run from anywhere:

    python speedycat_eval/run_eval.py            # baseline (+ AI if key present)
    python speedycat_eval/run_eval.py --markdown # emit a Markdown report

The AI columns print ``SKIPPED (no key)`` when neither ``SPEEDYCAT_OPENAI_API_KEY``
nor the gitignored key file is available; the baseline still runs and is reported.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from types import ModuleType

HERE = Path(__file__).resolve().parent
MODULE_PATH = HERE.parent / "anki" / "pylib" / "anki" / "speedycat_ai.py"
FIXTURES_PATH = HERE / "fixtures.json"


def load_checker() -> ModuleType:
    """Load the pure ``speedycat_ai`` module directly from the source tree."""
    spec = importlib.util.spec_from_file_location("speedycat_ai_eval_mod", MODULE_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"could not load checker module at {MODULE_PATH}")
    module = importlib.util.module_from_spec(spec)
    # Register before exec so @dataclass introspection can find the module.
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


@dataclass
class Metrics:
    honesty_correct: int = 0
    verdict_correct: int = 0
    exact: int = 0  # both honesty AND verdict right
    total: int = 0
    errors: int = 0  # AI calls that returned nothing

    def add(self, honesty_hit: bool, verdict_hit: bool) -> None:
        self.total += 1
        self.honesty_correct += int(honesty_hit)
        self.verdict_correct += int(verdict_hit)
        self.exact += int(honesty_hit and verdict_hit)

    def pct(self, n: int) -> str:
        return f"{(100.0 * n / self.total):.1f}%" if self.total else "n/a"


def evaluate(ai: ModuleType, run_ai: bool) -> tuple[Metrics, Metrics | None, list[dict]]:
    data = json.loads(FIXTURES_PATH.read_text(encoding="utf-8"))
    cases = data["cases"]

    baseline = Metrics()
    ai_metrics: Metrics | None = Metrics() if run_ai else None
    rows: list[dict] = []

    for case in cases:
        typed, expected, front = case["typed"], case["expected"], case["front"]
        want_honest, want_correct = bool(case["honest"]), bool(case["correct"])

        base_honest = ai.heuristic_is_honest_attempt(typed)
        base_correct = ai.deterministic_correct(typed, expected)
        baseline.add(base_honest == want_honest, base_correct == want_correct)

        row = {
            "typed": typed,
            "expected": expected,
            "want": (want_honest, want_correct),
            "baseline": (base_honest, base_correct),
        }

        if ai_metrics is not None:
            result = ai.run_check(front, typed, expected)
            if result is None:
                ai_metrics.errors += 1
                # A failed call falls back to the baseline in the live app; for
                # scoring we record it as the baseline prediction.
                ai_honest, ai_correct = base_honest, base_correct
            else:
                ai_honest, ai_correct = result.honest_attempt, result.correct
            ai_metrics.add(ai_honest == want_honest, ai_correct == want_correct)
            row["ai"] = (ai_honest, ai_correct)

        rows.append(row)

    return baseline, ai_metrics, rows


def print_report(ai: ModuleType, baseline: Metrics, ai_metrics: Metrics | None) -> None:
    print("=" * 68)
    print("SpeedyCAT AI answer-checker evaluation")
    print(f"  named source : {ai.SOURCE_AI}")
    print(f"  baseline     : {ai.SOURCE_BASELINE}")
    print(f"  test set     : {baseline.total} held-out labeled cases")
    print("=" * 68)
    print(f"{'metric':<22}{'baseline':>14}{'AI':>18}")
    print("-" * 68)
    ai_h = ai_metrics.pct(ai_metrics.honesty_correct) if ai_metrics else "SKIPPED (no key)"
    ai_v = ai_metrics.pct(ai_metrics.verdict_correct) if ai_metrics else "SKIPPED (no key)"
    ai_e = ai_metrics.pct(ai_metrics.exact) if ai_metrics else "SKIPPED (no key)"
    print(f"{'honesty accuracy':<22}{baseline.pct(baseline.honesty_correct):>14}{ai_h:>18}")
    print(f"{'verdict accuracy':<22}{baseline.pct(baseline.verdict_correct):>14}{ai_v:>18}")
    print(f"{'exact (both)':<22}{baseline.pct(baseline.exact):>14}{ai_e:>18}")
    print("-" * 68)
    if ai_metrics is None:
        print("AI eval skipped: set SPEEDYCAT_OPENAI_API_KEY or create the key file")
        print("(.speedycat-openai.key) to run the live comparison.")
    elif ai_metrics.errors:
        print(f"note: {ai_metrics.errors} AI call(s) failed and fell back to baseline.")


def print_markdown(ai: ModuleType, baseline: Metrics, ai_metrics: Metrics | None) -> None:
    ai_h = ai_metrics.pct(ai_metrics.honesty_correct) if ai_metrics else "_skipped (no key)_"
    ai_v = ai_metrics.pct(ai_metrics.verdict_correct) if ai_metrics else "_skipped (no key)_"
    ai_e = ai_metrics.pct(ai_metrics.exact) if ai_metrics else "_skipped (no key)_"
    print(f"- Named source: `{ai.SOURCE_AI}`  |  Baseline: `{ai.SOURCE_BASELINE}`")
    print(f"- Held-out test set: {baseline.total} labeled cases\n")
    print("| Metric | Baseline | AI |")
    print("| --- | --- | --- |")
    print(f"| Honesty accuracy | {baseline.pct(baseline.honesty_correct)} | {ai_h} |")
    print(f"| Verdict accuracy | {baseline.pct(baseline.verdict_correct)} | {ai_v} |")
    print(f"| Exact (both) | {baseline.pct(baseline.exact)} | {ai_e} |")


def main() -> int:
    parser = argparse.ArgumentParser(description="SpeedyCAT AI checker eval")
    parser.add_argument("--markdown", action="store_true", help="emit a Markdown report")
    parser.add_argument(
        "--baseline-only", action="store_true", help="never attempt the AI calls"
    )
    args = parser.parse_args()

    ai = load_checker()
    have_key = ai.key_present()
    run_ai = have_key and not args.baseline_only

    baseline, ai_metrics, _rows = evaluate(ai, run_ai)

    if args.markdown:
        print_markdown(ai, baseline, ai_metrics)
    else:
        print_report(ai, baseline, ai_metrics)
    return 0


if __name__ == "__main__":
    sys.exit(main())
