/*
 * **************************************************************************
 *  SubtitlePositionTest.kt
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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.videolan.tools.SubtitlePosition

/**
 * The subtitle position setting is the single control shared by the overlay and by VLC's own
 * renderer: 0 is the bottom edge of the screen, 100 the top, and styled (ASS/SSA) or bitmap
 * subtitles are never moved by it.
 */
class SubtitlePositionTest {

    @Test
    fun `the default keeps both renderers at their own position`() {
        assertEquals(0, SubtitlePosition.DEFAULT_PERCENT)
        assertEquals(0, SubtitlePosition.marginFor(SubtitlePosition.DEFAULT_PERCENT, styled = false))
    }

    @Test
    fun `the scale runs from the bottom edge to the top`() {
        assertEquals(0f, SubtitlePosition.normalize(0), 0f)
        assertEquals(0.5f, SubtitlePosition.normalize(50), 0f)
        assertEquals(1f, SubtitlePosition.normalize(100), 0f)
    }

    @Test
    fun `a percentage out of the scale is clamped`() {
        assertEquals(1f, SubtitlePosition.normalize(SubtitlePosition.MAX_PERCENT + 40), 0f)
        assertEquals(0f, SubtitlePosition.normalize(SubtitlePosition.MIN_PERCENT - 40), 0f)
        assertEquals(1000,
                SubtitlePosition.marginFor(SubtitlePosition.MAX_PERCENT + 40, styled = false, spanPx = 1000))
    }

    @Test
    fun `the native margin grows with the position`() {
        assertEquals(0, SubtitlePosition.marginFor(0, styled = false, spanPx = 1000))
        assertEquals(500, SubtitlePosition.marginFor(50, styled = false, spanPx = 1000))
        assertEquals(1000, SubtitlePosition.marginFor(100, styled = false, spanPx = 1000))
        assertEquals(8, SubtitlePosition.marginFor(25, styled = false, spanPx = 30))
    }

    @Test
    fun `the position is mapped onto the screen height, so 100 reaches the top`() {
        assertEquals(0, SubtitlePosition.marginFor(0, styled = false, spanPx = 1080))
        assertEquals(1080, SubtitlePosition.marginFor(100, styled = false, spanPx = 1080))
        assertEquals(
                SubtitlePosition.marginFor(50, styled = false, spanPx = SubtitlePosition.FALLBACK_MARGIN_SPAN_PX),
                SubtitlePosition.marginFor(50, styled = false))
    }

    @Test
    fun `styled subtitles are never moved`() {
        assertEquals(0, SubtitlePosition.marginFor(100, styled = true))
        assertEquals(0, SubtitlePosition.marginFor(50, styled = true))
        assertEquals(0, SubtitlePosition.marginFor(100, styled = true, spanPx = 1080))
    }

    @Test
    fun `the old top-anchored scale is migrated onto the new one`() {
        // The old overlay seek bar stored the distance from the top, defaulted to 98 and wrote it
        // as soon as the settings screen was opened: that whole band is the new default
        assertEquals(0, SubtitlePosition.migrate(98))
        assertEquals(0, SubtitlePosition.migrate(100))
        assertEquals(0, SubtitlePosition.migrate(99))
        assertEquals(5, SubtitlePosition.migrate(95))
        assertEquals(95, SubtitlePosition.migrate(5))
        // A normalized anchor written by the overlay itself is converted as is
        assertEquals(50, SubtitlePosition.migrate(0.5f))
        assertEquals(0, SubtitlePosition.migrate(null))
        assertEquals(0, SubtitlePosition.migrate("nonsense"))
    }

    @Test
    fun `libvlc descriptions identify the styled and bitmap formats`() {
        assertTrue(SubtitlePosition.isStyledSubtitleDescription("SubStation Alpha subtitles"))
        assertTrue(SubtitlePosition.isStyledSubtitleDescription("DVB subtitles"))
        assertTrue(SubtitlePosition.isStyledSubtitleDescription("VobSub subtitles"))
        assertTrue(SubtitlePosition.isStyledSubtitleDescription("HDMV PGS subtitles"))
        assertFalse(SubtitlePosition.isStyledSubtitleDescription("Track 3 - [eng]"))
        assertFalse(SubtitlePosition.isStyledSubtitleDescription("SubRip subtitles"))
        assertFalse(SubtitlePosition.isStyledSubtitleDescription(""))
        assertFalse(SubtitlePosition.isStyledSubtitleDescription(null))
    }

    @Test
    fun `file names identify the styled and bitmap formats`() {
        assertTrue(SubtitlePosition.isStyledSubtitleFile("movie.en.ass"))
        assertTrue(SubtitlePosition.isStyledSubtitleFile("movie.ASS"))
        assertTrue(SubtitlePosition.isStyledSubtitleFile("movie.ssa"))
        assertTrue(SubtitlePosition.isStyledSubtitleFile("movie.idx"))
        assertTrue(SubtitlePosition.isStyledSubtitleFile("movie.sup"))
        assertFalse(SubtitlePosition.isStyledSubtitleFile("movie.srt"))
        assertFalse(SubtitlePosition.isStyledSubtitleFile("movie.vtt"))
        assertFalse(SubtitlePosition.isStyledSubtitleFile(null))
    }
}
