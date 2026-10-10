/*
 * **************************************************************************
 *  SubtitleOverlayPrefs.kt — convenience helpers for the overlay's
 *  persistent preferences (position + enabled state).
 * **************************************************************************
 */

package org.videolan.vlc.subtitleoverlay

import android.app.Activity
import android.content.Context
import androidx.lifecycle.LifecycleOwner
import org.videolan.tools.Settings
import org.videolan.tools.putSingle

/** Convenience helpers for the overlay's persistent preferences. */
object SubtitleOverlayPrefs {

    /** Whether the screen-anchored overlay is enabled in the subsystem. */
    fun isEnabled(owner: LifecycleOwner): Boolean =
            Settings.getInstance(owner.contextOf()).getBoolean(
                    SubtitleOverlayController.PREF_OVERLAY_ENABLED, false)

    /**
     * Persisted vertical anchor (see [SubtitleOverlayView] docs for the
     * coordinate convention). Defaults to slightly above the bottom edge.
     */
    fun getPosition(owner: LifecycleOwner): Float =
            Settings.getInstance(owner.contextOf()).getFloat(
                    SubtitleOverlayView.PREF_OVERLAY_POSITION,
                    SubtitleOverlayView.DEFAULT_POSITION)

    fun setPosition(owner: LifecycleOwner, value: Float) {
        Settings.getInstance(owner.contextOf()).putSingle(
                SubtitleOverlayView.PREF_OVERLAY_POSITION, value)
    }

    private fun LifecycleOwner.contextOf(): Context = when (this) {
        is Activity -> this
        else -> throw IllegalStateException("Unsupported LifecycleOwner for SubtitleOverlayPrefs")
    }
}
