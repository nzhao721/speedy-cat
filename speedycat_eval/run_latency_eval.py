#!/usr/bin/env python3
# Copyright: Ankitects Pty Ltd and contributors
# License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

"""SpeedyCAT latency evaluation harness.

Times live AI surfaces (answer checker, explanation checker) and lightweight
startup proxies. Writes ``LATENCY_RESULTS.md`` by default.

    py -3 speedycat_eval/run_latency_eval.py
    py -3 speedycat_eval/run_latency_eval.py --samples 5 --no-write
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import statistics
import subprocess
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parent
RESULTS_PATH = HERE / "LATENCY_RESULTS.md"
MODULE_PATH = REPO_ROOT / "anki" / "pylib" / "anki" / "speedycat_ai.py"

DEFAULT_ANSWER_PROXY = (
    "https://us-central1-speedycat-mcat.cloudfunctions.net/checkSpeedycatAnswer"
)
DEFAULT_EXPLANATION_PROXY = (
    "https://us-central1-speedycat-mcat.cloudfunctions.net/checkPracticeExplanation"
)

sys.path.insert(0, str(HERE))
from run_eval import load_checker  # noqa: E402
from run_injection_eval import _call_explanation_checker  # noqa: E402

# Answer-checker cases that miss the deterministic string match (production AI path).
ANSWER_LATENCY_CASES: list[dict[str, str]] = [
    {
        "id": "paraphrase_depolarization",
        "front": "During depolarization, the membrane potential does what?",
        "typed": "goes up",
        "expected": "increases",
    },
    {
        "id": "synonym_na_k_pump",
        "front": "Common biochemical name for the Na+/K+ pump?",
        "typed": "Na+/K+ ATPase",
        "expected": "Sodium-potassium pump",
    },
    {
        "id": "abbrev_atp",
        "front": "What is the cell's main energy currency molecule?",
        "typed": "ATP",
        "expected": "Adenosine triphosphate",
    },
    {
        "id": "typo_mitochondria",
        "front": "What organelle is the powerhouse of the cell?",
        "typed": "mitochondira",
        "expected": "Mitochondria",
    },
    {
        "id": "honest_wrong_insulin",
        "front": "Which protein carries oxygen in the blood?",
        "typed": "insulin",
        "expected": "Hemoglobin",
    },
]

# Substantive practice explanations (realistic pass/fail mix).
EXPLANATION_LATENCY_CASES: list[dict[str, str]] = [
    {
        "id": "na_k_pump_good",
        "stem": (
            "After an action potential, which transporter primarily restores the "
            "resting membrane potential by maintaining Na+ and K+ gradients?"
        ),
        "user_explanation": (
            "The cell needs to restore ion gradients after depolarization. "
            "An active transporter uses ATP to move more sodium out than potassium "
            "in, which re-establishes the negative resting potential."
        ),
        "correct_answer": (
            "Sodium-potassium ATPase (Na+/K+ ATPase) pumps 3 sodium ions out and "
            "2 potassium ions in per ATP hydrolyzed."
        ),
    },
    {
        "id": "hemoglobin_good",
        "stem": "In the lungs, which protein binds oxygen for transport in erythrocytes?",
        "user_explanation": (
            "Red blood cells carry oxygen from the lungs to tissues. "
            "A globular protein with heme groups binds O2 cooperatively in the lungs "
            "and releases it where partial pressure is lower."
        ),
        "correct_answer": (
            "Hemoglobin, a tetrameric protein with heme groups that cooperatively bind O2."
        ),
    },
    {
        "id": "krebs_partial",
        "stem": (
            "Which mitochondrial pathway oxidizes acetyl-CoA to CO2 while generating "
            "NADH and FADH2?"
        ),
        "user_explanation": (
            "It happens in the mitochondria and makes energy carriers. "
            "Acetyl groups get oxidized in a cyclic pathway."
        ),
        "correct_answer": "The citric acid cycle (Krebs cycle, TCA cycle).",
    },
]


@dataclass
class Sample:
    elapsed_ms: float
    ok: bool
    source: str
    error: str | None = None
    fixture_id: str = ""


@dataclass
class LatencyStats:
    name: str
    samples: list[Sample] = field(default_factory=list)
    skipped: bool = False
    skip_reason: str = ""

    def add(self, sample: Sample) -> None:
        self.samples.append(sample)

    @property
    def successful_ms(self) -> list[float]:
        return [s.elapsed_ms for s in self.samples if s.ok]

    def summary(self) -> dict[str, Any]:
        vals = self.successful_ms
        if not vals:
            return {
                "count": len(self.samples),
                "success": 0,
                "failed": sum(1 for s in self.samples if not s.ok),
                "min_ms": None,
                "max_ms": None,
                "mean_ms": None,
                "median_ms": None,
                "p95_ms": None,
            }
        sorted_vals = sorted(vals)
        n = len(sorted_vals)
        p95_idx = min(n - 1, int(round(0.95 * (n - 1))))
        return {
            "count": len(self.samples),
            "success": n,
            "failed": sum(1 for s in self.samples if not s.ok),
            "min_ms": round(min(vals), 1),
            "max_ms": round(max(vals), 1),
            "mean_ms": round(statistics.mean(vals), 1),
            "median_ms": round(statistics.median(vals), 1),
            "p95_ms": round(sorted_vals[p95_idx], 1),
        }

    def sources(self) -> dict[str, int]:
        counts: dict[str, int] = {}
        for s in self.samples:
            if s.ok:
                counts[s.source] = counts.get(s.source, 0) + 1
        return counts


def time_call(fn: Callable[[], Any]) -> tuple[float, Any, str | None]:
    start = time.perf_counter()
    try:
        result = fn()
        elapsed_ms = (time.perf_counter() - start) * 1000.0
        return elapsed_ms, result, None
    except Exception as exc:
        elapsed_ms = (time.perf_counter() - start) * 1000.0
        return elapsed_ms, None, str(exc)


def bench_answer_checker(ai: Any, samples: int, warmup: int) -> LatencyStats:
    stats = LatencyStats(name="answer_checker")
    cases = ANSWER_LATENCY_CASES
    total = warmup + samples
    for i in range(total):
        case = cases[i % len(cases)]
        front, typed, expected = case["front"], case["typed"], case["expected"]

        def _run() -> tuple[Any, str]:
            return ai.run_check(front, typed, expected)

        elapsed_ms, result, err = time_call(_run)
        if err:
            sample = Sample(
                elapsed_ms=elapsed_ms,
                ok=False,
                source="",
                error=err,
                fixture_id=case["id"],
            )
        else:
            res, source = result
            ok = res is not None
            sample = Sample(
                elapsed_ms=elapsed_ms,
                ok=ok,
                source=source if ok else "failed",
                error=None if ok else "no result",
                fixture_id=case["id"],
            )
        if i >= warmup:
            stats.add(sample)
    return stats


def bench_explanation_checker(samples: int, warmup: int) -> LatencyStats:
    stats = LatencyStats(name="explanation_checker")
    cases = EXPLANATION_LATENCY_CASES
    total = warmup + samples
    for i in range(total):
        case = cases[i % len(cases)]
        stem = case["stem"]
        user_explanation = case["user_explanation"]
        correct_answer = case["correct_answer"]

        def _run() -> tuple[dict[str, Any] | None, str, str | None]:
            return _call_explanation_checker(stem, user_explanation, correct_answer)

        elapsed_ms, result, err = time_call(_run)
        if err:
            sample = Sample(elapsed_ms=elapsed_ms, ok=False, source="", error=err, fixture_id=case["id"])
        else:
            payload, source, call_err = result
            ok = payload is not None and call_err is None
            sample = Sample(
                elapsed_ms=elapsed_ms,
                ok=ok,
                source=source if ok else "failed",
                error=call_err,
                fixture_id=case["id"],
            )
        if i >= warmup:
            stats.add(sample)
    return stats


def bench_cold_module_import() -> LatencyStats:
    """Fresh subprocess: time to import speedycat_ai from source."""
    stats = LatencyStats(name="cold_import_speedycat_ai")
    script = f"""
import time, importlib.util, sys
from pathlib import Path
p = Path({str(MODULE_PATH)!r})
t0 = time.perf_counter()
spec = importlib.util.spec_from_file_location("speedycat_ai_cold", p)
mod = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = mod
spec.loader.exec_module(mod)
print((time.perf_counter() - t0) * 1000.0)
"""
    for attempt in range(3):
        try:
            proc = subprocess.run(
                [sys.executable, "-c", script],
                capture_output=True,
                text=True,
                timeout=30,
                cwd=str(REPO_ROOT),
            )
            if proc.returncode == 0:
                ms = float(proc.stdout.strip().splitlines()[-1])
                stats.add(Sample(elapsed_ms=ms, ok=True, source="subprocess_import"))
            else:
                stats.add(
                    Sample(
                        elapsed_ms=0,
                        ok=False,
                        source="",
                        error=(proc.stderr or proc.stdout or "import failed")[:200],
                    )
                )
        except Exception as exc:
            stats.add(Sample(elapsed_ms=0, ok=False, source="", error=str(exc)))
    return stats


def bench_checker_ready(ai: Any) -> LatencyStats:
    """Time from process start to first successful answer-checker call."""
    stats = LatencyStats(name="checker_ready_first_call")
    case = ANSWER_LATENCY_CASES[0]

    def _run() -> tuple[Any, str]:
        return ai.run_check(case["front"], case["typed"], case["expected"])

    elapsed_ms, result, err = time_call(_run)
    if err:
        stats.add(Sample(elapsed_ms=elapsed_ms, ok=False, source="", error=err))
    else:
        res, source = result
        stats.add(
            Sample(
                elapsed_ms=elapsed_ms,
                ok=res is not None,
                source=source if res is not None else "failed",
                error=None if res is not None else "no result",
            )
        )
    return stats


def bench_warm_import() -> LatencyStats:
    stats = LatencyStats(name="warm_import_speedycat_ai")
    for _ in range(3):
        start = time.perf_counter()
        load_checker()
        ms = (time.perf_counter() - start) * 1000.0
        stats.add(Sample(elapsed_ms=ms, ok=True, source="importlib_reload"))
    return stats


def bench_sync_connectivity() -> LatencyStats:
    """Lightweight TCP/HTTP probe to AnkiWeb sync host (not a full collection sync)."""
    stats = LatencyStats(name="sync_ankiweb_connectivity")
    try:
        import requests
    except ImportError:
        stats.skipped = True
        stats.skip_reason = "requests not installed"
        return stats

    url = "https://sync.ankiweb.net/"
    for _ in range(5):
        start = time.perf_counter()
        try:
            resp = requests.head(url, timeout=15, allow_redirects=True)
            ms = (time.perf_counter() - start) * 1000.0
            ok = resp.status_code < 500
            stats.add(
                Sample(
                    elapsed_ms=ms,
                    ok=ok,
                    source=f"HEAD {url} ({resp.status_code})",
                    error=None if ok else f"HTTP {resp.status_code}",
                )
            )
        except Exception as exc:
            ms = (time.perf_counter() - start) * 1000.0
            stats.add(Sample(elapsed_ms=ms, ok=False, source="", error=str(exc)))
    return stats


def bench_android_startup() -> LatencyStats:
    stats = LatencyStats(name="android_cold_start")
    sdk_adb = Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk" / "platform-tools" / "adb.exe"
    adb = sdk_adb if sdk_adb.is_file() else None
    if adb is None:
        stats.skipped = True
        stats.skip_reason = "adb not found under Android SDK platform-tools"
        return stats

    try:
        devices_out = subprocess.run(
            [str(adb), "devices"],
            capture_output=True,
            text=True,
            timeout=15,
        )
    except Exception as exc:
        stats.skipped = True
        stats.skip_reason = f"adb devices failed: {exc}"
        return stats

    lines = [ln for ln in devices_out.stdout.splitlines()[1:] if ln.strip()]
    online = [ln for ln in lines if "\tdevice" in ln]
    if not online:
        stats.skipped = True
        stats.skip_reason = "no emulator/device attached (expected speedrun_api36)"
        return stats

    pkg = "com.ichi2.anki.debug"
    activity = "com.ichi2.anki.IntentHandler"

    def force_stop() -> None:
        subprocess.run([str(adb), "shell", "am", "force-stop", pkg], capture_output=True, timeout=15)

    for _ in range(2):
        force_stop()
        time.sleep(0.5)
        start = time.perf_counter()
        launch = subprocess.run(
            [
                str(adb),
                "shell",
                "am",
                "start",
                "-W",
                "-n",
                f"{pkg}/{activity}",
            ],
            capture_output=True,
            text=True,
            timeout=60,
        )
        ms = (time.perf_counter() - start) * 1000.0
        ok = launch.returncode == 0 and "Status: ok" in launch.stdout
        stats.add(
            Sample(
                elapsed_ms=ms,
                ok=ok,
                source="adb shell am start -W",
                error=None if ok else (launch.stderr or launch.stdout)[:200],
            )
        )
    return stats


def format_stats_table(rows: list[tuple[str, dict[str, Any]]]) -> list[str]:
    lines = [
        "| Surface | n | success | min (ms) | median (ms) | mean (ms) | p95 (ms) | max (ms) |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for name, s in rows:
        lines.append(
            f"| {name} | {s['count']} | {s['success']} | "
            f"{s['min_ms'] if s['min_ms'] is not None else '—'} | "
            f"{s['median_ms'] if s['median_ms'] is not None else '—'} | "
            f"{s['mean_ms'] if s['mean_ms'] is not None else '—'} | "
            f"{s['p95_ms'] if s['p95_ms'] is not None else '—'} | "
            f"{s['max_ms'] if s['max_ms'] is not None else '—'} |"
        )
    return lines


def build_markdown(
    *,
    samples: int,
    warmup: int,
    answer: LatencyStats,
    explanation: LatencyStats,
    cold_import: LatencyStats,
    warm_import: LatencyStats,
    checker_ready: LatencyStats,
    sync_probe: LatencyStats,
    android: LatencyStats,
    ai: Any,
) -> str:
    now = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    proxy_url = ai.resolve_proxy_url() or DEFAULT_ANSWER_PROXY
    have_key = ai.key_present()

    sections: list[str] = [
        "# SpeedyCAT latency evaluation",
        "",
        f"- **Date:** {now}",
        f"- **Host:** {platform.system()} {platform.release()} ({platform.machine()})",
        f"- **Python:** {sys.version.split()[0]}",
        f"- **Samples per AI surface:** {samples} (after {warmup} warmup discard each)",
        f"- **Answer checker proxy:** `{proxy_url}`",
        f"- **Explanation checker proxy:** `{DEFAULT_EXPLANATION_PROXY}`",
        f"- **Local OpenAI key present:** {'yes (fallback available)' if have_key else 'no (proxy only)'}",
        "",
        "## AI endpoint latency",
        "",
        "Answer checker uses `run_check()` (proxy first, then direct key). "
        "Fixtures are cases that miss the deterministic string match in production. "
        "Explanation checker POSTs `{stem, userExplanation, correctAnswer}` to the proxy.",
        "",
        *format_stats_table(
            [
                ("Answer checker", answer.summary()),
                ("Explanation checker", explanation.summary()),
            ]
        ),
        "",
        "### Call sources (successful samples)",
        "",
        f"- Answer checker: `{json.dumps(answer.sources())}`",
        f"- Explanation checker: `{json.dumps(explanation.sources())}`",
        "",
    ]

    if answer.samples and any(not s.ok for s in answer.samples):
        sections += ["**Answer checker failures:**"]
        for s in answer.samples:
            if not s.ok:
                sections.append(f"- `{s.fixture_id}`: {s.error or 'failed'} ({s.elapsed_ms:.0f} ms)")
        sections.append("")

    if explanation.samples and any(not s.ok for s in explanation.samples):
        sections += ["**Explanation checker failures:**"]
        for s in explanation.samples:
            if not s.ok:
                sections.append(f"- `{s.fixture_id}`: {s.error or 'failed'} ({s.elapsed_ms:.0f} ms)")
        sections.append("")

    sections += [
        "## Startup / readiness",
        "",
        *format_stats_table(
            [
                ("Cold import `speedycat_ai` (subprocess)", cold_import.summary()),
                ("Warm import `speedycat_ai` (in-process)", warm_import.summary()),
                ("First successful checker call (warm process)", checker_ready.summary()),
            ]
        ),
        "",
        "Cold import spins a fresh Python subprocess loading the module from disk. "
        "Checker-ready time includes network RTT for the first live proxy call after import.",
        "",
    ]

    sections += ["## Sync", ""]
    if sync_probe.skipped:
        sections.append(f"_Skipped:_ {sync_probe.skip_reason}")
    else:
        sections += [
            "Full collection sync requires Anki credentials, a built `rsbridge`, and an open "
            "collection — not run here. Instead we measured **AnkiWeb sync host connectivity** "
            "(HTTP HEAD to `https://sync.ankiweb.net/`), which approximates network path latency only.",
            "",
            *format_stats_table([("AnkiWeb sync host HEAD", sync_probe.summary())]),
        ]
    sections.append("")

    sections += ["## Android cold start", ""]
    if android.skipped:
        sections.append(f"_Skipped:_ {android.skip_reason}")
    else:
        sections += [
            "Timed `adb shell am start -W` after `force-stop` on `com.ichi2.anki.debug`.",
            "",
            *format_stats_table([("AnkiDroid launch (-W)", android.summary())]),
        ]
    sections.append("")

    sections += [
        "## Notes",
        "",
        "- **Cold vs warm:** First AI sample after warmup may still be warmer than a true "
        "cold start because TLS sessions and Firebase/Google Cloud paths reuse connections.",
        "- **Network variance:** Cloud Function → OpenAI adds variable latency; re-run on "
        "different networks for production SLO planning.",
        "- **Production gating:** The live reviewer only calls the answer checker when the "
        "deterministic verdict is incorrect; this harness always invokes the checker API "
        "for consistent latency measurement.",
        "- **Secrets:** No API keys or auth tokens are recorded in this report.",
        "",
    ]
    return "\n".join(sections)


def main() -> int:
    parser = argparse.ArgumentParser(description="SpeedyCAT latency evaluation")
    parser.add_argument("--samples", type=int, default=8, help="timed samples per AI surface")
    parser.add_argument("--warmup", type=int, default=1, help="discarded warmup calls per surface")
    parser.add_argument("--no-write", action="store_true", help="print markdown to stdout only")
    parser.add_argument("--output", type=Path, default=RESULTS_PATH, help="markdown output path")
    args = parser.parse_args()

    print(f"Loading checker module from {MODULE_PATH} ...")
    ai = load_checker()

    print(f"Benchmarking answer checker ({args.warmup} warmup + {args.samples} samples) ...")
    answer = bench_answer_checker(ai, args.samples, args.warmup)

    print(f"Benchmarking explanation checker ({args.warmup} warmup + {args.samples} samples) ...")
    explanation = bench_explanation_checker(args.samples, args.warmup)

    print("Measuring module import / checker-ready ...")
    cold_import = bench_cold_module_import()
    warm_import = bench_warm_import()
    checker_ready = bench_checker_ready(ai)

    print("Probing sync host connectivity ...")
    sync_probe = bench_sync_connectivity()

    print("Checking Android startup ...")
    android = bench_android_startup()

    md = build_markdown(
        samples=args.samples,
        warmup=args.warmup,
        answer=answer,
        explanation=explanation,
        cold_import=cold_import,
        warm_import=warm_import,
        checker_ready=checker_ready,
        sync_probe=sync_probe,
        android=android,
        ai=ai,
    )

    if args.no_write:
        print(md)
    else:
        args.output.write_text(md, encoding="utf-8")
        print(f"Wrote {args.output}")

    # Console headline summary
    for label, st in (
        ("answer_checker", answer),
        ("explanation_checker", explanation),
    ):
        s = st.summary()
        print(
            f"{label}: median={s['median_ms']}ms p95={s['p95_ms']}ms "
            f"({s['success']}/{s['count']} ok)"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
