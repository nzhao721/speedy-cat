"""Build the shipped SpeedyCAT bundled deck package (``speedycat-mcat.apkg``).

Run from anki/ with the built venv python (SKIP_RUN=1), e.g.:

    cd anki
    $env:SKIP_RUN="1"; $env:PYTHONIOENCODING="utf-8"
    out\\pyenv\\Scripts\\python.exe ..\\content\\flashcards\\_tools\\build_bundle_apkg.py

Why this exists
---------------
Mobile (Anki-Android) does NOT run the desktop ``aqt`` layer, so the desktop-only
runtime reorg (``aqt.speedycat_themes.reorganize_into_themes``) never reaches it.
The mobile app just imports a prebuilt ``.apkg`` from its assets. If that ``.apkg``
is built from the raw community decks it keeps the original source deck tree,
source tags and source-named notetypes — and, worse, a small "starter" subset
drops most of the MileDown cards.

This tool bakes the cleanup into the shipped data by reproducing the EXACT desktop
first-run pipeline against the already-clean, already-complete ``cards.json`` and
exporting the result as an ``.apkg``, so desktop and mobile ship an identical,
label-free, COMPLETE collection with no runtime transformation required:

  1. import ``content/flashcards/cards.json`` via the rslib JSON importer
     (``Collection.import_builtin_deck``) -> notes filed under
     ``SpeedyCAT MCAT::<source>::<topic>`` with the neutral ``SpeedyCAT`` notetype
     and topic-only tags,
  2. ``reorganize_into_themes`` (``qt/aqt/speedycat_themes.py`` — the single source
     of truth shared with the desktop app) -> flat TOP-LEVEL thematic decks; the
     ``SpeedyCAT MCAT`` parent and every source container are deleted,
  3. export the whole collection as a text-only ``.apkg`` (no media, no
     scheduling),
  4. overwrite ``Anki-Android/AnkiDroid/src/main/assets/speedycat/speedycat-mcat.apkg``.

Per-source completeness is proven from ``cards.json`` (each note's guid is
``speedycat-<card_id>``), so MileDown and Mr. Pankow are both asserted fully
present before the asset is overwritten. No source string is written anywhere in
the collection (deck names, tags, notetype names, fields).

Pure pylib (the Rust-backed engine); no GUI/aqt is imported.
"""

from __future__ import annotations

import argparse
import collections
import importlib.util
import json
import os
import shutil
import sys
import tempfile

sys.path[0:0] = ["pylib", "out/pylib"]

REPO_ROOT = os.path.abspath(os.path.join(os.getcwd(), ".."))
CARDS_JSON = os.path.join(REPO_ROOT, "content", "flashcards", "cards.json")
THEMES_PY = os.path.join(REPO_ROOT, "anki", "qt", "aqt", "speedycat_themes.py")
DEFAULT_OUT = os.path.join(
    REPO_ROOT,
    "Anki-Android",
    "AnkiDroid",
    "src",
    "main",
    "assets",
    "speedycat",
    "speedycat-mcat.apkg",
)

PARENT_DECK = "SpeedyCAT MCAT"
DECK_KEY = "speedycat"


def load_themes():
    """Load qt/aqt/speedycat_themes.py as a standalone module (its top level is
    aqt-free) so we reuse the exact thematic mapping the desktop app uses."""
    spec = importlib.util.spec_from_file_location("speedycat_themes_src", THEMES_PY)
    assert spec and spec.loader
    mod = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = mod  # so dataclasses can resolve cls.__module__
    spec.loader.exec_module(mod)
    return mod


def source_by_card_id() -> dict[int, str]:
    """Map each card_id -> its original source label, straight from cards.json.

    This is the ground truth for per-source completeness. The guid the JSON
    importer assigns is ``speedycat-<card_id>``, so after import we can map every
    note back to the source it came from WITHOUT storing any source string in the
    shipped collection.
    """
    with open(CARDS_JSON, encoding="utf-8") as fh:
        cards = json.load(fh)["cards"]
    return {int(c["card_id"]): c.get("source", "unknown") for c in cards}


def per_source_counts(col, src_by_id: dict[int, str]) -> collections.Counter:
    counts: collections.Counter = collections.Counter()
    for nid in col.find_notes(""):
        guid = col.get_note(nid).guid
        try:
            cid = int(guid.rsplit("-", 1)[1])
        except (IndexError, ValueError):
            counts["<no-card-id-guid>"] += 1
            continue
        counts[src_by_id.get(cid, "<unmapped>")] += 1
    return counts


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--out",
        default=DEFAULT_OUT,
        help="output .apkg path (default: the Anki-Android bundled asset)",
    )
    args = parser.parse_args()

    for p in (CARDS_JSON, THEMES_PY):
        if not os.path.isfile(p):
            raise SystemExit(f"missing required file: {p}")

    from anki.collection import Collection, ExportAnkiPackageOptions

    themes = load_themes()
    src_by_id = source_by_card_id()
    src_total = collections.Counter(src_by_id.values())
    print(
        f"cards.json: {len(src_by_id)} cards; by_source={dict(sorted(src_total.items()))}",
        flush=True,
    )

    tmp = tempfile.mkdtemp(prefix="speedycat_apkg_")
    col = Collection(os.path.join(tmp, "collection.anki2"))
    tmp_apkg = os.path.join(tmp, "speedycat-mcat.apkg")
    try:
        print("importing cards.json via engine (JSON builtin importer) ...", flush=True)
        res = col.import_builtin_deck(
            package_path=CARDS_JSON,
            deck_key=DECK_KEY,
            parent_deck=PARENT_DECK,
            force=True,
        )
        print(f"  imported={res.imported} note_count={res.note_count}", flush=True)

        reorg = themes.reorganize_into_themes(col)
        print(
            f"reorganized into {len(reorg.themes)} thematic decks "
            f"(moved {reorg.moved} cards; removed {reorg.removed})",
            flush=True,
        )

        cards_total = len(list(col.find_cards("")))
        notes_total = len(list(col.find_notes("")))
        by_source = per_source_counts(col, src_by_id)
        print(
            f"built collection: cards={cards_total} notes={notes_total} "
            f"by_source={dict(sorted(by_source.items()))}",
            flush=True,
        )

        # Per-source completeness gate: refuse to ship if either source lost cards.
        problems = []
        for source, want in src_total.items():
            got = by_source.get(source, 0)
            if got != want:
                problems.append(f"source {source!r}: built {got} != cards.json {want}")
        if by_source.get("<unmapped>") or by_source.get("<no-card-id-guid>"):
            problems.append(f"un-traceable notes present: {dict(by_source)}")
        if notes_total != len(src_by_id):
            problems.append(
                f"note total {notes_total} != cards.json card records {len(src_by_id)}"
            )
        if problems:
            raise SystemExit("PER-SOURCE COMPLETENESS FAILED:\n  " + "\n  ".join(problems))

        print(f"exporting text-only .apkg -> {tmp_apkg}", flush=True)
        col.export_anki_package(
            out_path=tmp_apkg,
            options=ExportAnkiPackageOptions(
                with_scheduling=False,
                with_deck_configs=False,
                with_media=False,
                legacy=True,
            ),
            limit=None,  # whole collection
        )
    finally:
        col.close()

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    shutil.copyfile(tmp_apkg, args.out)
    size_mb = os.path.getsize(args.out) / (1024 * 1024)
    shutil.rmtree(tmp, ignore_errors=True)

    print(
        f"WROTE {args.out} ({size_mb:.2f} MB) — {len(src_by_id)} cards, "
        f"by_source={dict(sorted(src_total.items()))}",
        flush=True,
    )


if __name__ == "__main__":
    main()
