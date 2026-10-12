/*
 * **************************************************************************
 *  EmbeddedSubtitlesTest.kt
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The overlay draws an embedded track only when it can find the very track libvlc selected: the
 * ordinal libvlc lists it under (its container order) and, when the container has several subtitle
 * tracks, its language.
 */
class EmbeddedSubtitlesTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun source(vararg values: Int) = ByteArraySubtitleSource(bytes(*values))

    private fun uint32(value: Int) = bytes(
            (value shr 24) and 0xFF, (value shr 16) and 0xFF, (value shr 8) and 0xFF, value and 0xFF)

    private fun concat(vararg arrays: ByteArray): ByteArray {
        val out = ByteArray(arrays.sumOf { it.size })
        var offset = 0
        arrays.forEach {
            it.copyInto(out, offset)
            offset += it.size
        }
        return out
    }

    @Test
    fun `the container family comes from the file header`() {
        // EBML magic of Matroska and WebM
        assertEquals(EmbeddedContainer.MATROSKA,
                EmbeddedSubtitles.containerOf(source(0x1A, 0x45, 0xDF, 0xA3, 0x93, 0x42, 0x82, 0x88, 0, 0, 0, 0, 0, 0, 0, 0)))
        // ISO base media file format: the box type sits at offset 4
        val ftyp = concat(uint32(20), bytes('f'.code, 't'.code, 'y'.code, 'p'.code, 0, 0, 0, 0, 0, 0, 0, 0))
        assertEquals(EmbeddedContainer.MP4, EmbeddedSubtitles.containerOf(ByteArraySubtitleSource(ftyp)))
        assertNull(EmbeddedSubtitles.containerOf(source(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)))
        assertNull(EmbeddedSubtitles.containerOf(source(1, 2, 3)))
    }

    @Test
    fun `the track libvlc selected is the one taken over`() {
        val tracks = listOf(
                EmbeddedTrack(0, 2, EmbeddedFormat.ASS, "eng", null),
                EmbeddedTrack(1, 3, EmbeddedFormat.SRT, "fre", "Forced"))
        assertEquals(tracks[0], EmbeddedSubtitles.matchTrack(tracks, 0, "eng"))
        assertEquals(tracks[1], EmbeddedSubtitles.matchTrack(tracks, 1, "french"))
        assertEquals(tracks[1], EmbeddedSubtitles.matchTrack(tracks, 1, null))
        assertNull(EmbeddedSubtitles.matchTrack(tracks, 2, "eng"))
        assertNull(EmbeddedSubtitles.matchTrack(emptyList(), 0, "eng"))
    }

    @Test
    fun `a different track order is caught by the language`() {
        val tracks = listOf(
                EmbeddedTrack(0, 2, EmbeddedFormat.SRT, "fre", null),
                EmbeddedTrack(1, 3, EmbeddedFormat.SRT, "ger", null))
        // libvlc reports the French track at the ordinal of the German one: the language wins
        assertEquals(tracks[0], EmbeddedSubtitles.matchTrack(tracks, 1, "fra"))
        // Everything is tagged and none carries the wanted language: no track is taken over
        assertNull(EmbeddedSubtitles.matchTrack(tracks, 1, "eng"))
        // An untagged track is kept: there is nothing to contradict the ordinal
        val untagged = listOf(EmbeddedTrack(0, 2, EmbeddedFormat.SRT, null, null))
        assertEquals(untagged[0], EmbeddedSubtitles.matchTrack(untagged, 0, "eng"))
    }

    private val tenLanguages = listOf("eng", "fre", "ger", "spa", "ita", "por", "rus", "jpn", "kor", "chi")

    private fun tenTracks() = tenLanguages.mapIndexed { i, language ->
        EmbeddedTrack(i, (i + 2).toLong(), EmbeddedFormat.SRT, language, null)
    }

    /** libvlc 3 lists "Disable" (id -1) in front of the tracks; libvlc 4 lists the tracks only. */
    private val vlc3Menu = listOf("-1") + (0 until 10).map { (100 + it).toString() }
    private val vlc4Menu = (0 until 10).map { (100 + it).toString() }

    @Test
    fun `every one of ten tracks is found, the last one included`() {
        val tracks = tenTracks()
        for (menu in listOf(vlc3Menu, vlc4Menu)) {
            for (k in 0 until 10) {
                val ordinal = EmbeddedSubtitles.containerOrdinalOf(menu, (100 + k).toString())
                assertEquals(k, ordinal)
                assertEquals(tracks[k], EmbeddedSubtitles.matchTrack(tracks, ordinal, tenLanguages[k]))
            }
        }
    }

    @Test
    fun `the menu position of a VLC 3 list lost the last track`() {
        // What the overlay used to pass: the position in the menu, one more than the ordinal because
        // of the "Disable" entry. The other nine were rescued by the language, the tenth was one
        // past the end of the container's track list and stayed on VLC's renderer.
        val tracks = tenTracks()
        assertEquals(tracks[0], EmbeddedSubtitles.matchTrack(tracks, vlc3Menu.indexOf("100"), "eng"))
        assertEquals(tracks[8], EmbeddedSubtitles.matchTrack(tracks, vlc3Menu.indexOf("108"), "kor"))
        assertNull(EmbeddedSubtitles.matchTrack(tracks, vlc3Menu.indexOf("109"), "chi"))
    }

    @Test
    fun `entries that are not container tracks are never counted`() {
        val menu = listOf("-1", "5", SubtitleOverlayRuntime.TRACK_ID, "6")
        assertEquals(0, EmbeddedSubtitles.containerOrdinalOf(menu, "5"))
        assertEquals(1, EmbeddedSubtitles.containerOrdinalOf(menu, "6"))
        assertEquals(-1, EmbeddedSubtitles.containerOrdinalOf(menu, "-1"))
        assertEquals(-1, EmbeddedSubtitles.containerOrdinalOf(menu, "-2"))
        assertEquals(-1, EmbeddedSubtitles.containerOrdinalOf(menu, SubtitleOverlayRuntime.TRACK_ID))
        assertEquals(-1, EmbeddedSubtitles.containerOrdinalOf(menu, "99"))
        assertEquals(-1, EmbeddedSubtitles.containerOrdinalOf(emptyList(), "5"))
    }

    @Test
    fun `only text formats are drawn by the overlay`() {
        assertTrue(EmbeddedFormat.SRT.text)
        assertTrue(EmbeddedFormat.VTT.text)
        assertFalse(EmbeddedFormat.ASS.text)
        assertFalse(EmbeddedFormat.BITMAP.text)
        assertFalse(EmbeddedFormat.UNKNOWN.text)
    }

    @Test
    fun `languages are compared through their canonical code`() {
        assertEquals("en", IsoLanguages.canonical("eng"))
        assertEquals("en", IsoLanguages.canonical("en"))
        assertEquals("en", IsoLanguages.canonical("EN-GB"))
        assertEquals("fr", IsoLanguages.canonical("french"))
        assertEquals("zh", IsoLanguages.canonical("mandarin"))
        assertNull(IsoLanguages.canonical("und"))
        assertNull(IsoLanguages.canonical(""))
        assertNull(IsoLanguages.canonical(null))
        assertEquals("klingon", IsoLanguages.canonical("Klingon"))
    }

    @Test
    fun `the menu sees what the overlay draws`() {
        SubtitleOverlayRuntime.clear()
        assertFalse(SubtitleOverlayRuntime.isRendering)
        assertNull(SubtitleOverlayRuntime.externalSubtitleLabel)

        SubtitleOverlayRuntime.setExternalSubtitle("movie.srt")
        assertTrue(SubtitleOverlayRuntime.isRendering)
        assertEquals("movie.srt", SubtitleOverlayRuntime.externalSubtitleLabel)
        assertNull(SubtitleOverlayRuntime.embeddedTrackId)

        SubtitleOverlayRuntime.setEmbeddedSubtitle("3")
        assertTrue(SubtitleOverlayRuntime.isRendering)
        assertEquals("3", SubtitleOverlayRuntime.embeddedTrackId)
        assertNull(SubtitleOverlayRuntime.externalSubtitleLabel)

        SubtitleOverlayRuntime.clear()
        assertFalse(SubtitleOverlayRuntime.isRendering)
        assertNull(SubtitleOverlayRuntime.embeddedTrackId)
    }
}
