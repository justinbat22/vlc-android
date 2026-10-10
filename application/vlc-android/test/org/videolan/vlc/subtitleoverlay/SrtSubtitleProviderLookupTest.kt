package org.videolan.vlc.subtitleoverlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the cue lookup semantics of the overlay.
 *
 * [SrtSubtitleProvider] delegates every lookup to [CueIndex], so these tests exercise
 * the production algorithm itself instead of a stand-in copy: active cue = last cue
 * starting at or before the queried time that still covers it, deterministic
 * preference for the latest-starting overlapping cue, and a long cue that overlaps
 * several shorter ones is never missed.
 */
class SrtSubtitleProviderLookupTest {

    private lateinit var lookup: CueIndex

    private fun setCues(cues: List<SubtitleCue>) {
        lookup = CueIndex(cues)
    }

    @Test fun emptyIndexReturnsNull() {
        lookup = CueIndex()
        assertNull(lookup.cueAt(0))
        assertTrue(lookup.isEmpty)
    }

    @Test fun beforeFirstCueReturnsNull() {
        setCues(listOf(SubtitleCue(1000, 3000, listOf("x"))))
        assertNull(lookup.cueAt(999))
    }

    @Test fun cueStartingAtTimeIsActive() {
        setCues(listOf(SubtitleCue(1000, 3000, listOf("A"))))
        val cue = lookup.cueAt(1000)
        assertNotNull(cue)
        assertEquals(listOf("A"), cue!!.lines)
    }

    @Test fun cueEndingAtTimeIsNotActive() {
        setCues(listOf(SubtitleCue(1000, 2000, listOf("A"))))
        assertNull(lookup.cueAt(2000))
    }

    @Test fun duringCueIsActive() {
        setCues(listOf(SubtitleCue(1000, 3000, listOf("B"))))
        assertEquals(listOf("B"), lookup.cueAt(2000)!!.lines)
    }

    @Test fun beforeDuringAfter() {
        setCues(listOf(SubtitleCue(1000, 3000, listOf("x"))))
        assertNull(lookup.cueAt(500))
        assertNotNull(lookup.cueAt(1000))
        assertNotNull(lookup.cueAt(2999))
        assertNull(lookup.cueAt(3000))
    }

    @Test fun seekForwardMissBetweenCues() {
        setCues(listOf(
            SubtitleCue(1000, 2000, listOf("A")),
            SubtitleCue(4000, 5000, listOf("B"))
        ))
        assertEquals(listOf("A"), lookup.cueAt(1500)!!.lines)
        assertNull(lookup.cueAt(2500))
        assertEquals(listOf("B"), lookup.cueAt(4500)!!.lines)
    }

    @Test fun overlappingCuesPreferLatestStart() {
        setCues(listOf(
            SubtitleCue(0, 5000, listOf("A")),
            SubtitleCue(2000, 4000, listOf("B"))
        ))
        // At t=3000 both A and B cover it; B started later, so B is shown.
        assertEquals(listOf("B"), lookup.cueAt(3000)!!.lines)
    }

    @Test fun overlappingCuesWithGapStillPickLatestStart() {
        setCues(listOf(
            SubtitleCue(0, 4000, listOf("A")),
            SubtitleCue(3000, 6000, listOf("B"))
        ))
        assertEquals(listOf("B"), lookup.cueAt(3500)!!.lines)
        assertEquals(listOf("A"), lookup.cueAt(1000)!!.lines)
    }

    @Test fun longCueOverlappingSeveralShortOnesIsNotMissed() {
        // Regression: a long cue spanning the whole file with several shorter cues
        // inside it used to be discarded as soon as one of the short cues ended
        // before the queried time.
        setCues(listOf(
            SubtitleCue(0, 100_000, listOf("long")),
            SubtitleCue(10_000, 20_000, listOf("b")),
            SubtitleCue(30_000, 40_000, listOf("c")),
            SubtitleCue(60_000, 70_000, listOf("d"))
        ))
        assertEquals(listOf("long"), lookup.cueAt(50_000)!!.lines)
        assertEquals(listOf("long"), lookup.cueAt(25_000)!!.lines)
        assertEquals(listOf("long"), lookup.cueAt(95_000)!!.lines)
        assertEquals(listOf("c"), lookup.cueAt(35_000)!!.lines)
        assertEquals(listOf("d"), lookup.cueAt(65_000)!!.lines)
        assertNull(lookup.cueAt(100_000))
    }

    @Test fun manyShortCuesUnderALongOne() {
        val cues = ArrayList<SubtitleCue>(301)
        cues.add(SubtitleCue(0, 600_000, listOf("long")))
        // 300 short cues, one every 1000 ms, with a gap between each pair
        for (i in 0 until 300) {
            val start = 1_000L + i * 2_000L
            cues.add(SubtitleCue(start, start + 1_000, listOf("short $i")))
        }
        setCues(cues)
        assertEquals(listOf("short 0"), lookup.cueAt(1_500)!!.lines)
        assertEquals(listOf("long"), lookup.cueAt(2_500)!!.lines)
        // inside the last short cue: it started later than the long one, so it wins
        assertEquals(listOf("short 299"), lookup.cueAt(599_999)!!.lines)
        // both ended: nothing is shown
        assertNull(lookup.cueAt(600_000))
    }

    @Test fun smallIndexesOfAnySizeResolveTheLastCue() {
        // size is a power of two: check a few sizes around it (including one cue over)
        for (count in listOf(1, 2, 3, 5, 8, 9, 17)) {
            val cues = (0 until count).map { i -> SubtitleCue(i * 2_000L, i * 2_000L + 1_000, listOf("c$i")) }
            val index = CueIndex(cues)
            assertEquals("count=$count", listOf("c${count - 1}"), index.cueAt((count - 1) * 2_000L + 500)!!.lines)
            assertNull("count=$count", index.cueAt((count - 1) * 2_000L + 1_000))
        }
    }

    @Test fun unsortedInputIsIndexedByStartTime() {
        // Order of insertion does not matter; lookups use the sorted index.
        setCues(listOf(
            SubtitleCue(5000, 6000, listOf("second")),
            SubtitleCue(1000, 2000, listOf("first"))
        ))
        assertEquals(2, lookup.cueCount)
        assertEquals(listOf("first"), lookup.cueAt(1500)!!.lines)
        assertEquals(listOf("second"), lookup.cueAt(5500)!!.lines)
    }

    @Test fun rebuiltIndexClearsPreviousCues() {
        setCues(listOf(SubtitleCue(0, 1000, listOf("A"))))
        assertTrue(lookup.cueCount > 0)
        setCues(emptyList())
        assertFalse(lookup.cueCount > 0)
        assertTrue(lookup.isEmpty)
        assertNull(lookup.cueAt(500))
    }

    @Test fun singleFrameCueStillWorks() {
        setCues(listOf(SubtitleCue(1234, 1235, listOf("flash"))))
        assertEquals(listOf("flash"), lookup.cueAt(1234)!!.lines)
        assertNull(lookup.cueAt(1235))
    }

    @Test fun negativeTimesTreatedAsBeforeAnyCue() {
        setCues(listOf(SubtitleCue(0, 1000, listOf("A"))))
        assertNull(lookup.cueAt(-1))
    }

    @Test fun veryLargeTimeStillResolves() {
        setCues(listOf(SubtitleCue(1_000_000, 2_000_000, listOf("late"))))
        assertEquals(listOf("late"), lookup.cueAt(1_500_000)!!.lines)
    }
}
