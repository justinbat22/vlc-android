/*
 * **************************************************************************
 *  MatroskaSubtitle.kt
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

import java.io.EOFException
import java.io.IOException
import java.util.zip.Inflater

/**
 * Matroska (`.mkv`, `.mka`, `.mks`) and WebM subtitle reader.
 *
 * The container is walked without ever loading it in memory: element payloads that are not needed
 * (video and audio frames, the bulk of a movie) are skipped by their declared size, and the read
 * buffer is kept small so those skips cost a header read instead of megabytes. Listing the tracks
 * costs a few kilobytes; moving to a given playback position costs one header read per cluster.
 *
 * Only text tracks the overlay can draw are read; styled (ASS/SSA) and bitmap (VobSub, DVB, PGS)
 * tracks are reported but never extracted, VLC keeps rendering them with their authored layout.
 */
internal object MatroskaSubtitle {

    // EBML element ids (the marker bits are part of the constants, as usual for Matroska)
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_INFO = 0x1549A966L
    private const val ID_TIMESTAMP_SCALE = 0x2AD7B1L
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_TRACK_NUMBER = 0xD7L
    private const val ID_TRACK_TYPE = 0x83L
    private const val ID_CODEC_ID = 0x86L
    private const val ID_LANGUAGE = 0x22B59CL
    private const val ID_NAME = 0x536EL
    private const val ID_CONTENT_ENCODINGS = 0x6D80L
    private const val ID_CONTENT_ENCODING = 0x6240L
    private const val ID_CONTENT_COMPRESSION = 0x5034L
    private const val ID_CONTENT_COMP_ALGO = 0x4254L
    private const val ID_CONTENT_COMP_SETTINGS = 0x4255L
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_CLUSTER_TIMESTAMP = 0xE7L
    private const val ID_SIMPLE_BLOCK = 0xA3L
    private const val ID_BLOCK_GROUP = 0xA0L
    private const val ID_BLOCK = 0xA1L
    private const val ID_BLOCK_DURATION = 0x9BL

    private const val TRACK_TYPE_SUBTITLE = 0x11L
    private const val DEFAULT_TIMESTAMP_SCALE = 1_000_000L // 1 ms per tick
    private const val MAX_CUE_DURATION_MS = 30_000L
    private const val DEFAULT_CUE_DURATION_MS = 5_000L
    private const val COMPRESSION_ZLIB = 0L
    private const val COMPRESSION_HEADER_STRIPPING = 3L

    /** Unknown-size element (streamed segments): the file is not usable for extraction. */
    private const val UNKNOWN_SIZE = -1L

    /** Subtitle tracks of the file, in container order (the order VLC numbers its SPU tracks in). */
    fun tracks(source: SubtitleSource): List<EmbeddedTrack> {
        val metadata = readMetadata(source)
        return metadata.tracks.withIndex().map { (index, track) ->
            EmbeddedTrack(index, track.number, track.format, track.language, track.title)
        }
    }

    /** Windowed cue reader for the (text) track [track], or null when it cannot be read. */
    fun loader(source: SubtitleSource, track: EmbeddedTrack, trackId: String): CueWindowLoader? {
        val metadata = readMetadata(source)
        val info = metadata.tracks.firstOrNull { it.number == track.id } ?: return null
        if (!info.format.text) return null
        val compression = info.compression
        if (compression != null && compression != COMPRESSION_ZLIB && compression != COMPRESSION_HEADER_STRIPPING)
            return null
        return MatroskaCueLoader(
                source, Reader(source), metadata.segmentStart, metadata.segmentEnd,
                metadata.timestampScale, info.number, compression, info.compressionSettings, trackId)
    }

    // ---- metadata ----

    private class TrackInfo(
            val number: Long,
            val format: EmbeddedFormat,
            val language: String?,
            val title: String?,
            val compression: Long?,
            val compressionSettings: ByteArray?
    )

    private class Metadata(
            val segmentStart: Long,
            val segmentEnd: Long,
            val timestampScale: Long,
            val tracks: List<TrackInfo>
    )

    /**
     * Reads the Segment header, the timestamp scale and the track list. Video and audio payloads
     * are skipped, and the walk stops at the end of the tracks element, so this is a metadata-only
     * pass even on a multi-gigabyte file.
     */
    private fun readMetadata(source: SubtitleSource): Metadata {
        val reader = Reader(source)
        var timestampScale = DEFAULT_TIMESTAMP_SCALE
        var tracks: List<TrackInfo>? = null
        var segmentStart = -1L
        var segmentEnd = -1L
        while (tracks == null) {
            val element = try {
                readElement(reader, source.size)
            } catch (e: IOException) {
                break
            }
            if (element.size < 0) break
            when (element.id) {
                ID_SEGMENT -> {
                    segmentStart = element.contentStart
                    segmentEnd = element.contentStart + element.size
                    var scanning = true
                    while (scanning && reader.position < segmentEnd) {
                        val child = try {
                            readElement(reader, segmentEnd)
                        } catch (e: IOException) {
                            break
                        }
                        when (child.id) {
                            ID_INFO -> {
                                val infoEnd = child.contentStart + child.size
                                while (reader.position < infoEnd) {
                                    val field = try {
                                        readElement(reader, infoEnd)
                                    } catch (e: IOException) {
                                        break
                                    }
                                    if (field.id == ID_TIMESTAMP_SCALE) timestampScale = readUInt(reader, field)
                                    else reader.position = field.contentStart + field.size
                                }
                            }
                            ID_TRACKS -> {
                                tracks = readTracks(reader, child, segmentEnd)
                                scanning = false
                            }
                            else -> reader.position = child.contentStart + child.size
                        }
                    }
                }
                else -> reader.position = element.contentStart + element.size
            }
        }
        return Metadata(segmentStart.coerceAtLeast(0L), segmentEnd.coerceAtLeast(0L),
                timestampScale.coerceAtLeast(1L), tracks ?: emptyList())
    }

    private fun readTracks(reader: Reader, tracksElement: Element, limit: Long): List<TrackInfo> {
        val out = ArrayList<TrackInfo>(4)
        val end = (tracksElement.contentStart + tracksElement.size).coerceAtMost(limit)
        while (reader.position < end) {
            val entry = try {
                readElement(reader, end)
            } catch (e: IOException) {
                break
            }
            if (entry.id != ID_TRACK_ENTRY) {
                reader.position = entry.contentStart + entry.size
                continue
            }
            parseTrackEntry(reader, entry, end)?.let { out.add(it) }
        }
        return out
    }

    /** Parses a TrackEntry; returns it when it is a subtitle one, null otherwise. */
    private fun parseTrackEntry(reader: Reader, entry: Element, limit: Long): TrackInfo? {
        var number = -1L
        var type = -1L
        var codecId: String? = null
        var language: String? = null
        var name: String? = null
        var compression: Long? = null
        var compressionSettings: ByteArray? = null
        val end = (entry.contentStart + entry.size).coerceAtMost(limit)
        while (reader.position < end) {
            val element = try {
                readElement(reader, end)
            } catch (e: IOException) {
                break
            }
            when (element.id) {
                ID_TRACK_NUMBER -> number = readUInt(reader, element)
                ID_TRACK_TYPE -> type = readUInt(reader, element)
                ID_CODEC_ID -> codecId = readString(reader, element)
                ID_LANGUAGE -> language = readString(reader, element)
                ID_NAME -> name = readString(reader, element)
                ID_CONTENT_ENCODINGS -> {
                    val parsed = readContentEncodings(reader, element, end)
                    compression = parsed?.first
                    compressionSettings = parsed?.second
                }
                else -> reader.position = element.contentStart + element.size
            }
        }
        if (type != TRACK_TYPE_SUBTITLE) return null
        return TrackInfo(number, formatOf(codecId), language?.lowercase(), name, compression, compressionSettings)
    }

    /** Content compression of a track: `(algorithm, settings)` or null when there is none. */
    private fun readContentEncodings(reader: Reader, element: Element, limit: Long): Pair<Long?, ByteArray?>? {
        var algorithm: Long? = null
        var settings: ByteArray? = null
        val end = (element.contentStart + element.size).coerceAtMost(limit)
        while (reader.position < end) {
            val encoding = try {
                readElement(reader, end)
            } catch (e: IOException) {
                break
            }
            if (encoding.id != ID_CONTENT_ENCODING) {
                reader.position = encoding.contentStart + encoding.size
                continue
            }
            val encodingEnd = (encoding.contentStart + encoding.size).coerceAtMost(end)
            while (reader.position < encodingEnd) {
                val child = try {
                    readElement(reader, encodingEnd)
                } catch (e: IOException) {
                    break
                }
                if (child.id != ID_CONTENT_COMPRESSION) {
                    reader.position = child.contentStart + child.size
                    continue
                }
                val compressionEnd = (child.contentStart + child.size).coerceAtMost(encodingEnd)
                while (reader.position < compressionEnd) {
                    val field = try {
                        readElement(reader, compressionEnd)
                    } catch (e: IOException) {
                        break
                    }
                    when (field.id) {
                        ID_CONTENT_COMP_ALGO -> algorithm = readUInt(reader, field)
                        ID_CONTENT_COMP_SETTINGS -> settings = readPayload(reader, field)
                        else -> reader.position = field.contentStart + field.size
                    }
                }
            }
        }
        return if (algorithm == null) null else algorithm to settings
    }

    private fun formatOf(codecId: String?): EmbeddedFormat = when (codecId?.uppercase()) {
        "S_TEXT/UTF8", "S_TEXT/ASCII", "S_TEXT/USF" -> EmbeddedFormat.SRT
        "S_TEXT/WEBVTT", "D_WEBVTT/SUBTITLES", "D_WEBVTT/CAPTIONS" -> EmbeddedFormat.VTT
        "S_TEXT/ASS", "S_TEXT/SSA", "S_ASS", "S_SSA" -> EmbeddedFormat.ASS
        "S_VOBSUB", "S_DVBSUB", "S_HDMV/PGS", "S_HDMV/TEXTST" -> EmbeddedFormat.BITMAP
        else -> EmbeddedFormat.UNKNOWN
    }

    // ---- cue reading ----

    private class SubtitleEvent(val startMs: Long, val durationMs: Long?, val text: String)

    private class Cluster(val contentStart: Long, val end: Long) {
        var timestampMs = 0L
        var timestampRead = false
    }

    private class MatroskaCueLoader(
            private val source: SubtitleSource,
            private val reader: Reader,
            private val segmentStart: Long,
            private val segmentEnd: Long,
            private val timestampScale: Long,
            private val trackNumber: Long,
            private val compression: Long?,
            private val compressionSettings: ByteArray?,
            private val trackId: String
    ) : CueWindowLoader {

        private var cluster: Cluster? = null
        private var nextClusterPosition = segmentStart
        private var carry: SubtitleEvent? = null
        private var started = false
        private var atEndOfSegment = false
        private var lastFromMs = Long.MIN_VALUE
        private var lastUntilMs = Long.MIN_VALUE

        override fun loadWindow(fromMs: Long, untilMs: Long): List<SubtitleCue> {
            // The reader is sequential: it only has to be moved when the window that is asked for is
            // not the one that follows the events already read (a seek, forward or backward). While
            // looking for the right cluster only the cluster headers are read, their payloads are
            // skipped by size.
            if (shouldReposition(fromMs)) {
                reposition(fromMs)
                carry = null
                started = true
            }
            val cues = ArrayList<SubtitleCue>(32)
            var pending: SubtitleEvent? = null
            while (true) {
                val event = carry ?: nextEvent() ?: break
                carry = null
                if (event.startMs >= untilMs) {
                    carry = event
                    break
                }
                pending?.let { previous ->
                    cues.add(cueOf(previous, previous.durationMs?.let { previous.startMs + it } ?: event.startMs))
                }
                pending = if (event.startMs >= fromMs) event else null
            }
            pending?.let { previous ->
                val end = previous.durationMs?.let { previous.startMs + it }
                        ?: carry?.startMs
                        ?: minOf(previous.startMs + DEFAULT_CUE_DURATION_MS, previous.startMs + MAX_CUE_DURATION_MS)
                cues.add(cueOf(previous, end))
            }
            lastFromMs = fromMs
            lastUntilMs = untilMs
            return cues
        }

        /**
         * Whether the reader has to be moved: the next unread event (the one kept for closing the
         * end of the last cue) has to follow the requested window, otherwise the request comes from
         * a seek and the clusters have to be walked again from the right one. A window that simply
         * follows the previous one is read straight on.
         */
        private fun shouldReposition(fromMs: Long): Boolean {
            if (!started) return true
            val next = carry
            if (next == null) {
                // Nothing is buffered (the reader reached the end of the segment): only a request
                // that continues after the previous window can be served from here
                return fromMs < lastUntilMs - 2_000
            }
            return next.startMs < fromMs - 2_000 || next.startMs > fromMs + 60_000
        }

        private fun cueOf(event: SubtitleEvent, endMs: Long): SubtitleCue =
                SubtitleCue(event.startMs, maxOf(endMs, event.startMs + 1), SubtitleText.lines(event.text), trackId)

        /** Moves the reader to the last cluster starting at or before [timeMs]. */
        private fun reposition(timeMs: Long) {
            var position = segmentStart
            var lastCluster: Cluster? = null
            while (position < segmentEnd) {
                reader.position = position
                val element = try {
                    readElement(reader, segmentEnd)
                } catch (e: IOException) {
                    break
                }
                val end = (element.contentStart + element.size).coerceAtMost(segmentEnd)
                if (element.id == ID_CLUSTER) {
                    val candidate = Cluster(element.contentStart, end)
                    candidate.timestampMs = readClusterTimestamp(reader, candidate)
                    candidate.timestampRead = true
                    if (candidate.timestampMs > timeMs) {
                        // The wanted time is inside the previous cluster: use it so the cue that is
                        // already on screen is loaded too
                        val previous = lastCluster
                        if (previous != null) {
                            readClusterTimestamp(reader, previous)
                            cluster = previous
                            nextClusterPosition = previous.end
                        } else {
                            cluster = candidate
                            nextClusterPosition = candidate.end
                        }
                        atEndOfSegment = false
                        return
                    }
                    lastCluster = candidate
                }
                position = end
            }
            val last = lastCluster
            if (last != null) readClusterTimestamp(reader, last)
            cluster = last
            nextClusterPosition = last?.end ?: segmentEnd
            atEndOfSegment = false
        }

        /** Reads a cluster's Timestamp and leaves the reader right after it. */
        private fun readClusterTimestamp(reader: Reader, cluster: Cluster): Long {
            reader.position = cluster.contentStart
            while (reader.position < cluster.end) {
                val element = try {
                    readElement(reader, cluster.end)
                } catch (e: IOException) {
                    return 0L
                }
                if (element.id == ID_CLUSTER_TIMESTAMP) return readUInt(reader, element) * timestampScale / 1_000_000L
                reader.position = element.contentStart + element.size
            }
            return 0L
        }

        /** Next subtitle event of the wanted track, or null at the end of the segment. */
        private fun nextEvent(): SubtitleEvent? {
            while (!atEndOfSegment) {
                val current = cluster
                if (current == null) {
                    val next = openNextCluster() ?: run { atEndOfSegment = true; return null }
                    cluster = next
                    continue
                }
                if (reader.position >= current.end) {
                    cluster = null
                    nextClusterPosition = current.end
                    continue
                }
                val element = try {
                    readElement(reader, current.end)
                } catch (e: IOException) {
                    atEndOfSegment = true
                    return null
                }
                when (element.id) {
                    ID_CLUSTER_TIMESTAMP -> {
                        current.timestampMs = readUInt(reader, element) * timestampScale / 1_000_000L
                        current.timestampRead = true
                    }
                    ID_SIMPLE_BLOCK -> readBlockPayload(element, current, null)?.let { return it }
                    ID_BLOCK_GROUP -> readBlockGroup(element, current)?.let { return it }
                    else -> reader.position = element.contentStart + element.size
                }
            }
            return null
        }

        private fun openNextCluster(): Cluster? {
            var position = nextClusterPosition
            while (position < segmentEnd) {
                reader.position = position
                val element = try {
                    readElement(reader, segmentEnd)
                } catch (e: IOException) {
                    return null
                }
                val end = (element.contentStart + element.size).coerceAtMost(segmentEnd)
                if (element.id == ID_CLUSTER) {
                    val cluster = Cluster(element.contentStart, end)
                    cluster.timestampMs = readClusterTimestamp(reader, cluster)
                    cluster.timestampRead = true
                    nextClusterPosition = end
                    return cluster
                }
                position = end
            }
            return null
        }

        private fun readBlockGroup(element: Element, current: Cluster): SubtitleEvent? {
            val groupEnd = (element.contentStart + element.size).coerceAtMost(segmentEnd)
            var blockElement: Element? = null
            var durationTicks: Long? = null
            while (reader.position < groupEnd) {
                val child = try {
                    readElement(reader, groupEnd)
                } catch (e: IOException) {
                    return null
                }
                when (child.id) {
                    ID_BLOCK -> {
                        blockElement = child
                        reader.position = child.contentStart + child.size
                    }
                    ID_BLOCK_DURATION -> durationTicks = readUInt(reader, child)
                    else -> reader.position = child.contentStart + child.size
                }
            }
            val block = blockElement ?: return null
            reader.position = block.contentStart
            return readBlockPayload(block, current, durationTicks?.let { it * timestampScale / 1_000_000L })
        }

        /** Reads one (Simple)Block; returns its text when it belongs to the wanted track. */
        private fun readBlockPayload(element: Element, current: Cluster, durationMs: Long?): SubtitleEvent? {
            val payloadEnd = (element.contentStart + element.size).coerceAtMost(segmentEnd)
            val track = readVint(reader, false)
            val time = readInt16(reader)
            val flags = reader.readByte()
            if (flags < 0) return null
            if (track != trackNumber) {
                reader.position = payloadEnd
                return null
            }
            val lacing = (flags shr 1) and 0x03
            val frames = readFrames(payloadEnd, lacing)
            if (frames.isEmpty()) return null
            // Subtitles carry one frame; laced frames would be alternative texts of the same cue
            val text = SubtitleText.decode(frames[0])
            return SubtitleEvent(current.timestampMs + time, durationMs, text)
        }

        private fun readFrames(payloadEnd: Long, lacing: Int): List<ByteArray> {
            if (lacing == 0) {
                val size = (payloadEnd - reader.position).toInt()
                if (size <= 0) return emptyList()
                return listOf(decompress(reader.readBytes(size)))
            }
            val count = reader.readByte()
            if (count < 0) return emptyList()
            val frameCount = count + 1
            val sizes = IntArray(frameCount)
            when (lacing) {
                1 -> for (i in 0 until frameCount - 1) { // Xiph lacing
                    var size = 0
                    while (true) {
                        val b = reader.readByte()
                        if (b < 0) return emptyList()
                        size += b
                        if (b != 255) break
                    }
                    sizes[i] = size
                }
                2 -> { // fixed-size lacing
                    val remaining = (payloadEnd - reader.position).toInt()
                    val each = if (frameCount > 0) remaining / frameCount else 0
                    for (i in 0 until frameCount) sizes[i] = each
                }
                3 -> { // EBML lacing
                    var size = readVint(reader, false).toInt()
                    sizes[0] = size
                    for (i in 1 until frameCount - 1) {
                        size += readSignedVint(reader)
                        sizes[i] = size
                    }
                }
            }
            if (lacing != 2) sizes[frameCount - 1] = (payloadEnd - reader.position).toInt()
            val frames = ArrayList<ByteArray>(frameCount)
            for (size in sizes) {
                if (size <= 0) continue
                frames.add(decompress(reader.readBytes(size)))
            }
            return frames
        }

        private fun decompress(bytes: ByteArray): ByteArray {
            val settings = compressionSettings
            val data = if (compression == COMPRESSION_HEADER_STRIPPING && settings != null) settings + bytes else bytes
            if (compression != COMPRESSION_ZLIB) return data
            return try {
                val inflater = Inflater()
                inflater.setInput(data)
                val out = java.io.ByteArrayOutputStream(data.size * 2)
                val buffer = ByteArray(4096)
                while (!inflater.finished()) {
                    val read = inflater.inflate(buffer)
                    if (read == 0) {
                        if (inflater.needsInput() || inflater.needsDictionary()) break
                    } else out.write(buffer, 0, read)
                }
                inflater.end()
                out.toByteArray()
            } catch (e: Exception) {
                data
            }
        }

        override fun close() {
            source.close()
        }
    }

    // ---- EBML primitives ----

    /** Buffered sequential reader over a [SubtitleSource], with cheap seek/skip. */
    internal class Reader(private val source: SubtitleSource) {

        var position: Long = 0

        // Small on purpose: moving through a file skips most payloads, and each skip must not pull
        // a large buffer from a file the app does not need otherwise
        private val buffer = ByteArray(2 * 1024)
        private var bufferStart = 0L
        private var bufferLength = 0

        private fun fill(): Boolean {
            if (position >= source.size) return false
            val read = source.read(position, buffer, 0, buffer.size)
            if (read <= 0) return false
            bufferStart = position
            bufferLength = read
            return true
        }

        fun readByte(): Int {
            if (bufferLength <= 0 || position < bufferStart || position >= bufferStart + bufferLength) {
                if (!fill()) return -1
            }
            val value = buffer[(position - bufferStart).toInt()].toInt() and 0xFF
            position++
            return value
        }

        fun readBytes(count: Int): ByteArray {
            val out = ByteArray(count)
            var copied = 0
            while (copied < count) {
                if (bufferLength <= 0 || position < bufferStart || position >= bufferStart + bufferLength) {
                    if (!fill()) break
                }
                val offset = (position - bufferStart).toInt()
                val available = minOf(bufferLength - offset, count - copied)
                System.arraycopy(buffer, offset, out, copied, available)
                copied += available
                position += available
            }
            return if (copied == count) out else out.copyOf(copied)
        }
    }

    private class Element(val id: Long, val size: Long, val contentStart: Long)

    /** Reads an element header; [limit] bounds the size of unknown-size elements. */
    private fun readElement(reader: Reader, limit: Long): Element {
        val id = readVint(reader, true)
        val size = readVint(reader, false)
        val contentStart = reader.position
        val resolved = if (size == UNKNOWN_SIZE) (limit - contentStart).coerceAtLeast(0L) else size
        return Element(id, resolved, contentStart)
    }

    /**
     * Reads an EBML variable-size integer. With [keepMarker] the leading length bit is kept, which
     * is how element ids are compared; otherwise it is removed to get the value. A value made only
     * of one bits marks an unknown size, reported as [UNKNOWN_SIZE].
     */
    private fun readVint(reader: Reader, keepMarker: Boolean): Long {
        val first = reader.readByte()
        if (first < 0) throw EOFException("unexpected end of file")
        var length = 1
        var mask = 0x80
        while (mask > 0 && (first and mask) == 0) {
            mask = mask shr 1
            length++
        }
        if (mask == 0) throw IOException("invalid EBML variable-size integer")
        val value0 = first and (mask - 1)
        var value = if (keepMarker) first.toLong() else value0.toLong()
        var allOnes = !keepMarker && value0 == (mask - 1)
        for (i in 1 until length) {
            val b = reader.readByte()
            if (b < 0) throw EOFException("unexpected end of file")
            value = (value shl 8) or b.toLong()
            if (b != 0xFF) allOnes = false
        }
        return if (allOnes) UNKNOWN_SIZE else value
    }

    /** Signed EBML integer used by EBML lacing. */
    private fun readSignedVint(reader: Reader): Int {
        val first = reader.readByte()
        if (first < 0) throw EOFException("unexpected end of file")
        var length = 1
        var mask = 0x80
        while (mask > 0 && (first and mask) == 0) {
            mask = mask shr 1
            length++
        }
        val value0 = first and (mask - 1)
        val bim = mask shr 1
        val negative = (value0 and bim) != 0
        var value = if (negative) value0 - bim else value0
        for (i in 1 until length) {
            val b = reader.readByte()
            if (b < 0) throw EOFException("unexpected end of file")
            value = (value shl 8) or b
        }
        return value
    }

    /** Unsigned payload of [element] (a few bytes), used for numbers and strings. */
    private fun readPayload(reader: Reader, element: Element): ByteArray {
        val size = element.size.coerceAtMost(1L shl 20).toInt()
        return reader.readBytes(size)
    }

    private fun readUInt(reader: Reader, element: Element): Long {
        var value = 0L
        for (byte in readPayload(reader, element)) value = (value shl 8) or (byte.toLong() and 0xFF)
        return value
    }

    private fun readString(reader: Reader, element: Element): String =
            String(readPayload(reader, element), Charsets.UTF_8).trim('\u0000', ' ')

    private fun readInt16(reader: Reader): Int {
        val high = reader.readByte()
        val low = reader.readByte()
        if (high < 0 || low < 0) throw EOFException("unexpected end of file")
        return ((high shl 8) or low).toShort().toInt()
    }
}
