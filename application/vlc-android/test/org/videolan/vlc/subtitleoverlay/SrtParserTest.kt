package org.videolan.vlc.subtitleoverlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

class SrtParserLookupTest {

    /** Stand-in for a provider built only for cueAt() tests. */
    private fun makeProvider(cues: List<SubtitleCue>): TestableCueLookup =
            TestableCueLookup(cues.sortedBy { it.startMs })

    @Test
    fun beforeDuringAfter() {
        val p = makeProvider(listOf(SubtitleCue(1000, 3000, listOf("x"))))
        assertNull(p.cueAt(500))
        assertNotNull(p.cueAt(1000))
        assertNotNull(p.cueAt(2999))
        assertNull(p.cueAt(3000))
    }

    @Test
    fun seekForwardBackward() {
        val p = makeProvider(listOf(
                SubtitleCue(1000, 2000, listOf("A")),
                SubtitleCue(4000, 5000, listOf("B"))))
        assertEquals(listOf("A"), p.cueAt(1500)!!.lines)
        assertEquals(listOf("B"), p.cueAt(4500)!!.lines)
        assertNull(p.cueAt(2500))
    }

    @Test
    fun overlappingDeterministic() {
        val p = makeProvider(listOf(
                SubtitleCue(0, 5000, listOf("A")),
                SubtitleCue(2000, 4000, listOf("B"))))
        // two cues cover t=3000; the one starting latest wins, deterministically
        assertEquals(listOf("B"), p.cueAt(3000)!!.lines)
    }

    @Test
    fun afterReleaseNothingShows() {
        val p = makeProvider(listOf(SubtitleCue(0, 1000, listOf("A"))))
        p.release()
        assertNull(p.cueAt(500))
        assertFalse(p.isLoaded)
    }
}

/**
 * Pure-JVM stand-in mirroring [SrtSubtitleProvider]'s binary search so the
 * lookup semantics are tested without any Android dependency. The same
 * algorithm lives in the provider implementation.
 */
class TestableCueLookup(initial: List<SubtitleCue>) {
    @Volatile
    private var cues: List<SubtitleCue> = initial
    @Volatile
    private var loaded = initial.isNotEmpty()

    val isLoaded: Boolean get() = loaded

    fun cueAt(timeMs: Long): SubtitleCue? {
        val list = cues
        var lo = 0
        var hi = list.size - 1
        var candidate: SubtitleCue? = null
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = list[mid]
            when {
                c.endMs <= timeMs -> lo = mid + 1
                c.startMs > timeMs -> hi = mid - 1
                else -> {
                    if (candidate == null || c.startMs > candidate.startMs) candidate = c
                    lo = mid + 1
                }
            }
        }
        return candidate?.takeIf { it.contains(timeMs) }
    }

    fun release() {
        cues = emptyList()
        loaded = false
    }
}
