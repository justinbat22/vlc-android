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

    override val coroutineContext = Dispatchers.Main.immediate + SupervisorJob()

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
        val trackId = "$EXTERNAL_CUE_TRACK_PREFIX${uri}"
        val p = SrtSubtitleProvider(uri, trackId, owner, charset)
        provider = p
        loadJob = launch(Dispatchers.IO) {
            val count = try {
                p.load()
            } catch (e: Exception) {
                Log.e(TAG, "external load failed", e)
                -1
            }
            withContext(Dispatchers.Main) {
                if (provider !== p) return@withContext // superseded by a newer load
                if (count > 0) {
                    enabled = true
                    resync()
                } else {
                    overlayView.setCue(null)
                    enabled = false
                }
                onResult?.invoke(count > 0)
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
