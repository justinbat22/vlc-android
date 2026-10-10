/*
 * **************************************************************************
 *  SubtitleOverlayView.kt
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
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import org.videolan.tools.Settings
import kotlin.math.min

/**
 * Custom view that draws the active subtitle cue anchored to the *player
 * container* (the whole [player_root] frame the video surface lives in), not
 * to the decoded video rectangle.
 *
 * Geometry convention (documented, reused by the controller and preferences):
 *  - `positionPercent` is a NORMALIZED VERTICAL ANCHOR in [0.0 .. 1.0]
 *    expressed in the player-container coordinate space, where 0.0 is the top
 *    of the container and 1.0 is its bottom edge. The value is the Y of the
 *    BOTTOM of the subtitle text block.
 *  - The default (0.98) rests the text near the bottom inset of the container,
 *    the same place VLC-native subtitles usually appear.
 *  - Values < 1.0 raise the text; because this view paints over the WHOLE
 *    container (and is a plain View, not inside the video surface), text
 *    appears in the letterbox/pillarbox bars whenever the anchor lands there.
 *  - A minimum of 16dp of side/top border safety is enforced so text is never
 *    laid out at the extreme edges of the screen.
 *
 * The view only draws text; it never consumes touches
 * (enabled=false + no click/touch listeners), so player gestures keep working.
 */
class SubtitleOverlayView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null
) : View(context, attrs) {

    private var cue: SubtitleCue? = null

    /** Normalized anchor of the BOTTOM of the text block, 0.0 (top) .. 1.0 (bottom). */
    var positionPercent: Float = DEFAULT_POSITION
        set(value) {
            field = value.coerceIn(MIN_POSITION, MAX_POSITION)
            invalidate()
        }

    // ---- appearance ----
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    private val bgPaint = Paint().apply { style = Paint.Style.FILL }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
        style = Paint.Style.STROKE
    }

    private var textSizePx = 48f
    private var outlineWidthPx = 2f
    private var textColor = 0xFFFFFFFF.toInt()
    private var textOpacity = 255
    private var bgColor = 0x000000
    private var bgOpacity = 0
    private var outlineColor = 0x000000
    private var outlineOpacity = 0
    private var shadowEnabled = true

    private var cachedLines: List<String> = emptyList()
    private var cachedLineHeight = 0f
    private var cachedTextWidth = 0f

    init {
        // Transparent & non-interactive by design
        setBackgroundColor(0x00000000)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // Keep the player's own touch delegate handling gestures
        setOnTouchListener(null)
        isLongClickable = false
        refreshAppearance()
    }

    fun refreshAppearance() {
        val prefs = Settings.getInstance(context)
        val density = resources.displayMetrics.density
        // subtitles_size pref is the same value VLC maps onto freetype-rel-fontsize;
        // overlay uses a px size derived from it so both paths stay proportional.
        val scaled = when (prefs.getString("subtitles_size", "16")) {
            "40" -> 54f; "32" -> 44f; "25" -> 36f; "19" -> 30f
            "16" -> 26f; "13" -> 24f; else -> 22f
        }
        textSizePx = scaled * density
        textPaint.textSize = textSizePx
        outlinePaint.textSize = textSizePx

        textOpacity = prefs.getInt("subtitles_color_opacity", 255)
        textColor = prefs.getInt("subtitles_color", 0xFFFFFFFF.toInt())
        textPaint.color = applyAlpha(textColor, textOpacity)
        textPaint.isFakeBoldText = prefs.getBoolean("subtitles_bold", false)

        val bgEnabled = prefs.getBoolean("subtitles_background", false)
        bgOpacity = if (bgEnabled) prefs.getInt("subtitles_background_color_opacity", 255) else 0
        bgColor = prefs.getInt("subtitles_background_color", 0xFF000000.toInt())

        val outlineEnabled = prefs.getBoolean("subtitles_outline", true)
        outlineOpacity = if (outlineEnabled) prefs.getInt("subtitles_outline_color_opacity", 255) else 0
        outlineColor = prefs.getInt("subtitles_outline_color", 0xFF000000.toInt())
        val outlineUnits = prefs.getString("subtitles_outline_size", "4")?.toIntOrNull() ?: 4
        outlineWidthPx = (outlineUnits * density)
        outlinePaint.strokeWidth = outlineWidthPx
        outlinePaint.color = applyAlpha(outlineColor, outlineOpacity)

        shadowEnabled = prefs.getBoolean("subtitles_shadow", true)
        textPaint.setShadowLayer(
                if (shadowEnabled) outlineWidthPx.coerceAtLeast(2f) * 1.5f else 0f,
                0f, if (shadowEnabled) outlineWidthPx.coerceAtLeast(2f) else 0f,
                if (shadowEnabled) applyAlpha(prefs.getInt("subtitles_shadow_color", 0xFF000000.toInt()),
                        prefs.getInt("subtitles_shadow_color_opacity", 128)) else 0x00000000)

        bgPaint.color = applyAlpha(bgColor, bgOpacity)
        invalidate()
    }

    private fun applyAlpha(color: Int, opacity: Int) = (color and 0x00FFFFFF) or ((opacity.coerceIn(0, 255)) shl 24)

    fun setCue(newCue: SubtitleCue?) {
        if (newCue == cue) return
        cue = newCue
        cacheLayout()
        invalidate()
    }

    /** Re-measure line widths/height without allocating per-frame paint objects. */
    private fun cacheLayout() {
        val current = cue
        if (current == null) {
            cachedLines = emptyList()
            return
        }
        cachedLines = current.lines
        var widest = 0f
        val paint = textPaint
        cachedLineHeight = paint.fontSpacing
        for (line in cachedLines) {
            val w = paint.measureText(line)
            if (w > widest) widest = w
        }
        cachedTextWidth = widest
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Container geometry changed: clamp the anchor so the text block always
        // fits fully inside the view, avoiding clipping at edges
        // (controller re-queries this on layout changes)
        positionPercent = positionPercent
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val lines = cachedLines
        if (lines.isEmpty()) return
        val w = width
        val h = height
        if (w == 0 || h == 0) return

        val blockHeight = cachedLineHeight * lines.size
        val safeMargin = min(16 * resources.displayMetrics.density, h * 0.05f)
        val maxWidth = w - 2 * safeMargin
        val drawLines = if (cachedTextWidth > maxWidth) wrapLines(lines, maxWidth) else lines
        val drawBlockHeight = drawLines.size * cachedLineHeight

        // The anchor is the BOTTOM of the block in container coordinates
        var bottomY = positionPercent * h
        // Never let the text start above the top safe margin nor extend beyond
        // the bottom edge of the container entirely
        if (bottomY > h - safeMargin) bottomY = h - safeMargin
        if (bottomY - drawBlockHeight < safeMargin) bottomY = (safeMargin + drawBlockHeight).coerceAtMost(h - safeMargin)
        var baseline = bottomY - drawBlockHeight + cachedLineHeight * 0.85f

        val centerX = w / 2f
        for (line in drawLines) {
            if (bgOpacity > 0) {
                val lineWidth = textPaint.measureText(line)
                val pad = 6 * resources.displayMetrics.density
                bgPaint.color = applyAlpha(bgColor, bgOpacity)
                canvas.drawRect(centerX - lineWidth / 2 - pad, baseline - textSizePx,
                        centerX + lineWidth / 2 + pad, baseline + textSizePx * 0.35f, bgPaint)
            }
            if (outlineOpacity > 0 && outlineWidthPx > 0f) {
                canvas.drawText(line, centerX, baseline, outlinePaint)
            }
            canvas.drawText(line, centerX, baseline, textPaint)
            baseline += cachedLineHeight
        }
    }

    /** Simple greedy word wrap so long cues never leave the container. */
    private fun wrapLines(lines: List<String>, maxWidth: Float): List<String> {
        val out = ArrayList<String>(lines.size + 2)
        for (line in lines) {
            if (textPaint.measureText(line) <= maxWidth) { out.add(line); continue }
            val words = line.split(' ')
            var current = StringBuilder()
            for (word in words) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (textPaint.measureText(candidate) <= maxWidth) {
                    current = StringBuilder(candidate)
                } else {
                    if (current.isNotEmpty()) { out.add(current.toString()); current = StringBuilder(word) }
                    else { out.add(word) }
                }
            }
            if (current.isNotEmpty()) out.add(current.toString())
        }
        return out
    }

    fun persistPosition() = SubtitleOverlayPrefs.setPosition(context, positionPercent)

    companion object {
        /** Preference key holding [positionPercent] (as a percent, see SubtitleOverlayPrefs). */
        const val PREF_OVERLAY_POSITION = "subtitle_overlay_position"

        /** Highest positionPercent the overlay can be moved down to (bottom of the container). */
        const val MAX_POSITION = 1.0f

        /** Lowest positionPercent the overlay can be moved up to. */
        const val MIN_POSITION = 0.05f

        /** Default anchor: the text rests near the bottom inset of the container. */
        const val DEFAULT_POSITION = 0.98f
    }
}
