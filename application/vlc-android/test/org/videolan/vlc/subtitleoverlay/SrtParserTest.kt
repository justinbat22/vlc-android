package org.videolan.vlc.subtitleoverlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class SrtParserTest {

    private fun parse(content: String, charset: java.nio.charset.Charset = StandardCharsets.UTF_8) =
            SrtParser.parse(ByteArrayInputStream(content.toByteArray(charset)), "track-1")

    @Test
    fun standardCue() {
        val r = parse("1\n00:00:01,000 --> 00:00:02,000\nHello\n")
        assertEquals(1, r.cues.size)
        val cue = r.cues[0]
        assertEquals(1000L, cue.startMs)
        assertEquals(2000L, cue.endMs)
        assertEquals(listOf("Hello"), cue.lines)
        assertEquals("track-1", cue.trackId)
    }

    @Test
    fun multilineCue() {
        val r = parse("1\n00:00:01,000 --> 00:00:02,000\nHello\nWorld\n")
        assertEquals(listOf("Hello", "World"), r.cues[0].lines)
    }

    @Test
    fun crlf() {
        val r = parse("1\r\n00:00:01,000 --> 00:00:02,000\r\nHi\r\n", StandardCharsets.UTF_8)
        assertEquals(1, r.cues.size)
        assertEquals(listOf("Hi"), r.cues[0].lines)
    }

    @Test
    fun dotMilliseconds() {
        val r = parse("1\n00:00:01.250 --> 00:00:02.750\nHi\n")
        assertEquals(1250L, r.cues[0].startMs)
        assertEquals(2750L, r.cues[0].endMs)
    }

    @Test
    fun unicodeText() {
        val r = parse("1\n00:00:01,000 --> 00:00:02,000\nHéllo Café 测试\n")
        assertEquals(listOf("Héllo Café 测试"), r.cues[0].lines)
    }

    @Test
    fun malformedTimestampSkipped() {
        val r = parse("1\nnothing here\nbad\n\n2\n00:00:01,000 --> 00:00:02,000\nOK\n")
        assertEquals(1, r.cues.size)
        assertEquals(listOf("OK"), r.cues[0].lines)
    }

    @Test
    fun missingTrailingNewline() {
        val r = parse("1\n00:00:01,000 --> 00:00:02,000\nend")
        assertEquals(1, r.cues.size)
    }

    @Test
    fun emptyFile() {
        assertEquals(0, parse("").cues.size)
        assertEquals(0, parse("\n\n\n").cues.size)
    }

    @Test
    fun overlappingCuesBothKept() {
        val r = parse(
                "1\n00:00:05,000 --> 00:00:10,000\nlater\n\n" +
                "2\n00:00:01,000 --> 00:00:08,000\nearlier\n")
        assertEquals(2, r.cues.size)
    }

    @Test
    fun markupStripped() {
        val r = parse("1\n00:00:01,000 --> 00:00:02,000\n<i>bold</i> <b>text</b>\n")
        assertEquals(listOf("bold text"), r.cues[0].lines)
    }

    @Test
    fun largeCueCountSmoke() {
        val builder = StringBuilder()
        for (i in 0 until 5000) {
            val t = i * 2000L
            val ts = "00:%02d:%02d,%03d".format(t / 60000 % 60, t / 1000 % 60, t % 1000)
            builder.append(i).append('\n')
                    .append(ts).append(" --> ")
                    .append("00:%02d:%02d,%03d".format((t + 1000) / 60000 % 60, (t + 1000) / 1000 % 60, (t + 1000) % 1000))
                    .append('\n').append("cue ").append(i).append("\n\n")
        }
        val r = parse(builder.toString())
        assertEquals(5000, r.cues.size)
    }

    @Test
    fun containsBoundary() {
        val c = SubtitleCue(1000, 2000, listOf("x"))
        assertTrue(c.contains(1000))
        assertTrue(c.contains(1999))
        assertFalse(c.contains(2000))
    }
}
