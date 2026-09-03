package com.nhnengineering.rftest.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the stabiliser against the behaviour that prompted it.
 *
 * Reported from the field: a stable signal whose verdict "sporadically bounces to poor". Measured
 * on a stationary handset the same day: SS-RSRP spanning −88 to −95 dBm untouched on a desk. The
 * bouncing was in the presentation, and these tests hold the fix to two promises — that it stops
 * the flapping, and that it never invents stability the measurement does not have.
 */
class VerdictStabiliserTest {

    /** The real 50-sample stationary trace, captured 2026-09-02 with the phone on a desk. */
    private val stationaryTrace = listOf(
        -89, -92, -92, -95, -95, -95, -95, -89, -89, -89, -92, -92, -88, -88, -88, -88, -88,
        -88, -88, -88, -91, -91, -91, -91, -91, -91, -91, -88, -88, -88, -88, -88, -91, -91,
        -91, -91, -91, -88, -88, -88, -88, -88, -91, -91, -91, -91, -91, -91, -91, -91,
    )

    @Test
    fun `a single anomalous sample does not change the verdict`() {
        // The case the operator actually saw: one bad reading in an otherwise steady stream.
        val s = VerdictStabiliser()
        repeat(8) { s.update(-88, 20) }
        val before = s.update(-88, 20).severity

        val during = s.update(-118, -7).severity

        assertEquals("one outlier must not flip the conclusion", before, during)
    }

    @Test
    fun `a sustained change is shown, just not instantly`() {
        // The stabiliser must not become a filter that hides real degradation.
        val s = VerdictStabiliser()
        repeat(10) { s.update(-85, 20) }
        assertEquals(Verdict.Severity.GOOD, s.update(-85, 20).severity)

        var last = Verdict.Severity.GOOD
        repeat(12) { last = s.update(-118, -5).severity }

        assertEquals("a real drop must eventually be reported", Verdict.Severity.POOR, last)
    }

    @Test
    fun `the real stationary trace produces no verdict changes`() {
        // The trace crosses -95, the good/marginal boundary, four times. Unstabilised that is a
        // visible flip on a phone that never moved.
        val s = VerdictStabiliser()
        val seen = stationaryTrace.map { s.update(it, 20).severity }

        assertEquals(
            "a stationary phone must produce exactly one verdict",
            1,
            seen.toSet().size,
        )
        assertEquals(Verdict.Severity.GOOD, seen.first())
    }

    @Test
    fun `the real trace does not itself flap, and the record says so`() {
        // Worth stating plainly, because the first version of this test asserted the opposite and
        // failed. The captured trace bottoms out at exactly -95, which is still inside the good
        // band, so it never crosses. **This trace is not what the operator saw flapping** -- it
        // shows 7 dB of movement sitting hard against a boundary, which is the conditions for
        // flapping rather than the flapping itself.
        val raw = stationaryTrace.map { Verdict.cellular(it, 20).severity }.toSet()

        assertEquals("the trace stays inside one band even unstabilised", 1, raw.size)
    }

    @Test
    fun `a trace straddling the boundary flaps raw and is steady stabilised`() {
        // One dB either side of the good/marginal edge, which is well inside the 7 dB of movement
        // measured on a stationary phone. This is the mechanism.
        val straddling = listOf(-94, -96, -94, -96, -96, -94, -94, -96, -94, -96)

        fun transitions(v: List<Verdict.Severity>) = v.zipWithNext().count { (a, b) -> a != b }

        val raw = straddling.map { Verdict.cellular(it, 20).severity }
        val stabilised = VerdictStabiliser().let { s -> straddling.map { s.update(it, 20).severity } }

        // Raw flips on almost every sample. The stabiliser is not asked to pin a value that
        // genuinely straddles a boundary -- when half the samples really are below it, reporting
        // the worse band is correct -- only to stop the flapping and settle.
        assertTrue("raw must flap, or there is nothing to fix", transitions(raw) >= 5)
        assertTrue(
            "stabilised must settle, got ${transitions(stabilised)} changes",
            transitions(stabilised) <= 1,
        )
    }

    @Test
    fun `a single negative SINR spike does not flip a good reading to poor`() {
        // The likeliest cause of what was actually reported: SINR momentarily negative flips
        // "good coverage" straight to "strong but noisy", which is a POOR severity.
        val s = VerdictStabiliser()
        repeat(8) { s.update(-88, 20) }

        val spike = s.update(-88, -7).severity

        assertEquals(Verdict.Severity.GOOD, spike)
        // And unstabilised it would indeed have flipped, which is the point.
        assertEquals(Verdict.Severity.POOR, Verdict.cellular(-88, -7).severity)
    }

    @Test
    fun `loss of service is reported immediately, not held`() {
        // The one lag that would matter: showing a remembered "good coverage" after service has
        // actually gone. Everything else can wait a second; this cannot.
        val s = VerdictStabiliser()
        repeat(10) { s.update(-85, 20) }

        val v = s.update(null, null)

        assertEquals(Verdict.Severity.UNKNOWN, v.severity)
    }

    @Test
    fun `the median ignores an extreme outlier rather than being dragged by it`() {
        // A mean would let one absurd sample move the answer in proportion to how wrong it is,
        // which is exactly backwards.
        val s = VerdictStabiliser(windowSize = 5)
        listOf(-90, -90, -90, -90).forEach { s.update(it, 20) }
        s.update(-140, 20)

        assertEquals(-90, s.medianDbm)
    }

    @Test
    fun `the spread reports real variability rather than hiding it`() {
        // The point is to steady the conclusion, not to pretend the signal is steady. The screen
        // still gets to say how much the reading actually moved.
        val s = VerdictStabiliser(windowSize = 8)
        listOf(-88, -95, -91, -92).forEach { s.update(it, 20) }

        assertEquals(7, s.spreadDb)
    }

    @Test
    fun `wording may refine within a severity without waiting for confirmation`() {
        // A change of words inside the same severity is a refinement of the same conclusion, not a
        // contradiction, so it should not be held back.
        val s = VerdictStabiliser()
        repeat(8) { s.update(-80, 20) }

        // Same severity band, different level: the detail may update immediately.
        val v = s.update(-90, 20)

        assertEquals(Verdict.Severity.GOOD, v.severity)
    }

    @Test
    fun `reset clears the history so a new session does not inherit the last one`() {
        val s = VerdictStabiliser()
        repeat(8) { s.update(-85, 20) }
        s.reset()

        assertEquals(null, s.medianDbm)
        assertEquals(null, s.spreadDb)
        // First sample after a reset is shown immediately rather than confirmed against history.
        assertEquals(Verdict.Severity.POOR, s.update(-120, -5).severity)
    }
}
