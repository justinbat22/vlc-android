/*
 * **************************************************************************
 *  SubtitleOverlayController.kt
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
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.MediaPlayer
import java.nio.charset.Charset

private const val TAG = "SubtitleOverlay"

/**
 * Read-only view of the playback state the overlay needs (times and subtitle
 * delay). The delegate implements it against the real PlaybackService, keeping
 * the timing logic here testable and free of VLC app imports.
 */
interface PlaybackTimeSource {
    /** Current playback position in MILLISECONDS. */
    fun currentTime(): Long

    /** Current subtitle delay in MICROSECONDS (positive = subs shown later). */
    fun subtitleDelayUs(): Long
}

/**
 * Owns the lifecycle of the screen-anchored subtitle overlay while the player
 * is open: cue activation, delay handling, seek/pause reaction and source
 * replacement. Duplicate-free by construction: the overlay only consumes
 * external .srt files that are never passed to VLC as a native slave.
 *
 * Design notes
 * ------------
 * TIMING: the displayed cue is always computed from the position reported by
 * the libvlc engine through MediaPlayer.Event.TimeChanged — the same event the
 * app's seek bar and progress readouts use. No separate ticking clock is
 * created; between TimeChanged events the cue stays on screen, matching the
 * native subtitle behaviour. A cue is computed fresh after a seek/pause/play
 * event, so a stale text is never left on screen.
 *
 * DELAY: the project's existing subtitle-delay semantics (PlaybackService /
 * VideoDelayDelegate) expose the delay in MICROSECONDS of media timeline, the
 * same unit libvlc uses for audio/subtitle delays (positive = subtitles shown
 * later). The cue lookup time is `currentTime - delay`, keeping the overlay in
 * sync with the delay the user has set for native subtitles as well.
 */
class SubtitleOverlayController(
        private val timeSource: PlaybackTimeSource,
        private val owner: Context,
        private val overlayView: SubtitleOverlayView
) : CoroutineScope {

    /**
     * Catches anything a parser or window loader throws outside its own try/catch: a subtitle
     * problem is a rendering problem and must never be an application crash (see the regex
     * engine incompatibility that used to kill the process on the first embedded track).
     */
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "subtitle overlay background failure contained", throwable)
    }

    override val coroutineContext = Dispatchers.Main.immediate + SupervisorJob() + exceptionHandler

    companion object {
        /** Preference key for the overlay enable state. */
        const val PREF_OVERLAY_ENABLED = "subtitle_overlay_enabled"
    }

    // ---- current source ----
    private var provider: SubtitleCueProvider? = null
    private var loadJob: Job? = null
    private var lastCue: SubtitleCue? = null

    /** True while a source is loaded. */
    var enabled: Boolean = false
        private set

    fun start() {
        overlayView.refreshAppearance()
        overlayView.positionPercent = SubtitleOverlayPrefs.position(owner)
    }

    /**
     * Loads an external .srt and shows it in sync with the current playback.
     * The file is NOT passed to VLC, so the native renderer shows nothing for it.
     *
     * @param onResult called on the main thread with `true` once the file is displayed, `false`
     *   when it could not be read (or nothing usable was parsed). A load superseded by a newer one
     *   reports nothing.
     */
    fun loadExternal(uri: Uri, charset: Charset? = null, onResult: ((Boolean) -> Unit)? = null) {
        loadJob?.cancel()
        provider?.release()
        SubtitleOverlayRuntime.clear()
        val trackId = "$EXTERNAL_CUE_TRACK_PREFIX${uri}"
        val p = SrtSubtitleProvider(uri, trackId, owner, charset)
        provider = p
        loadJob = launch(Dispatchers.IO) {
            val count = try {
                p.load()
            } catch (e: CancellationException) {
                throw e // superseded load cleanly cancelled, not a read failure
            } catch (e: Exception) {
                Log.e(TAG, "external load failed", e)
                -1
            }
            withContext(Dispatchers.Main) {
                if (provider !== p) return@withContext // superseded by a newer load
                if (count > 0) {
                    enabled = true
                    // Published so the subtitle menu shows (and can pick) what the overlay draws
                    SubtitleOverlayRuntime.setExternalSubtitle(SubtitleOverlayPrefs.label(uri.toString()))
                    resync()
                } else {
                    overlayView.setCue(null)
                    enabled = false
                }
                onResult?.invoke(count > 0)
            }
        }
    }

    /**
     * Loads the text subtitle track embedded in the played media ([uri]) and shows it in sync with
     * the playback. libvlc keeps the track selected while the overlay draws its cues, so the caller
     * turns the native rendering off once this one succeeded ([onResult] reports the format).
     *
     * @param trackIndex ordinal of the track among the container's subtitle tracks, in the order
     *   libvlc lists them in.
     * @param nativeTrackId id libvlc uses for that track, reported to the subtitle menu as the
     *   selected entry.
     * @param onResult called on the main thread with the format of the track that was read: its cues
     *   are displayed when the format is a text one, and null when the container could not be read
     *   at all. A load superseded by a newer one reports nothing.
     */
    fun loadEmbedded(
            uri: Uri,
            trackIndex: Int,
            language: String?,
            nativeTrackId: String,
            onResult: ((EmbeddedFormat?) -> Unit)? = null
    ) {
        loadJob?.cancel()
        provider?.release()
        SubtitleOverlayRuntime.clear()
        val p = EmbeddedSubtitleProvider(
                FileSubtitleSource(owner, uri), trackIndex, language, "$EMBEDDED_CUE_TRACK_PREFIX$nativeTrackId")
        provider = p
        loadJob = launch(Dispatchers.IO) {
            val count = try {
                p.load()
            } catch (e: CancellationException) {
                throw e // superseded load cleanly cancelled, not a read failure
            } catch (e: Exception) {
                Log.e(TAG, "embedded load failed", e)
                -1
            }
            val format = p.loadedFormat
            withContext(Dispatchers.Main) {
                if (provider !== p) return@withContext // superseded by a newer load
                if (count > 0) {
                    enabled = true
                    SubtitleOverlayRuntime.setEmbeddedSubtitle(nativeTrackId)
                    resync()
                } else {
                    overlayView.setCue(null)
                    enabled = false
                }
                onResult?.invoke(format)
            }
        }
    }

    /** Clears any loaded source, releases the provider and resigns the overlay. */
    fun unload() {
        loadJob?.cancel()
        loadJob = null
        provider?.release()
        provider = null
        lastCue = null
        overlayView.setCue(null)
        SubtitleOverlayRuntime.clear()
        enabled = false
    }

    fun release() {
        unload()
        coroutineContext.cancel()
    }

    /** Recompute the displayed cue from the playback clock (main thread). */
    fun resync() {
        val p = provider ?: return
        if (!enabled || !p.isLoaded) return
        val delay = timeSource.subtitleDelayUs() // microseconds, matches the native subtitle delay
        val cue = p.cueAt(timeSource.currentTime() - delay / 1000L)
        if (cue !== lastCue) {
            lastCue = cue
            overlayView.setCue(cue)
        }
    }

    private fun onTimeChanged(timeMs: Long) {
        if (!enabled) return
        val delay = try { timeSource.subtitleDelayUs() } catch (ignored: Exception) { 0L }
        val cue = provider?.cueAt(timeMs - delay / 1000L)
        if (cue !== lastCue) {
            lastCue = cue
            overlayView.setCue(cue)
        }
    }

    /** Player event feed from [VideoPlayerActivity.onMediaPlayerEvent]. */
    fun onMediaPlayerEvent(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.TimeChanged -> onTimeChanged(event.timeChanged)
            MediaPlayer.Event.Playing, MediaPlayer.Event.Paused,
            MediaPlayer.Event.SeekableChanged, MediaPlayer.Event.PositionChanged -> {
                // Recompute immediately so a stale cue is not left after a seek/pause.
                resync()
            }
            MediaPlayer.Event.EndReached, MediaPlayer.Event.Stopped, MediaPlayer.Event.EncounteredError -> {
                lastCue = null
                overlayView.setCue(null)
            }
            else -> {}
        }
    }

    /** Hook for the player to call on media change: discard stale cues. */
    fun onMediaChanged() {
        unload()
    }
}
