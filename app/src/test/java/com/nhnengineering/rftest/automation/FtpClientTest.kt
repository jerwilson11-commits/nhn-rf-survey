package com.nhnengineering.rftest.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins [parsePasvReply] against real-shaped `227` replies. The control/data socket exchange itself
 * needs a live FTP server and is not covered here, the same way [SpeedTester]'s HTTP calls aren't.
 */
class FtpClientTest {

    @Test
    fun `a standard PASV reply decodes to host and port`() {
        val (host, port) = parsePasvReply("227 Entering Passive Mode (192,168,1,50,200,21)")!!

        assertEquals("192.168.1.50", host)
        // 200*256 + 21
        assertEquals(51221, port)
    }

    @Test
    fun `the port bytes combine high-then-low, not the other way round`() {
        // A port under 256 makes the ordering bug visible: get it backwards and this reads as 0.
        val (_, port) = parsePasvReply("227 PASV ok (10,0,0,1,0,21)")!!

        assertEquals(21, port)
    }

    @Test
    fun `text before or after the tuple does not confuse the parser`() {
        val (host, _) = parsePasvReply(
            "227 Entering Passive Mode, please use this address (203,0,113,9,15,160).",
        )!!
        assertEquals("203.0.113.9", host)
    }

    @Test
    fun `a reply with no tuple at all is null, not a crash`() {
        assertNull(parsePasvReply("500 Command not understood"))
        assertNull(parsePasvReply(""))
    }
}
