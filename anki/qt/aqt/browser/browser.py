# Copyright: Ankitects Pty Ltd and contributors
# License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

from __future__ import annotations

import math
import re
from collections.abc import Callable, Sequence
from typing import Any

from markdown import markdown

import aqt
import aqt.browser
import aqt.forms
import aqt.operations
from anki._legacy import deprecated
from anki.cards import Card, CardId
from anki.collection import Collection, Config, OpChanges, SearchNode
from anki.consts import *
from anki.errors import SearchError
from anki.lang import without_unicode_isolation
from anki.notes import NoteId
from anki.utils import is_mac
from aqt import AnkiQt, gui_hooks
from aqt.errors import show_exception
from aqt.qt import *
from aqt.sound import av_player
from aqt.switch import Switch
from aqt.theme import WidgetStyle
from aqt.utils import (
    HelpPage,
    current_window,
    no_arg_trigger,
    openHelp,
    restoreGeom,
    restoreSplitter,
    restoreState,
    saveGeom,
    saveSplitter,
    saveState,
    showWarning,
    skip_if_selection_is_empty,
    tooltip,
    tr,
)
from aqt.webview import AnkiWebView

from .card_info import BrowserCardInfo
from .card_pane import BrowserCardPane
from .layout import BrowserLayout, QSplitterHandleEventFilter
from .previewer import BrowserPreviewer as PreviewDialog
from .previewer import Previewer
from .sidebar import SidebarTreeView
from .table import Table


class MockModel:
    """This class only exists to support some legacy aliases."""

    def __init__(self, browser: aqt.browser.Browser) -> None:
        self.browser = browser

    @deprecated(replaced_by=aqt.operations.CollectionOp)
    def beginReset(self) -> None:
        self.browser.begin_reset()

    @deprecated(replaced_by=aqt.operations.CollectionOp)
    def endReset(self) -> None:
        self.browser.end_reset()

    @deprecated(replaced_by=aqt.operations.CollectionOp)
    def reset(self) -> None:
        self.browser.begin_reset()
        self.browser.end_reset()


class Browser(QMainWindow):
    mw: AnkiQt
    col: Collection
    table: Table
    _card_pane: BrowserCardPane

    def __init__(
        self,
        mw: AnkiQt,
        card: Card | None = None,
        search: tuple[str | SearchNode] | None = None,
    ) -> None:
        """
        card -- try to select the provided card after executing "search" or
                "deck:current" (if "search" was None)
        search -- set and perform search; caller must ensure validity
        """

        QMainWindow.__init__(self, None, Qt.WindowType.Window)
        self.mw = mw
        self.col = self.mw.col
        self.lastFilter = ""
        self._previewer: Previewer | None = None
        self._card_info = BrowserCardInfo(self.mw)
        self._closeEventHasCleanedUp = False
        self.auto_layout = True
        self.aspect_ratio = 0.0
        self.form = aqt.forms.browser.Ui_Dialog()
        self.form.setupUi(self)
        self.form.splitter.setChildrenCollapsible(False)
        splitter_handle_event_filter = QSplitterHandleEventFilter(self.form.splitter)

        splitter_handle = self.form.splitter.handle(1)
        assert splitter_handle is not None

        splitter_handle.installEventFilter(splitter_handle_event_filter)
        # set if exactly 1 row is selected; used by the previewer
        self.card: Card | None = None
        self.current_card: Card | None = None
        self.singleCard = False
        self.setupSidebar()
        self.setup_table()
        self.setupMenus()
        self.setupHooks()
        self.setup_card_pane()
        gui_hooks.browser_will_show(self)

        # restoreXXX() should be called after all child widgets have been created
        # and attached to QMainWindow
        self._editor_state_key = (
            "editorRTL"
            if self.layoutDirection() == Qt.LayoutDirection.RightToLeft
            else "editor"
        )
        restoreGeom(self, self._editor_state_key)
        restoreSplitter(self.form.splitter, "editor3")
        restoreState(self, self._editor_state_key)

        # responsive layout
        if self.height() != 0:
            self.aspect_ratio = self.width() / self.height()
        self.set_layout(self.mw.pm.browser_layout(), True)
        self.onSidebarVisibilityChange(not self.sidebarDockWidget.isHidden())
        # legacy alias
        self.model = MockModel(self)
        self.setupSearch(card, search)
        self.show()

    def on_operation_did_execute(
        self, changes: OpChanges, handler: object | None
    ) -> None:
        focused = current_window() == self
        self.table.op_executed(changes, handler, focused)
        self.sidebar.op_executed(changes, handler, focused)
        if changes.note_text and self.singleCard:
            # the shown note may have been edited elsewhere; re-render it
            self.card = self.table.get_single_selected_card()
            self._card_pane.set_card(self.card)

        if changes.browser_table and changes.card:
            self.card = self.table.get_single_selected_card()
            self.current_card = self.table.get_current_card()
            self._update_card_info()
            self._update_current_actions()

        # changes.card is required for updating flag icon
        if changes.note_text or changes.card:
            self._renderPreview()

    def on_focus_change(self, new: QWidget | None, old: QWidget | None) -> None:
        if current_window() == self:
            self.setUpdatesEnabled(True)
            self.table.redraw_cells()
            self.sidebar.refresh_if_needed()

    def set_layout(self, mode: BrowserLayout, init: bool = False) -> None:
        self.mw.pm.set_browser_layout(mode)

        if mode == BrowserLayout.AUTO:
            self.auto_layout = True
            self.maybe_update_layout(self.aspect_ratio, True)
            self.form.actionLayoutAuto.setChecked(True)
            self.form.actionLayoutVertical.setChecked(False)
            self.form.actionLayoutHorizontal.setChecked(False)
            if not init:
                tooltip(tr.qt_misc_layout_auto_enabled())
        else:
            self.auto_layout = False
            self.form.actionLayoutAuto.setChecked(False)

            if mode == BrowserLayout.VERTICAL:
                self.form.splitter.setOrientation(Qt.Orientation.Vertical)
                self.form.actionLayoutVertical.setChecked(True)
                self.form.actionLayoutHorizontal.setChecked(False)
                if not init:
                    tooltip(tr.qt_misc_layout_vertical_enabled())

            elif mode == BrowserLayout.HORIZONTAL:
                self.form.splitter.setOrientation(Qt.Orientation.Horizontal)
                self.form.actionLayoutHorizontal.setChecked(True)
                self.form.actionLayoutVertical.setChecked(False)
                if not init:
                    tooltip(tr.qt_misc_layout_horizontal_enabled())

    def maybe_update_layout(self, aspect_ratio: float, force: bool = False) -> None:
        if force or math.floor(aspect_ratio) != math.floor(self.aspect_ratio):
            if aspect_ratio < 1:
                self.form.splitter.setOrientation(Qt.Orientation.Vertical)
            else:
                self.form.splitter.setOrientation(Qt.Orientation.Horizontal)

    def resizeEvent(self, event: QResizeEvent | None) -> None:
        assert event is not None

        if self.height() != 0:
            aspect_ratio = self.width() / self.height()

            if self.auto_layout:
                self.maybe_update_layout(aspect_ratio)

            self.aspect_ratio = aspect_ratio

        QMainWindow.resizeEvent(self, event)

    # If in the Browser we open Preview and press Ctrl+W there,
    # both Preview and Browser windows get closed by Qt out of the box.
    # We circumvent that behavior by only closing the currently active window
    def _handle_close(self):
        active_window = QApplication.activeWindow()
        if active_window and active_window != self:
            if isinstance(active_window, QDialog):
                active_window.reject()
            else:
                active_window.close()
        else:
            self.close()

    def setupMenus(self) -> None:
        # actions
        f = self.form

        # edit
        qconnect(f.actionInvertSelection.triggered, self.table.invert_selection)
        qconnect(f.actionSelectNotes.triggered, self.selectNotes)
        if not is_mac:
            f.actionClose.setVisible(False)

        # view
        qconnect(f.actionFullScreen.triggered, self.mw.on_toggle_full_screen)
        qconnect(
            f.actionZoomIn.triggered,
            lambda: self._web_view().setZoomFactor(
                self._web_view().zoomFactor() + 0.1
            ),
        )
        qconnect(
            f.actionZoomOut.triggered,
            lambda: self._web_view().setZoomFactor(
                self._web_view().zoomFactor() - 0.1
            ),
        )
        qconnect(
            f.actionResetZoom.triggered,
            lambda: self._web_view().setZoomFactor(1),
        )
        qconnect(
            self.form.actionLayoutAuto.triggered,
            lambda: self.set_layout(BrowserLayout.AUTO),
        )
        qconnect(
            self.form.actionLayoutVertical.triggered,
            lambda: self.set_layout(BrowserLayout.VERTICAL),
        )
        qconnect(
            self.form.actionLayoutHorizontal.triggered,
            lambda: self.set_layout(BrowserLayout.HORIZONTAL),
        )

        # cards (read-only)
        qconnect(f.action_Info.triggered, self.showCardInfo)

        # jumps
        qconnect(f.actionPreviousCard.triggered, self.onPreviousCard)
        qconnect(f.actionNextCard.triggered, self.onNextCard)
        qconnect(f.actionFirstCard.triggered, self.onFirstCard)
        qconnect(f.actionLastCard.triggered, self.onLastCard)
        qconnect(f.actionFind.triggered, self.onFind)
        qconnect(f.actionNote.triggered, self.onNote)
        qconnect(f.actionSidebar.triggered, self.focusSidebar)
        qconnect(f.actionToggleSidebar.triggered, self.toggle_sidebar)
        qconnect(f.actionCardList.triggered, self.onCardList)

        # help
        qconnect(f.actionGuide.triggered, self.onHelp)

        # keyboard shortcut for shift+home/end
        self.pgUpCut = QShortcut(QKeySequence("Shift+Home"), self)
        qconnect(self.pgUpCut.activated, self.onFirstCard)
        self.pgDownCut = QShortcut(QKeySequence("Shift+End"), self)
        qconnect(self.pgDownCut.activated, self.onLastCard)

        # add-on hook
        gui_hooks.browser_menus_did_init(self)
        self.mw.maybeHideAccelerators(self)

    def _web_view(self) -> AnkiWebView:
        return self._card_pane.web

    def closeEvent(self, evt: QCloseEvent | None) -> None:
        assert evt is not None

        if self._closeEventHasCleanedUp:
            evt.accept()
            return

        self._closeWindow()
        evt.ignore()

    def _closeWindow(self) -> None:
        self._cleanup_preview()
        self._card_info.close()
        self._card_pane.cleanup()
        self.table.cleanup()
        self.sidebar.cleanup()
        saveSplitter(self.form.splitter, "editor3")
        saveGeom(self, self._editor_state_key)
        saveState(self, self._editor_state_key)
        self.teardownHooks()
        self.mw.maybeReset()
        aqt.dialogs.markClosed("Browser")
        self._closeEventHasCleanedUp = True
        self.mw.deferred_delete_and_garbage_collect(self)
        self.close()

    def closeWithCallback(self, onsuccess: Callable) -> None:
        self._closeWindow()
        onsuccess()

    def keyPressEvent(self, evt: QKeyEvent | None) -> None:
        assert evt is not None

        if evt.key() == Qt.Key.Key_Escape:
            self.close()
        else:
            super().keyPressEvent(evt)

    def reopen(
        self,
        _mw: AnkiQt,
        card: Card | None = None,
        search: tuple[str | SearchNode] | None = None,
    ) -> None:
        if search is not None:
            self.search_for_terms(*search)
            self.form.searchEdit.setFocus()
        if card is not None:
            if search is None:
                # implicitly assume 'card' is in the current deck
                self._default_search(card)
                self.form.searchEdit.setFocus()
            self.table.select_single_card(card.id)

    # Searching
    ######################################################################

    def setupSearch(
        self,
        card: Card | None = None,
        search: tuple[str | SearchNode] | None = None,
    ) -> None:
        assert self.mw.pm.profile is not None

        line_edit = self._line_edit()
        qconnect(line_edit.returnPressed, self.onSearchActivated)
        self.form.searchEdit.setCompleter(None)
        line_edit.setPlaceholderText(tr.browsing_search_bar_hint())
        line_edit.setMaxLength(2000000)
        self.form.searchEdit.addItems(
            [""] + self.mw.pm.profile.get("searchHistory", [])
        )
        if search is not None:
            self.search_for_terms(*search)
        else:
            self._default_search(card)
        self.form.searchEdit.setFocus()
        if card:
            self.table.select_single_card(card.id)

    # search triggered by user
    def onSearchActivated(self) -> None:
        text = self.current_search()
        try:
            normed = self.col.build_search_string(text)
        except SearchError as err:
            showWarning(markdown(str(err)))
        except Exception as err:
            showWarning(str(err))
        else:
            self.search_for(normed)
            self.update_history()

    def search_for(self, search: str, prompt: str | None = None) -> None:
        """Keep track of search string so that we reuse identical search when
        refreshing, rather than whatever is currently in the search field.
        Optionally set the search bar to a different text than the actual search.
        """

        self._lastSearchTxt = search
        prompt = search if prompt is None else prompt
        self.form.searchEdit.setCurrentIndex(-1)
        self._line_edit().setText(prompt)
        self.search()

    def current_search(self) -> str:
        return re.sub(r"\s", " ", self._line_edit().text())

    def search(self) -> None:
        """Search triggered programmatically. Caller must have saved note first."""

        try:
            self.table.search(self._lastSearchTxt)
        except Exception as err:
            showWarning(str(err))

    def update_history(self) -> None:
        assert self.mw.pm.profile is not None

        sh = self.mw.pm.profile.get("searchHistory", [])
        if self._lastSearchTxt in sh:
            sh.remove(self._lastSearchTxt)
        sh.insert(0, self._lastSearchTxt)
        sh = sh[:30]
        self.form.searchEdit.clear()
        self.form.searchEdit.addItems(sh)
        self.mw.pm.profile["searchHistory"] = sh

    def updateTitle(self) -> None:
        selected = self.table.len_selection()
        cur = self.table.len()
        tr_title = (
            tr.browsing_window_title_notes
            if self.table.is_notes_mode()
            else tr.browsing_window_title
        )
        self.setWindowTitle(
            without_unicode_isolation(tr_title(total=cur, selected=selected))
        )

    def search_for_terms(self, *search_terms: str | SearchNode) -> None:
        search = self.col.build_search_string(*search_terms)
        self.form.searchEdit.setEditText(search)
        self.onSearchActivated()

    def _default_search(self, card: Card | None = None) -> None:
        default = self.col.get_config_string(Config.String.DEFAULT_SEARCH_TEXT)
        if default.strip():
            search = default
            prompt = default
        else:
            search = self.col.build_search_string(SearchNode(deck="current"))
            prompt = ""
        if card is not None:
            search = gui_hooks.default_search(search, card)
        self.search_for(search, prompt)

    def onReset(self) -> None:
        self.sidebar.refresh()
        self.begin_reset()
        self.end_reset()

    def begin_reset(self) -> None:
        self._card_pane.set_card(None)
        self.mw.progress.start()
        self.table.begin_reset()

    def end_reset(self) -> None:
        self.table.end_reset()
        self.mw.progress.finish()

    # Table & card pane
    ######################################################################

    def setup_table(self) -> None:
        self.table = Table(self)
        self.table.set_view(self.form.tableView)
        self._switch = switch = Switch(12, tr.browsing_cards(), tr.browsing_notes())
        switch.setChecked(self.table.is_notes_mode())
        switch.setToolTip(tr.browsing_toggle_showing_cards_notes())
        qconnect(self.form.action_toggle_mode.triggered, switch.toggle)
        qconnect(switch.toggled, self.on_table_state_changed)
        self.form.gridLayout.addWidget(switch, 0, 0)

    def setup_card_pane(self) -> None:
        QShortcut(QKeySequence("Ctrl+Shift+P"), self, self.onTogglePreview)
        self._card_pane = BrowserCardPane(self.mw)
        layout = QVBoxLayout()
        layout.setContentsMargins(0, 0, 0, 0)
        layout.addWidget(self._card_pane)
        self.form.fieldsArea.setLayout(layout)

    def on_all_or_selected_rows_changed(self) -> None:
        """Called after the selected or all rows (searching, toggling mode) have
        changed. Update window title, card preview, context actions, and pane.
        """
        if self._closeEventHasCleanedUp:
            return

        self.updateTitle()
        # if there is only one selected card, show it in the read-only pane
        # it might differ from the current card
        self.card = self.table.get_single_selected_card()
        self.singleCard = bool(self.card)

        splitter_widget = self.form.splitter.widget(1)
        assert splitter_widget is not None

        splitter_widget.setVisible(self.singleCard)

        self._card_pane.set_card(self.card if self.singleCard else None)
        self._renderPreview()
        self._update_row_actions()
        self._update_selection_actions()
        gui_hooks.browser_did_change_row(self)

    @deprecated(info="please use on_all_or_selected_rows_changed() instead.")
    def onRowChanged(self, *args: Any) -> None:
        self.on_all_or_selected_rows_changed()

    def on_current_row_changed(self) -> None:
        """Called after the row of the current element has changed."""
        if self._closeEventHasCleanedUp:
            return
        self.current_card = self.table.get_current_card()
        self._update_current_actions()
        self._update_card_info()

    def _update_row_actions(self) -> None:
        has_rows = bool(self.table.len())
        self.form.actionSelectAll.setEnabled(has_rows)
        self.form.actionInvertSelection.setEnabled(has_rows)
        self.form.actionFirstCard.setEnabled(has_rows)
        self.form.actionLastCard.setEnabled(has_rows)

    def _update_selection_actions(self) -> None:
        has_selection = bool(self.table.len_selection())
        self.form.actionSelectNotes.setEnabled(has_selection)

    def _update_current_actions(self) -> None:
        self.form.action_Info.setEnabled(self.table.has_current())
        self.form.actionPreviousCard.setEnabled(self.table.has_previous())
        self.form.actionNextCard.setEnabled(self.table.has_next())

    def on_table_state_changed(self, checked: bool) -> None:
        self.mw.progress.start()
        try:
            self.table.toggle_state(checked, self._lastSearchTxt)
        except Exception as err:
            self.mw.progress.finish()
            self._switch.blockSignals(True)
            self._switch.toggle()
            self._switch.blockSignals(False)
            show_exception(parent=self, exception=err)
        else:
            self.mw.progress.finish()

    # Sidebar
    ######################################################################

    def setupSidebar(self) -> None:
        dw = self.sidebarDockWidget = QDockWidget(tr.browsing_sidebar(), self)
        dw.setFeatures(QDockWidget.DockWidgetFeature.DockWidgetClosable)
        dw.setObjectName("Sidebar")
        dock_area = (
            Qt.DockWidgetArea.RightDockWidgetArea
            if self.layoutDirection() == Qt.LayoutDirection.RightToLeft
            else Qt.DockWidgetArea.LeftDockWidgetArea
        )
        dw.setAllowedAreas(dock_area)

        self.sidebar = SidebarTreeView(self)
        self.sidebarTree = self.sidebar  # legacy alias
        dw.setWidget(self.sidebar)
        qconnect(
            self.form.actionSidebarFilter.triggered,
            self.focusSidebarSearchBar,
        )
        qconnect(dw.visibilityChanged, self.onSidebarVisibilityChange)
        grid = QGridLayout()
        grid.addWidget(self.sidebar.searchBar, 0, 0)
        grid.addWidget(self.sidebar.toolbar, 0, 1)
        grid.addWidget(self.sidebar, 1, 0, 1, 2)
        grid.setContentsMargins(8, 4, 0, 0)
        grid.setSpacing(0)
        w = QWidget()
        w.setLayout(grid)
        dw.setWidget(w)
        self.sidebarDockWidget.setFloating(False)

        self.sidebarDockWidget.setTitleBarWidget(QWidget())
        self.addDockWidget(dock_area, dw)

        # schedule sidebar to refresh after browser window has loaded, so the
        # UI is more responsive
        self.mw.progress.timer(10, self.sidebar.refresh, False, parent=self.sidebar)

    def showSidebar(self, show: bool = True) -> None:
        self.sidebarDockWidget.setVisible(show)

    def onSidebarVisibilityChange(self, visible):
        margins = self.form.verticalLayout_3.contentsMargins()
        skip_left_margin = visible and not (
            is_mac and aqt.mw.pm.get_widget_style() == WidgetStyle.NATIVE
        )
        margins.setLeft(0 if skip_left_margin else margins.right())
        self.form.verticalLayout_3.setContentsMargins(margins)

        if visible:
            self.sidebar.refresh()

    def focusSidebar(self) -> None:
        self.showSidebar()
        self.sidebar.setFocus()

    def focusSidebarSearchBar(self) -> None:
        self.showSidebar()
        self.sidebar.searchBar.setFocus()

    def toggle_sidebar(self) -> None:
        self.showSidebar(not self.sidebarDockWidget.isVisible())

    # legacy

    def setFilter(self, *terms: str) -> None:
        self.sidebar.update_search(*terms)

    # Info
    ######################################################################

    def showCardInfo(self) -> None:
        self._card_info.show()

    def _update_card_info(self) -> None:
        self._card_info.set_card(self.current_card)

    # Menu helpers
    ######################################################################

    def selected_cards(self) -> Sequence[CardId]:
        return self.table.get_selected_card_ids()

    def selected_notes(self) -> Sequence[NoteId]:
        return self.table.get_selected_note_ids()

    def selectedNotesAsCards(self) -> Sequence[CardId]:
        return self.table.get_card_ids_from_selected_note_ids()

    def onHelp(self) -> None:
        openHelp(HelpPage.BROWSING)

    # legacy

    selectedCards = selected_cards
    selectedNotes = selected_notes

    # Preview
    ######################################################################

    def onTogglePreview(self) -> None:
        if self._previewer:
            self._previewer.close()
        elif self.singleCard and self.card:
            self._previewer = PreviewDialog(self, self.mw, self._on_preview_closed)
            self._previewer.open()

    def _renderPreview(self) -> None:
        if self._previewer:
            if self.singleCard:
                self._previewer.render_card()
            else:
                self.onTogglePreview()

    def _cleanup_preview(self) -> None:
        if self._previewer:
            self._previewer.cancel_timer()
            self._previewer.close()

    def _on_preview_closed(self) -> None:
        av_player.stop_and_clear_queue()
        self._previewer = None

    # Edit: selection
    ######################################################################

    @no_arg_trigger
    @skip_if_selection_is_empty
    def selectNotes(self) -> None:
        nids = self.selected_notes()
        # clear the selection so we don't waste energy preserving it
        self.table.clear_selection()
        search = self.col.build_search_string(
            SearchNode(nids=SearchNode.IdList(ids=nids))
        )
        self.search_for(search)
        self.table.select_all()

    # Hooks
    ######################################################################

    def setupHooks(self) -> None:
        gui_hooks.backend_will_block.append(self.table.on_backend_will_block)
        gui_hooks.backend_did_block.append(self.table.on_backend_did_block)
        gui_hooks.operation_did_execute.append(self.on_operation_did_execute)
        gui_hooks.focus_did_change.append(self.on_focus_change)
        gui_hooks.collection_will_temporarily_close.append(self._on_temporary_close)

    def teardownHooks(self) -> None:
        gui_hooks.backend_will_block.remove(self.table.on_backend_will_block)
        gui_hooks.backend_did_block.remove(self.table.on_backend_did_block)
        gui_hooks.operation_did_execute.remove(self.on_operation_did_execute)
        gui_hooks.focus_did_change.remove(self.on_focus_change)
        gui_hooks.collection_will_temporarily_close.remove(self._on_temporary_close)

    def _on_temporary_close(self, col: Collection) -> None:
        # we could reload browser columns in the future; for now we just close
        self.close()

    # Jumping
    ######################################################################

    def has_previous_card(self) -> bool:
        return self.table.has_previous()

    def has_next_card(self) -> bool:
        return self.table.has_next()

    def onPreviousCard(self) -> None:
        self.table.to_previous_row()

    def onNextCard(self) -> None:
        self.table.to_next_row()

    def onFirstCard(self) -> None:
        self.table.to_first_row()

    def onLastCard(self) -> None:
        self.table.to_last_row()

    def onFind(self) -> None:
        self.form.searchEdit.setFocus()
        self._line_edit().selectAll()

    def onNote(self) -> None:
        self._card_pane.web.setFocus()

    def onCardList(self) -> None:
        self.form.tableView.setFocus()

    def _line_edit(self) -> QLineEdit:
        line_edit = self.form.searchEdit.lineEdit()
        assert line_edit is not None
        return line_edit
