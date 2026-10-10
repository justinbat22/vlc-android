/*
 * **************************************************************************
 *  SubtitleCue.kt
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
 * A single subtitle cue.
 *
 * All timestamps are in MILLISECONDS of the media timeline, the unit the
 * Android side of LibVLC exposes through [MediaPlayer.Event.TimeChanged] and
 * MediaPlayer.getTime(). Parser inputs (SRT is `HH:MM:SS,mmm`) are converted
 * once at parse time, so nothing further downstream mixes units.
 *
 * @property startMs cue start time in milliseconds.
 * @property endMs cue end time in milliseconds (exclusive; must be >= startMs).
 * @property lines text lines as authored (line breaks preserved, markup stripped).
 * @property trackId identifier of the track the cue belongs to (parser-provided,
 *   non-libvlc), used to keep multiple sources apart in the controller.
 */
class SubtitleCue(
        val startMs: Long,
        val endMs: Long,
        val lines: List<String>,
        val trackId: String = ""
) {
    /** Single-line digest of the cue, used by the controller for debugging. */
    val text: String get() = lines.joinToString("\n")

    fun contains(timeMs: Long) = timeMs in startMs until endMs
}

/**
 * Version of the overlay enabled for the current playback.
 */
enum class SubtitleOverlayMode {
    /** Overlay disabled: native VLC subtitles only. */
    OFF,
    /** External .srt file only. */
    EXTERNAL,
}

/**
 * Interface for sources able to produce subtitle cues.
 * Implementations must be cheap to construct and must do their work off the
 * main thread (the controller invokes them from coroutines).
 */
interface SubtitleCueProvider {
    /** Whether the provider was able to load its source at all. */
    val isLoaded: Boolean

    /**
     * Load (or reload) the underlying source. Runs off the main thread.
     * @return number of cues parsed, or -1 when the source could not be read.
     */
    suspend fun load(): Int

    /**
     * Find the cue displayed at [timeMs] (media timeline, milliseconds).
     * Providers keep an internal index and should return quickly (binary search).
     */
    fun cueAt(timeMs: Long): SubtitleCue?

    /** Release any resources the provider held. */
    fun release()
}

/** Stable ID prefix used to tag external-file cues inside [SubtitleCue.trackId]. */
const val EXTERNAL_CUE_TRACK_PREFIX = "external-srt:"
