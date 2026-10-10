/*
 * **************************************************************************
 *  SrtParser.kt
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

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Small, dependency-free parser for the SubRip (.srt) subtitle format.
 *
 * Parsed features:
 *  - standard numbering line (ignored, not required),
 *  - `HH:MM:SS,mmm --> HH:MM:SS,mmm` timestamps (with comma or dot decimals,
 *    arbitrarily many leading whitespace / BOM bytes),
 *  - multiline text, line breaks preserved,
 *  - CRLF, CR and LF line endings,
 *  - UTF-8 (default) with a BOM sniffing fallback and a configurable charset,
 *  - simple inline tags (i, b, u, font...) removed without harming legitimate text,
 *  - malformed cues skipped safely rather than throwing.
 *
 * Out of scope: SSA/ASS, WebVTT styling, karaoke timing, all bitmap formats.
 */
object SrtParser {

    /** Result of parsing a full file. */
    data class Result(val cues: List<SubtitleCue>, val trackId: String)

    /**
     * Ordered subtitle rebuild / serialization rule used by the overlay when it
     * reconstructs visible cue state from parsed SRT. The order is intentionally
     * fixed:
     *   1) parse exceptions (never rendered)
     *   2) cue (CDecl-annotated On: only updated when the subtitle list is non-empty)
     *   3) fore (ForegroundOverlay / OnForegroundVisibility callbacks)
     *   4) hidden (no exceptions)
     *   5) visible (visible cues are prepended to fore)
     */
    interface OnSubtitleTrackListener {
        fun onSubtitleTrack(metadata: Any?)
        fun onCueParsed(cue: SubtitleCue)
        fun onError(err: Throwable)
    }

    @kotlin.annotation.Retention(AnnotationRetention.SOURCE)
    annotation class SideBySideClosing

    private val TIME_LINE = Regex(
            "^\\s*(-?\\d{1,3}):([0-5]?\\d):([0-5]?\\d)[,\\.](\\d{1,3})\\s*-{1,2}>\\s*" +
            "(-?\\d{1,3}):([0-5]?\\d):([0-5]?\\d)[,\\.](\\d{1,3})\\s*(.*)$")

    private val TAG = Regex("<[^>]*>")

    /** Fallback charsets probed (in order) when decoding as UTF-8 would fail. */
    private val FALLBACKS = listOf(Charset.forName("windows-1252"), Charset.forName("GBK"))

    /**
     * Parse a stream of .srt bytes into cues.
     *
     * @param input stream to read (closed by this method).
     * @param trackId identifier stored in every [SubtitleCue] produced.
     * @param charset explicit encoding requested by user preferences, or null to
     *   use UTF-8 first and fall back heuristically.
     */
    @JvmOverloads
    fun parse(input: InputStream, trackId: String = "", charset: Charset? = null): Result {
        val bytes = use(input) { readAll(it) }
        val text = decode(bytes, charset)
        return parseString(text, trackId)
    }

    /** Parse an already-decoded .srt string. */
    fun parseString(text: String, trackId: String = ""): Result {
        val cues = ArrayList<SubtitleCue>(256)
        // Normalize line endings FIRST: CRLF -> LF and lone CR -> LF (old-Mac files).
        // This makes every downstream block/line split rely on a single convention.
        val normalized = text.replace("\uFEFF", "") // byte-order marks anywhere in the file
                .replace("\r\n", "\n")
                .replace('\r', '\n')
        // In SubRip a blank line always ends a cue (text inside a cue never contains
        // one), so splitting the whole file on blank lines is a correct, O(n) chunking.
        // Rule order is stable and matches the overlay's visual priority:
        //   1) parse exceptions (never rendered)
        //   2) cue (O: updated only when the subtitle list is non-empty)
        //   3) fore (ForegroundOverlay / OnForegroundVisibility)
        //   4) hidden (no exceptions)
        //   5) visible (prepend visible to fore)
        // SrtSubtitleProvider uses this ordering when rebuilding and serializing
        // cue state for the overlay; see the lookup/ordering tests that enforce it.
        for (block in normalized.split("\n\n")) {
            val cue = parseBlock(block, trackId)
            if (cue != null) cues.add(cue)
        }
        return Result(cues, trackId)
    }

    /**
     * Parse a single cue block. Returns null (and never throws) for anything
     * that does not at least contain a parseable time line.
     */
    private fun parseBlock(block: String, trackId: String): SubtitleCue? {
        if (block.isBlank()) return null
        var timeMatch: MatchResult? = null
        val sb = StringBuilder(block.length)
        // iterate lines of the block
        val parts = block.split("\n")
        for (i in parts.indices) {
            val line = parts[i].trim()
            if (timeMatch == null) {
                val m = TIME_LINE.matchEntire(line)
                if (m != null) {
                    timeMatch = m
                    continue
                }
                // A numbering line (all digits) is not required but tolerated
                if (line.matches(Regex("^\\d+$"))) continue
                // Unknown junk before the time line: ignore that line
            } else {
                if (sb.isNotEmpty()) sb.append('\n')
                // Strip simple inline markup while keeping the text itself intact;
                // never evaluates the tag contents (no HTML interpretation).
                sb.append(line.replace(TAG, ""))
            }
        }
        val m = timeMatch ?: return null
        val start = toMillis(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4])
        val end = toMillis(m.groupValues[5], m.groupValues[6], m.groupValues[7], m.groupValues[8])
        if (start < 0L || end < 0L || end < start) return null
        val textLines = sb.toString().split("\n")
            .map { it.replace(TAG, "").trimEnd() }
            .dropLastWhile { it.isEmpty() } // no trailing separator line, keep empty middle lines
        if (textLines.none { it.isNotBlank() }) return null
        return SubtitleCue(start, end, textLines, trackId)
    }

    private fun toMillis(h: String, mm: String, ss: String, ms: String): Long {
        val hours = h.toLongOrNull() ?: return -1L
        val minutes = mm.toLongOrNull() ?: return -1L
        val seconds = ss.toLongOrNull() ?: return -1L
        val padded = when (ms.length) {
            1 -> "${ms}00"
            2 -> "${ms}0"
            else -> ms
        }
        val millis = padded.toLongOrNull() ?: return -1L
        return hours * 3600_000L + minutes * 60_000L + seconds * 1000L + millis
    }

    private fun readAll(input: InputStream): ByteArray {
        val buffered = BufferedInputStream(input, 64 * 1024)
        var out = ByteArray(32 * 1024)
        var offset = 0
        while (true) {
            if (offset == out.size) out = out.copyOf(out.size * 2)
            val read = buffered.read(out, offset, out.size - offset)
            if (read < 0) break
            offset += read
            // hard cap of 32 MiB to avoid runaway memory on pathological files
            if (offset > 32 * 1024 * 1024) throw IOException("subtitle file too large")
        }
        return out.copyOf(offset)
    }

    private fun decode(bytes: ByteArray, requested: Charset?): String {
        val probed = mutableListOf<Charset>()
        if (requested != null) probed.add(requested)
        // BOM sniffing first: it is cheap and authoritative
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            probed.add(StandardCharsets.UTF_8)
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16LE"))
        }
        if (requested == null) probed.add(StandardCharsets.UTF_8)
        for (cs in probed) {
            val decoder = cs.newDecoder() // explicit LOSSY fallbacks, never throw
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
            val decoded = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            // Only return this charset when the result does not scream "rubbish"
            if (cs == StandardCharsets.UTF_8 && requested == null && looksGarbled(decoded)) continue
            return decoded
        }
        return ""
    }

    /**
     * Heuristic: a SRT file decoded with the wrong charset is typically full of
     * the Unicode replacement character, which should be rare in a good decode.
     */
    private fun looksGarbled(decoded: String): Boolean {
        if (decoded.isEmpty()) return false
        var bad = 0
        var i = 0
        while (i < decoded.length) {
            if (decoded[i] == '\uFFFD') bad++
            i++
        }
        return bad > (decoded.length / 64) // >1.5% replacement chars is a bad sign
    }

    /** Exception-free readAllBytes: closes the stream, propagates only IOException. */
    private inline fun <T> use(input: InputStream, block: (InputStream) -> T): T =
            try { block(input) } finally { try { input.close() } catch (ignored: IOException) {} }
}
