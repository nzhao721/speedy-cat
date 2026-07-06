# Copyright: Ankitects Pty Ltd and contributors
# License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html
from __future__ import annotations

import html
from collections.abc import Callable
from dataclasses import dataclass

import aqt
import aqt.operations
from anki.collection import OpChanges
from anki.scheduler import UnburyDeck
from aqt import gui_hooks
from aqt.operations import QueryOp
from aqt.operations.scheduling import (
    empty_filtered_deck,
    rebuild_filtered_deck,
    unbury_deck,
)
from aqt.sound import av_player
from aqt.utils import askUserDialog, openLink, tooltip, tr


@dataclass
class OverviewContent:
    """Stores sections of HTML content that the overview will be
    populated with.

    Attributes:
        deck {str} -- Plain text deck name
        shareLink {str} -- HTML of the share link section
        table {str} -- HTML of the deck stats table section
    """

    deck: str
    shareLink: str
    table: str


class Overview:
    "Deck overview."

    def __init__(self, mw: aqt.AnkiQt) -> None:
        self.mw = mw
        self.web = mw.web
        self._refresh_needed = False

    def show(self) -> None:
        av_player.stop_and_clear_queue()
        self.web.set_bridge_command(self._linkHandler, self)
        self.mw.setStateShortcuts(self._shortcutKeys())
        self.refresh()

    def refresh(self) -> None:
        def success(_counts: tuple) -> None:
            self._refresh_needed = False
            self._renderPage()
            self.mw.web.setFocus()
            gui_hooks.overview_did_refresh(self)

        QueryOp(
            parent=self.mw, op=lambda col: col.sched.counts(), success=success
        ).run_in_background()

    def refresh_if_needed(self) -> None:
        if self._refresh_needed:
            self.refresh()

    def op_executed(
        self, changes: OpChanges, handler: object | None, focused: bool
    ) -> bool:
        if changes.study_queues:
            self._refresh_needed = True

        if focused:
            self.refresh_if_needed()

        return self._refresh_needed

    # Handlers
    ############################################################

    def _linkHandler(self, url: str) -> bool:
        if url == "study":
            self.mw.col.startTimebox()
            self.mw.moveToState("review")
            if self.mw.state == "overview":
                tooltip(tr.studying_no_cards_are_due_yet())
        elif url == "anki":
            print("anki menu")
        elif url == "refresh":
            self.rebuild_current_filtered_deck()
        elif url == "empty":
            self.empty_current_filtered_deck()
        elif url == "decks":
            self.mw.moveToState("deckBrowser")
        elif url == "review":
            pass
        elif url == "unbury":
            self.on_unbury()
        elif url.lower().startswith("http"):
            openLink(url)
        return False

    def _shortcutKeys(self) -> list[tuple[str, Callable]]:
        return [
            ("r", self.rebuild_current_filtered_deck),
            ("e", self.empty_current_filtered_deck),
            ("u", self.on_unbury),
        ]

    def rebuild_current_filtered_deck(self) -> None:
        rebuild_filtered_deck(
            parent=self.mw, deck_id=self.mw.col.decks.selected()
        ).run_in_background()

    def empty_current_filtered_deck(self) -> None:
        empty_filtered_deck(
            parent=self.mw, deck_id=self.mw.col.decks.selected()
        ).run_in_background()

    def on_unbury(self) -> None:
        mode = UnburyDeck.Mode.ALL
        info = self.mw.col.sched.congratulations_info()
        if info.have_sched_buried and info.have_user_buried:
            opts = [
                tr.studying_manually_buried_cards(),
                tr.studying_buried_siblings(),
                tr.studying_all_buried_cards(),
                tr.actions_cancel(),
            ]

            diag = askUserDialog(tr.studying_what_would_you_like_to_unbury(), opts)
            diag.setDefault(0)
            ret = diag.run()
            if ret == opts[0]:
                mode = UnburyDeck.Mode.USER_ONLY
            elif ret == opts[1]:
                mode = UnburyDeck.Mode.SCHED_ONLY
            elif ret == opts[3]:
                return

        unbury_deck(
            parent=self.mw, deck_id=self.mw.col.decks.get_current_id(), mode=mode
        ).run_in_background()

    onUnbury = on_unbury

    # HTML
    ############################################################

    def _renderPage(self) -> None:
        deck = self.mw.col.decks.current()
        self.sid = deck.get("sharedFrom")
        if self.sid:
            self.sidVer = deck.get("ver", None)
            shareLink = '<a class=smallLink href="review">Reviews and Updates</a>'
        else:
            shareLink = ""
        if self.mw.col.sched._is_finished():
            self._show_finished_screen()
            return
        content = OverviewContent(
            deck=deck["name"],
            shareLink=shareLink,
            table=self._table(),
        )
        gui_hooks.overview_will_render_content(self, content)
        content.deck = html.escape(content.deck)
        self.web.stdHtml(
            self._body % content.__dict__,
            css=["css/overview.css"],
            js=["js/vendor/jquery.min.js"],
            context=self,
        )

    def _show_finished_screen(self) -> None:
        self.web.load_sveltekit_page("congrats")

    def _table(self) -> str:
        counts = list(self.mw.col.sched.counts())
        current_did = self.mw.col.decks.get_current_id()
        deck_node = self.mw.col.sched.deck_due_tree(current_did)

        but = self.mw.button
        if self.mw.col.v3_scheduler():
            assert deck_node is not None
            buried_new = deck_node.new_count - counts[0]
            buried_learning = deck_node.learn_count - counts[1]
            buried_review = deck_node.review_count - counts[2]
        else:
            buried_new = buried_learning = buried_review = 0
        buried_label = tr.studying_counts_differ()

        def number_row(title: str, klass: str, count: int, buried_count: int) -> str:
            buried = f"{buried_count:+}" if buried_count else ""
            return f"""
<tr>
    <td>{title}:</td>
    <td>
        <b>
            <span class={klass}>{count}</span>
            <span class=bury-count title="{buried_label}">{buried}</span>
        </b>
    </td>
</tr>
"""

        return f"""
<table width=400 cellpadding=5>
<tr><td align=center valign=top>
<table cellspacing=5>
{number_row(tr.actions_new(), "new-count", counts[0], buried_new)}
{number_row(tr.scheduling_learning(), "learn-count", counts[1], buried_learning)}
{number_row(tr.studying_to_review(), "review-count", counts[2], buried_review)}
</table>
</td><td align=center>
{but("study", tr.studying_study_now(), id="study", extra=" autofocus")}</td></tr></table>"""

    _body = """
<center>
<h3>%(deck)s</h3>
%(shareLink)s
%(table)s
</center>
"""
