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
import org.videolan.tools.putSingle
import kotlin.math.roundToInt

/**
 * Persistent preferences of the overlay: the enable state and the vertical anchor
 * of the text.
 *
 * The anchor is edited by a `SeekBarPreference` and therefore stored as a whole
 * percentage (5..100, see `preferences_subtitles.xml`). [position] normalizes it to
 * the 0.05..1.0 value the overlay works with, and keeps accepting an already
 * normalized float, so a value written under the same key by [SubtitleOverlayView]
 * is never read back as the wrong type (SharedPreferences.getFloat throws when the
 * stored value is an Int, and the other way around).
 */
object SubtitleOverlayPrefs {

    /** Extension of the files the overlay's parser can read. */
    private const val SRT_EXTENSION = "srt"

    /** Minimum anchor percentage the preferences seek bar allows. */
    private const val MIN_POSITION_PERCENT = 5

    /** Maximum anchor percentage the preferences seek bar allows. */
    private const val MAX_POSITION_PERCENT = 100

    /** Whether the screen-anchored overlay is enabled. */
    fun isEnabled(context: Context): Boolean =
            Settings.getInstance(context)
                    .getBoolean(SubtitleOverlayController.PREF_OVERLAY_ENABLED, false)

    /** Vertical anchor of the overlay text, in the [SubtitleOverlayView] coordinate space. */
    fun position(context: Context): Float {
        val stored = Settings.getInstance(context).all[SubtitleOverlayView.PREF_OVERLAY_POSITION]
        return when (stored) {
            is Int -> stored.coerceIn(MIN_POSITION_PERCENT, MAX_POSITION_PERCENT) / 100f
            is Float -> stored.coerceIn(SubtitleOverlayView.MIN_POSITION, SubtitleOverlayView.MAX_POSITION)
            else -> SubtitleOverlayView.DEFAULT_POSITION
        }
    }

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

    /** Stores the anchor as the whole percentage the preferences seek bar edits. */
    fun setPosition(context: Context, value: Float) {
        Settings.getInstance(context).putSingle(
                SubtitleOverlayView.PREF_OVERLAY_POSITION,
                (value.coerceIn(SubtitleOverlayView.MIN_POSITION, SubtitleOverlayView.MAX_POSITION) * 100)
                        .roundToInt()
                        .coerceIn(MIN_POSITION_PERCENT, MAX_POSITION_PERCENT))
    }
}
