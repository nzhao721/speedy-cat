# Copyright: Ankitects Pty Ltd and contributors
# License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html
from __future__ import annotations

import enum
import html
import re
from collections.abc import Callable
from typing import Any, cast

import aqt
from anki.sync import SyncStatus
from aqt import gui_hooks, props
from aqt.qt import *
from aqt.sync import get_sync_status
from aqt.theme import theme_manager
from aqt.utils import tr
from aqt.webview import AnkiWebView, AnkiWebViewKind


class HideMode(enum.IntEnum):
    FULLSCREEN = 0
    ALWAYS = 1


# wrapper class for set_bridge_command()
class TopToolbar:
    def __init__(self, toolbar: Toolbar) -> None:
        self.toolbar = toolbar


# wrapper class for set_bridge_command()
class BottomToolbar:
    def __init__(self, toolbar: Toolbar) -> None:
        self.toolbar = toolbar


class ToolbarWebView(AnkiWebView):
    hide_condition: Callable[..., bool]

    def __init__(
        self, mw: aqt.AnkiQt, kind: AnkiWebViewKind = AnkiWebViewKind.DEFAULT
    ) -> None:
        AnkiWebView.__init__(self, mw, kind=kind)
        self.mw = mw
        self.setFocusPolicy(Qt.FocusPolicy.WheelFocus)
        self.disable_zoom()
        self.hidden = False
        self.hide_timer = QTimer()
        self.hide_timer.setSingleShot(True)
        self.reset_timer()

    def reset_timer(self) -> None:
        self.hide_timer.stop()
        self.hide_timer.setInterval(2000)

    def hide(self) -> None:
        self.hidden = True

    def show(self) -> None:
        self.hidden = False


class TopWebView(ToolbarWebView):
    def __init__(self, mw: aqt.AnkiQt) -> None:
        super().__init__(mw, kind=AnkiWebViewKind.TOP_TOOLBAR)
        self.web_height = 0
        qconnect(self.hide_timer.timeout, self.hide_if_allowed)

    def eventFilter(self, obj, evt):
        if handled := super().eventFilter(obj, evt):
            return handled

        # prevent collapse of both toolbars if pointer is inside one of them
        if evt.type() == QEvent.Type.Enter:
            self.reset_timer()
            self.mw.bottomWeb.reset_timer()
            return True

        return False

    def _full_length_lockdown_active(self) -> bool:
        return bool(getattr(self.mw, "_full_length_lockdown", False))

    def on_body_classes_need_update(self) -> None:
        super().on_body_classes_need_update()

        if self._full_length_lockdown_active():
            self.collapse_for_lockdown()
            return

        if self.mw.state == "review":
            if self.mw.pm.hide_top_bar():
                self.eval("""document.body.classList.remove("flat"); """)
            else:
                self.flatten()

        self.adjustHeightToFit()
        self.show()

    def _onHeight(self, qvar: int | None) -> None:
        super()._onHeight(qvar)
        if qvar:
            self.web_height = int(qvar)

    def hide_if_allowed(self) -> None:
        if self.mw.state != "review":
            return

        # Invariant: The `hide_if_allowed` method ensures that the fullscreen state is checked
        # and the menubar will be hidden if necessary
        # Note: The `eventFilter` and `_reviewState` methods in `qt/aqt/main.py` rely on this invariant
        if self.mw.fullscreen:
            self.mw.hide_menubar()

        if self.mw.pm.hide_top_bar():
            if (
                self.mw.pm.top_bar_hide_mode() == HideMode.FULLSCREEN
                and not self.mw.windowState() & Qt.WindowState.WindowFullScreen
            ):
                self.show()
                return

            self.hide()

    def animate_height(self, height: int) -> None:
        self.web_height = height

        if self.mw.pm.reduce_motion() or height == self.height():
            self.setFixedHeight(height)
        else:
            self.setMinimumHeight(0)
            self.animation = QPropertyAnimation(
                self, cast(QByteArray, b"maximumHeight")
            )
            self.animation.setDuration(int(theme_manager.var(props.TRANSITION)))
            self.animation.setStartValue(self.height())
            self.animation.setEndValue(height)
            qconnect(self.animation.finished, lambda: self.setFixedHeight(height))
            self.animation.start()

    def hide(self) -> None:
        super().hide()

        self.hidden = True
        self.eval(
            """document.body.classList.add("hidden"); """,
        )

    def collapse_for_lockdown(self) -> None:
        """Hide the study-mode nav and reclaim its layout height (full-length exam)."""
        self.hide()
        self.animate_height(0)

    def restore_from_lockdown(self) -> None:
        """Restore the study-mode nav after a full-length exam ends."""
        if self._full_length_lockdown_active():
            return
        super().show()
        self.hidden = False
        self.eval("""document.body.classList.remove("hidden"); """)
        self.adjustHeightToFit()

    def show(self) -> None:
        if self._full_length_lockdown_active():
            return

        super().show()

        self.eval("""document.body.classList.remove("hidden"); """)

    def flatten(self) -> None:
        self.eval("""document.body.classList.add("flat"); """)

    def elevate(self) -> None:
        self.eval(
            """
            document.body.classList.remove("flat");
            document.body.style.removeProperty("background");
            """
        )

    def update_background_image(self) -> None:
        if self.mw.pm.minimalist_mode():
            return

        def set_background(computed: str) -> None:
            # remove offset from copy
            background = re.sub(r"-\d+px ", "0%", computed)
            # ensure alignment with main webview
            background = re.sub(r"\sfixed", "", background)
            # change computedStyle px value back to 100vw
            background = re.sub(r"\d+px", "100vw", background)

            self.eval(
                f"""
                    document.body.style.setProperty("background", '{background}');
                """
            )
            self.set_body_height(self.mw.web.height())

            # offset reviewer background by toolbar height
            if self.web_height:
                self.mw.web.eval(
                    f"""document.body.style.setProperty("background-position-y", "-{self.web_height}px"); """
                )

        self.mw.web.evalWithCallback(
            """window.getComputedStyle(document.body).background; """,
            set_background,
        )

    def set_body_height(self, height: int) -> None:
        self.eval(
            f"""document.body.style.setProperty("min-height", "{self.mw.web.height()}px"); """
        )

    def adjustHeightToFit(self) -> None:
        self.eval("""document.body.style.setProperty("min-height", "0px"); """)
        self.evalWithCallback("document.documentElement.offsetHeight", self._onHeight)

    def resizeEvent(self, event: QResizeEvent | None) -> None:
        super().resizeEvent(event)

        self.mw.web.evalWithCallback(
            """window.innerHeight; """,
            self.set_body_height,
        )


class BottomWebView(ToolbarWebView):
    def __init__(self, mw: aqt.AnkiQt) -> None:
        super().__init__(mw, kind=AnkiWebViewKind.BOTTOM_TOOLBAR)
        qconnect(self.hide_timer.timeout, self.hide_if_allowed)

    def adjustHeightToFit(self) -> None:
        self.eval("""document.body.style.setProperty("min-height", "0px"); """)
        self.evalWithCallback(
            "Math.max(document.documentElement.offsetHeight, document.documentElement.scrollHeight)",
            self._onHeight,
        )

    def eventFilter(self, obj, evt):
        if handled := super().eventFilter(obj, evt):
            return handled

        if evt.type() == QEvent.Type.Enter:
            self.reset_timer()
            self.mw.toolbarWeb.reset_timer()
            return True

        return False

    def on_body_classes_need_update(self) -> None:
        super().on_body_classes_need_update()
        if self.mw.state == "review":
            self.show()
        else:
            self.hide()

    def animate_height(self, height: int) -> None:
        self.web_height = height

        if self.mw.pm.reduce_motion() or height == self.height():
            self.setFixedHeight(height)
        else:
            # Collapse/Expand animation
            self.setMinimumHeight(0)
            self.animation = QPropertyAnimation(
                self, cast(QByteArray, b"maximumHeight")
            )
            self.animation.setDuration(int(theme_manager.var(props.TRANSITION)))
            self.animation.setStartValue(self.height())
            self.animation.setEndValue(height)
            qconnect(self.animation.finished, lambda: self.setFixedHeight(height))
            self.animation.start()

    def hide_if_allowed(self) -> None:
        if self.mw.state != "review":
            return

        if self.mw.pm.hide_bottom_bar():
            if (
                self.mw.pm.bottom_bar_hide_mode() == HideMode.FULLSCREEN
                and not self.mw.windowState() & Qt.WindowState.WindowFullScreen
            ):
                self.show()
                return

            self.hide()

    def hide(self) -> None:
        super().hide()

        self.hidden = True
        # SpeedyCAT: collapse to zero height so deck list / dashboard never
        # show an empty beige strip (upstream used 1px).
        self.animate_height(0)

    def show(self) -> None:
        super().show()

        self.hidden = False
        if self.mw.state == "review":
            self._measure_review_height()
        else:
            self.adjustHeightToFit()

    def collapse_review_bar(self) -> None:
        """Collapse the review bottom bar when it has no buttons to show."""
        if self.mw.state != "review":
            return
        self.hidden = True
        self.animate_height(0)

    def _measure_review_height(self) -> None:
        # delay to account for reflow (see ankitects/anki#3625)
        def cb(height: int | None) -> None:
            # "When QWebEnginePage is deleted, the callback is triggered with an invalid value"
            if height is not None and height > 1:
                self.animate_height(height)
            else:
                self.animate_height(0)

        self.mw.progress.single_shot(
            50,
            lambda: self.evalWithCallback(
                "Math.max(document.documentElement.offsetHeight, document.documentElement.scrollHeight)",
                cb,
            ),
            False,
        )


class Toolbar:
    UNDO_ICON_SVG = (
        '<svg viewBox="0 0 24 24" role="img" aria-hidden="true" '
        'focusable="false" xmlns="http://www.w3.org/2000/svg">'
        "<title>Undo</title>"
        '<path d="M9 14 4 9l5-5" fill="none" stroke="currentColor" '
        'stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>'
        '<path d="M4 9h10.5a5.5 5.5 0 1 1 0 11H12" fill="none" '
        'stroke="currentColor" stroke-width="2" stroke-linecap="round" '
        'stroke-linejoin="round"/>'
        "</svg>"
    )

    def __init__(self, mw: aqt.AnkiQt, web: AnkiWebView) -> None:
        self.mw = mw
        self.web = web
        self.link_handlers: dict[str, Callable] = {
            "study": self._studyLinkHandler,
        }
        self.web.requiresCol = False

    def draw(
        self,
        buf: str = "",
        web_context: Any | None = None,
        link_handler: Callable[[str], Any] | None = None,
    ) -> None:
        web_context = web_context or TopToolbar(self)
        link_handler = link_handler or self._linkHandler
        self.web.set_bridge_command(link_handler, web_context)
        body = self._body.format(
            toolbar_content=self._centerLinks(),
            left_tray_content=self._left_tray_content(),
            right_tray_content=self._right_tray_content(),
        )
        self.web.stdHtml(
            body,
            css=["css/toolbar.css"],
            js=["js/vendor/jquery.min.js", "js/toolbar.js"],
            context=web_context,
        )
        self.web.adjustHeightToFit()

    def redraw(self) -> None:
        self.set_sync_active(self.mw.media_syncer.is_syncing())
        self.update_sync_status()
        gui_hooks.top_toolbar_did_redraw(self)

    # Available links
    ######################################################################

    def create_link(
        self,
        cmd: str,
        label: str,
        func: Callable,
        tip: str | None = None,
        id: str | None = None,
    ) -> str:
        """Generates HTML link element and registers link handler

        Arguments:
            cmd {str} -- Command name used for the JS → Python bridge
            label {str} -- Display label of the link
            func {Callable} -- Callable to be called on clicking the link

        Keyword Arguments:
            tip {Optional[str]} -- Optional tooltip text to show on hovering
                                   over the link (default: {None})
            id: {Optional[str]} -- Optional id attribute to supply the link with
                                   (default: {None})

        Returns:
            str -- HTML link element
        """

        self.link_handlers[cmd] = func

        title_attr = f'title="{tip}"' if tip else ""
        id_attr = f'id="{id}"' if id else ""

        return (
            f"""<a class=hitem tabindex="-1" aria-label="{label}" """
            f"""{title_attr} {id_attr} href=# onclick="return pycmd('{cmd}')">"""
            f"""{label}</a>"""
        )

    def create_icon_link(
        self,
        cmd: str,
        label: str,
        func: Callable,
        icon_svg: str,
        tip: str | None = None,
        id: str | None = None,
    ) -> str:
        """Like create_link, but renders an inline SVG instead of text."""
        self.link_handlers[cmd] = func

        safe_label = html.escape(label, quote=True)
        title_attr = f'title="{html.escape(tip, quote=True)}"' if tip else ""
        id_attr = f'id="{id}"' if id else ""

        return (
            f"""<a class="hitem hitem-icon" tabindex="-1" """
            f"""aria-label="{safe_label}" {title_attr} {id_attr} href=# """
            f"""onclick="if(this.classList.contains('disabled'))return false; """
            f"""return pycmd('{cmd}')">{icon_svg}</a>"""
        )

    def _centerLinks(self) -> str:
        links = [
            self.create_link(
                "dashboard",
                "Dashboard",
                self._dashboardLinkHandler,
                tip=tr.actions_shortcut_key(val="T"),
                id="dashboard",
            ),
            # SpeedyCAT: the former "Decks" tab is relabelled "Flashcards" (the
            # deck browser is unchanged); it sits alongside the MCAT study modes.
            self.create_link(
                "decks",
                "Flashcards",
                self._deckLinkHandler,
                tip=tr.actions_shortcut_key(val="D"),
                id="decks",
            ),
            self.create_link(
                "practice",
                "Practice Questions",
                self._practiceLinkHandler,
                tip=tr.actions_shortcut_key(val="P"),
                id="practice",
            ),
            self.create_link(
                "fullLength",
                "Full-Length Tests",
                self._fullLengthLinkHandler,
                tip=tr.actions_shortcut_key(val="F"),
                id="full-length",
            ),
        ]

        gui_hooks.top_toolbar_did_init_links(links, self)

        return "\n".join(links)

    # Add-ons
    ######################################################################

    def _left_tray_content(self) -> str:
        left_tray_content: list[str] = []
        gui_hooks.top_toolbar_will_set_left_tray_content(left_tray_content, self)
        return self._process_tray_content(left_tray_content)

    def _right_tray_content(self) -> str:
        right_tray_content: list[str] = []
        gui_hooks.top_toolbar_will_set_right_tray_content(right_tray_content, self)
        return self._process_tray_content(right_tray_content)

    def _process_tray_content(self, content: list[str]) -> str:
        return "\n".join(f"""<div class="tray-item">{item}</div>""" for item in content)

    # Sync
    ######################################################################

    def _create_sync_link(self) -> str:
        name = tr.qt_misc_sync()
        title = tr.actions_shortcut_key(val="Y")
        label = "sync"
        self.link_handlers[label] = self._syncLinkHandler

        return f"""
<a class=hitem tabindex="-1" aria-label="{name}" title="{title}" id="{label}" href=# onclick="return pycmd('{label}')"
>{name}<img id=sync-spinner src='/_anki/imgs/refresh.svg'>
</a>"""

    def set_sync_active(self, active: bool) -> None:
        method = "add" if active else "remove"
        self.web.eval(
            f"""(function() {{
    const elem = document.getElementById('sync-spinner');
    if (elem) elem.classList.{method}('spin');
}})();"""
        )

    def set_sync_status(self, status: SyncStatus) -> None:
        self.web.eval(
            f"""(function() {{
    if (typeof updateSyncColor === 'function') updateSyncColor({status.required});
}})();"""
        )

    def update_sync_status(self) -> None:
        get_sync_status(self.mw, self.mw.toolbar.set_sync_status)

    # Link handling
    ######################################################################

    def _linkHandler(self, link: str) -> bool:
        if link in self.link_handlers:
            self.link_handlers[link]()
        return False

    def _deckLinkHandler(self) -> None:
        self.mw.moveToState("deckBrowser")

    def _studyLinkHandler(self) -> None:
        self.mw.startStudying()

    def _practiceLinkHandler(self) -> None:
        # SpeedyCAT: study modes render inside the main window (no pop-up);
        # switching tabs swaps the central content in place.
        self.mw.moveToState("speedycat", "practice")

    def _fullLengthLinkHandler(self) -> None:
        self.mw.moveToState("speedycat", "full-length")

    def _dashboardLinkHandler(self) -> None:
        # SpeedyCAT: the unified dashboard (readiness pillars + stats graphs)
        # renders inside the main window like the other tabs.
        self.mw.moveToState("speedycat", "dashboard")

    def _syncLinkHandler(self) -> None:
        self.mw.on_sync_button_clicked()

    # HTML & CSS
    ######################################################################

    _body = """
<div class="header">
  <div class="left-tray">{left_tray_content}</div>
  <div class="toolbar">{toolbar_content}</div>
  <div class="right-tray">{right_tray_content}</div>
</div>
"""


# Bottom bar
######################################################################


class BottomBar(Toolbar):
    _centerBody = """
<center id=outer><table width=100%% id=header><tr><td align=center>
%s</td></tr></table></center>
"""

    def draw(
        self,
        buf: str = "",
        web_context: Any | None = None,
        link_handler: Callable[[str], Any] | None = None,
    ) -> None:
        # note: some screens may override this
        web_context = web_context or BottomToolbar(self)
        link_handler = link_handler or self._linkHandler
        self.web.set_bridge_command(link_handler, web_context)
        self.web.stdHtml(
            self._centerBody % buf,
            css=["css/toolbar.css", "css/toolbar-bottom.css"],
            context=web_context,
        )
        self.web.adjustHeightToFit()
