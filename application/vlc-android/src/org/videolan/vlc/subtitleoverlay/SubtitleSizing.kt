/*
 * **************************************************************************
 *  SubtitleSizing.kt
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

/**
 * Text size of the overlay, derived from the same "Subtitle size" setting VLC uses.
 *
 * The setting holds libvlc's `freetype-rel-fontsize` DIVISOR: the text height is the video height
 * divided by it, so a LARGER value means SMALLER text (nano = 40 ... normal = 16 ... huge = 10).
 * The overlay is not tied to the video rectangle, so it divides the SHORT side of its own container
 * (the screen height in landscape, the width in portrait: the same on both orientations) instead.
 * With the default "normal" (16) that gives 25.7dp on a 411dp wide phone, the size the overlay had
 * before the setting was honoured.
 */
object SubtitleSizing {

    /** Divisor of the default "normal" size. */
    const val DEFAULT_DIVISOR = 16

    /** Largest divisor (smallest text) and smallest divisor (largest text) accepted. */
    const val MAX_DIVISOR = 100
    const val MIN_DIVISOR = 4

    /** The setting as an int; unknown, empty or out-of-range values fall back to the default. */
    fun divisorOf(setting: String?): Int {
        val value = setting?.trim()?.toIntOrNull() ?: return DEFAULT_DIVISOR
        return if (value in MIN_DIVISOR..MAX_DIVISOR) value else DEFAULT_DIVISOR
    }

    /** Text size, in px, for [setting] on a container whose short side is [shortSidePx]. */
    fun textSizePx(setting: String?, shortSidePx: Float): Float =
            (shortSidePx / divisorOf(setting)).coerceAtLeast(MIN_TEXT_PX)

    private const val MIN_TEXT_PX = 8f
}
