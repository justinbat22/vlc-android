/*
 * **************************************************************************
 *  SubtitleOverlayRuntime.kt
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
 * What the screen-anchored overlay is drawing right now, published for the rest of the player.
 *
 * The overlay renders subtitles libvlc does not know about (the external file it took over from
 * VLC), so the player has no track to show for them: the subtitle tracks menu would look as if no
 * subtitle was selected while the overlay draws one. [PlayerController] reads this to expose the
 * overlay's subtitle as a track ([TRACK_ID] for an external file, the embedded track itself when
 * the overlay draws a track of the container), and so does the delegate to recognize its own entry
 * in the menu.
 *
 * Only the activity that owns the overlay sets it; the values are read from the player thread too,
 * hence the volatiles.
 */
object SubtitleOverlayRuntime {

    /**
     * Id of the fake track exposed in the subtitle tracks menu for the external file the overlay
     * draws. Like the external audio entry it is deliberately not numeric (VLC uses "-1" for
     * "disable track" and "-2" as a UI sentinel).
     */
    const val TRACK_ID = "overlay-subtitle"

    /** Description of the external subtitle the overlay draws, or null when it draws none. */
    @Volatile
    var externalSubtitleLabel: String? = null
        private set

    /**
     * Id of the embedded (container) track the overlay draws, or null. The track itself is already
     * listed by libvlc: publishing its id is what makes the menu show it as the selected one.
     */
    @Volatile
    var embeddedTrackId: String? = null
        private set

    /** Whether the overlay is drawing a subtitle (external file or embedded track). */
    val isRendering: Boolean get() = externalSubtitleLabel != null || embeddedTrackId != null

    /** Registers the external subtitle the overlay has taken over, [label] being its file name. */
    fun setExternalSubtitle(label: String) {
        externalSubtitleLabel = label
        embeddedTrackId = null
    }

    /** Registers the embedded track ([trackId] as libvlc reports it) the overlay has taken over. */
    fun setEmbeddedSubtitle(trackId: String) {
        embeddedTrackId = trackId
        externalSubtitleLabel = null
    }

    /** Nothing is drawn by the overlay anymore. */
    fun clear() {
        externalSubtitleLabel = null
        embeddedTrackId = null
    }
}
