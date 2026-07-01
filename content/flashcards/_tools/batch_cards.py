"""Split content/flashcards/cards.json into 20 self-contained eval batches.

Batches are SLIM (no bulky rendered HTML) and PRETTY-PRINTED so an eval
subagent can page through one file with Read offset/limit. Each record keeps:
  ids, deck, notetype, ord, question_text, answer_text, raw `fields`, tags.

Also writes INDEX.json with totals and per-batch ranges.
"""

from __future__ import annotations

import json
import os

ROOT = r"C:\alpha_ai\speedrun\content\flashcards"
SRC = os.path.join(ROOT, "cards.json")
OUTDIR = os.path.join(ROOT, "batches")
NUM_BATCHES = 20


def slim(c: dict) -> dict:
    return {
        "card_id": c["card_id"],
        "note_id": c["note_id"],
        "ord": c["ord"],
        "source": c.get("source", ""),
        "topic": c.get("topic", ""),
        "notetype": c["notetype"],
        "question_text": c["question_text"],
        "answer_text": c["answer_text"],
        "fields": c["fields"],
        "tags": c["tags"],
    }


def main() -> None:
    with open(SRC, encoding="utf-8") as fh:
        data = json.load(fh)
    cards = data["cards"]
    n = len(cards)
    os.makedirs(OUTDIR, exist_ok=True)

    sizes = [n // NUM_BATCHES + (1 if i < n % NUM_BATCHES else 0) for i in range(NUM_BATCHES)]

    notetype_counts: dict[str, int] = {}
    for c in cards:
        k = c.get("notetype", "")
        notetype_counts[k] = notetype_counts.get(k, 0) + 1

    checks = [
        "(a) question/answer correspond: the rendered answer actually answers/fills the question (for cloze, the revealed deletion fits the blank); flag empty answers, answer==question, or clear non-correspondence.",
        "(b) text well-formed: question_text/answer_text non-empty and readable; no unrendered template artifacts ('{{', '}}', 'FrontSide', '{{c1::'), no obviously garbled/broken text; note image-only cards (no usable text).",
        "(c) regex/LaTeX/cloze markup valid & balanced in the raw `fields`: cloze {{cN::...}} well-formed & balanced; MathJax/LaTeX delimiters balanced ( \\(..\\), \\[..\\], [$]..[/$], [$$]..[/$$], $$..$$ ); HTML tags reasonably balanced.",
    ]

    index = {
        "meta": {
            "title": "SpeedyCAT flashcard eval batches (slim)",
            "generatedOn": "2026-06-30",
            "total_cards": n,
            "total_notes": len({c["note_id"] for c in cards}),
            "num_batches": NUM_BATCHES,
            "by_source": data.get("meta", {}).get("by_source", {}),
            "by_notetype": notetype_counts,
            "checks": checks,
            "note": "All cards are cloze-type; `fields` holds the authored cloze/LaTeX/HTML markup to validate for check (c).",
        },
        "batches": [],
    }

    start = 0
    max_chars = 0
    max_lines = 0
    for i in range(NUM_BATCHES):
        size = sizes[i]
        chunk = [slim(c) for c in cards[start : start + size]]
        raw_chunk = cards[start : start + size]
        start += size
        name = f"batch_{i + 1:02d}.json"
        payload = {
            "meta": {
                "batch": i + 1,
                "file": name,
                "card_count": len(chunk),
                "note_count": len({c["note_id"] for c in raw_chunk}),
                "card_id_range": (
                    [chunk[0]["card_id"], chunk[-1]["card_id"]] if chunk else None
                ),
                "eval_checks": checks,
            },
            "cards": chunk,
        }
        text = json.dumps(payload, ensure_ascii=False, indent=2)
        path = os.path.join(OUTDIR, name)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(text)
        max_chars = max(max_chars, len(text))
        max_lines = max(max_lines, text.count("\n") + 1)
        index["batches"].append(
            {
                "batch": i + 1,
                "file": name,
                "card_count": len(chunk),
                "note_count": payload["meta"]["note_count"],
                "card_id_range": payload["meta"]["card_id_range"],
            }
        )

    with open(os.path.join(OUTDIR, "INDEX.json"), "w", encoding="utf-8") as fh:
        json.dump(index, fh, ensure_ascii=False, indent=2)

    print(
        f"WROTE {NUM_BATCHES} slim batches to {OUTDIR}\n"
        f"  total_cards={n} sizes={sizes[0]}..{sizes[-1]}\n"
        f"  largest batch: {max_chars:,} chars / {max_lines:,} lines",
        flush=True,
    )


if __name__ == "__main__":
    main()
