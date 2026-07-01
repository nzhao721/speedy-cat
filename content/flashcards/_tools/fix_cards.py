"""Repair failed-eval cards AND normalize source metadata, via the Anki engine.

Run from anki/ with the venv python (SKIP_RUN=1).

Content fixes (study-breaking):
  - empty cloze deletions  {{cN::}}  -> removed,
  - cloze notes with NO deletion -> a real {{c1::...}} added (tailored/fallback),
  - broken reference links ('ahttps://', stray &quot; in href) -> repaired.

Source normalization (per user request — keep exactly ONE source field):
  - strip the 'MileDown::' (any source root) prefix from every tag, so tags are
    topic-only AND the template-rendered breadcrumb in the Q/A text is cleaned,
  - emit a single canonical `source` field per card,
  - replace `deck` with a thematic `topic` (the same flat themes the app shows,
    produced by speedycat.reorganize_into_themes — never a source/unit token),
  - source-neutralize the `notetype` name,
  - drop `source_deck_key` / `deck`.

Outputs corrected content/flashcards/cards.json + reports/fixes.json, and
verifies (a) 0 content defects remain and (b) 0 source leakage outside `source`.
"""

from __future__ import annotations

import html
import importlib.util
import json
import os
import re
import sys
import tempfile

sys.path[0:0] = ["pylib", "out/pylib"]

REPO_ROOT = os.path.abspath(os.path.join(os.getcwd(), ".."))
PKG_DIR = os.path.join(REPO_ROOT, "content", "flashcards", "_packages")
OUT_JSON = os.path.join(REPO_ROOT, "content", "flashcards", "cards.json")
FIXES_JSON = os.path.join(REPO_ROOT, "content", "flashcards", "reports", "fixes.json")
MILEDOWN = os.path.join(PKG_DIR, "MileDown.apkg")
PANKOW = os.path.join(PKG_DIR, "Pankow.apkg")
# the thematic reorg is shared with the app (single source of truth)
SPEEDYCAT_THEMES_PY = os.path.join(
    REPO_ROOT, "anki", "qt", "aqt", "speedycat_themes.py"
)
PARENT_DECK = "SpeedyCAT MCAT"

EMPTY_CLOZE = re.compile(r"\{\{c\d+::\s*\}\}")
HAS_CLOZE = re.compile(r"\{\{c\d+::\s*\S")

# Single canonical source label per deck (the ONLY place a source is named).
SOURCE_LABELS = {"miledown": "MileDown MCAT", "pankow": "Mr. Pankow P/S"}
# Source/author tokens to scrub from tags, notetypes, topics, text.
SOURCE_TOKENS = ["MileDown", "MrPankow", "Pankow"]
TAG_SOURCE_ROOTS = ("MileDown", "MrPankow", "Pankow")
LEAK_RE = re.compile(r"(?i)(miledown|pankow)")

TAILORED = {
    1554178673134: {
        "expect": "Deposition is the phase change", "field": "Text",
        "new": "{{c1::Deposition}} is the phase change from gas to solid",
    },
    1556150381528: {
        "expect": "Positive and Negative", "field": "Text",
        "new": "<div>{{c1::Positive and Negative Selection}} of T-cells</div>",
    },
}

_TAG_RE = re.compile(r"<[^>]+>")
_WS_RE = re.compile(r"\s+")


def to_text(s: str) -> str:
    s = s.replace("\u00a0", " ")
    s = re.sub(r"(?is)<style.*?</style>", " ", s)
    s = re.sub(r"(?is)<script.*?</script>", " ", s)
    s = _TAG_RE.sub(" ", s)
    s = html.unescape(s)
    return _WS_RE.sub(" ", s).strip()


def fix_links(val: str) -> str:
    out = val.replace('href="ahttps://', 'href="https://').replace(
        'href="ahttp://', 'href="http://'
    )
    out = out.replace('href="&quot;', 'href="').replace('&quot;">', '">')
    return out


def strip_tag_source(tag: str) -> str:
    for root in TAG_SOURCE_ROOTS:
        if tag == root:
            return ""
        if tag.startswith(root + "::"):
            return tag[len(root) + 2 :]
    return tag


def clean_notetype(nt: str) -> str:
    for tok in SOURCE_TOKENS:
        nt = nt.replace(tok, "")
    nt = re.sub(r"-{2,}", "-", nt).strip("-").strip()
    return nt or "Cloze"


def load_speedycat_themes():
    """Load qt/aqt/speedycat_themes.py as a standalone module (its top level is
    aqt-free), so the dataset reuses the exact same thematic mapping as the app."""
    spec = importlib.util.spec_from_file_location(
        "speedycat_themes_src", SPEEDYCAT_THEMES_PY
    )
    assert spec and spec.loader
    mod = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = mod  # so dataclasses can resolve cls.__module__
    spec.loader.exec_module(mod)
    return mod


def topic_from_deck(deck: str) -> str:
    """The thematic topic is just the deck name under the parent. After
    reorganize_into_themes the tree is flat, so this is the theme itself."""
    prefix = PARENT_DECK + "::"
    return deck[len(prefix) :] if deck.startswith(prefix) else deck


def main() -> None:
    for p in (MILEDOWN, PANKOW):
        if not os.path.isfile(p):
            raise SystemExit(f"missing package {p}; run extract_cards.py first")

    from anki.collection import Collection

    themes = load_speedycat_themes()

    tmp = tempfile.mkdtemp(prefix="speedycat_fix_")
    col = Collection(os.path.join(tmp, "collection.anki2"))
    card_source: dict[int, str] = {}
    seen: set[int] = set()
    fixes: list[dict] = []
    tags_stripped_notes = 0
    try:
        for key, path in (("miledown", MILEDOWN), ("pankow", PANKOW)):
            print(f"importing {key} ...", flush=True)
            col.import_builtin_deck(
                package_path=path, deck_key=key, parent_deck=PARENT_DECK, force=True
            )
            now = {int(c) for c in col.find_cards("")}
            for cid in now - seen:
                card_source[cid] = key
            seen = now

        # Group cards into the same flat thematic topics the app shows (this is
        # what makes `topic` source-free). Card ids are stable across the move,
        # so card_source stays valid.
        reorg = themes.reorganize_into_themes(col)
        print(
            f"reorganized into {len(reorg.themes)} thematic decks "
            f"(moved {reorg.moved} cards)",
            flush=True,
        )

        all_nids = list(col.find_notes(""))
        print(f"scanning/fixing/normalizing {len(all_nids)} notes ...", flush=True)

        def deck_of(note) -> str:
            cids = note.card_ids()
            return col.decks.name(col.get_card(cids[0]).did) if cids else ""

        for nid in all_nids:
            note = col.get_note(nid)
            names = note.keys()
            changed = False

            # --- content fixes: empty clozes + broken links (any field) ---
            for fname in names:
                val = note[fname]
                new = val
                if EMPTY_CLOZE.search(new):
                    removed = new
                    new = EMPTY_CLOZE.sub("", new)
                    fixes.append({"note_id": int(nid), "deck": deck_of(note),
                                  "type": "empty_cloze_removed", "field": fname,
                                  "before": removed[:200], "after": new[:200]})
                if ('href="ahttp' in new) or ('href="&quot;' in new) or ('&quot;">' in new):
                    before = new
                    new = fix_links(new)
                    if new != before:
                        fixes.append({"note_id": int(nid), "deck": deck_of(note),
                                      "type": "broken_link_fixed", "field": fname,
                                      "before": before[:200], "after": new[:200]})
                if new != val:
                    note[fname] = new
                    changed = True

            # --- content fix: missing cloze deletion ---
            if not any(HAS_CLOZE.search(note[f]) for f in names):
                cloze_field = "Text" if "Text" in names else names[0]
                before = note[cloze_field]
                tailored = TAILORED.get(int(nid))
                if tailored and tailored["expect"] in to_text(before):
                    note[tailored["field"]] = tailored["new"]
                    ftype, after = "missing_cloze_added_tailored", tailored["new"]
                else:
                    note[cloze_field] = "{{c1::" + (before.strip() or "card") + "}}"
                    ftype, after = "missing_cloze_added_fallback", note[cloze_field]
                fixes.append({"note_id": int(nid), "deck": deck_of(note), "type": ftype,
                              "field": cloze_field, "before": before[:200], "after": after[:200]})
                changed = True

            # --- source normalization: strip source root from tags ---
            if note.tags:
                new_tags = [t for t in (strip_tag_source(x) for x in note.tags) if t]
                if new_tags != list(note.tags):
                    note.tags = new_tags
                    tags_stripped_notes += 1
                    changed = True

            if changed:
                col.update_note(note)

        # ---- content verification --------------------------------------
        remaining_missing, remaining_empty = [], []
        for nid in col.find_notes(""):
            note = col.get_note(nid)
            if any(EMPTY_CLOZE.search(note[f]) for f in note.keys()):
                remaining_empty.append(int(nid))
            if not any(HAS_CLOZE.search(note[f]) for f in note.keys()):
                remaining_missing.append(int(nid))
        render_bad = []
        for f in fixes:
            note = col.get_note(f["note_id"])
            for cid in note.card_ids():
                card = col.get_card(cid)
                if "No cloze" in card.question() or "{{c" in card.answer():
                    render_bad.append(int(cid))

        # ---- re-render -> normalized records ---------------------------
        records = []
        for cid in col.find_cards(""):
            card = col.get_card(cid)
            note = card.note()
            src_key = card_source.get(int(cid), "")
            nt = note.note_type() or {}
            records.append({
                "card_id": int(cid), "note_id": int(note.id), "ord": int(card.ord),
                "source": SOURCE_LABELS.get(src_key, src_key or "unknown"),
                "topic": topic_from_deck(col.decks.name(card.did)),
                "notetype": clean_notetype(nt.get("name", "")),
                "question_html": card.question(), "answer_html": card.answer(),
                "question_text": to_text(card.question()),
                "answer_text": to_text(card.answer()),
                "fields": dict(note.items()), "tags": list(note.tags),
            })
    finally:
        col.close()

    # ---- source-leak verification (no source token outside `source`) ---
    leaks = []
    for r in records:
        for k, v in r.items():
            if k == "source":
                continue
            if LEAK_RE.search(json.dumps(v, ensure_ascii=False)):
                leaks.append({"card_id": r["card_id"], "field": k})
                break

    note_ids = {r["note_id"] for r in records}
    by_source: dict[str, int] = {}
    for r in records:
        by_source[r["source"]] = by_source.get(r["source"], 0) + 1

    payload = {
        "meta": {
            "title": "SpeedyCAT built-in deck — flattened cards (fixed + source-normalized)",
            "generatedOn": "2026-06-30",
            "method": "engine render via anki pylib; defects repaired and source metadata consolidated to a single `source` field",
            "card_count": len(records), "note_count": len(note_ids),
            "by_source": by_source, "fixes_applied": len(fixes),
            "schema_note": "Each card has exactly one source field (`source`). `topic` is source-stripped; `tags` are topic-only; `notetype` is source-neutralized; `deck`/`source_deck_key` removed.",
        },
        "cards": records,
    }
    with open(OUT_JSON, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False)

    by_type: dict[str, int] = {}
    for f in fixes:
        by_type[f["type"]] = by_type.get(f["type"], 0) + 1
    os.makedirs(os.path.dirname(FIXES_JSON), exist_ok=True)
    with open(FIXES_JSON, "w", encoding="utf-8") as fh:
        json.dump({
            "generatedOn": "2026-06-30",
            "content_fixes_total": len(fixes), "by_type": by_type,
            "source_normalization": {
                "single_source_field": "source",
                "notes_with_tags_stripped": tags_stripped_notes,
                "dropped_fields": ["source_deck_key", "deck"],
                "added_fields": ["source", "topic"],
                "notetype_neutralized": True,
                "source_leaks_outside_source_field": len(leaks),
                "leak_examples": leaks[:20],
            },
            "verification": {
                "remaining_missing_cloze": remaining_missing,
                "remaining_empty_cloze": remaining_empty,
                "remaining_bad_render_cards": render_bad,
                "source_leaks": len(leaks),
                "passed": not (remaining_missing or remaining_empty or render_bad or leaks),
            },
            "content_fixes": fixes,
        }, fh, ensure_ascii=False, indent=2)

    print(
        f"FIXES content={len(fixes)} {by_type} | tags_stripped_notes={tags_stripped_notes} "
        f"| cards={len(records)} notes={len(note_ids)} by_source={by_source} "
        f"| remaining missing={len(remaining_missing)} empty={len(remaining_empty)} "
        f"bad_render={len(render_bad)} source_leaks={len(leaks)} "
        f"| PASS={not (remaining_missing or remaining_empty or render_bad or leaks)}",
        flush=True,
    )


if __name__ == "__main__":
    main()
