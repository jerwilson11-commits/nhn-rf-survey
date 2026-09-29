package com.nhnengineering.rftest.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure pieces of [AutomationRunner]: reading `ping`'s own summary line, the step labels
 * used to identify a result, and that a script preserves the order it was assembled in. The actual
 * probes (ping, HTTP, throughput, FTP) each need a live network and are covered by their own
 * class's existing tests where that class has one ([FtpClientTest], [SpeedTester]'s lack of one).
 */
class AutomationRunnerTest {

    // ---- ping summary -------------------------------------------------------------

    @Test
    fun `loss and average round-trip time are read from one ping run`() {
        val output = """
            PING 8.8.8.8 (8.8.8.8) 56(84) bytes of data.
            64 bytes from 8.8.8.8: icmp_seq=1 ttl=115 time=11.2 ms
            64 bytes from 8.8.8.8: icmp_seq=2 ttl=115 time=12.8 ms

            --- 8.8.8.8 ping statistics ---
            2 packets transmitted, 2 received, 0% packet loss, time 1002ms
            rtt min/avg/max/mdev = 11.200/12.000/12.800/0.800 ms
        """.trimIndent()

        assertEquals("loss 0%, avg 12.000 ms", formatPingSummary(output))
    }

    @Test
    fun `total loss still reports a summary rather than nothing`() {
        val output = """
            --- 10.0.0.99 ping statistics ---
            4 packets transmitted, 0 received, 100% packet loss, time 3050ms
        """.trimIndent()

        val summary = formatPingSummary(output)!!
        assertTrue(summary.contains("loss 100%"))
        assertTrue(summary.contains("avg ?")) // no rtt line when nothing came back
    }

    @Test
    fun `output with no packet-loss summary at all is null, not a crash`() {
        assertNull(formatPingSummary("permission denied"))
        assertNull(formatPingSummary(""))
    }

    // ---- step labels -------------------------------------------------------------

    @Test
    fun `each step type names its own target in its label`() {
        assertEquals("Ping 1.1.1.1", AutomationStep.Ping("1.1.1.1").label)
        assertEquals("HTTP GET https://example.com", AutomationStep.HttpGet("https://example.com").label)
        assertEquals("FTP upload to ftp.example.com", AutomationStep.Ftp(FtpConfig(host = "ftp.example.com")).label)
    }

    // ---- script assembly -------------------------------------------------------------

    @Test
    fun `a script keeps its steps in the order they were given`() {
        val script = AutomationScript(
            listOf(AutomationStep.Ping("a"), AutomationStep.HttpGet("https://b"), AutomationStep.Ping("c")),
        )
        assertEquals(listOf("Ping a", "HTTP GET https://b", "Ping c"), script.steps.map { it.label })
    }
}
