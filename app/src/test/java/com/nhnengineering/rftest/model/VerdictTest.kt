package com.nhnengineering.rftest.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the plain-language verdict.
 *
 * This is the one place the app speaks in conclusions rather than numbers, so it is the one place a
 * wrong answer is invisible to the person least able to catch it. An engineer reading "−104 dBm"
 * knows what they are looking at; an executive reading "Good coverage" over a marginal reading has
 * been told something false and has no way to know.
 */
class VerdictTest {

    @Test
    fun `strong signal with clean quality reads as good`() {
        val v = Verdict.cellular(rsrpDbm = -80, sinrDb = 22)

        assertEquals(Verdict.Severity.GOOD, v.severity)
        assertFalse(v.interferenceLimited)
    }

    @Test
    fun `strong signal with poor quality is called out as interference, not coverage`() {
        // The distinction that earns this feature its place. A signal-strength bar shows this as
        // full bars; the remediation is the opposite of adding coverage.
        val v = Verdict.cellular(rsrpDbm = -80, sinrDb = -3)

        assertTrue("must be flagged interference-limited", v.interferenceLimited)
        assertEquals(Verdict.Severity.POOR, v.severity)
        assertTrue(
            "the advice must say adding coverage will not help: ${v.detail}",
            v.detail.contains("not help"),
        )
    }

    @Test
    fun `weak signal with clean quality is called a coverage gap, not interference`() {
        // Same severity band as the case above by level alone, opposite diagnosis.
        val v = Verdict.cellular(rsrpDbm = -103, sinrDb = 20)

        assertFalse("clean quality is not interference", v.interferenceLimited)
        assertTrue(
            "the advice must point at coverage: ${v.detail}",
            v.detail.contains("More signal") || v.detail.contains("coverage"),
        )
    }

    @Test
    fun `the two failure modes are distinguishable at the same severity`() {
        // -80/-3 and -103/20 would look identical on any signal bar. They must not read the same
        // here, because they lead to different remediation.
        val noisy = Verdict.cellular(-80, -3)
        val distant = Verdict.cellular(-103, 20)

        assertTrue(noisy.headline != distant.headline)
        assertTrue(noisy.interferenceLimited && !distant.interferenceLimited)
    }

    @Test
    fun `a missing SINR speaks to coverage only rather than assuming zero`() {
        // Treating an absent quality reading as 0 dB would turn every unreported sample into an
        // interference finding -- the same null-as-zero mistake this project has now made twice.
        val v = Verdict.cellular(rsrpDbm = -80, sinrDb = null)

        assertEquals(Verdict.Severity.GOOD, v.severity)
        assertFalse(v.interferenceLimited)
        assertTrue(
            "must say the verdict covers level only: ${v.detail}",
            v.detail.contains("coverage only"),
        )
    }

    @Test
    fun `no serving cell is unknown, not poor`() {
        // "No reading" and "bad reading" are different findings, and only one of them is about the
        // network. A phone still registering must not be reported as a coverage failure.
        val v = Verdict.cellular(rsrpDbm = null, sinrDb = null)

        assertEquals(Verdict.Severity.UNKNOWN, v.severity)
    }

    @Test
    fun `severity worsens monotonically as level falls at constant quality`() {
        val order = listOf(-75, -90, -100, -112, -125).map { Verdict.cellular(it, 20).severity }
        val rank = mapOf(
            Verdict.Severity.GOOD to 0,
            Verdict.Severity.FAIR to 1,
            Verdict.Severity.POOR to 2,
            Verdict.Severity.UNKNOWN to 99,
        )
        for (i in 1 until order.size) {
            assertTrue(
                "severity must not improve as signal falls: ${order[i - 1]} -> ${order[i]}",
                rank.getValue(order[i]) >= rank.getValue(order[i - 1]),
            )
        }
    }

    @Test
    fun `verdicts never promise a speed or a percentage`() {
        // The measurement cannot support either -- both depend on load, configuration and the far
        // end. Overclaiming here would spend the credibility the rest of the app is built on.
        val samples = listOf(
            Verdict.cellular(-70, 25), Verdict.cellular(-80, -3), Verdict.cellular(-103, 20),
            Verdict.cellular(-120, -5), Verdict.wifi(-55, 0), Verdict.wifi(-80, 5),
        )
        for (v in samples) {
            val text = v.headline + " " + v.detail
            assertFalse("must not quote Mbps: $text", text.contains("Mbps"))
            assertFalse("must not quote a percentage: $text", text.contains("%"))
        }
    }

    @Test
    fun `a strong AP on a crowded channel is congestion, not coverage`() {
        val v = Verdict.wifi(rssiDbm = -55, coChannelCount = 4)

        assertTrue(v.interferenceLimited)
        assertTrue(
            "must point at the channel rather than the access point: ${v.detail}",
            v.detail.contains("channel"),
        )
    }

    @Test
    fun `a strong AP on a clear channel is simply good`() {
        val v = Verdict.wifi(rssiDbm = -55, coChannelCount = 0)

        assertEquals(Verdict.Severity.GOOD, v.severity)
        assertFalse(v.interferenceLimited)
    }

    @Test
    fun `no Wi-Fi connection is unknown rather than a failure`() {
        assertEquals(Verdict.Severity.UNKNOWN, Verdict.wifi(null, null).severity)
    }
}
