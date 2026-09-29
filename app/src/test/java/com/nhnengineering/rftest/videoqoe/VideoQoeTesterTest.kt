package com.nhnengineering.rftest.videoqoe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure scoring logic: the RantCell-shaped percentage formula, its Good/Fair/Poor bands,
 * and resolution-drop detection. The live ExoPlayer instrumentation in [VideoQoeTester.run] needs
 * a real player and device and is not covered here, the same way this project's other live
 * testers (SpeedTester, Ndt7Tester) aren't.
 */
class VideoQoeTesterTest {

    // ---- quality score -------------------------------------------------------------

    @Test
    fun `a clean run with no load, launch or stall time scores zero`() {
        assertEquals(0.0, qualityScorePct(0, 0, 0, 20_000)!!, 0.001)
    }

    @Test
    fun `load, launch and stall time are all summed into the score`() {
        // (500 + 300 + 200) * 100 / 20000 = 5.0
        assertEquals(5.0, qualityScorePct(500, 300, 200, 20_000)!!, 0.001)
    }

    @Test
    fun `a zero-length run has no score rather than dividing by zero`() {
        assertNull(qualityScorePct(100, 100, 0, 0))
    }

    // ---- rating bands -------------------------------------------------------------

    @Test
    fun `the RantCell-stated bands are Good 0-5, Fair 5-10, Poor above`() {
        assertEquals(VideoQoeRating.GOOD, rate(0.0))
        assertEquals(VideoQoeRating.GOOD, rate(5.0))
        assertEquals(VideoQoeRating.FAIR, rate(5.001))
        assertEquals(VideoQoeRating.FAIR, rate(10.0))
        assertEquals(VideoQoeRating.POOR, rate(10.001))
        assertEquals(VideoQoeRating.POOR, rate(50.0))
    }

    // ---- resolution drop -------------------------------------------------------------

    @Test
    fun `a steady resolution never counts as a drop`() {
        assertFalse(resolutionDropped(listOf("1280x720", "1280x720", "1280x720")))
    }

    @Test
    fun `stepping up is not a drop`() {
        assertFalse(resolutionDropped(listOf("640x360", "1280x720", "1920x1080")))
    }

    @Test
    fun `stepping down anywhere in the sequence is a drop`() {
        assertTrue(resolutionDropped(listOf("1920x1080", "1280x720")))
        // Up then down: the up doesn't excuse the later down.
        assertTrue(resolutionDropped(listOf("640x360", "1920x1080", "1280x720")))
    }

    @Test
    fun `zero or one resolution observed cannot have dropped`() {
        assertFalse(resolutionDropped(emptyList()))
        assertFalse(resolutionDropped(listOf("1280x720")))
    }

    @Test
    fun `an unparseable resolution label is ignored rather than crashing`() {
        // Malformed entries are simply skipped -- a real onVideoSizeChanged callback always hands
        // back real numbers, but the parser must not throw if that ever isn't true.
        assertFalse(resolutionDropped(listOf("garbage", "1280x720")))
    }
}
