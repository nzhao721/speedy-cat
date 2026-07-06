# Copyright: Ankitects Pty Ltd and contributors
# License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

import os
import sys

# Windows taskbar grouping/icon must be set before Qt loads.
if sys.platform == "win32" and not os.environ.get("SPEEDYCAT_WIN_APPID_SET"):
    import ctypes

    ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID(  # type: ignore[attr-defined]
        "com.speedycat.anki"
    )
    os.environ["SPEEDYCAT_WIN_APPID_SET"] = "1"

sys.path.extend(["pylib", "qt", "out/pylib", "out/qt"])

import aqt

if not os.environ.get("SKIP_RUN"):
    aqt.run()
