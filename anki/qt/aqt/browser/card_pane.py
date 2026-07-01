# Copyright: Ankitects Pty Ltd and contributors
# License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

"""Read-only detail pane for the browser.

The browser is a read-only viewer, so instead of an editor we show the selected
card's topic together with its front and back, rendered exactly as they appear
when studying. Nothing here is editable.
"""

from __future__ import annotations

import json
from typing import Any

from anki.cards import Card
from aqt import AnkiQt, gui_hooks
from aqt.qt import QLabel, Qt, QVBoxLayout, QWidget
from aqt.sound import av_player, play_clicked_audio
from aqt.theme import theme_manager
from aqt.webview import AnkiWebView, AnkiWebViewKind


class BrowserCardPane(QWidget):
    """Show a card's topic, front and back as read-only rendered content."""

    def __init__(self, mw: AnkiQt) -> None:
        super().__init__()
        self.mw = mw
        self._card: Card | None = None

        layout = QVBoxLayout()
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)

        self.topic_label = QLabel()
        self.topic_label.setObjectName("browserTopic")
        self.topic_label.setStyleSheet("font-weight: bold; padding: 8px 12px;")
        self.topic_label.setTextInteractionFlags(
            Qt.TextInteractionFlag.TextSelectableByMouse
        )
        layout.addWidget(self.topic_label)

        self.web = AnkiWebView(kind=AnkiWebViewKind.PREVIEWER)
        layout.addWidget(self.web, 1)
        self.setLayout(layout)

        self.web.stdHtml(
            self.mw.reviewer.revHtml(),
            css=["css/reviewer.css"],
            js=[
                "js/mathjax.js",
                "js/vendor/mathjax/tex-chtml-full.js",
                "js/reviewer.js",
            ],
            context=self,
        )
        self.web.set_bridge_command(self._on_bridge_cmd, self)
        # never autoplay audio in the read-only viewer
        self.web.setPlaybackRequiresGesture(True)

    def set_card(self, card: Card | None) -> None:
        """Render the given card's front and back, or clear the pane."""
        self._card = card
        if card is None:
            self.topic_label.setText("")
            self.web.eval("_showAnswer('', '');")
            return
        self.topic_label.setText(self._topic_label(card))
        # the answer side renders the full card (front + back) as studied
        answer = card.answer()
        answer = self.mw.prepare_card_text_for_display(answer)
        answer = gui_hooks.card_will_show(answer, card, "browserReadOnly")
        bodyclass = theme_manager.body_classes_for_card_ord(card.ord)
        self.web.eval(f"_showAnswer({json.dumps(answer)}, {json.dumps(bodyclass)});")

    def _topic_label(self, card: Card) -> str:
        # the card's topic is its top-level deck
        deck_name = self.mw.col.decks.name(card.current_deck_id())
        topic = deck_name.split("::", 1)[0]
        return f"Topic: {topic}"

    def _on_bridge_cmd(self, cmd: str) -> Any:
        if cmd.startswith("play:") and self._card is not None:
            play_clicked_audio(cmd, self._card)

    def cleanup(self) -> None:
        av_player.stop_and_clear_queue()
        self.web.cleanup()
