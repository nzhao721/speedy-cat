"""Aggregate the expanded practice-question files into one summary.

Scans content/practice-questions/*.json, counts items by MCAT section and by
source, flags LICENSE-UNCERTAIN items, validates the basic item schema, and
writes _EXPANSION_SUMMARY.json + _EXPANSION_SUMMARY.md.
"""

from __future__ import annotations

import glob
import json
import os

ROOT = r"C:\alpha_ai\speedrun\content\practice-questions"
SECTIONS = ["CPBS", "CARS", "BBLS", "PSBB"]
REQUIRED = ["section", "stem", "choices", "correctAnswer", "sourceName", "sourceUrl"]


def iter_questions(obj):
    if isinstance(obj, list):
        yield from obj
        return
    if not isinstance(obj, dict):
        return
    if "questions" in obj:
        yield from obj["questions"]
    if "passageSets" in obj:  # CarsPassageSet format (generated CARS)
        for ps in obj["passageSets"]:
            if isinstance(ps, dict):
                yield from ps.get("questions", [])


def main() -> None:
    files = sorted(glob.glob(os.path.join(ROOT, "*.json")))
    by_section = {s: 0 for s in SECTIONS}
    by_section_clean = {s: 0 for s in SECTIONS}
    license_uncertain = 0
    sources: dict[str, int] = {}
    licenses: dict[str, int] = {}
    per_file = []
    schema_problems = []
    grand_total = 0
    seen_stems: set[str] = set()
    dupes = 0

    for path in files:
        name = os.path.basename(path)
        try:
            with open(path, encoding="utf-8") as fh:
                obj = json.load(fh)
        except Exception as e:  # noqa: BLE001
            per_file.append({"file": name, "error": f"unparseable: {e}"})
            continue
        n = 0
        fsec: dict[str, int] = {}
        for q in iter_questions(obj):
            if not isinstance(q, dict):
                continue
            n += 1
            grand_total += 1
            sec = q.get("section", "?")
            fsec[sec] = fsec.get(sec, 0) + 1
            if sec in by_section:
                by_section[sec] += 1
            lic = str(q.get("sourceLicense", ""))
            licenses[lic[:60]] = licenses.get(lic[:60], 0) + 1
            uncertain = "LICENSE-UNCERTAIN" in lic.upper()
            if uncertain:
                license_uncertain += 1
            elif sec in by_section_clean:
                by_section_clean[sec] += 1
            src = str(q.get("sourceName", "?"))
            sources[src] = sources.get(src, 0) + 1
            missing = [k for k in REQUIRED if not q.get(k)]
            if missing:
                schema_problems.append(
                    {"file": name, "id": q.get("id"), "missing": missing}
                )
            stem = (q.get("stem") or "").strip().lower()
            if stem:
                if stem in seen_stems:
                    dupes += 1
                seen_stems.add(stem)
        per_file.append({"file": name, "count": n, "by_section": fsec})

    summary = {
        "title": "SpeedyCAT practice-question expansion — summary",
        "generatedOn": "2026-06-30",
        "files_scanned": len(files),
        "grand_total_items": grand_total,
        "by_section": by_section,
        "by_section_clean_license": by_section_clean,
        "license_uncertain_items": license_uncertain,
        "duplicate_stems_detected": dupes,
        "distinct_sources": len(sources),
        "top_sources": sorted(sources.items(), key=lambda kv: kv[1], reverse=True)[:25],
        "license_breakdown": sorted(licenses.items(), key=lambda kv: kv[1], reverse=True),
        "schema_problems": schema_problems[:50],
        "per_file": per_file,
    }
    with open(os.path.join(ROOT, "_EXPANSION_SUMMARY.json"), "w", encoding="utf-8") as fh:
        json.dump(summary, fh, ensure_ascii=False, indent=2)

    lines = ["# SpeedyCAT practice-question expansion — summary\n"]
    lines.append(f"Files scanned: **{len(files)}**  ·  Total items: **{grand_total}**\n")
    lines.append("## By section (clean / total)\n")
    for s in SECTIONS:
        lines.append(f"- **{s}**: {by_section_clean[s]} clean / {by_section[s]} total")
    lines.append(f"\nLICENSE-UNCERTAIN items: **{license_uncertain}**")
    lines.append(f"Duplicate stems detected: **{dupes}**\n")
    lines.append("## Top sources\n")
    for src, c in summary["top_sources"]:
        lines.append(f"- {c} — {src}")
    lines.append("\n## Per file\n")
    lines.append("| File | Items | Sections |")
    lines.append("| --- | ---: | --- |")
    for pf in per_file:
        if "error" in pf:
            lines.append(f"| {pf['file']} | — | _{pf['error']}_ |")
        else:
            secs = ", ".join(f"{k}:{v}" for k, v in pf["by_section"].items())
            lines.append(f"| {pf['file']} | {pf['count']} | {secs} |")
    if schema_problems:
        lines.append(f"\n> Schema problems (first 50): {len(schema_problems)} items missing required fields\n")
    with open(os.path.join(ROOT, "_EXPANSION_SUMMARY.md"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")

    print(
        f"AGG done: {grand_total} items across {len(files)} files; "
        f"by_section={by_section}; license_uncertain={license_uncertain}; dupes={dupes}",
        flush=True,
    )


if __name__ == "__main__":
    main()
