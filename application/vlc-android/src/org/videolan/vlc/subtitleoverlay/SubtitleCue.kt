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

/**
 * Immutable index over [SubtitleCue]s answering "which cue is displayed at a given
 * time" in O(log n), and the single implementation of the overlay's cue lookup:
 * both [SrtSubtitleProvider] and the unit tests use it.
 *
 * The cues are sorted by start time and a segment tree stores, for every range of
 * cues, the end time of the longest cue inside it. That is what makes a long cue
 * that overlaps several shorter ones safe: looking for the active cue through the
 * start times alone would discard the long cue as soon as one of the short cues
 * ending before the queried time is met, while the range maximum tells whether any
 * cue still covers the time.
 *
 * The cue returned for a time is the one that started latest among those covering
 * it (`startMs <= timeMs < endMs`), which keeps overlapping cues deterministic.
 */
class CueIndex(cues: List<SubtitleCue> = emptyList()) {

    private val cues: List<SubtitleCue> = cues.sortedBy { it.startMs }

    /** Number of leaves of the segment tree (power of two). */
    private val size: Int = run {
        var size = 1
        while (size < this.cues.size) size = size shl 1
        size
    }

    /** Segment tree of end times: `maxEnd[i]` is the max end time of the range of node `i`. */
    private val maxEnd: LongArray = LongArray(size shl 1) { Long.MIN_VALUE }.also { tree ->
        for (i in this.cues.indices) tree[size + i] = this.cues[i].endMs
        for (i in size - 1 downTo 1) tree[i] = maxOf(tree[i shl 1], tree[(i shl 1) or 1])
    }

    /** True when no cue was indexed. */
    val isEmpty: Boolean get() = cues.isEmpty()

    /** Number of cues in the index. */
    val cueCount: Int get() = cues.size

    /** The cue displayed at [timeMs] (media timeline, milliseconds), or null. */
    fun cueAt(timeMs: Long): SubtitleCue? {
        if (cues.isEmpty()) return null
        // Last cue starting at or before timeMs: only those can cover it.
        var lo = 0
        var hi = cues.size - 1
        var last = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs <= timeMs) {
                last = mid
                lo = mid + 1
            } else hi = mid - 1
        }
        if (last < 0) return null
        val index = rightmostCovering(last, timeMs)
        return if (index < 0) null else cues[index]
    }

    /**
     * Index (in [cues]) of the latest-starting cue covering [timeMs] among the first
     * [last] + 1 cues, or -1 when none does.
     */
    private fun rightmostCovering(last: Int, timeMs: Long): Int {
        // Canonical segment tree nodes covering [0, last], collected so that the
        // rightmost range is inspected first: [rightNodes] is filled from right to
        // left and [leftNodes] from left to right.
        var l = size
        var r = size + last
        val leftNodes = ArrayList<Int>(8)
        val rightNodes = ArrayList<Int>(8)
        while (l <= r) {
            if (l and 1 == 1) {
                leftNodes.add(l)
                l++
            }
            if (r and 1 == 0) {
                rightNodes.add(r)
                r--
            }
            l = l shr 1
            r = r shr 1
        }
        var node = -1
        for (i in rightNodes.indices) {
            if (maxEnd[rightNodes[i]] > timeMs) {
                node = rightNodes[i]
                break
            }
        }
        if (node < 0) {
            for (i in leftNodes.indices.reversed()) {
                if (maxEnd[leftNodes[i]] > timeMs) {
                    node = leftNodes[i]
                    break
                }
            }
        }
        if (node < 0) return -1
        // Descend to the rightmost leaf covering timeMs.
        while (node < size) {
            val rightChild = (node shl 1) or 1
            node = if (maxEnd[rightChild] > timeMs) rightChild else node shl 1
        }
        val index = node - size
        return if (index in 0..last && cues[index].endMs > timeMs) index else -1
    }
}
