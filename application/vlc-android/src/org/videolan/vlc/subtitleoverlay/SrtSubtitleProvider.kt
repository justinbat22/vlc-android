/*
 * **************************************************************************
 *  SrtSubtitleProvider.kt
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

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.charset.Charset

/**
 * [SubtitleCueProvider] reading an external .srt file (a local file path or a
 * content URI). Everything is loaded once into memory sorted by start time,
 * then cue lookup is an O(log n) binary search, so a 100k-cue file costs a
 * handful of comparisons per TimeChanged event.
 */
class SrtSubtitleProvider(
        private val uri: Uri,
        private val trackId: String,
        private val context: Context,
        private val charset: Charset? = null
) : SubtitleCueProvider {

    private var cues: List<SubtitleCue> = emptyList()
    override val isLoaded: Boolean get() = loaded
    @Volatile
    private var loaded = false

    override suspend fun load(): Int = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val stream = try {
            resolver.openInputStream(uri)
        } catch (e: FileNotFoundException) {
            Log.w("SubtitleOverlay", "srt uri not openable: $uri")
            null
        } ?: return@withContext -1
        try {
            val result = SrtParser.parse(stream, trackId, charset)
            cues = result.cues.sortedBy { it.startMs }
            loaded = cues.isNotEmpty()
            cues.size
        } catch (e: IOException) {
            Log.w("SubtitleOverlay", "srt parse failed: $e")
            -1
        } catch (e: SecurityException) {
            Log.w("SubtitleOverlay", "srt uri no longer readable: $uri")
            -1
        }
    }

    override fun cueAt(timeMs: Long): SubtitleCue? {
        val list = cues
        // cues are sorted; the active cue = the last one starting at or before
        // time, and it must still cover the time, otherwise nothing is shown.
        var lo = 0
        var hi = list.size - 1
        var candidate: SubtitleCue? = null
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = list[mid]
            when {
                c.endMs <= timeMs -> lo = mid + 1
                c.startMs > timeMs -> hi = mid - 1
                else -> {
                    // overlapping cues: display the one that started latest, keeping
                    // the cue currently shown when durations overlap (deterministic)
                    if (candidate == null || c.startMs > candidate.startMs) candidate = c
                    lo = mid + 1
                }
            }
        }
        return candidate?.takeIf { it.contains(timeMs) ?: false }
    }

    override fun release() {
        cues = emptyList()
        loaded = false
    }

    /**
     * Test seam (called from the jvm test sources, see SubtitleOverlayTestSupport.kt):
     * injects a parsed + sorted cue list without file I/O.
     */
    internal fun setCuesForTestInternal(sorted: List<SubtitleCue>) {
        cues = sorted
        loaded = sorted.isNotEmpty()
    }
}
