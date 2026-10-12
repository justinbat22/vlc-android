/*
 * **************************************************************************
 *  EmbeddedSubtitles.kt
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

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.nio.channels.FileChannel

/**
 * Text subtitle tracks *embedded* in the media container (as opposed to side files), drawn by the
 * same overlay as external .srt files.
 *
 * libvlc only selects embedded tracks for its own renderer and has no API giving their text back,
 * so the container is read directly. Only formats VLC renders as plain text and whose layout the
 * overlay can therefore own are supported: SubRip (`S_TEXT/UTF8`) and WebVTT in Matroska, 3GPP
 * timed text (`tx3g`) and WebVTT in MP4. Styled (ASS/SSA) and bitmap (DVB/VobSub/PGS) tracks are
 * left to VLC, which keeps their authored coordinates.
 */

/** Random-access byte source, so the container readers are testable without Android. */
interface SubtitleSource : Closeable {
    val size: Long

    /** Reads up to [length] bytes at [position]; returns the number of bytes read (-1 at EOF). */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

/** [SubtitleSource] over an in-memory array (unit tests). */
class ByteArraySubtitleSource(private val bytes: ByteArray) : SubtitleSource {
    override val size: Long get() = bytes.size.toLong()

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position < 0 || position >= bytes.size) return -1
        val count = minOf(length.toLong(), bytes.size - position).toInt()
        System.arraycopy(bytes, position.toInt(), buffer, offset, count)
        return count
    }

    override fun close() {}
}

/**
 * [SubtitleSource] over a local file or a content URI: the readers need random access (Matroska
 * skips over clusters, MP4 reads samples by offset) and the file is never loaded in memory.
 */
class FileSubtitleSource(context: Context, uri: Uri) : SubtitleSource {

    private val stream: ParcelFileDescriptor.AutoCloseInputStream? = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
    } catch (e: Exception) {
        Log.w("SubtitleOverlay", "cannot open $uri for embedded subtitles", e)
        null
    }
    private val channel: FileChannel? = stream?.channel
    private val lock = Any()

    override val size: Long
        get() = try {
            channel?.size() ?: 0L
        } catch (e: IOException) {
            0L
        }

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        val ch = channel ?: return -1
        return synchronized(lock) {
            try {
                val javaBuffer = java.nio.ByteBuffer.wrap(buffer, offset, length)
                ch.position(position)
                ch.read(javaBuffer)
            } catch (e: IOException) {
                Log.w("SubtitleOverlay", "embedded subtitle read failed", e)
                -1
            }
        }
    }

    override fun close() {
        try {
            stream?.close()
        } catch (ignored: IOException) {
        }
    }
}

/** Format of an embedded (or side) subtitle track, from the overlay's point of view. */
enum class EmbeddedFormat(val text: Boolean) {
    /** SubRip / Matroska `S_TEXT/UTF8` and 3GPP timed text (`tx3g`). */
    SRT(true),

    /** WebVTT (`S_TEXT/WEBVTT`, `wvtt`). */
    VTT(true),

    /** SubStation Alpha (`S_TEXT/ASS`, `S_TEXT/SSA`): VLC keeps the authored layout. */
    ASS(false),

    /** Bitmap subtitles (DVB, VobSub, PGS...): only VLC can render them. */
    BITMAP(false),

    /** Anything the overlay does not know. */
    UNKNOWN(false)
}

/** A subtitle track found in the container. */
data class EmbeddedTrack(
        /** Ordinal among the container's subtitle tracks, following the order VLC lists them in. */
        val index: Int,
        /** Container-internal identifier (Matroska track number, MP4 track id), for extraction. */
        val id: Long,
        val format: EmbeddedFormat,
        /** Lower-case language tag of the track (`eng`, `en`, ...), when the container has one. */
        val language: String?,
        /** Track title, when the container has one. */
        val title: String?
)

/** Container families the embedded readers understand. */
enum class EmbeddedContainer { MATROSKA, MP4 }

/** Reads a window of cues around the playback position; implemented by the container readers. */
interface CueWindowLoader : Closeable {
    /**
     * Cues starting between [fromMs] and [untilMs] (exclusive), sorted, with their end time
     * resolved (the reader looks one cue further ahead so the last cue of a window is closed).
     * [fromMs] may jump in either direction: the loader repositions its reader as needed.
     */
    fun loadWindow(fromMs: Long, untilMs: Long): List<SubtitleCue>
}

/**
 * [SubtitleCueProvider] for a text subtitle track embedded in the media container.
 *
 * A movie's cues span the whole file, so the track is never read at once: a background loader
 * keeps a window of cues around the playback position and follows seeks, while [cueAt] only reads
 * what is already in memory (it is called from the main thread on every player event).
 */
class EmbeddedSubtitleProvider(
        private val source: SubtitleSource,
        /** Ordinal of the track among the container's subtitle tracks. */
        private val trackIndex: Int,
        /** Language libvlc reports for the selected track, used to double-check [trackIndex]. */
        private val expectedLanguage: String?,
        private val trackId: String
) : SubtitleCueProvider {

    companion object {
        private const val TAG = "SubtitleOverlay"

        /** Cues kept before the playhead (a cue already on screen when the track is opened). */
        private const val WINDOW_BEHIND_MS = 30_000L

        /** How far ahead of the playhead cues are buffered. */
        private const val WINDOW_AHEAD_MS = 180_000L

        /** The window is extended once the playhead comes this close to its end. */
        private const val WINDOW_RELOAD_AHEAD_MS = 60_000L

        private const val IDLE_DELAY_MS = 500L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private var loader: CueWindowLoader? = null

    @Volatile private var loaded = false
    @Volatile private var index = CueIndex()
    @Volatile private var windowFromMs = Long.MAX_VALUE
    @Volatile private var windowUntilMs = Long.MIN_VALUE
    @Volatile private var playheadMs = 0L

    override val isLoaded: Boolean get() = loaded

    /**
     * Format of the track the last [load] read, null while nothing was read. It is reported even
     * when the format is not a text one: a styled (ASS/SSA) or bitmap track stays on VLC's renderer,
     * and the caller has to know that to keep the subtitle position away from it.
     */
    @Volatile
    var loadedFormat: EmbeddedFormat? = null
        private set

    override suspend fun load(): Int = withContext(Dispatchers.IO) {
        val container = EmbeddedSubtitles.containerOf(source) ?: return@withContext -1
        val tracks = try {
            when (container) {
                EmbeddedContainer.MATROSKA -> MatroskaSubtitle.tracks(source)
                EmbeddedContainer.MP4 -> Mp4Subtitle.tracks(source)
            }
        } catch (e: Exception) {
            Log.w(TAG, "embedded subtitle tracks could not be read", e)
            return@withContext -1
        }
        val track = EmbeddedSubtitles.matchTrack(tracks, trackIndex, expectedLanguage)
                ?: return@withContext -1
        loadedFormat = track.format
        if (!track.format.text) return@withContext -1
        val newLoader = try {
            when (container) {
                EmbeddedContainer.MATROSKA -> MatroskaSubtitle.loader(source, track, trackId)
                EmbeddedContainer.MP4 -> Mp4Subtitle.loader(source, track, trackId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "embedded subtitle reader failed", e)
            null
        } ?: return@withContext -1
        loader = newLoader
        loaded = true
        startWorker(newLoader)
        1
    }

    override fun cueAt(timeMs: Long): SubtitleCue? {
        playheadMs = timeMs
        val currentIndex = index
        if (currentIndex.isEmpty) return null
        // A window loaded for another position must not keep drawing text after a seek
        if (timeMs < windowFromMs || timeMs >= windowUntilMs) return null
        return currentIndex.cueAt(timeMs)
    }

    override fun release() {
        loaded = false
        worker?.cancel()
        worker = null
        loader?.close()
        loader = null
        index = CueIndex()
        windowFromMs = Long.MAX_VALUE
        windowUntilMs = Long.MIN_VALUE
        scope.cancel()
        source.close()
    }

    /**
     * Keeps a window of cues around the playhead, following the playback (and its seeks).
     *
     * The window loads run on this job's dispatcher and are *blocking* reads (a [CueWindowLoader]
     * walks the container), so coroutine cancellation does not interrupt them: it only marks the
     * job cancelled and the next suspension point (the [delay] below) throws
     * [JobCancellationException]. The worker must then LEAVE the loop immediately: catching that
     * exception here and retrying would keep a cancelled provider reading behind a released
     * loader (its file descriptor is already closed), spin forever on the swallowing retry loop
     * and starve the replacement track's own window worker (every run loops silently and keeps the
     * IO dispatcher busy, breaking the next track picked in the menu).
     */
    private fun startWorker(newLoader: CueWindowLoader) {
        worker = scope.launch {
            var loadedFrom = Long.MIN_VALUE
            var loadedUntil = Long.MIN_VALUE
            var cues = emptyList<SubtitleCue>()
            while (isActive) {
                val position = playheadMs
                val needsWindow = loadedFrom == Long.MIN_VALUE || position < loadedFrom || position >= loadedUntil
                val needsExtension = !needsWindow && loadedUntil < position + WINDOW_AHEAD_MS - WINDOW_RELOAD_AHEAD_MS
                try {
                    if (needsWindow) {
                        loadedFrom = position - WINDOW_BEHIND_MS
                        loadedUntil = position + WINDOW_AHEAD_MS
                        cues = newLoader.loadWindow(loadedFrom, loadedUntil)
                    } else if (needsExtension) {
                        val nextFrom = loadedUntil
                        loadedUntil = position + WINDOW_AHEAD_MS
                        cues = cues + newLoader.loadWindow(nextFrom, loadedUntil)
                    } else {
                        delay(IDLE_DELAY_MS)
                        continue
                    }
                    windowFromMs = loadedFrom
                    windowUntilMs = loadedUntil
                    index = CueIndex(cues)
                    // Let a pending seek take over before loading more
                    delay(10)
                } catch (e: CancellationException) {
                    Log.i(TAG, "embedded subtitle window worker cancelled")
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "embedded subtitle window failed", e)
                    delay(IDLE_DELAY_MS)
                }
            }
        }
    }
}

/** Entry points of the embedded subtitle readers. */
object EmbeddedSubtitles {

    private const val TAG = "SubtitleOverlay"

    /** Detects the container family from the first bytes of [source]. */
    fun containerOf(source: SubtitleSource): EmbeddedContainer? {
        val header = ByteArray(16)
        val count = source.read(0, header, 0, header.size)
        if (count < 8) return null
        // EBML magic (\x1A\x45\xDF\xA3): Matroska (.mkv/.mka/.mks) and WebM
        if (header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() &&
                header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte()) return EmbeddedContainer.MATROSKA
        // ISO base media file format: the box type sits at offset 4 of the first box
        val type = String(header, 4, 4, Charsets.ISO_8859_1)
        return if (type == "ftyp" || type == "moov" || type == "mdat" || type == "free" || type == "wide")
            EmbeddedContainer.MP4 else null
    }

    /**
     * Format of the subtitle track [index] of the media at [uri] ([index] being the ordinal libvlc
     * lists its subtitle tracks in), or null when the container can't be read or has no such track.
     *
     * Used to know what a track the overlay does *not* draw really is: a styled (ASS/SSA) or bitmap
     * track keeps its own coordinates and must not be moved by the subtitle position setting.
     */
    suspend fun formatOf(context: Context, uri: Uri, index: Int, language: String?): EmbeddedFormat? =
            withContext(Dispatchers.IO) {
                val source = FileSubtitleSource(context, uri)
                try {
                    val container = containerOf(source) ?: return@withContext null
                    val tracks = when (container) {
                        EmbeddedContainer.MATROSKA -> MatroskaSubtitle.tracks(source)
                        EmbeddedContainer.MP4 -> Mp4Subtitle.tracks(source)
                    }
                    matchTrack(tracks, index, language)?.format
                } catch (e: Exception) {
                    Log.w(TAG, "embedded subtitle tracks could not be read", e)
                    null
                } finally {
                    source.close()
                }
            }

    /**
     * Entries of libvlc's subtitle menu that are not tracks of the container: "-1" is libvlc's
     * "Disable" entry, "-2" the player's "nothing selected" sentinel and [SubtitleOverlayRuntime.TRACK_ID]
     * the overlay's own entry for an external file.
     */
    private val NOT_CONTAINER_TRACK_IDS = setOf("-1", "-2", SubtitleOverlayRuntime.TRACK_ID)

    /**
     * Ordinal, among the subtitle tracks *of the container*, of the track [trackId], given the ids
     * libvlc lists in its subtitle menu ([listedIds], in menu order); -1 when [trackId] is not a
     * container track of that list.
     *
     * The menu is not the container's track list: VLC 3 puts a "Disable" entry (id "-1") in front
     * of the tracks (VLC 4 does not). Using the position in the menu shifted every ordinal by one
     * on VLC 3: the other tracks were only rescued by the language check of [matchTrack], while the
     * LAST track of the container landed one past the end of its track list, was never found and
     * stayed on VLC's own renderer (scaling and moving with the video).
     */
    fun containerOrdinalOf(listedIds: List<String>, trackId: String): Int {
        if (trackId in NOT_CONTAINER_TRACK_IDS) return -1
        var ordinal = 0
        for (id in listedIds) {
            if (id in NOT_CONTAINER_TRACK_IDS) continue
            if (id == trackId) return ordinal
            ordinal++
        }
        return -1
    }

    /**
     * The track to render, or null when the container has no such subtitle track: [index] is the
     * ordinal libvlc assigned to the selected track, [language] what libvlc reports for it.
     * The language is a safeguard against a different track order: a track carrying another
     * language is rejected, and so is the ordinal when no track carries the wanted one. A track
     * with no language tag at all is kept (there is nothing to contradict the ordinal).
     */
    fun matchTrack(tracks: List<EmbeddedTrack>, index: Int, language: String?): EmbeddedTrack? {
        val track = tracks.getOrNull(index) ?: return null
        val wanted = IsoLanguages.canonical(language) ?: return track
        val candidate = IsoLanguages.canonical(track.language)
        if (candidate == wanted) return track
        // The ordinal is kept when the track carries no language at all (nothing to contradict it),
        // which is the common case for containers that only tag some of their tracks
        return tracks.firstOrNull { IsoLanguages.canonical(it.language) == wanted }
                ?: track.takeIf { candidate == null }
    }
}

/**
 * Tiny ISO 639 helper: containers tag tracks with two- or three-letter codes, libvlc reports
 * whatever the container gave it and the app's preferred-language setting uses two-letter codes.
 * Only the common languages need to be known; anything else matches by its own spelling.
 */
object IsoLanguages {

    /** canonical two-letter code -> accepted spellings (three-letter codes and names). */
    private val LANGUAGES = mapOf(
            "en" to listOf("eng", "english"),
            "fr" to listOf("fre", "fra", "french"),
            "de" to listOf("ger", "deu", "german"),
            "es" to listOf("spa", "spanish"),
            "it" to listOf("ita", "italian"),
            "pt" to listOf("por", "portuguese"),
            "nl" to listOf("dut", "nld", "dutch"),
            "ru" to listOf("rus", "russian"),
            "uk" to listOf("ukr", "ukrainian"),
            "pl" to listOf("pol", "polish"),
            "cs" to listOf("cze", "ces", "czech"),
            "sk" to listOf("slo", "slk", "slovak"),
            "hu" to listOf("hun", "hungarian"),
            "ro" to listOf("rum", "ron", "romanian"),
            "bg" to listOf("bul", "bulgarian"),
            "el" to listOf("gre", "ell", "greek"),
            "tr" to listOf("tur", "turkish"),
            "ar" to listOf("ara", "arabic"),
            "he" to listOf("heb", "hebrew"),
            "fa" to listOf("per", "fas", "persian"),
            "hi" to listOf("hin", "hindi"),
            "bn" to listOf("ben", "bengali"),
            "ta" to listOf("tam", "tamil"),
            "te" to listOf("tel", "telugu"),
            "th" to listOf("tha", "thai"),
            "vi" to listOf("vie", "vietnamese"),
            "id" to listOf("ind", "indonesian"),
            "ms" to listOf("may", "msa", "malay"),
            "ja" to listOf("jpn", "japanese"),
            "ko" to listOf("kor", "korean"),
            "zh" to listOf("chi", "zho", "chinese", "mandarin", "cantonese"),
            "sv" to listOf("swe", "swedish"),
            "no" to listOf("nor", "norwegian"),
            "da" to listOf("dan", "danish"),
            "fi" to listOf("fin", "finnish"),
            "is" to listOf("ice", "isl", "icelandic"),
            "hr" to listOf("hrv", "croatian"),
            "sr" to listOf("srp", "serbian"),
            "sl" to listOf("slv", "slovenian"),
            "lt" to listOf("lit", "lithuanian"),
            "lv" to listOf("lav", "latvian"),
            "et" to listOf("est", "estonian"),
            "ca" to listOf("cat", "catalan")
    )

    /** Canonical two-letter code of [value] (an ISO code, a three-letter code or a language name). */
    fun canonical(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val raw = value.trim().lowercase().substringBefore('-')
        if (raw.isEmpty() || raw == "und" || raw == "unknown") return null
        if (raw.length == 2) return raw
        LANGUAGES.forEach { (code, spellings) -> if (spellings.contains(raw)) return code }
        return raw
    }
}
