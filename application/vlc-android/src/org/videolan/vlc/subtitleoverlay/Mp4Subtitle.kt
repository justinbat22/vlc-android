/*
 * **************************************************************************
 *  Mp4Subtitle.kt
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

import java.io.IOException

/**
 * MP4 / MOV (ISO base media file format) subtitle reader.
 *
 * Only the boxes that describe the tracks and their sample tables are read (`moov`), then the text
 * of each subtitle sample is read by offset: the rest of the file (audio, video, the `mdat` box)
 * is never touched, and the sample table of a movie is a few kilobytes.
 *
 * Supported sample formats are the two ones that carry plain text: `tx3g`/`text` (3GPP / QuickTime
 * timed text) and `wvtt` (WebVTT). Styled and bitmap formats are reported but never read, VLC
 * keeps rendering them.
 */
internal object Mp4Subtitle {

    private const val MAX_SAMPLES = 200_000

    /** Display time used for a cue whose sample has no duration and that nothing closes. */
    private const val DEFAULT_CUE_DURATION_MS = 5_000L

    /** Subtitle tracks of the file, in container order (the order VLC numbers its SPU tracks in). */
    fun tracks(source: SubtitleSource): List<EmbeddedTrack> {
        val bytes = Bytes(source)
        val moov = findBox(bytes, 0, source.size, "moov") ?: return emptyList()
        val out = ArrayList<EmbeddedTrack>(4)
        var index = 0
        forEachBox(bytes, moov.contentStart, moov.end) { box ->
            if (box.type != "trak") return@forEachBox
            val parsed = parseTrack(bytes, box) ?: return@forEachBox
            out.add(EmbeddedTrack(index++, parsed.id, parsed.format, parsed.language, parsed.title))
        }
        return out
    }

    /** Windowed cue reader for the (text) track [track], or null when it cannot be read. */
    fun loader(source: SubtitleSource, track: EmbeddedTrack, trackId: String): CueWindowLoader? {
        val bytes = Bytes(source)
        val moov = findBox(bytes, 0, source.size, "moov") ?: return null
        var info: TrackInfo? = null
        var ordinal = 0
        forEachBox(bytes, moov.contentStart, moov.end) { box ->
            if (box.type != "trak") return@forEachBox
            val parsed = parseTrack(bytes, box) ?: return@forEachBox
            if (ordinal++ == track.index) info = parsed
        }
        val parsed = info ?: return null
        if (!parsed.format.text || parsed.samples.isEmpty()) return null
        return Mp4CueLoader(source, bytes, parsed.samples, parsed.timescale, parsed.format, trackId)
    }

    // ---- track parsing ----

    private class Sample(val offset: Long, val size: Int, val startMs: Long, val durationMs: Long)

    private class TrackInfo(
            val id: Long,
            val format: EmbeddedFormat,
            val language: String?,
            val title: String?,
            val timescale: Long,
            val samples: List<Sample>
    )

    /** Parses one `trak`: null when it is not a text subtitle track. */
    private fun parseTrack(bytes: Bytes, trak: Box): TrackInfo? {
        var handler: String? = null
        var timescale = 0L
        var language: String? = null
        var format: EmbeddedFormat? = null
        var samples: List<Sample> = emptyList()
        val mdia = findBox(bytes, trak.contentStart, trak.end, "mdia") ?: return null
        forEachBox(bytes, mdia.contentStart, mdia.end) { box ->
            when (box.type) {
                "mdhd" -> {
                    val parsed = parseMdhd(bytes, box)
                    if (parsed != null) {
                        timescale = parsed.first
                        language = parsed.second
                    }
                }
                "hdlr" -> {
                    // version/flags (4) + predefined (4) + handler type (4)
                    handler = fourcc(bytes, box.contentStart + 8).takeIf { it.isNotBlank() }
                }
                "minf" -> {
                    val stbl = findBox(bytes, box.contentStart, box.end, "stbl") ?: return@forEachBox
                    format = parseStsd(bytes, stbl)
                    samples = if (format?.text == true) parseSampleTable(bytes, stbl, timescale) else emptyList()
                }
            }
        }
        val type = handler ?: return null
        if (type != "sbtl" && type != "subt" && type != "text") return null
        val parsedFormat = format ?: return null
        return TrackInfo(trak.contentStart, parsedFormat, language, null, timescale, samples)
    }

    /** `(timescale, language)` of a media header box. */
    private fun parseMdhd(bytes: Bytes, box: Box): Pair<Long, String?>? {
        val version = bytes.u8(box.contentStart)
        val timescale: Long
        val languageOffset: Long
        if (version == 1) {
            timescale = bytes.u32(box.contentStart + 20)
            languageOffset = box.contentStart + 32
        } else {
            timescale = bytes.u32(box.contentStart + 12)
            languageOffset = box.contentStart + 20
        }
        if (timescale <= 0) return null
        val packed = bytes.u16(languageOffset)
        val language = if (packed == 0 || packed == 0x55C4) null else decodeLanguage(packed)
        return timescale to language
    }

    /** ISO 639-2/T language packed into the 15 bits of the media header. */
    private fun decodeLanguage(packed: Int): String? {
        val first = ((packed shr 10) and 0x1F) + 0x60
        val second = ((packed shr 5) and 0x1F) + 0x60
        val third = (packed and 0x1F) + 0x60
        if (first !in 0x61..0x7A || second !in 0x61..0x7A || third !in 0x61..0x7A) return null
        return "" + first.toChar() + second.toChar() + third.toChar()
    }

    /** Format of the first sample description of the `stsd` box. */
    private fun parseStsd(bytes: Bytes, stbl: Box): EmbeddedFormat? {
        val stsd = findBox(bytes, stbl.contentStart, stbl.end, "stsd") ?: return null
        // version/flags (4) + entry count (4), then the sample entries
        val entriesStart = stsd.contentStart + 8
        if (entriesStart + 8 > stsd.end) return null
        val entrySize = bytes.u32(entriesStart)
        if (entrySize < 8) return null
        val format = fourcc(bytes, entriesStart + 4)
        return when (format) {
            "tx3g", "text" -> EmbeddedFormat.SRT
            "wvtt" -> EmbeddedFormat.VTT
            "stpp", "sbtt", "txml" -> EmbeddedFormat.UNKNOWN
            "c608", "c708", "clcp" -> EmbeddedFormat.BITMAP
            else -> EmbeddedFormat.UNKNOWN
        }
    }

    /** Builds the sample list (offset, size, time, duration) out of the stub boxes. */
    private fun parseSampleTable(bytes: Bytes, stbl: Box, timescale: Long): List<Sample> {
        if (timescale <= 0) return emptyList()
        val stts = findBox(bytes, stbl.contentStart, stbl.end, "stts") ?: return emptyList()
        val stsz = findBox(bytes, stbl.contentStart, stbl.end, "stsz") ?: return emptyList()
        val stsc = findBox(bytes, stbl.contentStart, stbl.end, "stsc") ?: return emptyList()
        val stco = findBox(bytes, stbl.contentStart, stbl.end, "stco")
                ?: findBox(bytes, stbl.contentStart, stbl.end, "co64") ?: return emptyList()
        val largeOffsets = stco.type == "co64"

        // Times and durations
        val starts = ArrayList<Long>(256)
        val durations = ArrayList<Long>(256)
        var time = 0L
        val timeCount = bytes.u32(stts.contentStart + 4).toInt()
        var timeOffset = stts.contentStart + 8
        for (i in 0 until timeCount.coerceAtMost(MAX_SAMPLES)) {
            if (timeOffset + 8 > stts.end) break
            val count = bytes.u32(timeOffset).toInt()
            val delta = bytes.u32(timeOffset + 4)
            for (j in 0 until count) {
                if (starts.size >= MAX_SAMPLES) break
                starts.add(time)
                durations.add(delta)
                time += delta
            }
            timeOffset += 8
        }
        if (starts.isEmpty()) return emptyList()

        // Sizes
        val uniformSize = bytes.u32(stsz.contentStart + 4).toInt()
        val sampleCount = bytes.u32(stsz.contentStart + 8).toInt().coerceAtMost(starts.size)
        val sizes = IntArray(sampleCount)
        if (uniformSize > 0) {
            java.util.Arrays.fill(sizes, uniformSize)
        } else {
            var sizeOffset = stsz.contentStart + 12
            for (i in 0 until sampleCount) {
                if (sizeOffset + 4 > stsz.end) break
                sizes[i] = bytes.u32(sizeOffset).toInt()
                sizeOffset += 4
            }
        }

        // Chunks
        val chunkCount = bytes.u32(stco.contentStart + 4).toInt()
        val chunkOffsets = LongArray(chunkCount.coerceAtMost(MAX_SAMPLES))
        var chunkOffset = stco.contentStart + 8
        for (i in chunkOffsets.indices) {
            val available = if (largeOffsets) 8 else 4
            if (chunkOffset + available > stco.end) break
            chunkOffsets[i] = if (largeOffsets) bytes.u64(chunkOffset) else bytes.u32(chunkOffset)
            chunkOffset += available
        }

        // Samples per chunk, from the compact table
        val stscCount = bytes.u32(stsc.contentStart + 4).toInt()
        val firstChunk = IntArray(stscCount.coerceAtMost(MAX_SAMPLES))
        val samplesPerChunk = IntArray(firstChunk.size)
        var stscOffset = stsc.contentStart + 8
        for (i in firstChunk.indices) {
            if (stscOffset + 12 > stsc.end) break
            firstChunk[i] = bytes.u32(stscOffset).toInt()
            samplesPerChunk[i] = bytes.u32(stscOffset + 4).toInt()
            stscOffset += 12
        }

        val samples = ArrayList<Sample>(starts.size)
        var sampleIndex = 0
        var stscIndex = 0
        for (chunk in 0 until chunkCount) {
            while (stscIndex + 1 < firstChunk.size && firstChunk[stscIndex + 1] <= chunk + 1) stscIndex++
            val perChunk = if (stscIndex < samplesPerChunk.size) samplesPerChunk[stscIndex] else 0
            var offset = if (chunk < chunkOffsets.size) chunkOffsets[chunk] else break
            for (i in 0 until perChunk) {
                if (sampleIndex >= sampleCount || sampleIndex >= starts.size) break
                val size = sizes[sampleIndex]
                if (size <= 0) break
                samples.add(Sample(offset, size, toMillis(starts[sampleIndex], timescale),
                        toMillis(durations[sampleIndex], timescale)))
                offset += size
                sampleIndex++
            }
            if (sampleIndex >= sampleCount) break
        }
        return samples
    }

    private fun toMillis(value: Long, timescale: Long): Long =
            if (timescale <= 0) value else value * 1000L / timescale

    // ---- cue reading ----

    private class Mp4CueLoader(
            private val source: SubtitleSource,
            private val bytes: Bytes,
            private val samples: List<Sample>,
            private val timescale: Long,
            private val format: EmbeddedFormat,
            private val trackId: String
    ) : CueWindowLoader {

        override fun loadWindow(fromMs: Long, untilMs: Long): List<SubtitleCue> {
            val cues = ArrayList<SubtitleCue>(32)
            var index = firstSampleAtOrAfter(fromMs)
            var previous: Sample? = null
            var previousLines: List<String> = emptyList()
            while (index < samples.size && samples[index].startMs < untilMs) {
                val sample = samples[index]
                index++
                val lines = SubtitleText.lines(readText(sample))
                if (lines.isEmpty()) {
                    // Timed text tracks clear a cue with an empty sample: it ends the one on screen
                    previous?.let { cues.add(cueOf(it, previousLines, sample.startMs)) }
                    previous = null
                    previousLines = emptyList()
                    continue
                }
                previous?.let { cues.add(cueOf(it, previousLines, sample.startMs)) }
                previous = sample
                previousLines = lines
            }
            // Close the last cue of the window: its own duration when the sample table has one, the
            // next cue otherwise (the sample that follows the window closes it too)
            previous?.let { last ->
                // The sample duration is the authored display time (VLC uses it too); only samples
                // without one are displayed until the next cue
                val end = when {
                    last.durationMs > 0 -> last.startMs + last.durationMs
                    samples.size > index -> samples[index].startMs
                    else -> last.startMs + DEFAULT_CUE_DURATION_MS
                }
                cues.add(cueOf(last, previousLines, end))
            }
            return cues
        }

        private fun cueOf(sample: Sample, lines: List<String>, endMs: Long): SubtitleCue =
                SubtitleCue(sample.startMs, maxOf(endMs, sample.startMs + 1), lines, trackId)

        /** First sample starting at or after [timeMs] (binary search). */
        private fun firstSampleAtOrAfter(timeMs: Long): Int {
            var lo = 0
            var hi = samples.size - 1
            var result = samples.size
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (samples[mid].startMs >= timeMs) {
                    result = mid
                    hi = mid - 1
                } else lo = mid + 1
            }
            return result
        }

        /** Text of one sample, per sample format (`tx3g`/`text` length-prefixed, `wvtt` boxes). */
        private fun readText(sample: Sample): String {
            val payload = bytes.bytes(sample.offset, sample.size) ?: return ""
            if (payload.size < 2) return ""
            return when (format) {
                EmbeddedFormat.VTT -> readWebVtt(payload)
                else -> readTimedText(payload)
            }
        }

        /** 3GPP/QuickTime timed text: a big-endian text length, then the text, then style boxes. */
        private fun readTimedText(payload: ByteArray): String {
            val length = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
            if (length <= 0) return ""
            val end = minOf(2 + length, payload.size)
            val text = SubtitleText.decodeTimedText(payload.copyOfRange(2, end))
            return text.trim('\u0000')
        }

        /** WebVTT in ISO BMFF: a sequence of boxes, `vttc` carrying the payload (`payl`). */
        private fun readWebVtt(payload: ByteArray): String {
            val builder = StringBuilder()
            var position = 0
            while (position + 8 <= payload.size) {
                val size = ((payload[position].toInt() and 0xFF) shl 24) or
                        ((payload[position + 1].toInt() and 0xFF) shl 16) or
                        ((payload[position + 2].toInt() and 0xFF) shl 8) or
                        (payload[position + 3].toInt() and 0xFF)
                val type = String(payload, position + 4, 4, Charsets.ISO_8859_1)
                if (size < 8 || position + size > payload.size) break
                if (type == "vttc") {
                    var child = position + 8
                    while (child + 8 <= position + size) {
                        val childSize = ((payload[child].toInt() and 0xFF) shl 24) or
                                ((payload[child + 1].toInt() and 0xFF) shl 16) or
                                ((payload[child + 2].toInt() and 0xFF) shl 8) or
                                (payload[child + 3].toInt() and 0xFF)
                        val childType = String(payload, child + 4, 4, Charsets.ISO_8859_1)
                        if (childSize < 8 || child + childSize > position + size) break
                        if (childType == "payl") {
                            val text = SubtitleText.decodeTimedText(
                                    payload.copyOfRange(child + 8, child + childSize))
                            if (builder.isNotEmpty()) builder.append('\n')
                            builder.append(text)
                        }
                        child += childSize
                    }
                }
                position += size
            }
            return builder.toString().trim('\u0000')
        }

        override fun close() {
            source.close()
        }
    }

    // ---- box primitives ----

    private class Box(val type: String, val start: Long, val contentStart: Long, val end: Long)

    /** Reads the box header at [start]; null at the end of [limit] or on a malformed box. */
    private fun readBox(bytes: Bytes, start: Long, limit: Long): Box? {
        if (start + 8 > limit) return null
        val size32 = bytes.u32(start)
        val type = fourcc(bytes, start + 4)
        var headerSize = 8L
        var size = size32
        if (size32 == 1L) {
            if (start + 16 > limit) return null
            size = bytes.u64(start + 8)
            headerSize = 16L
        } else if (size32 == 0L) {
            size = limit - start
        }
        if (size < headerSize || start + size > limit) return null
        return Box(type, start, start + headerSize, start + size)
    }

    /** Walks the direct children of the range `[from, until)` and calls [action] on each box. */
    private inline fun forEachBox(bytes: Bytes, from: Long, until: Long, action: (Box) -> Unit) {
        var position = from
        while (position < until) {
            val box = readBox(bytes, position, until) ?: return
            action(box)
            position = box.end
        }
    }

    /** First child box of the given [type] in the range `[from, until)`. */
    private fun findBox(bytes: Bytes, from: Long, until: Long, type: String): Box? {
        var position = from
        while (position < until) {
            val box = readBox(bytes, position, until) ?: return null
            if (box.type == type) return box
            position = box.end
        }
        return null
    }

    private fun fourcc(bytes: Bytes, position: Long): String =
            String(bytes.bytes(position, 4) ?: ByteArray(0), Charsets.ISO_8859_1)

    /** Random-access reader over a [SubtitleSource] with a small sliding cache. */
    private class Bytes(private val source: SubtitleSource) {

        private val cache = ByteArray(64 * 1024)
        private var cacheStart = -1L
        private var cacheLength = 0

        private fun load(position: Long): Boolean {
            val read = source.read(position, cache, 0, cache.size)
            if (read <= 0) return false
            cacheStart = position
            cacheLength = read
            return true
        }

        private fun fetch(position: Long, length: Int): ByteArray? {
            if (position < 0 || length < 0 || position + length > source.size) return null
            val out = ByteArray(length)
            var copied = 0
            while (copied < length) {
                val current = position + copied
                if (cacheLength <= 0 || current < cacheStart || current >= cacheStart + cacheLength) {
                    if (!load(current)) return null
                }
                val offset = (current - cacheStart).toInt()
                val available = minOf(cacheLength - offset, length - copied)
                System.arraycopy(cache, offset, out, copied, available)
                copied += available
            }
            return out
        }

        fun bytes(position: Long, length: Int): ByteArray? =
                if (length <= 0) ByteArray(0) else fetch(position, length.coerceAtMost(1 shl 20))

        fun u8(position: Long): Int = fetch(position, 1)?.get(0)?.toInt()?.and(0xFF) ?: 0

        fun u16(position: Long): Int {
            val data = fetch(position, 2) ?: return 0
            return ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        }

        fun u32(position: Long): Long {
            val data = fetch(position, 4) ?: return 0L
            var value = 0L
            for (byte in data) value = (value shl 8) or (byte.toLong() and 0xFF)
            return value
        }

        fun u64(position: Long): Long {
            val data = fetch(position, 8) ?: return 0L
            var value = 0L
            for (byte in data) value = (value shl 8) or (byte.toLong() and 0xFF)
            return value
        }
    }
}
