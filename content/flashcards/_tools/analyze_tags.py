"""Inspect tag structure in cards.json to find source-prefix roots."""
import collections
import json
import os

ROOT = r"C:\alpha_ai\speedrun\content\flashcards"
data = json.load(open(os.path.join(ROOT, "cards.json"), encoding="utf-8"))

tops_by_src = collections.defaultdict(collections.Counter)
example = {}
notes_with_tags = 0
total_tag_strings = 0
for c in data["cards"]:
    for t in c.get("tags", []):
        total_tag_strings += 1
        top = t.split("::", 1)[0]
        tops_by_src[c.get("source_deck_key", "")][top] += 1
        example.setdefault(top, t)

for src, counter in tops_by_src.items():
    print(f"\n=== TAGS source_deck_key={src!r} ===")
    for top, n in counter.most_common(20):
        print(f"  {n:5d}  first-seg={top!r}   e.g. {example[top]!r}")
print(f"\ntotal tag strings: {total_tag_strings}")

decks_by_src = collections.defaultdict(collections.Counter)
notetypes_by_src = collections.defaultdict(collections.Counter)
for c in data["cards"]:
    decks_by_src[c.get("source_deck_key", "")][c.get("deck", "")] += 1
    notetypes_by_src[c.get("source_deck_key", "")][c.get("notetype", "")] += 1
for src, counter in decks_by_src.items():
    print(f"\n=== DECKS source_deck_key={src!r} ({len(counter)} distinct) ===")
    for d, n in counter.most_common(40):
        print(f"  {n:5d}  {d!r}")
for src, counter in notetypes_by_src.items():
    print(f"\n=== NOTETYPES source_deck_key={src!r} ===")
    for nt, n in counter.most_common():
        print(f"  {n:5d}  {nt!r}")
