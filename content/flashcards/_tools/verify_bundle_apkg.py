"""Authoritative verification of a built SpeedyCAT ``.apkg`` (engine-based).

Run from anki/ with the built venv python (SKIP_RUN=1), e.g.:

    cd anki
    $env:SKIP_RUN="1"; $env:PYTHONIOENCODING="utf-8"
    out\\pyenv\\Scripts\\python.exe ..\\content\\flashcards\\_tools\\verify_bundle_apkg.py
    # or point it at any package:
    ...\\python.exe ..\\content\\flashcards\\_tools\\verify_bundle_apkg.py <path-to.apkg>

Unlike ``verify_deck_reorg.py`` (which simulates the import layouts to exercise the
reorg logic), this opens an ACTUAL ``.apkg`` file through the real Anki engine and
asserts the shipped artifact is both:

  * LABEL-CLEAN — zero case-insensitive matches for ``miledown`` / ``pankow`` /
    ``mrpankow`` / ``speedycat mcat`` in tag strings, notetype/model names and a
    full scan of field contents; and deck names carry no source/unit token at all
    (reusing ``verify_deck_reorg.deck_leaks``: MileDown/Pankow/MrPankow/P/S Deck/
    the blue-heart emoji/bare "SpeedyCAT"/AAMC unit codes 6A..9C), and
  * COMPLETE — the total AND per-source card/note counts are preserved
    (MileDown + Mr. Pankow, traced from cards.json via each note's
    ``speedycat-<card_id>`` guid) and the deck tree is exactly the expected ~19
    TOP-LEVEL thematic decks.

Prints the full deck list + counts and a PASS/FAIL summary (exit code 0/1).
"""

from __future__ import annotations

import collections
import importlib.util
import json
import os
import sys
import tempfile

sys.stdout.reconfigure(encoding="utf-8")
sys.path[0:0] = ["pylib", "out/pylib"]

REPO_ROOT = os.path.abspath(os.path.join(os.getcwd(), ".."))
CARDS_JSON = os.path.join(REPO_ROOT, "content", "flashcards", "cards.json")
VERIFY_REORG_PY = os.path.join(
    REPO_ROOT, "content", "flashcards", "_tools", "verify_deck_reorg.py"
)
DEFAULT_APKG = os.path.join(
    REPO_ROOT,
    "Anki-Android",
    "AnkiDroid",
    "src",
    "main",
    "assets",
    "speedycat",
    "speedycat-mcat.apkg",
)

# Authoritative source strings that must never appear in tags/notetypes/fields
# (case-insensitive). Note: the bare "SpeedyCAT" app marker (tag + notetype) is
# intentionally allowed — only the old "SpeedyCAT MCAT" parent-deck label is not.
FORBIDDEN = ("miledown", "pankow", "mrpankow", "speedycat mcat")


def _load(path: str, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec and spec.loader
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


def source_by_card_id() -> dict[int, str]:
    with open(CARDS_JSON, encoding="utf-8") as fh:
        cards = json.load(fh)["cards"]
    return {int(c["card_id"]): c.get("source", "unknown") for c in cards}


def scan(text: str) -> list[str]:
    low = text.lower()
    return [tok for tok in FORBIDDEN if tok in low]


def main() -> None:
    apkg = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_APKG
    if not os.path.isfile(apkg):
        raise SystemExit(f"missing apkg: {apkg}")
    if not os.path.isfile(CARDS_JSON):
        raise SystemExit(f"missing cards.json: {CARDS_JSON}")

    reorg = _load(VERIFY_REORG_PY, "verify_deck_reorg_src")
    expected_counts: dict[str, int] = reorg.EXPECTED_COUNTS
    expected_total: int = reorg.EXPECTED_TOTAL
    deck_leaks = reorg.deck_leaks

    src_by_id = source_by_card_id()
    src_total = collections.Counter(src_by_id.values())

    from anki.collection import Collection

    failures: list[str] = []
    tmp = tempfile.mkdtemp(prefix="speedycat_verifyapkg_")
    col = Collection(os.path.join(tmp, "collection.anki2"))
    try:
        print(f"### verifying {apkg}")
        col.import_builtin_deck(
            package_path=apkg, deck_key="verify", parent_deck="", force=True
        )

        cards_total = len(list(col.find_cards("")))
        notes_total = len(list(col.find_notes("")))

        # ---- deck list + per-deck card counts ----
        all_decks = sorted(col.decks.all_names_and_ids(), key=lambda d: d.name)
        theme_decks = [d for d in all_decks if d.name != "Default"]
        theme_counts: dict[str, int] = {}
        print("=== TOP-LEVEL deck list ===")
        for d in all_decks:
            n = col.decks.card_count(d.id, include_subdecks=False)
            if d.name != "Default":
                theme_counts[d.name] = n
            print(f"  cards={n:5d}  {d.name!r}")

        # (a) deck names purely thematic + top-level, no source/unit token
        for d in all_decks:
            tok = deck_leaks(d.name)
            if tok:
                failures.append(f"(deck) {d.name!r} leaks token {tok!r}")
        nested = [d.name for d in theme_decks if "::" in d.name]
        if nested:
            failures.append(f"(deck) non-top-level decks remain: {nested}")

        names = {d.name for d in theme_decks}
        unexpected = sorted(names - set(expected_counts))
        missing = sorted(set(expected_counts) - names)
        if unexpected:
            failures.append(f"(deck) unexpected decks: {unexpected}")
        if missing:
            failures.append(f"(deck) expected thematic decks missing: {missing}")
        for theme, want in expected_counts.items():
            got = theme_counts.get(theme)
            if got != want:
                failures.append(f"(deck) count {theme!r}: got {got}, want {want}")

        # (b) notetype/model names clean
        print("=== notetypes ===")
        for nt in col.models.all():
            name = nt["name"]
            print(f"  {name!r}")
            hits = scan(name)
            if hits:
                failures.append(f"(notetype) {name!r} contains {hits}")

        # (c) tags clean
        all_tags = set()
        for nid in col.find_notes(""):
            all_tags.update(col.get_note(nid).tags)
        print(f"=== tags ({len(all_tags)} distinct) ===")
        print("  " + ", ".join(sorted(all_tags)[:40]) + (" ..." if len(all_tags) > 40 else ""))
        for tag in all_tags:
            hits = scan(tag)
            if hits:
                failures.append(f"(tag) {tag!r} contains {hits}")

        # (d) field contents clean (full scan)
        field_leaks: list[str] = []
        for nid in col.find_notes(""):
            note = col.get_note(nid)
            for fname, val in note.items():
                hits = scan(val)
                if hits:
                    field_leaks.append(f"note {nid} field {fname!r}: {hits}")
        if field_leaks:
            failures.append(f"(field) {len(field_leaks)} field leak(s), e.g. {field_leaks[:5]}")

        # (e) per-source completeness (traced from cards.json via guid)
        by_source: collections.Counter = collections.Counter()
        for nid in col.find_notes(""):
            guid = col.get_note(nid).guid
            try:
                cid = int(guid.rsplit("-", 1)[1])
            except (IndexError, ValueError):
                by_source["<no-card-id-guid>"] += 1
                continue
            by_source[src_by_id.get(cid, "<unmapped>")] += 1

        print("=== counts ===")
        print(f"  cards={cards_total} notes={notes_total} (expected total {expected_total})")
        print(f"  by_source(built)   = {dict(sorted(by_source.items()))}")
        print(f"  by_source(cards.json) = {dict(sorted(src_total.items()))}")

        if cards_total != expected_total:
            failures.append(f"(count) cards {cards_total} != {expected_total}")
        for source, want in src_total.items():
            got = by_source.get(source, 0)
            if got != want:
                failures.append(f"(source) {source!r}: got {got}, want {want}")
        if by_source.get("<unmapped>") or by_source.get("<no-card-id-guid>"):
            failures.append(f"(source) un-traceable notes: {dict(by_source)}")
    finally:
        col.close()
        import shutil

        shutil.rmtree(tmp, ignore_errors=True)

    print("\n" + "=" * 60)
    if failures:
        print(f"FAIL ({len(failures)} problem(s)):")
        for f in failures:
            print(f"  - {f}")
        sys.exit(1)
    print(
        f"PASS — {apkg}\n"
        f"  {cards_total} cards / {notes_total} notes across "
        f"{len(theme_counts)} TOP-LEVEL thematic decks; "
        f"per-source {dict(sorted(by_source.items()))}; "
        f"no source strings in decks/tags/notetypes/fields."
    )


if __name__ == "__main__":
    main()
