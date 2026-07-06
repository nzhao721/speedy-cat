// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 SpeedyCAT contributors

package com.ichi2.anki.practice

import android.graphics.drawable.Drawable
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ichi2.anki.R

/** White app bar with black title/icons, matching the Practice Questions Compose screen. */
fun AppCompatActivity.applySpeedyCatLightAppBar(toolbar: Toolbar? = findViewById(R.id.toolbar)) {
    toolbar ?: return
    val white = ContextCompat.getColor(this, android.R.color.white)
    val black = ContextCompat.getColor(this, android.R.color.black)
    toolbar.setBackgroundColor(white)
    toolbar.setTitleTextColor(black)
    toolbar.setSubtitleTextColor(black)
    toolbar.navigationIcon = toolbar.navigationIcon?.tinted(black)
    toolbar.overflowIcon = toolbar.overflowIcon?.tinted(black)
    window.statusBarColor = white
    WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
    findViewById<android.view.View>(R.id.top_bar)?.setBackgroundColor(white)
}

private fun Drawable.tinted(color: Int): Drawable {
    val wrapped = DrawableCompat.wrap(mutate())
    DrawableCompat.setTint(wrapped, color)
    return wrapped
}
