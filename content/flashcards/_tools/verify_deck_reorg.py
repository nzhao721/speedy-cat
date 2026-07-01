"""Verify the SpeedyCAT thematic deck reorganization (no Rust rebuild needed).

Run from anki/ with the venv python:

    cd anki
    $env:SKIP_RUN="1"; $env:PYTHONIOENCODING="utf-8"
    out\\pyenv\\Scripts\\python.exe ..\\content\\flashcards\\_tools\\verify_deck_reorg.py

It applies the real ``reorganize_into_themes`` function loaded straight from
``qt/aqt/speedycat_themes.py`` (single source of truth — an aqt-free module) to
BOTH import layouts and asserts, for each:

  (a) every thematic topic deck is TOP-LEVEL (bare name, no "::", no parent),
  (b) NO "SpeedyCAT MCAT" deck remains anywhere,
  (c) no deck name leaks a source/unit token (MileDown, Pankow, MrPankow,
      "MCAT 💙", "P/S Deck", or a bare AAMC unit code 6A..9C),
  (d) the total card count is unchanged (5142),
  (e) the reorganization is idempotent (a second pass moves 0 cards).

Two layouts are checked:
  * APKG  — imports both community .apkg packages (the community-deck structure).
  * JSON  — reproduces the app's bundled-deck layout from the shipped cards.json
            (each note filed under "SpeedyCAT MCAT::<source>::<topic>", exactly
            as the rslib JSON importer does). Simulated with the deck API so the
            check does not depend on the Rust backend being rebuilt.

Prints PASS/FAIL.
"""

from __future__ import annotations

import importlib.util
import json
import os
import re
import sys
import tempfile

sys.stdout.reconfigure(encoding="utf-8")
sys.path[0:0] = ["pylib", "out/pylib"]

REPO_ROOT = os.path.abspath(os.path.join(os.getcwd(), ".."))
PKG_DIR = os.path.join(REPO_ROOT, "content", "flashcards", "_packages")
MILEDOWN = os.path.join(PKG_DIR, "MileDown.apkg")
PANKOW = os.path.join(PKG_DIR, "Pankow.apkg")
CARDS_JSON = os.path.join(REPO_ROOT, "content", "flashcards", "cards.json")
THEMES_PY = os.path.join(REPO_ROOT, "anki", "qt", "aqt", "speedycat_themes.py")

PARENT = "SpeedyCAT MCAT"
EXPECTED_TOTAL = 5142

# expected final thematic decks -> card count (from the source inventory)
EXPECTED_COUNTS = {
    "Behavioral Sciences": 828,
    "Biochemistry": 515,
    "Biology": 604,
    "Equations & Constants": 91,
    "General Chemistry": 250,
    "Organic Chemistry": 287,
    "Physics and Math": 313,
    "Sensation & Perception": 254,
    "Learning, Memory & Cognition": 381,
    "Emotion, Stress & Motivation": 145,
    "Identity, Personality & Biological Bases of Behavior": 617,
    "Social Processes & Socialization": 43,
    "Attitudes & Behavior Change": 111,
    "Self-Identity & Social Cognition": 162,
    "Social Interactions": 120,
    "Social Structures & Institutions": 73,
    "Demographics & Culture": 98,
    "Social Inequality": 32,
    "Research Methods & Statistics": 218,
}

# anything that would reveal WHERE a card came from, or leftover organization
FORBIDDEN_SUBSTRINGS = [
    "MileDown",
    "Pankow",
    "MrPankow",
    "P/S Deck",
    "\U0001f499",
    "SpeedyCAT",
]
UNIT_CODE_RE = re.compile(r"\b[6-9][ABC]\b")


def load_themes():
    spec = importlib.util.spec_from_file_location("speedycat_themes_src", THEMES_PY)
    assert spec and spec.loader
    mod = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = mod  # so dataclasses can resolve cls.__module__
    spec.loader.exec_module(mod)
    return mod


def deck_leaks(name: str) -> str | None:
    for tok in FORBIDDEN_SUBSTRINGS:
        if tok in name:
            return tok
    m = UNIT_CODE_RE.search(name)
    if m:
        return m.group(0)
    return None


def check_layout(label: str, build, themes) -> list[str]:
    """Import via ``build(col)``, reorganize, print the tree, and return any
    verification failures for this layout."""
    from anki.collection import Collection

    failures: list[str] = []
    tmp = tempfile.mkdtemp(prefix=f"speedycat_verify_{label}_")
    col = Collection(os.path.join(tmp, "collection.anki2"))
    try:
        build(col)
        before = len(list(col.find_cards("")))
        result = themes.reorganize_into_themes(col)
        print(
            f"\n### {label}: cards before={before}, moved={result.moved}, "
            f"themes={len(result.themes)}, removed={result.removed}"
        )

        all_decks = sorted(col.decks.all_names_and_ids(), key=lambda d: d.name)
        # top-level SpeedyCAT theme decks = everything except the auto 'Default'
        theme_decks = [d for d in all_decks if d.name != "Default"]
        theme_counts: dict[str, int] = {}
        print(f"=== {label}: TOP-LEVEL deck list ===")
        for d in all_decks:
            n = col.decks.card_count(d.id, include_subdecks=False)
            if d.name != "Default":
                theme_counts[d.name] = n
            print(f"  cards={n:5d}  {d.name!r}")

        # (a) every thematic deck is TOP-LEVEL (no "::")
        nested = [d.name for d in theme_decks if "::" in d.name]
        if nested:
            failures.append(f"[{label}] (a) non-top-level decks remain: {nested}")

        # (b) NO "SpeedyCAT MCAT" parent (or any child) remains
        parent_left = [
            d.name
            for d in all_decks
            if d.name == PARENT or d.name.startswith(PARENT + "::")
        ]
        if parent_left:
            failures.append(f"[{label}] (b) 'SpeedyCAT MCAT' still present: {parent_left}")

        names = {d.name for d in theme_decks}
        unexpected = sorted(names - set(EXPECTED_COUNTS))
        if unexpected:
            failures.append(f"[{label}] unexpected decks: {unexpected}")
        missing = sorted(set(EXPECTED_COUNTS) - names)
        if missing:
            failures.append(f"[{label}] expected theme decks missing: {missing}")

        # (c) no source/unit/leftover token in ANY deck name
        for d in all_decks:
            tok = deck_leaks(d.name)
            if tok:
                failures.append(f"[{label}] (c) {d.name!r} leaks token {tok!r}")

        for theme, want in EXPECTED_COUNTS.items():
            got = theme_counts.get(theme)
            if got != want:
                failures.append(
                    f"[{label}] count mismatch {theme!r}: got {got}, want {want}"
                )

        # (d) total card count unchanged
        after = len(list(col.find_cards("")))
        if after != EXPECTED_TOTAL:
            failures.append(f"[{label}] (d) total {after} != {EXPECTED_TOTAL}")
        if after != before:
            failures.append(f"[{label}] (d) total changed {before}->{after}")

        # (e) idempotent on a second run
        tree_before = sorted(d.name for d in col.decks.all_names_and_ids())
        again = themes.reorganize_into_themes(col)
        tree_after = sorted(d.name for d in col.decks.all_names_and_ids())
        if again.moved != 0 or again.removed:
            failures.append(
                f"[{label}] (e) not idempotent: moved={again.moved} removed={again.removed}"
            )
        if tree_before != tree_after:
            failures.append(f"[{label}] (e) tree changed on 2nd pass")
        if len(list(col.find_cards(""))) != EXPECTED_TOTAL:
            failures.append(f"[{label}] (e) total changed after 2nd pass")
    finally:
        col.close()
    return failures


def main() -> None:
    for p in (MILEDOWN, PANKOW, CARDS_JSON, THEMES_PY):
        if not os.path.isfile(p):
            raise SystemExit(f"missing required file: {p}")

    themes = load_themes()

    def build_apkg(col) -> None:
        for key, path in (("miledown", MILEDOWN), ("pankow", PANKOW)):
            print(f"[APKG] importing {key} ...", flush=True)
            col.import_builtin_deck(
                package_path=path, deck_key=key, parent_deck=PARENT, force=True
            )

    def build_migrate(col) -> None:
        # Simulate a collection produced by the PRIOR reorg: flat
        # "SpeedyCAT MCAT::<theme>" subdecks that must migrate up to top level.
        notetype = col.models.by_name("Basic") or col.models.all()[0]
        print("[MIGRATE] filing notes under 'SpeedyCAT MCAT::<theme>' ...", flush=True)
        for theme, count in EXPECTED_COUNTS.items():
            did = col.decks.id(f"{PARENT}::{theme}", create=True)
            for _ in range(count):
                note = col.new_note(notetype)
                note.fields[0] = "Q"
                note.fields[1] = "A"
                col.add_note(note, did)

    def build_json(col) -> None:
        # Reproduce exactly what rslib's import_builtin_json_deck builds from
        # cards.json: file each note under "parent::<source>::<topic>" (empty
        # components skipped, "::" collapsed to ":"), so we verify the reorg on
        # the app's real layout without needing the (JSON-aware) backend rebuilt.
        with open(CARDS_JSON, encoding="utf-8") as fh:
            cards = json.load(fh)["cards"]
        notetype = col.models.by_name("Basic") or col.models.all()[0]
        print(f"[JSON] filing {len(cards)} notes under parent::source::topic ...",
              flush=True)
        for card in cards:
            name = PARENT
            for comp in (card.get("source", ""), card.get("topic", "")):
                comp = comp.replace("::", ":").strip()
                if comp:
                    name += "::" + comp
            did = col.decks.id(name, create=True)
            note = col.new_note(notetype)
            note.fields[0] = "Q"
            note.fields[1] = "A"
            col.add_note(note, did)

    failures = check_layout("APKG", build_apkg, themes)
    failures += check_layout("JSON", build_json, themes)
    failures += check_layout("MIGRATE", build_migrate, themes)

    print("\n" + "=" * 60)
    if failures:
        print(f"FAIL ({len(failures)} problem(s)):")
        for f in failures:
            print(f"  - {f}")
        sys.exit(1)
    print(
        f"PASS — both APKG and JSON layouts collapse to {len(EXPECTED_COUNTS)} "
        f"TOP-LEVEL thematic decks (no 'SpeedyCAT MCAT' parent), {EXPECTED_TOTAL} "
        f"cards, no source/unit tokens, idempotent."
    )


if __name__ == "__main__":
    main()
