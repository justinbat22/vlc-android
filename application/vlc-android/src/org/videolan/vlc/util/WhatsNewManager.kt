/*
 * ************************************************************************
 *  WhatsNewManager.kt
 * *************************************************************************
 * Copyright © 2023 VLC authors and VideoLAN
 * Author: Nicolas POMEPUY
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 * **************************************************************************
 *
 *
 */

package org.videolan.vlc.util

import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import org.videolan.tools.KEY_LAST_WHATS_NEW
import org.videolan.tools.Settings
import org.videolan.tools.putSingle

object WhatsNewManager {
    fun launchIfNeeded(context: AppCompatActivity) {
        val preferences = Settings.getInstance(context)
        // The "What's new" dialog is permanently disabled in this build: it popped up over
        // the app on start and the user removed it. markAsShown still keeps the stored
        // version current in case this is ever re-enabled.
        markAsShown(preferences)
    }

    fun markAsShown(preferences: SharedPreferences) {
        preferences.putSingle(KEY_LAST_WHATS_NEW, "3.7")
    }
}