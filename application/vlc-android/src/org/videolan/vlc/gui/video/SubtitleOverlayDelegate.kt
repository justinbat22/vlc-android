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

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import java.io.File
import org.videolan.libvlc.MediaPlayer
import org.videolan.tools.Settings
import org.videolan.tools.putSingle
import org.videolan.vlc.PlaybackService
import org.videolan.vlc.R
import org.videolan.vlc.subtitleoverlay.PlaybackTimeSource
import org.videolan.vlc.subtitleoverlay.SubtitleOverlayController
import org.videolan.vlc.subtitleoverlay.SubtitleOverlayView

/**
 * Binds the screen-anchored subtitle overlay to [VideoPlayerActivity] without
 * touching any of the player's existing behaviour.
 *
 * Responsibilities:
 *  - create the [SubtitleOverlayView] once per activity inside player_root,
 *    ABOVE the video surface and BELOW the player controls (it is added right
 *    after the video layout so the later-inflated UI container z-orders above);
 *  - own the [SubtitleOverlayController] for the lifetime of the player session;
 *  - forward libvlc player events coming through PlaybackService.Callback (the
 *    activity already receives them as a registered callback) to the controller;
 *  - restore the last used external subtitle for a media, per session.
 *
 * The overlay is opt-in: it only activates when the "subtitle_overlay_enabled"
 * preference is true, so all existing subtitle behaviour is preserved when it
 * is off.
 */
class SubtitleOverlayDelegate(private val activity: VideoPlayerActivity) {

    companion object {
        /** Request code for the external-subtitle picker of this feature. */
        const val REQUEST_CODE_PICK_SUBTITLE = 90001

        /** Preference key storing the last overlay subtitle URI for reconnection. */
        const val PREF_LAST_OVERLAY_URI = "subtitle_overlay_last_uri"
    }

    private var overlayView: SubtitleOverlayView? = null
    private var controller: SubtitleOverlayController? = null

    val isEnabled: Boolean
        get() = Settings.getInstance(activity)
                .getBoolean(SubtitleOverlayController.PREF_OVERLAY_ENABLED, false)

    /** Must be called from onCreate after setContentView, before playback starts. */
    fun initialize(rootView: View?) {
        if (!isEnabled || overlayView != null) return
        val container = rootView?.findViewById<ViewGroup>(R.id.player_root) ?: return
        // Insert directly above the video layout: the video is the first child,
        // and index 1 puts the overlay under every control stub inflated later,
        // matching the z-order VLC relies on (video < subtitle < player chrome).
        val view = SubtitleOverlayView(activity)
        view.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT)
        val videoIndex = container.indexOfChild(activity.videoLayout)
        container.addView(view, if (videoIndex >= 0) videoIndex + 1 else container.childCount)
        overlayView = view
    }

    /** Called when the PlaybackService gets bound in the activity. */
    fun onServiceConnected(service: PlaybackService?) {
        if (!isEnabled) return
        val active = service ?: return
        if (controller != null) {
            // Player rebuilt (settings change): re-attach to the new service/model.
            controller?.onMediaChanged()
            return
        }
        val view = overlayView ?: initializeInternal() ?: return
        val c = SubtitleOverlayController(object : PlaybackTimeSource {
            override fun currentTime(): Long = active.getTime()
            override fun subtitleDelayUs(): Long = active.spuDelay
        }, activity, view)
        controller = c
        c.start()
        // Restore the last external subtitle of this session, if any
        Settings.getInstance(activity).getString(PREF_LAST_OVERLAY_URI, null)?.let { last ->
            runCatching { c.loadExternal(Uri.parse(last)) }
        }
    }

    private fun initializeInternal(): SubtitleOverlayView? {
        initialize(activity.rootView)
        return overlayView
    }

    /**
     * Forwarder of PlaybackService.Callback.onMediaPlayerEvent: the activity
     * already implements the interface; it delegates to this delegate so the
     * overlay sees every transport event (time, seek, pause, stop...).
     */
    fun onMediaPlayerEvent(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.Opening -> onMediaChanged()
            MediaPlayer.Event.Vout -> controller?.resync()
            else -> {}
        }
        controller?.onMediaPlayerEvent(event)
    }

    /** External subtitle picked via the file dialog: display through the overlay. */
    fun handlePickedSubtitle(uri: Uri) {
        val c = controller ?: return
        c.loadExternal(uri, null)
        Settings.getInstance(activity).putSingle(PREF_LAST_OVERLAY_URI, uri.toString())
    }

    /** External subtitle from the library/disk (used by the subtitle download flow). */
    fun loadLocalSubtitle(file: File) {
        handlePickedSubtitle(Uri.fromFile(file))
    }

    /** Discards the overlay text and forgets the saved uri (feature toggled off). */
    fun onDisabled() {
        Settings.getInstance(activity).putSingle(PREF_LAST_OVERLAY_URI, "")
        controller?.unload()
    }

    /** Clears the current media's overlay state when the playlist advances. */
    fun onMediaChanged() {
        controller?.onMediaChanged()
        if (isEnabled) {
            Settings.getInstance(activity).getString(PREF_LAST_OVERLAY_URI, null)?.takeIf { it.isNotBlank() }?.let {
                runCatching { controller?.loadExternal(Uri.parse(it)) }
            }
        }
    }

    /** Detach from the activity; release the view and controller. */
    fun release() {
        controller?.release()
        controller = null
        overlayView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        overlayView = null
    }
}
