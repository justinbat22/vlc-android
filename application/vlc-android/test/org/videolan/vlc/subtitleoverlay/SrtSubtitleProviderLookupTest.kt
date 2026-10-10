package org.videolan.vlc.subtitleoverlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies the subtitle cue lookup semantics used by
 * [SrtSubtitleProvider.cueAt] and mirrored in the pure-JVM stand-in
 * [TestableCueLookup] in SrtParserTest.
 *
 * These tests are intentionally not tied to any Android dependency; they
 * exercise the ordering and timing rules that the overlay controller depends
 * on (active cue = last cue starting at or before `timeMs` that still covers
 * `timeMs`, deterministic preference for the latest-starting overlapping cue).
 */
class SrtSubtitleProviderLookupTest {

    private lateinit var lookup: TestableCueLookup

    @Before fun setUp() {
        lookup = TestableCueLookup(emptyList())
    }

    @Test fun emptyProviderReturnsNull() {
        assertNull(lookup.cueAt(0))
        assertFalse(lookup.isLoaded)
    }

    @Test fun beforeFirstCueReturnsNull() {
        lookup.setCues(listOf(SubtitleCue(1000, 3000, listOf("x"))))
        assertNull(lookup.cueAt(999))
    }

    @Test fun cueStartingAtTimeIsActive() {
        lookup.setCues(listOf(SubtitleCue(1000, 3000, listOf("A"))))
        val cue = lookup.cueAt(1000)
        assertNotNull(cue)
        assertEquals(listOf("A"), cue!!.lines)
    }

    @Test fun cueEndingAtTimeIsNotActive() {
        lookup.setCues(listOf(SubtitleCue(1000, 2000, listOf("A"))))
        assertNull(lookup.cueAt(2000))
    }

    @Test fun duringCueIsActive() {
        lookup.setCues(listOf(SubtitleCue(1000, 3000, listOf("B"))))
        assertEquals(listOf("B"), lookup.cueAt(2000)!!.lines)
    }

    @Test fun seekForwardMissBetweenCues() {
        lookup.setCues(listOf(
            SubtitleCue(1000, 2000, listOf("A")),
            SubtitleCue(4000, 5000, listOf("B"))
        ))
        assertEquals(listOf("A"), lookup.cueAt(1500)!!.lines)
        assertNull(lookup.cueAt(2500))
        assertEquals(listOf("B"), lookup.cueAt(4500)!!.lines)
    }

    @Test fun overlappingCuesPreferLatestStart() {
        lookup.setCues(listOf(
            SubtitleCue(0, 5000, listOf("A")),
            SubtitleCue(2000, 4000, listOf("B"))
        ))
        // At t=3000 both A and B cover it; B started later, so B is shown.
        assertEquals(listOf("B"), lookup.cueAt(3000)!!.lines)
    }

    @Test fun overlappingCuesWithGapStillPickLatestStart() {
        lookup.setCues(listOf(
            SubtitleCue(0, 4000, listOf("A")),
            SubtitleCue(3000, 6000, listOf("B"))
        ))
        assertEquals(listOf("B"), lookup.cueAt(3500)!!.lines)
        assertEquals(listOf("A"), lookup.cueAt(1000)!!.lines)
    }

    @Test fun untimedOrderDeterministicAcrossReload() {
        // Order of insertion into the provider does not matter; lookup uses the
        // sorted-by-start-time index, so the same ordering rule is reproduced every
        // time cues are set/reloaded.
        val cues = listOf(
            SubtitleCue(5000, 6000, listOf("second")),
            SubtitleCue(1000, 2000, listOf("first"))
        )
        lookup.setCues(cues)
        assertEquals(listOf("first"), lookup.cueAt(1500)!!.lines)
        assertEquals(listOf("second"), lookup.cueAt(5500)!!.lines)
    }

    @Test fun releaseClearsLoadedState() {
        lookup.setCues(listOf(SubtitleCue(0, 1000, listOf("A"))))
        assertTrue(lookup.isLoaded)
        lookup.release()
        assertFalse(lookup.isLoaded)
        assertNull(lookup.cueAt(500))
    }

    @Test fun singleFrameCueStillWorks() {
        lookup.setCues(listOf(SubtitleCue(1234, 1235, listOf("flash"))))
        assertEquals(listOf("flash"), lookup.cueAt(1234)!!.lines)
        assertNull(lookup.cueAt(1235))
    }

    @Test fun negativeTimesTreatedAsBeforeAnyCue() {
        lookup.setCues(listOf(SubtitleCue(0, 1000, listOf("A"))))
        assertNull(lookup.cueAt(-1))
    }

    @Test fun veryLargeTimeStillResolves() {
        lookup.setCues(listOf(SubtitleCue(1_000_000, 2_000_000, listOf("late"))))
        assertEquals(listOf("late"), lookup.cueAt(1_500_000)!!.lines)
    }

    /**
     * Stand-in for [SrtSubtitleProvider] used only by these tests. The cue lookup
     * algorithm here is intentionally kept in sync with
     * [SrtSubtitleProvider.cueAt] so this class documents the expected behaviour
     * for the JVM test suite without any Android dependency.
     */
    private class TestableCueLookup(initial: List<SubtitleCue>) {
        @Volatile
        private var cues: List<SubtitleCue> = initial.sortedBy { it.startMs }
        @Volatile
        private var loaded = initial.isNotEmpty()

        val isLoaded: Boolean get() = loaded

        fun setCues(cues: List<SubtitleCue>) {
            this.cues = cues.sortedBy { it.startMs }
            loaded = this.cues.isNotEmpty()
        }

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
}
