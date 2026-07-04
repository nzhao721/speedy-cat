/*
 * Copyright (c) 2023 lukstbit <52494258+lukstbit@users.noreply.github.com>
 *
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.ichi2.anki.preferences

import com.ichi2.anki.R

/** SpeedyCAT: backup limit settings are not user-configurable. */
class BackupLimitsSettingsFragment : SettingsFragment() {
    override val preferenceResource: Int
        get() = R.xml.preferences_backup_limits

    override val analyticsScreenNameConstant: String
        get() = "prefs.backup_limits"

    override fun initSubscreen() = Unit
}
