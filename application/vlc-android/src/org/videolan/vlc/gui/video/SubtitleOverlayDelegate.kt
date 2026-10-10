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
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.tools.Settings
import org.videolan.vlc.PlaybackService
import org.videolan.vlc.R
import org.videolan.vlc.gui.dialogs.VideoTracksDialog
import org.videolan.vlc.isVLC4
import org.videolan.vlc.setSpuTrack
import org.videolan.vlc.repository.SlaveRepository
import org.videolan.vlc.subtitleoverlay.PlaybackTimeSource
import org.videolan.vlc.subtitleoverlay.SubtitleOverlayController
import org.videolan.vlc.subtitleoverlay.SubtitleOverlayPrefs
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
 *  - take the external SubRip subtitles of the played media over from VLC while the overlay is
 *    enabled: the file picked, downloaded or saved for the media is displayed by the overlay and is
 *    never attached to the media as a native slave (see [SubtitleOverlayPrefs.handles] and
 *    [org.videolan.vlc.media.PlayerController.setSlaves]), so it is never rendered twice;
 *  - apply a settings change (enable, position) to the playback that is running instead of waiting
 *    for the player to be opened again.
 *
 * Every subtitle format the overlay cannot parse keeps going through VLC even when it is on, and
 * VLC's own rendering is only turned off while the overlay actually displays a subtitle.
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
    }

    private val settings = Settings.getInstance(activity)

    private var rootContainer: ViewGroup? = null
    private var overlayView: SubtitleOverlayView? = null
    private var controller: SubtitleOverlayController? = null

    /** Subtitle the user just picked, and the media it belongs to. */
    private var requestedSubtitle: Uri? = null
    private var requestedForMedia: String? = null

    /** Subtitle currently displayed by the overlay. */
    private var activeSubtitle: Uri? = null
    private var restoreJob: Job? = null

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
                // restored by PlayerController.loadMediaMeta or the activity): the same text would
                // be rendered twice, so the track is taken away again and remembered
                syncNativeSubtitleRendering()
            }
            MediaPlayer.Event.Vout -> controller?.resync()
            else -> {}
        }
        controller?.onMediaPlayerEvent(event)
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

    /**
     * Called by the activity when it is about to select the native subtitle track [trackId] while
     * the overlay displays the media's external subtitle: the native track is kept off and
     * remembered, so both renderers don't draw subtitles at the same time.
     */
    fun onNativeSubtitleAutoSelected(trackId: String) {
        if (!rendersSubtitle) return
        if (trackId != NO_NATIVE_SUBTITLE && trackId != NO_TRACK)
            nativeSubtitleBeforeOverlay = nativeSubtitleBeforeOverlay ?: trackId
        setNativeSubtitleTrack(NO_NATIVE_SUBTITLE)
    }

    /**
     * Called when the user selects a subtitle entry (or turns subtitles off) in the tracks panel:
     * an explicit choice always wins over the overlay, so the overlay gives the rendering back to
     * VLC and stops drawing the external subtitle.
     */
    fun onNativeSubtitleSelected(trackId: String) {
        // The user is in control from now on: nothing has to be given back when the overlay stops
        nativeSubtitleBeforeOverlay = null
        requestedSubtitle = null
        requestedForMedia = null
        if (!rendersSubtitle) return
        activeSubtitle = null
        rendersSubtitle = false
        controller?.unload()
    }

    /** Detach from the activity; release the view and controller. */
    fun release() {
        settings.unregisterOnSharedPreferenceChangeListener(this)
        restoreJob?.cancel()
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
        activeSubtitle = null
        rendersSubtitle = false
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
        activeSubtitle = null
        rendersSubtitle = false
        restoreNativeSubtitleRendering()
        restoreSubtitle()
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
        if (!isEnabled || uri == activeSubtitle) return
        // VLC must not draw the subtitle the overlay is about to display, and the activity must not
        // select one behind it (see onNativeSubtitleAutoSelected)
        activeSubtitle = uri
        rendersSubtitle = true
        syncNativeSubtitleRendering()
        val c = attach() ?: return // no service yet: restored when it connects
        c.loadExternal(uri, null) { loaded -> onSubtitleLoaded(uri, loaded) }
    }

    private fun onSubtitleLoaded(uri: Uri, loaded: Boolean) {
        if (loaded) {
            if (activeSubtitle != uri) activeSubtitle = uri
            rendersSubtitle = true
            syncNativeSubtitleRendering()
            return
        }
        // Not readable as SubRip (deleted file, unsupported content...): hand the rendering back to
        // VLC, which keeps whatever subtitle it had
        if (activeSubtitle == uri) activeSubtitle = null
        if (activeSubtitle == null) {
            rendersSubtitle = false
            restoreNativeSubtitleRendering()
        }
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
