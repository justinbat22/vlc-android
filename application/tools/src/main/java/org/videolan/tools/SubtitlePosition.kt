/*
 * **************************************************************************
 *  SubtitlePosition.kt
 *  Shared subtitle vertical position setting
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

package org.videolan.tools

import android.content.Context
import kotlin.math.roundToInt

/**
 * The single subtitle vertical position setting of the app, shared by the screen-anchored
 * subtitle overlay and by VLC's own (native) subtitle renderer.
 *
 * Semantics:
 *  - the value is a whole percentage, 0 = bottom edge of the player screen, 100 = top;
 *  - the overlay anchors the bottom of the subtitle text at that height;
 *  - VLC's renderer is moved with `--sub-margin`, mapped onto the screen height, because
 *    libvlc only exposes a pixel margin whose origin is its own bottom position.
 *
 * Styled subtitles (ASS/SSA) are authored with their own coordinates and must not be moved:
 * when one is selected, [nativeMarginPx] returns 0 so no `--sub-margin` is passed at all.
 * libvlc reads that option when the video output is created, so a change of the "styled"
 * state only takes effect on the next video output creation; [needsEngineRefresh] tells the
 * player when that is required.
 */
object SubtitlePosition {

    /** Preference key holding the position, as a whole percentage [MIN_PERCENT]..[MAX_PERCENT]. */
    const val PREF_KEY = "subtitle_overlay_position"

    /** Preference key of the one-shot migration marker of the pre-1.0 "distance from top" scale. */
    private const val PREF_MIGRATED = "subtitle_overlay_position_migrated"

    /** Lowest position: the subtitles rest on the bottom edge of the screen. */
    const val MIN_PERCENT = 0

    /** Highest position: the subtitles reach the top edge of the screen. */
    const val MAX_PERCENT = 100

    /**
     * Default position. 0 means "bottom" for the overlay and "VLC's own position" for the native
     * renderer (no `--sub-margin` option), so builds that never touched the setting keep the
     * subtitles exactly where upstream puts them.
     */
    const val DEFAULT_PERCENT = 0

    /**
     * Fallback span of the native margin when the display size is unknown. The position is mapped
     * onto the height of the screen, so that the top of the scale really reaches the top.
     */
    const val FALLBACK_MARGIN_SPAN_PX = 500

    /**
     * True while the subtitle currently selected for the playback is a styled one (ASS/SSA) or a
     * bitmap one (DVB, VobSub, PGS): VLC renders those itself with their own coordinates, so the
     * native subtitle margin must stay 0.
     */
    @Volatile
    private var styledSubtitleActive: Boolean = false

    /** Whether the subtitle VLC is currently configured for is a styled (ASS/SSA) or bitmap one. */
    val isStyledSubtitleActive: Boolean get() = styledSubtitleActive

    /** Margin (in pixels) the running libvlc instance was built with, -1 when it is unknown. */
    @Volatile
    var appliedMarginPx: Int = -1

    /**
     * Position of the subtitles, as a whole percentage between [MIN_PERCENT] and [MAX_PERCENT].
     *
     * Values stored by the old overlay seek bar are migrated once: that bar stored the distance
     * from the *top* (5..100, 100 = bottom edge), the opposite of this scale. Its default (98)
     * was written as soon as the settings screen was opened, so the whole 98..100 band maps to
     * the new default (no visible move for users who never touched it).
     */
    fun positionPercent(context: Context): Int {
        val settings = Settings.getInstance(context)
        val stored = settings.all[PREF_KEY]
        if (settings.getBoolean(PREF_MIGRATED, false))
            return (stored as? Number)?.toInt()?.coerceIn(MIN_PERCENT, MAX_PERCENT) ?: DEFAULT_PERCENT
        val value = migrate(stored)
        settings.putSingle(PREF_KEY, value)
        settings.putSingle(PREF_MIGRATED, true)
        return value
    }

    /**
     * Maps a value stored before this scale existed onto it.
     *
     * The old overlay seek bar stored the distance from the *top* (5..100, 100 = bottom edge), the
     * opposite of this scale, and wrote its default (98) as soon as the settings screen was opened:
     * that whole top band maps to the default so users who never touched it see no move. A
     * normalized float (0.0..1.0, the value the overlay itself writes) is converted as is.
     */
    fun migrate(stored: Any?): Int = when (stored) {
        is Float, is Double -> ((stored as Number).toDouble() * 100).roundToInt().coerceIn(MIN_PERCENT, MAX_PERCENT)
        is Number -> (MAX_PERCENT - stored.toInt()).let { if (it <= 2) DEFAULT_PERCENT else it.coerceIn(MIN_PERCENT, MAX_PERCENT) }
        else -> DEFAULT_PERCENT
    }

    /** Stores the position as a whole percentage between [MIN_PERCENT] and [MAX_PERCENT]. */
    fun setPositionPercent(context: Context, percent: Int) {
        val settings = Settings.getInstance(context)
        settings.putSingle(PREF_KEY, percent.coerceIn(MIN_PERCENT, MAX_PERCENT))
        if (!settings.getBoolean(PREF_MIGRATED, false)) settings.putSingle(PREF_MIGRATED, true)
    }

    /** Normalized position (0.0 = bottom, 1.0 = top) of the current setting. */
    fun position(context: Context): Float = normalize(positionPercent(context))

    /** A whole percentage as the normalized anchor the overlay works with. */
    fun normalize(percent: Int): Float = percent.coerceIn(MIN_PERCENT, MAX_PERCENT) / 100f

    /**
     * `--sub-margin` value, in pixels, matching the current position, or 0 when the option must
     * not be passed at all: at the bottom of the scale the native renderer keeps its own default
     * position, and styled subtitles are never moved.
     */
    fun nativeMarginPx(context: Context): Int =
            marginFor(positionPercent(context), styledSubtitleActive, screenHeightPx(context))

    /**
     * `--sub-margin` value, in pixels, for [percent] and [styled]: 0 when the option must not be
     * passed at all, which is the case at the bottom of the scale (VLC's own position is kept) and
     * for styled subtitles, whose coordinates are authored and must not be shifted.
     *
     * [spanPx] is the span the scale is mapped onto (the height of the screen), so the whole travel
     * of the seek bar is used; libvlc keeps a subtitle that would leave the image inside it.
     */
    fun marginFor(percent: Int, styled: Boolean, spanPx: Int = FALLBACK_MARGIN_SPAN_PX): Int {
        if (styled) return 0
        val value = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)
        if (value <= MIN_PERCENT) return 0
        return (value / 100f * spanPx).roundToInt().coerceAtLeast(0)
    }

    /** Height of the display the subtitles are drawn on: the span of the position scale. */
    private fun screenHeightPx(context: Context): Int =
            context.resources.displayMetrics.heightPixels.takeIf { it > 0 } ?: FALLBACK_MARGIN_SPAN_PX

    /**
     * Records the margin the current libvlc instance was built with (called from the option
     * builder) so the player can tell whether the running engine matches the setting.
     */
    fun onEngineBuilt(marginPx: Int) {
        appliedMarginPx = marginPx
    }

    /**
     * Whether the running libvlc instance was built with a subtitle margin that no longer matches
     * the setting and the selected subtitle type: the video output has to be rebuilt (see
     * PlaybackService.reloadPlayback) for the change to show.
     */
    fun needsEngineRefresh(context: Context): Boolean {
        val applied = appliedMarginPx
        return applied >= 0 && applied != nativeMarginPx(context)
    }

    /**
     * Records whether the subtitle now rendered by VLC is a styled one (ASS/SSA) or a bitmap one,
     * and reports whether the running engine is out of sync with the setting:
     * `--sub-margin` is read when the video output is created, so a margin that has to appear (a
     * text subtitle is selected) or disappear (a styled subtitle is selected) only applies to a
     * player built after this call.
     */
    fun setStyledSubtitleActive(context: Context, styled: Boolean): Boolean {
        styledSubtitleActive = styled
        return needsEngineRefresh(context)
    }

    /**
     * Whether a subtitle described by [description] (the track codec name reported by libvlc,
     * for instance "SubStation Alpha subtitles" or "DVB subtitles") is rendered by VLC from its
     * own coordinates and must not be shifted by the subtitle margin.
     */
    fun isStyledSubtitleDescription(description: String?): Boolean {
        if (description.isNullOrBlank()) return false
        val value = description.lowercase()
        return value.contains("substation") || value.contains("ssa") ||
                value.contains("dvb") || value.contains("vobsub") || value.contains("pgs") ||
                value.contains("hdmv") || value.contains("cvd") || value.contains("teletext")
    }

    /**
     * Whether a subtitle file name is of a format rendered by VLC with its own coordinates
     * (ASS/SSA or the common bitmap formats).
     */
    fun isStyledSubtitleFile(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        return when (name.substringAfterLast('.', "").lowercase()) {
            "ass", "ssa", "idx", "sup", "pgs" -> true
            else -> false
        }
    }
}
