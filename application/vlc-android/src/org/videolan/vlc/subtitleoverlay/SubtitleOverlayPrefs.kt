/*
 * **************************************************************************
 *  SubtitleOverlayPrefs.kt
 *  Screen-anchored subtitle overlay subsystem
 * **************************************************************************
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *  **************************************************************************
 */

package org.videolan.vlc.subtitleoverlay

import android.content.Context
import android.net.Uri
import org.videolan.tools.Settings
import org.videolan.tools.SubtitlePosition
import kotlin.math.roundToInt

/**
 * Preferences of the overlay: the enable state and the vertical anchor of the text.
 *
 * The anchor is the app-wide subtitle position setting ([SubtitlePosition]), stored as a whole
 * percentage in `preferences_subtitles.xml` and shared with VLC's native renderer: 0 is the
 * bottom edge of the player screen, 100 the top. [position] normalizes it to the 0.0..1.0 value
 * the overlay works with.
 */
object SubtitleOverlayPrefs {

    /** Extension of the files the overlay's parser can read. */
    private const val SRT_EXTENSION = "srt"

    /** Whether the screen-anchored overlay is enabled. */
    fun isEnabled(context: Context): Boolean =
            Settings.getInstance(context)
                    .getBoolean(SubtitleOverlayController.PREF_OVERLAY_ENABLED, false)

    /** Vertical anchor of the overlay text: 0.0 = bottom edge, 1.0 = top edge of the screen. */
    fun position(context: Context): Float = SubtitlePosition.position(context)

    /** Stores the anchor (normalized value). */
    fun setPosition(context: Context, value: Float) =
            SubtitlePosition.setPositionPercent(context, (value * 100).roundToInt())

    /**
     * Whether the overlay takes over the external subtitle at [source] instead of leaving it to
     * VLC's renderer: the overlay must be enabled and the file must be a SubRip one, the only
     * format [SrtParser] understands. Every other subtitle format keeps going through VLC, so
     * enabling the overlay never makes a subtitle disappear.
     */
    fun handles(context: Context, source: String?): Boolean = isEnabled(context) && isSrt(source)

    /** Whether [source] is a SubRip (.srt) file the overlay can parse. */
    fun isSrt(source: String?): Boolean {
        if (source.isNullOrBlank()) return false
        val name = Uri.parse(source).lastPathSegment ?: source
        return name.substringAfterLast('.', "").equals(SRT_EXTENSION, ignoreCase = true)
    }

    /**
     * Name shown for [source] in the subtitle tracks menu while the overlay draws it: the file
     * name, like VLC shows for an attached subtitle slave.
     */
    fun label(source: String): String {
        val uri = Uri.parse(source)
        return Uri.decode(uri.lastPathSegment ?: source)
    }
}
