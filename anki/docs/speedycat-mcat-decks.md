# SpeedyCAT built-in MCAT deck

SpeedyCAT ships a ready-to-study built-in flashcard deck so users don't have to
hunt for and import deck files manually. It is composed of two **free,
redistributable** community MCAT Anki decks, imported and then reorganized into
purely **thematic, top-level topic decks** (bare names like `Biochemistry`).
There is **no `SpeedyCAT MCAT` wrapper deck**, and the deck tree reveals
**nothing** about where a card came from (no author names, no "P/S Deck", no
AAMC unit codes like `6A`/`7B`/`9C`).

> CARS is intentionally **not** covered by flashcards.
>
> The paid **AnKing** MCAT deck is **not** used: it is gated behind AnkiHub
> (paid login) and is not legally redistributable. It is a possible future
> *optional* add-on for users who own it, not a bundled default.

## Decks, authors, sources, licensing

| Deck | Author | Cards (verified) | Sections | Source (attribution) |
| --- | --- | --- | --- | --- |
| MileDown MCAT | u/MileDown | 2,888 cards / 2,885 notes | CPBS, BBLS, PSBB + Essential Equations | r/MCAT community deck. Direct mirror: <https://github.com/DarrenPHS/DarrenPHS/releases/download/1.1/MileDown.apkg> |
| Mr. Pankow P/S | Mr. Pankow | 2,254 cards / 1,761 notes | Psychology/Sociology (PSBB) | AnkiWeb shared deck `710572280`. Mirror: Google Drive id `1Vy6zqbdHcdBaAGKC_RPaDy-yJSkZ5IVP` |

## Thematic organization (what the user sees)

After import, the original source/unit subdeck structure is **collapsed into a
flat set of top-level thematic topic decks** by `reorganize_into_themes` (in
`qt/aqt/speedycat_themes.py`, called from `qt/aqt/speedycat.py` right after the
import). Cards are moved into bare top-level `<Theme>` decks and the now-empty
`SpeedyCAT MCAT` parent is deleted, so the user only ever sees topics — never the
source, and never a leftover wrapper deck. The final deck list is exactly these
**19 top-level decks** (5,142 cards total):

| Deck (top-level) | Cards | Built from |
| --- | ---: | --- |
| Behavioral Sciences | 828 | MileDown · Behavioral |
| Biology | 604 | MileDown · Biology |
| Biochemistry | 515 | MileDown · Biochemistry |
| Organic Chemistry | 287 | MileDown · Organic Chemistry |
| General Chemistry | 250 | MileDown · General Chemistry |
| Physics and Math | 313 | MileDown · Physics and Math |
| Equations & Constants | 91 | MileDown · Essential Equations |
| Sensation & Perception | 254 | Pankow · 6A |
| Learning, Memory & Cognition | 381 | Pankow · 6B |
| Emotion, Stress & Motivation | 145 | Pankow · 6C |
| Identity, Personality & Biological Bases of Behavior | 617 | Pankow · 7A |
| Social Processes & Socialization | 43 | Pankow · 7B |
| Attitudes & Behavior Change | 111 | Pankow · 7C |
| Self-Identity & Social Cognition | 162 | Pankow · 8A/8B |
| Social Interactions | 120 | Pankow · 8C (+ Social Behavior, Social Interactions, Biological Explanations…) |
| Social Structures & Institutions | 73 | Pankow · 9A (+ Social Structures) |
| Demographics & Culture | 98 | Pankow · 9B (+ Culture, Demographics) |
| Social Inequality | 32 | Pankow · 9C Social Inequality |
| Research Methods & Statistics | 218 | Pankow · 9C Statistics/Study Types/Unformatted Extras + loose P/S cards |

The AAMC unit codes in the "Built from" column are internal only — they never
appear in a deck name. The mapping table (`THEME_BY_SOURCE_DECK`, plus the
JSON `source::topic` handling) is the single source of truth and lives in
`qt/aqt/speedycat_themes.py`, which is deliberately `aqt`-free so the dataset
tooling and verification can reuse it. It handles **both** layouts:

- **JSON** (the shipped `cards.json`): the importer files each note under
  `SpeedyCAT MCAT::<source>::<topic>`; the reorg drops the `<source>` level and
  lifts the already-thematic `<topic>` up to the top level.
- **`.apkg`** (tests/flexibility): the community decks' own
  `MileDown's MCAT Decks::…` and `MCAT 💙::P/S Deck::<unit>` structure is mapped
  to a top-level theme via `THEME_BY_SOURCE_DECK`.

`reorganize_into_themes` is **idempotent** and runs on every import (including a
forced re-import). It also **migrates** a collection produced by an earlier
version (flat `SpeedyCAT MCAT::<theme>` subdecks) up to the top level and removes
the parent, so a user whose deck predates this change can re-run
**Tools → Add SpeedyCAT MCAT Deck** to fix it in place. Verify all layouts with
`content/flashcards/_tools/verify_deck_reorg.py` (imports the two `.apkg`
packages, reproduces the JSON layout, and simulates the old
`SpeedyCAT MCAT::<theme>` layout, asserting 19 **top-level** decks / no
`SpeedyCAT MCAT` parent / 5,142 cards / no source or unit tokens / idempotent).

Both decks are free community resources distributed publicly via r/MCAT,
AnkiWeb, and Google Drive mirrors. They carry no explicit SPDX license; they are
shared for free, non-commercial student use, and attribution to the original
authors is preserved here and shown in-app on import.

SpeedyCAT itself remains **AGPL-3.0-or-later** and preserves Anki attribution
(see `../../UPSTREAM.md` and the in-app About screen). Anki is created by Damien
Elmes and the Anki contributors.

## How the user gets the deck

- **First run:** on first profile open, SpeedyCAT **auto-imports** the bundled
  deck silently (no prompt, no download).
- **On demand / repair:** anytime via **Tools → Add SpeedyCAT MCAT Deck**, which
  force-reimports and re-runs the thematic reorganization in place.

The import is **idempotent** — the automatic path is a no-op once done (tracked
by the collection config marker `speedycat_builtin_deck_speedycat`), so the deck
is never duplicated. Mobile (AnkiDroid) receives the deck automatically via
normal Anki **sync**; no AnkiDroid changes are required.

## Asset delivery

The app ships a single, text-only **`cards.json`** (pre-rendered question/answer
HTML, no media) under the app data folder (`<data>/speedycat/cards.json`; source
copy: `qt/aqt/data/speedycat/cards.json`). `qt/aqt/speedycat.py` resolves it in
this order:

1. `SPEEDYCAT_DECK_DIR` environment variable — a directory holding an alternate
   `cards.json` (lets a developer/installer override the bundle).
2. The bundled copy in the app data folder.

`cards.json` is generated from the two community `.apkg` packages by the dataset
pipeline (`content/flashcards/_tools/fix_cards.py`): it repairs/normalizes the
cards, assigns the thematic `topic` (via `reorganize_into_themes`), and
consolidates attribution into a single `source` field. Regenerate with
`fix_cards.py` then `batch_cards.py`, and copy the result to
`qt/aqt/data/speedycat/cards.json`.

## Engine change

The import is driven by a real backend change in the Rust engine (not a
Python-only screen):

- Proto: `ImportBuiltinDeck` RPC + `ImportBuiltinDeckRequest` /
  `ImportBuiltinDeckResponse` in `proto/anki/import_export.proto`.
- Rust: `Collection::add_builtin_deck` in
  `rslib/src/import_export/builtin.rs` — imports either the bundled `.json` (via
  the native `ForeignData` importer, filing notes under
  `parent::<source>::<topic>`) or an `.apkg`, and records the idempotency
  marker; wired through `rslib/src/import_export/service.rs`.
- Python: `Collection.import_builtin_deck` in `pylib/anki/collection.py`.
- Qt: `qt/aqt/speedycat.py` (bundled-deck resolution + auto-import + Tools
  action) and `qt/aqt/speedycat_themes.py` (the thematic reorganization),
  registered from `qt/aqt/main.py`.

The thematic reorganization is a Python post-import step, but it still executes
through the Rust backend: each move is `col.set_deck(card_ids, deck_id)` into a
bare top-level `<Theme>` deck, and the emptied `SpeedyCAT MCAT` parent subtree is
dropped with `col.decks.remove(...)` (backend `set_deck` / `remove_decks` RPCs).
It is idempotent and lossless (card count is unchanged), so it doubles as the
repair path for pre-existing installs.

> Note: the `.json` import branch in `builtin.rs` requires the Rust backend to
> be compiled (`tools\ninja pylib`). The Python reorganization needs no rebuild.
