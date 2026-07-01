"""Synthesize the 20 per-batch eval reports into one summary.

Reads content/flashcards/reports/batch_NN_report.json (1..20), aggregates
totals / failure types / worst offenders, and writes SUMMARY.json + SUMMARY.md.
Robust to missing or slightly-malformed reports.
"""

from __future__ import annotations

import json
import os

REPORTS = r"C:\alpha_ai\speedrun\content\flashcards\reports"
NUM = 20


def load(i: int):
    path = os.path.join(REPORTS, f"batch_{i:02d}_report.json")
    if not os.path.isfile(path):
        return None, "missing"
    try:
        with open(path, encoding="utf-8") as fh:
            return json.load(fh), None
    except Exception as e:  # noqa: BLE001
        return None, f"unparseable: {e}"


def main() -> None:
    totals = {
        "cards_evaluated": 0,
        "ok": 0,
        "cards_with_issues": 0,
        "by_check": {"qa_correspondence": 0, "wellformed": 0, "markup": 0},
        "by_severity": {"error": 0, "warning": 0, "info": 0},
    }
    issue_types: dict[str, int] = {}
    per_batch = []
    problems = []
    all_errors = []  # flagged error-severity cards across batches

    for i in range(1, NUM + 1):
        rep, err = load(i)
        if err:
            problems.append({"batch": i, "issue": err})
            per_batch.append({"batch": i, "status": err})
            continue
        ce = int(rep.get("cards_evaluated", 0) or 0)
        summ = rep.get("summary", {}) or {}
        by_check = summ.get("by_check", {}) or {}
        by_sev = summ.get("by_severity", {}) or {}
        totals["cards_evaluated"] += ce
        totals["ok"] += int(summ.get("ok", 0) or 0)
        totals["cards_with_issues"] += int(summ.get("cards_with_issues", 0) or 0)
        for k in totals["by_check"]:
            totals["by_check"][k] += int(by_check.get(k, 0) or 0)
        for k in totals["by_severity"]:
            totals["by_severity"][k] += int(by_sev.get(k, 0) or 0)
        for t, c in (rep.get("issue_type_counts", {}) or {}).items():
            issue_types[t] = issue_types.get(t, 0) + int(c or 0)
        for f in rep.get("flagged", []) or []:
            if (f or {}).get("severity") == "error":
                all_errors.append(
                    {
                        "batch": i,
                        "card_id": f.get("card_id"),
                        "check": f.get("check"),
                        "type": f.get("type"),
                        "detail": f.get("detail"),
                    }
                )
        per_batch.append(
            {
                "batch": i,
                "status": "ok",
                "cards_evaluated": ce,
                "ok": int(summ.get("ok", 0) or 0),
                "cards_with_issues": int(summ.get("cards_with_issues", 0) or 0),
                "error": int(by_sev.get("error", 0) or 0),
                "warning": int(by_sev.get("warning", 0) or 0),
                "info": int(by_sev.get("info", 0) or 0),
                "notes": rep.get("notes", ""),
            }
        )

    top_types = sorted(issue_types.items(), key=lambda kv: kv[1], reverse=True)

    summary = {
        "title": "SpeedyCAT flashcard eval — synthesized summary",
        "generatedOn": "2026-06-30",
        "batches_reported": sum(1 for b in per_batch if b.get("status") == "ok"),
        "batches_expected": NUM,
        "missing_or_bad": problems,
        "totals": totals,
        "expected_total_cards": 5142,
        "common_failure_types": [{"type": t, "count": c} for t, c in top_types],
        "error_severity_examples": all_errors[:60],
        "per_batch": per_batch,
    }
    with open(os.path.join(REPORTS, "SUMMARY.json"), "w", encoding="utf-8") as fh:
        json.dump(summary, fh, ensure_ascii=False, indent=2)

    # markdown
    lines = []
    lines.append("# SpeedyCAT flashcard eval — summary\n")
    lines.append(
        f"Batches reported: **{summary['batches_reported']}/{NUM}**  ·  "
        f"Cards evaluated: **{totals['cards_evaluated']}** (expected 5142)\n"
    )
    t = totals
    lines.append("## Totals\n")
    lines.append(f"- OK cards: **{t['ok']}**")
    lines.append(f"- Cards with issues: **{t['cards_with_issues']}**")
    lines.append(
        f"- By severity: error **{t['by_severity']['error']}**, "
        f"warning **{t['by_severity']['warning']}**, info **{t['by_severity']['info']}**"
    )
    lines.append(
        f"- By check: Q/A correspondence **{t['by_check']['qa_correspondence']}**, "
        f"well-formed **{t['by_check']['wellformed']}**, "
        f"markup **{t['by_check']['markup']}**\n"
    )
    if problems:
        lines.append(f"> Missing/bad reports: {problems}\n")
    lines.append("## Most common failure types\n")
    if top_types:
        for tname, c in top_types[:20]:
            lines.append(f"- `{tname}`: {c}")
    else:
        lines.append("- (none reported)")
    lines.append("\n## Per-batch\n")
    lines.append("| Batch | Cards | OK | Issues | Err | Warn | Info |")
    lines.append("| ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
    for b in per_batch:
        if b.get("status") != "ok":
            lines.append(f"| {b['batch']} | — | — | — | — | — | — | (_{b['status']}_) |")
            continue
        lines.append(
            f"| {b['batch']} | {b['cards_evaluated']} | {b['ok']} | "
            f"{b['cards_with_issues']} | {b['error']} | {b['warning']} | {b['info']} |"
        )
    lines.append("\n## Worst offenders (error-severity, up to 60)\n")
    if all_errors:
        for e in all_errors[:60]:
            lines.append(
                f"- b{e['batch']} card {e['card_id']} — {e['check']}/{e['type']}: {e['detail']}"
            )
    else:
        lines.append("- (no error-severity cards reported)")
    with open(os.path.join(REPORTS, "SUMMARY.md"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")

    print(
        f"SYNTH done: {summary['batches_reported']}/{NUM} batches, "
        f"cards_evaluated={totals['cards_evaluated']}, "
        f"errors={totals['by_severity']['error']}, "
        f"warnings={totals['by_severity']['warning']}",
        flush=True,
    )


if __name__ == "__main__":
    main()
