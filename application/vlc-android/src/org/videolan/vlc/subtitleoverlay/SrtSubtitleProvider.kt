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
 * content URI). Everything is loaded once into memory into a [CueIndex], which
 * answers cue lookups with an O(log n) range query, so a 100k-cue file costs a
 * handful of comparisons per TimeChanged event.
 */
class SrtSubtitleProvider(
        private val uri: Uri,
        private val trackId: String,
        private val context: Context,
        private val charset: Charset? = null
) : SubtitleCueProvider {

    @Volatile
    private var index = CueIndex()
    override val isLoaded: Boolean get() = index.cueCount > 0

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
            index = CueIndex(result.cues)
            index.cueCount
        } catch (e: IOException) {
            Log.w("SubtitleOverlay", "srt parse failed: $e")
            -1
        } catch (e: SecurityException) {
            Log.w("SubtitleOverlay", "srt uri no longer readable: $uri")
            -1
        }
    }

    override fun cueAt(timeMs: Long): SubtitleCue? = index.cueAt(timeMs)

    override fun release() {
        index = CueIndex()
    }
}
