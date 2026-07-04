# Copyright: Ankitects Pty Ltd and contributors
# License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

from __future__ import annotations

import re
from collections.abc import Callable

import anki.lang
import aqt
import aqt.forms
from anki.utils import is_mac
from aqt import AnkiQt
from aqt.profiles import VideoDriver
from aqt.qt import *
from aqt.theme import Theme
from aqt.utils import (
    HelpPage,
    disable_help_button,
    hide_button_box_help_button,
    is_win,
    openHelp,
    showInfo,
    tr,
)


class Preferences(QDialog):
    def __init__(self, mw: AnkiQt) -> None:
        QDialog.__init__(self, mw, Qt.WindowType.Window)
        self.mw = mw
        self.prof = self.mw.pm.profile
        self.form = aqt.forms.preferences.Ui_Preferences()
        self.form.setupUi(self)

        disable_help_button(self)
        self.setWindowFlags(
            self.windowFlags()
            & ~Qt.WindowType.WindowMinimizeButtonHint
            & ~Qt.WindowType.WindowMaximizeButtonHint
        )
        hide_button_box_help_button(self.form.buttonBox)

        close_button = self.form.buttonBox.button(QDialogButtonBox.StandardButton.Close)
        assert close_button is not None
        close_button.setAutoDefault(False)
        self.silentlyClose = True
        self.remove_disabled_tabs()
        self.setup_global()
        self.show()

    def remove_disabled_tabs(self) -> None:
        """SpeedyCAT: drop stock Editing, Review, Syncing, and Backups tabs."""
        for tab in (
            self.form.tab_1,
            self.form.tab_3,
            self.form.tab_2,
            self.form.tab,
        ):
            index = self.form.tabWidget.indexOf(tab)
            if index != -1:
                self.form.tabWidget.removeTab(index)

    def accept(self) -> None:
        self.accept_with_callback()

    def accept_with_callback(self, callback: Callable[[], None] | None = None) -> None:
        # avoid exception if main window is already closed
        if not self.mw.col:
            return

        self.update_global()
        self.mw.pm.save()
        self.done(0)
        aqt.dialogs.markClosed("Preferences")

        if callback:
            callback()

    def reject(self) -> None:
        self.accept()

    # Global preferences
    ######################################################################

    def setup_global(self) -> None:
        "Setup options global to all profiles."
        self.form.uiScale.setValue(int(self.mw.pm.uiScale() * 100))
        themes = [
            tr.preferences_theme_follow_system(),
            tr.preferences_theme_light(),
            tr.preferences_theme_dark(),
        ]
        self.form.theme.addItems(themes)
        self.form.theme.setCurrentIndex(self.mw.pm.theme().value)
        qconnect(self.form.theme.currentIndexChanged, self.on_theme_changed)

        self.form.styleComboBox.addItems(["SpeedyCAT"] + (["Native"] if not is_win else []))
        self.form.styleComboBox.setCurrentIndex(self.mw.pm.get_widget_style())
        qconnect(
            self.form.styleComboBox.currentIndexChanged,
            self.mw.pm.set_widget_style,
        )
        self.form.styleLabel.setVisible(not is_win)
        self.form.styleComboBox.setVisible(not is_win)
        qconnect(self.form.resetWindowSizes.clicked, self.on_reset_window_sizes)

        self.setup_language()
        self.setup_video_driver()

        self.setupOptions()

    def update_global(self) -> None:
        restart_required = False

        self.update_video_driver()

        newScale = self.form.uiScale.value() / 100
        if newScale != self.mw.pm.uiScale():
            self.mw.pm.setUiScale(newScale)
            restart_required = True

        if restart_required:
            showInfo(tr.preferences_changes_will_take_effect_when_you())

        self.updateOptions()

    def on_theme_changed(self, index: int) -> None:
        self.mw.set_theme(Theme(index))

    def on_reset_window_sizes(self) -> None:
        assert self.prof is not None
        regexp = re.compile(r"(Geom(etry)?|State|Splitter|Header)(\d+.\d+)?$")
        for key in list(self.prof.keys()):
            if regexp.search(key):
                del self.prof[key]
        showInfo(tr.preferences_reset_window_sizes_complete())

    # legacy - one of Henrik's add-ons is currently wrapping them

    def setupOptions(self) -> None:
        pass

    def updateOptions(self) -> None:
        pass

    # Global: language
    ######################################################################

    def setup_language(self) -> None:
        f = self.form
        f.lang.addItems([x[0] for x in anki.lang.langs])
        f.lang.setCurrentIndex(self.current_lang_index())
        qconnect(f.lang.currentIndexChanged, self.on_language_index_changed)

    def current_lang_index(self) -> int:
        codes = [x[1] for x in anki.lang.langs]
        lang = anki.lang.current_lang
        if lang in anki.lang.compatMap:
            lang = anki.lang.compatMap[lang]
        else:
            lang = lang.replace("-", "_")
        try:
            return codes.index(lang)
        except Exception:
            return codes.index("en_US")

    def on_language_index_changed(self, idx: int) -> None:
        code = anki.lang.langs[idx][1]
        self.mw.pm.setLang(code)
        showInfo(tr.preferences_please_restart_anki_to_complete_language(), parent=self)

    # Global: video driver
    ######################################################################

    def setup_video_driver(self) -> None:
        self.video_drivers = VideoDriver.all_for_platform()
        names = [video_driver_name_for_platform(d) for d in self.video_drivers]
        self.form.video_driver.addItems(names)
        self.form.video_driver.setCurrentIndex(
            self.video_drivers.index(self.mw.pm.video_driver())
        )

    def update_video_driver(self) -> None:
        new_driver = self.video_drivers[self.form.video_driver.currentIndex()]
        if new_driver != self.mw.pm.video_driver():
            self.mw.pm.set_video_driver(new_driver)
            showInfo(tr.preferences_changes_will_take_effect_when_you())


def video_driver_name_for_platform(driver: VideoDriver) -> str:
    if qtmajor < 6:
        if driver == VideoDriver.ANGLE:
            return tr.preferences_video_driver_angle()
        elif driver == VideoDriver.Software:
            if is_mac:
                return tr.preferences_video_driver_software_mac()
            else:
                return tr.preferences_video_driver_software_other()
        elif driver == VideoDriver.OpenGL:
            if is_mac:
                return tr.preferences_video_driver_opengl_mac()
            else:
                return tr.preferences_video_driver_opengl_other()

    label = driver.name
    if driver == VideoDriver.default_for_platform():
        label += f" ({tr.preferences_video_driver_default()})"

    return label
