/*
 * **************************************************************************
 *  SubtitleOverlayDelegate.kt
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

package org.videolan.vlc.gui.video

import android.content.SharedPreferences
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.resources.VLCInstance
import org.videolan.tools.Settings
import org.videolan.tools.SubtitlePosition
import org.videolan.tools.runIO
import org.videolan.vlc.PlaybackService
import org.videolan.vlc.R
import org.videolan.vlc.getAllTracks
import org.videolan.vlc.gui.dialogs.VideoTracksDialog
import org.videolan.vlc.isVLC4
import org.videolan.vlc.repository.SlaveRepository
import org.videolan.vlc.setSpuTrack
import org.videolan.vlc.subtitleoverlay.EmbeddedFormat
import org.videolan.vlc.subtitleoverlay.EmbeddedSubtitles
import org.videolan.vlc.subtitleoverlay.PlaybackTimeSource
import org.videolan.vlc.subtitleoverlay.SubtitleOverlayController
import org.videolan.vlc.subtitleoverlay.SubtitleOverlayPrefs
import org.videolan.vlc.subtitleoverlay.SubtitleOverlayRuntime
import org.videolan.vlc.subtitleoverlay.SubtitleOverlayView
import java.io.File

/**
 * Binds the screen-anchored subtitle overlay to [VideoPlayerActivity] without touching any of the
 * player's behaviour when the overlay is off.
 *
 * Responsibilities:
 *  - create the [SubtitleOverlayView] inside player_root, ABOVE the video surface and BELOW the
 *    player controls (it is added right after the video layout so the later-inflated UI container
 *    z-orders above);
 *  - own the [SubtitleOverlayController] for the lifetime of the player session and forward the
 *    libvlc player events the activity already receives to it;
 *  - take the text subtitles of the played media over from VLC while the overlay is enabled: the
 *    external SubRip file that was picked, downloaded or saved for the media, and the SubRip/WebVTT
 *    tracks embedded in the container. An external file is never attached to the media as a native
 *    slave (see [SubtitleOverlayPrefs.handles] and [org.videolan.vlc.media.PlayerController.setSlaves])
 *    and an embedded track has its native rendering turned off right after the container was read,
 *    so the same text is never drawn twice (see [SubtitleOverlayRuntime]);
 *  - keep VLC's own subtitle *position* away from the formats that carry their own coordinates:
 *    styled ASS/SSA and bitmap subtitles are never moved by the app-wide position setting
 *    ([SubtitlePosition]), text subtitles are;
 *  - apply a settings change (enable, position) to the playback that is running instead of waiting
 *    for the player to be opened again.
 *
 * Every subtitle format the overlay cannot draw keeps going through VLC even when it is on.
 */
class SubtitleOverlayDelegate(private val activity: VideoPlayerActivity) :
        SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        /** Priority a subtitle picked by the user is saved with (VLC "user" priority). */
        private const val SUBTITLE_SLAVE_PRIORITY = 2

        /** SPU track id libvlc uses for "no subtitle". */
        private const val NO_NATIVE_SUBTITLE = "-1"

        /** Subtitle track id meaning "nothing selected/restored" (see VideoPlayerActivity). */
        private const val NO_TRACK = "-2"

        /**
         * How long implicit subtitle decisions are suppressed after an explicit track pick (see
         * [onNativeSubtitleSelected]). Covers the ESAdded(Text) restore the activity fires right
         * behind the pick plus the first Playing/Vout auto-adoptions; the deferred restore of the
         * (now updated) saved track applies the pick again afterwards.
         */
        private const val EXPLICIT_PICK_HOLD_MS = 4_000L
    }

    private val settings = Settings.getInstance(activity)

    private var rootContainer: ViewGroup? = null
    private var overlayView: SubtitleOverlayView? = null
    private var controller: SubtitleOverlayController? = null

    /** Subtitle the user just picked, and the media it belongs to. */
    private var requestedSubtitle: Uri? = null
    private var requestedForMedia: String? = null

    /** External subtitle currently drawn by the overlay. */
    private var activeExternalSubtitle: Uri? = null

    /** Id (as libvlc reports it) of the embedded track currently drawn by the overlay. */
    private var activeEmbeddedTrackId: String? = null

    private var restoreJob: Job? = null
    private var resolveJob: Job? = null

    /** Last track auto-adoption was attempted for (nil media guard, see [autoAdoptNativeSubtitle]). */
    private var lastAutoAdoptAttempted: String? = null

    /**
     * Uptime-ms deadline until which implicit subtitle decisions (the saved-track restore, the
     * preferred-language reselection and the Playing/Vout auto-adoptions) must not override an
     * explicit pick of the user. Selecting another track from the menu fires the ESAdded(Text)
     * restore path right behind it (the activity replays the *saved* meta, which still holds the
     * previous track), and it used to cancel the in-flight container read of the freshly picked
     * track — the pick then fell back to VLC's own renderer and scaled with the video. The
     * explicit pick persists its own track id (rememberSubtitleTrack), so the restore that runs
     * after the grace period converges on it anyway.
     */
    private var explicitPickHoldUntilMs: Long = 0L

    /** Whether implicit subtitle decisions are currently suppressed (see [explicitPickHoldUntilMs]). */
    private fun explicitPickOnHold(): Boolean {
        if (explicitPickHoldUntilMs == 0L) return false
        if (SystemClock.elapsedRealtime() >= explicitPickHoldUntilMs) {
            explicitPickHoldUntilMs = 0L
            return false
        }
        return true
    }

    /** Native subtitle track that was displayed before the overlay took the rendering over. */
    private var nativeSubtitleBeforeOverlay: String? = null

    val isEnabled: Boolean
        get() = SubtitleOverlayPrefs.isEnabled(activity)

    /**
     * True while the overlay owns the subtitle rendering of the current media, so VLC's own
     * subtitle rendering is kept off (both renderers would otherwise draw the same text).
     */
    var rendersSubtitle: Boolean = false
        private set

    /** True when the overlay must display this external subtitle instead of VLC. */
    fun handlesExternalSubtitle(source: String?): Boolean = SubtitleOverlayPrefs.handles(activity, source)

    /** Must be called from onCreate after setContentView, before playback starts. */
    fun initialize(rootView: View?) {
        rootContainer = rootView?.findViewById(R.id.player_root)
        settings.registerOnSharedPreferenceChangeListener(this)
        attach()
    }

    /** Called when the PlaybackService gets bound in the activity. */
    fun onServiceConnected(service: PlaybackService?) {
        if (service == null || !isEnabled) return
        // The player is rebuilt when the libvlc options change: re-attach to the new service/model
        controller?.onMediaChanged()
        attach()
        restoreSubtitle()
    }

    /**
     * Forwarder of PlaybackService.Callback.onMediaPlayerEvent: the activity already implements the
     * interface; it delegates to this delegate so the overlay sees every transport event (time,
     * seek, pause, stop...).
     */
    fun onMediaPlayerEvent(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.Opening -> onMediaChanged()
            MediaPlayer.Event.Playing -> {
                controller?.resync()
                // VLC selects its own subtitle track when a media starts (the preferred track
                // restored by PlayerController.loadMediaMeta, the activity, or libvlc itself): the
                // same text would be rendered twice, so the track is taken over or taken away
                if (!rendersSubtitle) autoAdoptNativeSubtitle() else syncNativeSubtitleRendering()
            }
            MediaPlayer.Event.Vout -> {
                controller?.resync()
                // A track libvlc selected by itself ( VLC4 does this for its preferred language)
                // is only visible once there is a video output; adopting here keeps the first
                // subtitle from being rendered by the engine before the overlay takes it over.
                if (!rendersSubtitle) autoAdoptNativeSubtitle()
            }
            else -> {}
        }
        controller?.onMediaPlayerEvent(event)
    }

    /**
     * When libvlc selected a subtitle track on its own (the engine's auto-selection, not an
     * app-initiated one) and the overlay is enabled, that track is adopted by the overlay: an
     * embedded text track is loaded from the container and VLC's own rendering is turned off
     * (see [adoptSubtitleSelection]). Non-text formats (ASS/SSA, bitmap) stay on VLC.
     *
     * Nothing happens while the overlay already renders a subtitle of this media, nor when the
     * player intentionally selected "no subtitle" ("-1"/-2 sentinels).
     */
    private fun autoAdoptNativeSubtitle() {
        if (!isEnabled) return
        // An explicit pick in flight wins: the auto-adoption reads VLC's own selection, which may
        // still report the previous track (or nothing) while the pick's container read is running
        if (explicitPickOnHold()) return
        val service = activity.service ?: return
        val current = service.spuTrack
        if (current == NO_NATIVE_SUBTITLE || current == NO_TRACK) return
        // Vout sees several events for the same playback (vout attach, re-attach on PiP/clone...);
        // the container read of a pending adoption must not be torn down repeatedly. Once the
        // adoption succeeded or was attempted for this media, the playback events drive the rest.
        if (current == lastAutoAdoptAttempted) return
        // Already the track the overlay registers for itself: nothing to do
        if (SubtitleOverlayRuntime.embeddedTrackId == current) return
        lastAutoAdoptAttempted = current
        adoptSubtitleSelection(current, explicit = false)
    }

    /**
     * External SubRip subtitle chosen by the user (file picker, download, "play with subtitle"
     * extra), displayed by the overlay instead of VLC's renderer.
     *
     * @param saveForMedia whether the subtitle is remembered for the media, like the picker always
     *   did. Downloads and one-shot intent extras are restored by their own flow and pass false.
     */
    fun handlePickedSubtitle(source: String?, saveForMedia: Boolean = true) {
        if (source.isNullOrBlank()) return
        val uri = toUri(source)
        val location = activity.service?.currentMediaWrapper?.location
        requestedForMedia = location
        requestedSubtitle = uri
        if (saveForMedia && location != null) {
            SlaveRepository.getInstance(activity)
                    .saveSlave(location, IMedia.Slave.Type.Subtitle, SUBTITLE_SLAVE_PRIORITY, uri.toString())
        }
        loadSubtitle(uri)
    }

    // ---- subtitle ownership ----

    /**
     * Called by the player when it is about to select the subtitle track [trackId] by itself (media
     * start, preferred or restored track).
     *
     * @return true when the overlay takes the rendering over and the track must NOT be selected in
     *   VLC, false when VLC keeps it (the overlay may take an embedded track over afterwards, once
     *   the container has been read).
     */
    fun onNativeSubtitleAboutToSelect(trackId: String): Boolean {
        if (rendersSubtitle && activeExternalSubtitle != null) {
            // The overlay draws the external subtitle of this media: VLC's own track stays off, and
            // the track that was about to be restored is remembered so it can be given back
            if (trackId != NO_NATIVE_SUBTITLE && trackId != NO_TRACK)
                nativeSubtitleBeforeOverlay = nativeSubtitleBeforeOverlay ?: trackId
            return true
        }
        // A restore of the *saved* track right after an explicit pick would cancel the pick's
        // in-flight container read and land the playback back on the old track; the pick itself
        // persists its id, so the deferred restore converges on it (see explicitPickHoldUntilMs)
        if (explicitPickOnHold()) return false
        return adoptSubtitleSelection(trackId, explicit = false)
    }

    /**
     * Called when the user selects a subtitle entry (or turns subtitles off) in the tracks panel:
     * an explicit choice always wins over the overlay.
     *
     * @return true when the overlay keeps drawing the chosen entry, in which case VLC must not be
     *   given the track (selecting it would draw the same text twice).
     */
    fun onNativeSubtitleSelected(trackId: String): Boolean {
        // Shield the takeover from the restore/auto-adopt flows that the selection itself triggers
        // (ESAdded(Text) replays the saved track, Playing/Vout run auto-adoptions): for a moment
        // they must not cancel the freshly started container read of the picked track
        explicitPickHoldUntilMs = SystemClock.elapsedRealtime() + EXPLICIT_PICK_HOLD_MS
        return adoptSubtitleSelection(trackId, explicit = true)
    }

    /** Detach from the activity; release the view and controller. */
    fun release() {
        settings.unregisterOnSharedPreferenceChangeListener(this)
        restoreJob?.cancel()
        resolveJob?.cancel()
        restoreNativeSubtitleRendering()
        detach()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            SubtitleOverlayController.PREF_OVERLAY_ENABLED -> onOverlayToggled()
            SubtitleOverlayView.PREF_OVERLAY_POSITION ->
                overlayView?.let { it.positionPercent = SubtitleOverlayPrefs.position(activity) }
        }
    }

    // ---- overlay lifecycle ----

    private fun attach(): SubtitleOverlayController? {
        if (!isEnabled) return null
        controller?.let { return it }
        val container = rootContainer ?: return null
        activity.service ?: return null
        val view = overlayView ?: createView(container)
        val c = SubtitleOverlayController(object : PlaybackTimeSource {
            override fun currentTime(): Long = activity.service?.getTime() ?: 0L
            override fun subtitleDelayUs(): Long = activity.service?.spuDelay ?: 0L
        }, activity, view)
        controller = c
        c.start()
        return c
    }

    private fun createView(container: ViewGroup): SubtitleOverlayView {
        // Insert directly above the video layout: the video is the first child, and index 1 puts
        // the overlay under every control stub inflated later, matching the z-order VLC relies on
        // (video < subtitle < player chrome).
        val view = SubtitleOverlayView(activity)
        view.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT)
        val videoIndex = container.indexOfChild(activity.videoLayout)
        container.addView(view, if (videoIndex >= 0) videoIndex + 1 else container.childCount)
        overlayView = view
        return view
    }

    private fun detach() {
        controller?.release()
        controller = null
        overlayView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        overlayView = null
        clearRenderingState()
        SubtitleOverlayRuntime.clear()
    }

    /** Applies the overlay setting to the playback that is running. */
    private fun onOverlayToggled() {
        if (isEnabled) {
            attach()
            restoreSubtitle()
            return
        }
        val wasRendering = rendersSubtitle
        requestedSubtitle = null
        requestedForMedia = null
        detach()
        restoreNativeSubtitleRendering()
        // VLC attaches external subtitles when a playback starts: replay the media so the file the
        // overlay was showing is handed back to the native renderer. The player is not reopened
        // when it is not on screen (see PlaylistManager.reload).
        if (wasRendering) activity.service?.reloadPlayback()
    }

    // ---- subtitle handling ----

    /** Discards the previous media's state and restores the new media's external subtitle, if any. */
    private fun onMediaChanged() {
        val location = activity.service?.currentMediaWrapper?.location
        if (location != requestedForMedia) {
            requestedSubtitle = null
            requestedForMedia = null
        }
        controller?.onMediaChanged()
        clearRenderingState()
        SubtitleOverlayRuntime.clear()
        this.lastAutoAdoptAttempted = null
        explicitPickHoldUntilMs = 0L
        // The remembered native track belongs to the previous media: giving it to VLC here would
        // select a (wrong) track of the new one. It is simply forgotten, the new media's preference
        // (media meta, preferred language) being applied by the player itself.
        nativeSubtitleBeforeOverlay = null
        restoreSubtitle()
    }

    /** Forgets what the overlay draws, without touching the controller or VLC's track. */
    private fun clearRenderingState() {
        activeExternalSubtitle = null
        activeEmbeddedTrackId = null
        rendersSubtitle = false
    }

    /**
     * Displays the external subtitle of the current media: the one the user just picked, or the one
     * saved for it (VLC slaves of the media and the subtitle repository), exactly like VLC would
     * have attached them. Only SubRip files are taken over.
     */
    private fun restoreSubtitle() {
        val service = activity.service ?: return
        val mw = service.currentMediaWrapper
        val requested = requestedSubtitle
        if (requested != null && requestedForMedia == mw?.location) {
            loadSubtitle(requested)
            return
        }
        restoreJob?.cancel()
        restoreJob = activity.lifecycleScope.launch {
            val location = mw?.location ?: return@launch
            val sources = ArrayList<String>(4)
            mw.slaves?.filter { it.type == IMedia.Slave.Type.Subtitle }?.forEach { sources.add(it.uri) }
            val persisted = withContext(Dispatchers.IO) {
                runCatching { SlaveRepository.getInstance(activity).getSlaves(location) }
                        .getOrDefault(emptyList())
            }
            persisted.filter { it.type == IMedia.Slave.Type.Subtitle }
                    .forEach { if (!sources.contains(it.uri)) sources.add(it.uri) }
            val source = sources.firstOrNull { SubtitleOverlayPrefs.isSrt(it) } ?: return@launch
            if (activity.service?.currentMediaWrapper?.location == location) loadSubtitle(toUri(source))
        }
    }

    /** Loads [uri] in the overlay, keeping VLC's rendering off while it is displayed. */
    private fun loadSubtitle(uri: Uri) {
        if (!isEnabled || uri == activeExternalSubtitle) return
        // VLC must not draw the subtitle the overlay is about to display, and the activity must not
        // select one behind it (see onNativeSubtitleAboutToSelect)
        activeExternalSubtitle = uri
        activeEmbeddedTrackId = null
        rendersSubtitle = true
        syncNativeSubtitleRendering()
        val c = attach() ?: return // no service yet: restored when it connects
        c.loadExternal(uri, null) { loaded -> onSubtitleLoaded(uri, loaded) }
    }

    private fun onSubtitleLoaded(uri: Uri, loaded: Boolean) {
        if (loaded) {
            if (activeExternalSubtitle != uri) activeExternalSubtitle = uri
            activeEmbeddedTrackId = null
            rendersSubtitle = true
            syncNativeSubtitleRendering()
            return
        }
        // Not readable as SubRip (deleted file, unsupported content...): hand the rendering back to
        // VLC, which keeps whatever subtitle it had
        if (activeExternalSubtitle == uri) activeExternalSubtitle = null
        if (activeExternalSubtitle == null) {
            rendersSubtitle = false
            restoreNativeSubtitleRendering()
        }
    }

    /**
     * Applies the subtitle choice of the user (or of the player) to the overlay: an explicit choice
     * gives the rendering back to VLC, while a track the overlay can draw (an embedded SubRip or
     * WebVTT track of the container) is taken over.
     *
     * The format of an embedded track is only known by the container, so it is read off the main
     * thread; VLC renders the track until then, and for good when the container can't be read.
     */
    private fun adoptSubtitleSelection(trackId: String, explicit: Boolean): Boolean {
        // The overlay's own entry of the subtitle menu: keep the overlay as the renderer
        if (trackId == SubtitleOverlayRuntime.TRACK_ID)
            return SubtitleOverlayRuntime.externalSubtitleLabel != null
        // The embedded track the overlay already draws: nothing to change, VLC must not get it
        if (activeEmbeddedTrackId != null && activeEmbeddedTrackId == trackId) {
            syncNativeSubtitleRendering()
            return true
        }
        if (explicit) {
            // The user is in control from now on: nothing has to be given back when the overlay stops
            nativeSubtitleBeforeOverlay = null
            requestedSubtitle = null
            requestedForMedia = null
            // The user's explicit pick may be the same track the auto-adoption already tried: the
            // next auto-adoption (Vout) must be allowed to run for it again
            lastAutoAdoptAttempted = null
        }
        // Any other choice belongs to VLC: the overlay stops drawing what it had
        if (rendersSubtitle) {
            clearRenderingState()
            controller?.unload()
        }
        if (trackId == NO_NATIVE_SUBTITLE || trackId == NO_TRACK) return false

        val service = activity.service
        val location = service?.currentMediaWrapper?.location

        // A subtitle slave of the media (an attached .srt) shows up in the tracks menu too. It is
        // not a track of the container: loading the container here would find a wrong (or no)
        // ordinal and leave the file to VLC, whose renderer scales text with the video size.
        // When the menu entry is that file instead, the overlays draws it itself.
        if (service != null && location != null && adoptSubtitleSlave(service.currentMediaWrapper, trackId))
            return true

        // Ordinal among the container's subtitle tracks, NOT the position in libvlc's menu: on VLC 3
        // the menu starts with a "Disable" entry, which would shift every ordinal by one and push
        // the last track of the container past the end of its track list (see containerOrdinalOf)
        val index = service?.spuTracks?.let { tracks ->
            EmbeddedSubtitles.containerOrdinalOf(tracks.map { it.getId() }, trackId)
        } ?: -1
        if (service == null || index < 0 || location == null) {
            // Not an embedded track (or the media is gone): only libvlc's own description can tell
            // what the format is
            applySubtitleMargin(styled = SubtitlePosition.isStyledSubtitleDescription(descriptionOf(trackId)))
            return false
        }
        val language = mediaTrackOf(trackId)?.language
        val uri = toUri(location)
        val c = if (isEnabled) attach() else null
        resolveJob?.cancel()
        if (c != null) {
            // Single read of the container: it reports the format and, for a text one, loads the
            // cues in the overlay (see onEmbeddedResolved)
            c.loadEmbedded(uri, index, language, trackId) { format -> onEmbeddedResolved(trackId, format) }
        } else {
            resolveJob = activity.lifecycleScope.launch {
                val format = withContext(Dispatchers.IO) {
                    runCatching { EmbeddedSubtitles.formatOf(activity, uri, index, language) }.getOrNull()
                }
                if (activity.service?.currentMediaWrapper?.location != location) return@launch
                applySubtitleMargin(styled = format?.let { !it.text }
                        ?: SubtitlePosition.isStyledSubtitleDescription(descriptionOf(trackId)))
            }
        }
        return false
    }

    /** Outcome of the container read of an embedded subtitle track (see [adoptSubtitleSelection]). */
    private fun onEmbeddedResolved(trackId: String, format: EmbeddedFormat?) {
        applySubtitleMargin(styled = format?.let { !it.text }
                ?: SubtitlePosition.isStyledSubtitleDescription(descriptionOf(trackId)))
        // The controller loaded the cues only when nothing newer took the rendering over in between
        if (format != null && format.text && controller != null && activeExternalSubtitle == null) {
            // The overlay loaded the cues of the track: VLC's own rendering is turned off, and the
            // track stays the one the menu shows as selected
            activeEmbeddedTrackId = trackId
            activeExternalSubtitle = null
            rendersSubtitle = true
            syncNativeSubtitleRendering()
            rememberSubtitleTrack(trackId)
        }
    }

    /**
     * Remembers [trackId] as the subtitle track of the current media, like the non-overlay path of
     * the tracks menu does: without this, a reload of the player (libvlc option change, subtitle
     * position change) restores "no subtitle" and the user has to pick the track again.
     */
    private fun rememberSubtitleTrack(trackId: String) {
        val mw = activity.service?.currentMediaWrapper ?: return
        runIO {
            runCatching {
                val media = Medialibrary.getInstance().findMedia(mw)
                val libraryMedia = media?.takeIf { it.id != 0L } ?: return@runCatching
                libraryMedia.setStringMeta(MediaWrapper.META_SUBTITLE_TRACK, trackId)
            }
        }
    }

    /**
     * Whether the menu entry [trackId] is a subtitle file attached to the media (a slave), in
     * which case the overlay loads that file and returns true (VLC must not render it).
     *
     * VLC reports an attached subtitle as a track named after the file; the media's slaves and
     * the persisted ones are searched for a matching file name. The overlay handles it when it is
     * a SubRip file it is able to parse.
     */
    private fun adoptSubtitleSlave(mw: MediaWrapper?, trackId: String): Boolean {
        if (mw == null || trackId == NO_NATIVE_SUBTITLE || trackId == NO_TRACK) return false
        val description = descriptionOf(trackId)
        if (description.isBlank()) return false
        val sources = ArrayList<String>(4)
        mw.slaves?.filter { it.type == IMedia.Slave.Type.Subtitle }?.forEach { sources.add(it.uri) }
        if (sources.isEmpty()) return false
        // VLC names an attached subtitle track after its file: match the entry against the known
        // slaves (the persisted ones are already in mw.slaves once VLC attached them), without a
        // blocking repository read (the caller is on the main thread)
        val fileName = description.substringAfterLast('\\').trim()
        val bareName = fileName.substringBeforeLast('.', fileName)
        val source = sources.firstOrNull { slaveUri ->
            val name = SubtitleOverlayPrefs.label(slaveUri)
            name.equals(fileName, ignoreCase = true) || name.startsWith("$bareName.", ignoreCase = true)
        } ?: return false
        if (!SubtitleOverlayPrefs.handles(activity, source)) return false
        // The entry belongs to the overlay from now on: forget any subtitle that was given back
        // to VLC and draw the file
        requestedSubtitle = null
        requestedForMedia = mw.location
        loadSubtitle(toUri(source))
        return rendersSubtitle
    }

    /**
     * Keeps VLC's subtitle position in sync with the format of the rendered subtitle: styled
     * (ASS/SSA) and bitmap subtitles are authored with their own coordinates and must not be moved,
     * text subtitles follow the app-wide position setting.
     *
     * When a styled subtitle is selected, no playback reload is triggered: VLC3 can only change
     * `--sub-margin` by rebuilding the engine, which replays the media and looks like the video
     * closing. The styled state is only recorded here so that the next engine build (media change,
     * libvlc option change) is created without the margin, matching VLC's own coordinates. Text
     * subtitles mixed into the same playback stay on the overlay anyway, so VLC's margin does not
     * matter for them while the overlay draws them.
     */
    private fun applySubtitleMargin(styled: Boolean) {
        SubtitlePosition.setStyledSubtitleActive(activity, styled)
        if (!styled) {
            // Text subtitle selected: the margin the running engine was built with matches the
            // position setting in almost every case (it was built from it); only the styled flag
            // going back to text needs a new engine, and the playback reload that goes with it.
            if (!SubtitlePosition.needsEngineRefresh(activity)) return
            activity.lifecycleScope.launch {
                withContext(Dispatchers.IO) { VLCInstance.restart() }
                PlaybackService.instance?.reloadPlayback()
            }
        }
    }

    /** Description libvlc gives for the subtitle track [trackId] (empty when it is unknown). */
    private fun descriptionOf(trackId: String): String =
            activity.service?.spuTracks?.firstOrNull { it.getId() == trackId }?.getName() ?: ""

    /** Media track [trackId], which carries the language and the codec libvlc reported for it. */
    private fun mediaTrackOf(trackId: String): IMedia.Track? {
        val media = activity.service?.mediaplayer?.media ?: return null
        return runCatching { media.getAllTracks().firstOrNull { it.id.toString() == trackId } }.getOrNull()
    }

    /**
     * Stops VLC from drawing subtitles while the overlay draws them, remembering the track that was
     * in use so [restoreNativeSubtitleRendering] can give it back.
     *
     * Called again every time VLC may have selected a track behind the overlay's back (media start,
     * the activity restoring the preferred track of the media), so the overlay's copy stays the
     * only one on screen.
     */
    private fun syncNativeSubtitleRendering() {
        if (!rendersSubtitle) return
        val service = activity.service ?: return
        val current = service.spuTrack
        if (current == NO_NATIVE_SUBTITLE || current == NO_TRACK) return
        nativeSubtitleBeforeOverlay = nativeSubtitleBeforeOverlay ?: current
        setNativeSubtitleTrack(NO_NATIVE_SUBTITLE)
    }

    private fun restoreNativeSubtitleRendering() {
        val track = nativeSubtitleBeforeOverlay ?: return
        nativeSubtitleBeforeOverlay = null
        setNativeSubtitleTrack(track)
    }

    private fun setNativeSubtitleTrack(track: String) {
        val service = activity.service ?: return
        // The player is used directly: VLC remembers the selected track per media through
        // PlaylistManager.setSpuTrack, and the overlay only borrows the selection, it does not
        // change the user's choice for the media
        if (isVLC4() && track == NO_NATIVE_SUBTITLE)
            service.unselectTrackType(VideoTracksDialog.TrackType.SPU)
        else service.mediaplayer.setSpuTrack(track)
    }

    /** A subtitle path may be a plain file path: give it the scheme the resolver needs. */
    private fun toUri(source: String): Uri {
        val uri = Uri.parse(source)
        return if (uri.scheme.isNullOrEmpty()) Uri.fromFile(File(source)) else uri
    }
}
