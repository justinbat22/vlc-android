package org.videolan.vlc.subtitleoverlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleSizingTest {

    // The values of R.array.subtitles_size_values, nano .. huge
    private val settings = listOf("40", "32", "25", "19", "16", "13", "10")

    private val phoneShortSide = 1080f

    @Test
    fun `a larger setting value is smaller text, as in libvlc`() {
        val sizes = settings.map { SubtitleSizing.textSizePx(it, phoneShortSide) }
        // nano < micro < smallest < small < normal < big < huge, strictly
        for (i in 0 until sizes.size - 1) assertTrue("${settings[i]} vs ${settings[i + 1]}", sizes[i] < sizes[i + 1])
    }

    @Test
    fun `every one of the seven sizes is distinct, huge included`() {
        // "10" (huge) used to fall into the catch-all of the old mapping
        assertEquals(7, settings.map { SubtitleSizing.textSizePx(it, phoneShortSide) }.toSet().size)
    }

    @Test
    fun `the text height is the short side divided by the setting`() {
        assertEquals(1080f / 16, SubtitleSizing.textSizePx("16", 1080f), 0.001f)
        assertEquals(1080f / 40, SubtitleSizing.textSizePx("40", 1080f), 0.001f)
        assertEquals(1080f / 10, SubtitleSizing.textSizePx("10", 1080f), 0.001f)
    }

    @Test
    fun `the default size is what the overlay always had`() {
        // 411dp wide phone at 2.625 density: 25.7dp, the 26dp of the old "normal"
        assertEquals(26f, SubtitleSizing.textSizePx("16", 411f * 2.625f) / 2.625f, 0.5f)
    }

    @Test
    fun `an unusable setting falls back to normal`() {
        val normal = SubtitleSizing.textSizePx("16", phoneShortSide)
        for (bad in listOf(null, "", " ", "abc", "0", "-5", "3", "101", "1e3")) {
            assertEquals("'$bad'", normal, SubtitleSizing.textSizePx(bad, phoneShortSide), 0f)
        }
        assertEquals(16, SubtitleSizing.divisorOf(" 16 "))
    }

    @Test
    fun `tiny containers never give an unreadable size`() {
        assertEquals(8f, SubtitleSizing.textSizePx("40", 100f), 0f)
    }
}
