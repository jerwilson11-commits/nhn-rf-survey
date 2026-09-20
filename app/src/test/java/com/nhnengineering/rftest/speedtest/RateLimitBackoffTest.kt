package com.nhnengineering.rftest.speedtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the rate-limit response that shipped broken and never ran.
 *
 * On the walk of 2026-09-20 the public endpoint returned HTTP 429 to eight consecutive download
 * bursts. The session recorded the words "rate-limited" on every one of those rows, so the report
 * was correct -- and the app kept firing at an unchanged 41-second cadence the whole time, because
 * the backoff was keyed on an exception type that had already been discarded.
 *
 * `SpeedTester.transfer` collected each stream's failure as `it.message` and rethrew the text via
 * `error(...)`, an IllegalStateException. `WalkThroughput` asked `it is SpeedTester.RateLimited`,
 * got false, and reset the backoff to zero on every burst. The human-readable half of the system
 * worked perfectly and the machine-readable half was silently absent -- which is the harder failure
 * to notice, because every artefact a person looked at said the right thing.
 *
 * These tests are on the two pure functions the behaviour now goes through, so the progression can
 * be checked without an endpoint to be refused by.
 */
class RateLimitBackoffTest {

    // ---- which failure gets reported --------------------------------------

    @Test
    fun `a rate limit outranks other failures whatever order they arrived in`() {
        val limited = SpeedTester.RateLimited("download rate-limited by example (HTTP 429)")
        val reset = java.io.IOException("connection reset")

        assertSame(limited, pickFailure(listOf(reset, limited)))
        assertSame(limited, pickFailure(listOf(limited, reset)))
    }

    @Test
    fun `the throwable is returned, not its message`() {
        // The distinction this whole fix exists for: a caller has to be able to ask what kind of
        // failure it was, and a String cannot answer that.
        val picked = pickFailure(listOf(SpeedTester.RateLimited("429")))
        assertTrue(picked is SpeedTester.RateLimited)
    }

    @Test
    fun `an ordinary failure is reported when nothing was rate-limited`() {
        val first = java.io.IOException("connection reset")
        assertSame(first, pickFailure(listOf(first, java.io.IOException("broken pipe"))))
    }

    @Test
    fun `no failures means nothing to report`() {
        assertNull(pickFailure(emptyList()))
    }

    // ---- how far apart the bursts land ------------------------------------

    @Test
    fun `backoff doubles from the step and stops at the ceiling`() {
        var b = 0L
        val seen = mutableListOf<Long>()
        repeat(6) {
            b = nextBackoffMs(b, rateLimited = true, stepMs = 60_000, maxMs = 300_000)
            seen += b
        }

        assertEquals(listOf(60_000L, 120_000L, 240_000L, 300_000L, 300_000L, 300_000L), seen)
    }

    @Test
    fun `a clean burst clears the backoff`() {
        val after = nextBackoffMs(240_000, rateLimited = false, stepMs = 60_000, maxMs = 300_000)
        assertEquals(0L, after)
    }

    @Test
    fun `eight consecutive rate limits back away instead of hammering`() {
        // The walk that exposed this: eight 429s in a row. Gap is the configured idle time plus
        // whatever backoff has accumulated.
        var b = 0L
        val gaps = (1..8).map {
            b = nextBackoffMs(b, rateLimited = true, stepMs = 60_000, maxMs = 300_000)
            30_000 + b
        }

        assertEquals(
            listOf(90_000L, 150_000L, 270_000L, 330_000L, 330_000L, 330_000L, 330_000L, 330_000L),
            gaps,
        )
        assertTrue("Each gap must be at least as long as the one before it.",
            gaps.zipWithNext().all { (a, b2) -> b2 >= a })
    }

    @Test
    fun `the behaviour that actually shipped kept the cadence flat`() {
        // What the lost exception type produced: rateLimited false on every burst, so the gap never
        // moved off the configured interval however many times the endpoint refused us. Kept as a
        // test so the regression is described rather than remembered.
        var b = 0L
        val gaps = (1..8).map {
            b = nextBackoffMs(b, rateLimited = false, stepMs = 60_000, maxMs = 300_000)
            30_000 + b
        }

        assertEquals(List(8) { 30_000L }, gaps)
    }
}
