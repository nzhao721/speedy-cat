# Copyright: Ankitects Pty Ltd and contributors
# License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

"""SpeedyCAT: collapse the imported deck tree into purely THEMATIC TOP-LEVEL decks.

The bundled MCAT deck is imported under ``SpeedyCAT MCAT`` by the rslib
``import_builtin_deck`` backend. Whatever the input format, the initial tree
exposes *where* the cards came from, which the SpeedyCAT product must never
show. Two source layouts are handled:

* JSON (the shipped ``cards.json``) files each card under
  ``SpeedyCAT MCAT::<source>::<topic>`` — e.g. ``…::Mr. Pankow P/S::…`` — where
  ``<topic>`` is already a clean theme; the *source* middle level is the leak.
* ``.apkg`` (kept for tests/flexibility) preserves the community decks' own
  structure: ``…::MileDown's MCAT Decks::<topic>`` and
  ``…::MCAT 💙::P/S Deck::<AAMC unit or leaf>`` (unit codes 6A..9C).

:func:`reorganize_into_themes` moves every card into a flat set of **top-level**
theme decks (bare names like ``Biochemistry`` — *no* ``SpeedyCAT MCAT`` parent),
then deletes the now-empty ``SpeedyCAT MCAT`` parent and its source containers.
The Rust backend performs the moves via ``set_deck``. It is idempotent and
lossless, and it migrates an older ``SpeedyCAT MCAT::<theme>`` layout up to the
top level, so it doubles as the repair path for an already-imported install.

This module deliberately has **no ``aqt`` dependency** at import time so the
mapping can be exercised headlessly by the dataset tooling and the verification
script (``content/flashcards/_tools/{fix_cards,verify_deck_reorg}.py``).
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from anki.collection import Collection

PARENT_DECK = "SpeedyCAT MCAT"

# --- tag-breadcrumb cleanup (shared by the desktop app and the apkg builder) --
#
# The bundled cards are pre-rendered HTML: the dataset was produced by rendering
# the free community notes through their ORIGINAL notetypes, whose templates
# wrapped ``{{Tags}}`` in a ``<div class="tags">…</div>`` header. That rendered
# element is therefore baked into the stored Front/Back HTML of every tagged
# card, so the Browse Front/Back columns show a leading tag breadcrumb
# (e.g. ``General_Chemistry::Atomic_Structure``) before the real question/answer
# — even though the shipped ``SpeedyCAT`` notetype's own templates are just
# ``{{Front}}``/``{{Back}}`` and the raw source fields were clean.
#
# The fix strips that one wrapper from the field HTML, leaving cloze, images,
# the answer "extra" block and all styling intact. It is applied both at build
# time (baked into the shipped apkg) and as an idempotent desktop migration for
# already-imported collections.
_SPEEDYCAT_NOTETYPE = "SpeedyCAT"

# Matches the question form ``<div class="tags">…</div>`` and the answer form
# ``<div class="tags" id='tags'>…</div>``. The inner text is plain tag names
# (never nested markup), so the non-greedy body match is safe; trailing
# whitespace is swallowed to avoid leaving a blank line.
_TAG_BREADCRUMB_RE = re.compile(
    r'<div class="tags"[^>]*>.*?</div>\s*', re.IGNORECASE | re.DOTALL
)

# Cheap, self-healing guard/selector: bundled ``SpeedyCAT`` notes whose fields
# still contain the breadcrumb wrapper. An empty result means there is nothing
# to do, so the migration is a no-op once a collection is already clean. Only
# the bundled notetype is matched, so a user's own notes are never touched.
TAG_BREADCRUMB_SEARCH = f'note:{_SPEEDYCAT_NOTETYPE} "re:<div class=.tags."'


def strip_tag_breadcrumb_html(html: str) -> str:
    """Return ``html`` with the baked-in ``<div class="tags">…</div>`` breadcrumb
    wrapper removed. Idempotent: HTML without the wrapper is returned unchanged."""
    return _TAG_BREADCRUMB_RE.sub("", html)


def strip_tag_breadcrumbs(col: Collection) -> int:
    """Strip the leading tag breadcrumb from every bundled ``SpeedyCAT`` note.

    Only notes of the bundled ``SpeedyCAT`` notetype are inspected (via
    :data:`TAG_BREADCRUMB_SEARCH`), so a user's own notes are never modified.
    Returns the number of notes changed; a second run matches nothing and
    returns ``0`` (idempotent, lossless — only field HTML changes)."""
    changed = []
    for nid in col.find_notes(TAG_BREADCRUMB_SEARCH):
        note = col.get_note(nid)
        dirty = False
        for i, val in enumerate(note.fields):
            new = strip_tag_breadcrumb_html(val)
            if new != val:
                note.fields[i] = new
                dirty = True
        if dirty:
            changed.append(note)
    if changed:
        col.update_notes(changed)
    return len(changed)


# --- source deck -> theme mapping (single source of truth) -------------------
#
# AAMC Psychological/Social/Biological Foundations content categories, mapped to
# reader-friendly themes for the .apkg (Mr. Pankow) layout:
#   6A -> Sensation & Perception          6B -> Learning, Memory & Cognition
#   6C -> Emotion, Stress & Motivation    7A -> Identity, Personality & Bio Bases
#   7B -> Social Processes & Socialization 7C -> Attitudes & Behavior Change
#   8A/8B -> Self-Identity & Social Cognition   8C -> Social Interactions
#   9A -> Social Structures & Institutions 9B -> Demographics & Culture
#   9C -> Social Inequality  (Statistics/Study Types/Extras -> Research Methods…)
_MILE = "MileDown's MCAT Decks"
_PS = "MCAT \U0001f499::P/S Deck"  # "MCAT 💙::P/S Deck"

THEME_BY_SOURCE_DECK: dict[str, str] = {
    # MileDown science sections — already thematic, just source-strip.
    f"{_MILE}::Behavioral": "Behavioral Sciences",
    f"{_MILE}::Biochemistry": "Biochemistry",
    f"{_MILE}::Biology": "Biology",
    f"{_MILE}::Essential Equations": "Equations & Constants",
    f"{_MILE}::General Chemistry": "General Chemistry",
    f"{_MILE}::Organic Chemistry": "Organic Chemistry",
    f"{_MILE}::Physics and Math": "Physics and Math",
    # Mr. Pankow P/S — collapse AAMC unit codes + leaves into themes.
    _PS: "Research Methods & Statistics",  # loose, unclassified P/S cards
    f"{_PS}::MrPankow 6A": "Sensation & Perception",
    f"{_PS}::MrPankow 6B": "Learning, Memory & Cognition",
    f"{_PS}::MrPankow 6C": "Emotion, Stress & Motivation",
    f"{_PS}::MrPankow 7A": "Identity, Personality & Biological Bases of Behavior",
    f"{_PS}::MrPankow 7B": "Social Processes & Socialization",
    f"{_PS}::MrPankow 7C": "Attitudes & Behavior Change",
    f"{_PS}::MrPankow 8A/8B": "Self-Identity & Social Cognition",
    f"{_PS}::8C": "Social Interactions",
    f"{_PS}::8C::Social Behavior": "Social Interactions",
    f"{_PS}::8C::Social Interactions": "Social Interactions",
    f"{_PS}::8C::Biological Explanations of Social behavior in Animals": (
        "Social Interactions"
    ),
    f"{_PS}::9A": "Social Structures & Institutions",
    f"{_PS}::9A::Social Structures": "Social Structures & Institutions",
    f"{_PS}::9B": "Demographics & Culture",
    f"{_PS}::9B::Culture": "Demographics & Culture",
    f"{_PS}::9B::Demographics": "Demographics & Culture",
    f"{_PS}::9C": "Social Inequality",
    f"{_PS}::9C::Social Inequality": "Social Inequality",
    f"{_PS}::9C::Statistics": "Research Methods & Statistics",
    f"{_PS}::9C::Study Types": "Research Methods & Statistics",
    f"{_PS}::9C::Unformatted Extras": "Research Methods & Statistics",
}

# JSON layout: "<parent>::<source label>::<topic>". The topic is already a clean
# theme (produced by content/flashcards/_tools/fix_cards.py), so we simply drop
# the source-label middle level. Each entry's fallback covers the (unexpected)
# case of cards filed directly under the source label with no topic.
_JSON_SOURCE_LABELS: tuple[tuple[str, str], ...] = (
    ("MileDown MCAT", "Behavioral Sciences"),
    ("Mr. Pankow P/S", "Research Methods & Statistics"),
)

# apkg container roots (relative to parent) + a safe non-source fallback, so no
# card is lost if an unmapped deck under a root ever holds cards directly.
_APKG_SOURCE_ROOTS: tuple[tuple[str, str], ...] = (
    (_MILE, "Behavioral Sciences"),
    ("MCAT \U0001f499", "Research Methods & Statistics"),
)

# Unreachable-in-practice bucket for any card found directly in the parent (no
# source/topic) or under an unexpected nesting: keeps the reorg lossless when we
# delete the parent subtree.
_STRAY_FALLBACK = "Research Methods & Statistics"


@dataclass
class ReorgResult:
    """Outcome of :func:`reorganize_into_themes`."""

    moved: int  # cards re-homed into top-level thematic decks
    themes: list[str]  # top-level theme deck names this run populated
    removed: list[str]  # source/parent deck names that were deleted


def theme_for_source_deck(relname: str) -> str | None:
    """Thematic deck name for a source deck (name relative to the parent).

    Returns ``None`` for names that are not part of a source layout (e.g. an
    already-flat ``SpeedyCAT MCAT::<theme>`` deck from a prior reorg); those are
    handled separately by :func:`_top_level_theme`.
    """
    # .apkg community-deck layout (explicit map).
    theme = THEME_BY_SOURCE_DECK.get(relname)
    if theme is not None:
        return theme
    # JSON layout: drop the "<source label>::" prefix, keep the (thematic) topic.
    for label, fallback in _JSON_SOURCE_LABELS:
        if relname == label:
            return fallback
        if relname.startswith(label + "::"):
            return relname[len(label) + 2 :]
    # .apkg safety net: any other deck under a source root keeps no source token.
    for root, fallback in _APKG_SOURCE_ROOTS:
        if relname == root or relname.startswith(root + "::"):
            return fallback
    return None


def _top_level_theme(relname: str) -> str | None:
    """Target TOP-LEVEL deck name for a deck currently under the parent.

    ``relname`` is the deck name with the ``SpeedyCAT MCAT::`` prefix removed
    (``""`` for the parent itself). Covers the source layouts via
    :func:`theme_for_source_deck`, and also migrates a prior reorg's flat
    ``SpeedyCAT MCAT::<theme>`` decks (a single non-source segment maps to
    itself, i.e. straight up to the top level). Returns ``None`` when unknown.
    """
    theme = theme_for_source_deck(relname)
    if theme is not None:
        return theme
    if relname and "::" not in relname:
        return relname
    return None


def reorganize_into_themes(col: Collection, parent: str = PARENT_DECK) -> ReorgResult:
    """Flatten the imported deck tree into TOP-LEVEL thematic topic decks.

    Every card under ``parent`` is moved into a bare top-level ``<Theme>`` deck
    (no parent wrapper), then the empty ``parent`` subtree is deleted. Safe to
    re-run: once the parent is gone this is a no-op. Card ids are preserved (only
    the deck changes), so the total card count is unchanged.
    """
    # imported lazily: at module import time (e.g. loaded early by the dataset
    # tooling) anki.decks is not yet ready, but by the time this runs the
    # collection — hence anki — is fully initialised.
    from anki.decks import DeckId

    if col.decks.id_for_name(parent) is None:
        return ReorgResult(0, [], [])

    prefix = parent + "::"
    # snapshot the whole parent subtree (parent + descendants) before mutating
    subtree = [
        (d.name, DeckId(d.id))
        for d in col.decks.all_names_and_ids()
        if d.name == parent or d.name.startswith(prefix)
    ]

    moved = 0
    touched: set[str] = set()
    for name, did in subtree:
        relname = name[len(prefix) :] if name.startswith(prefix) else ""
        cids = col.decks.cids(did, children=False)  # this deck only, not children
        if not cids:
            continue
        target = _top_level_theme(relname) or _STRAY_FALLBACK
        target_did = col.decks.id(target, create=True)  # TOP-LEVEL: no prefix
        assert target_did is not None  # create=True always returns an id
        col.set_deck(cids, target_did)
        moved += len(cids)
        touched.add(target)

    # Every card now lives in a top-level theme deck, so the parent subtree
    # (parent + old source containers + emptied theme subdecks) can go wholesale.
    removed: list[str] = []
    parent_id = col.decks.id_for_name(parent)
    if parent_id is not None:
        col.decks.remove([parent_id])
        removed.append(parent)

    return ReorgResult(moved, sorted(touched), removed)
