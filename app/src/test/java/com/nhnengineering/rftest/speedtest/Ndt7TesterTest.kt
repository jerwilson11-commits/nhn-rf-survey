package com.nhnengineering.rftest.speedtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure logic in [Ndt7Tester]: parsing a real-shaped locate response, the sender's message
 * ramp, and reading `ping`'s own output. The WebSocket transfer and the locate/ping network calls
 * themselves are not covered here for the same reason [SpeedTester] isn't -- they need a live
 * network, and this suite runs offline.
 */
class Ndt7TesterTest {

    // ---- locate response -------------------------------------------------------

    private val twoServerResponse = """
        {
          "results": [
            {
              "machine": "ndt-mlab1-mia03.mlab-oti.measurement-lab.org",
              "location": {"city": "Miami", "country": "US"},
              "urls": {
                "https:///ndt/v7/download": "https://ndt-mlab1-mia03.mlab-oti.measurement-lab.org/ndt/v7/download?access_token=abc",
                "https:///ndt/v7/upload": "https://ndt-mlab1-mia03.mlab-oti.measurement-lab.org/ndt/v7/upload?access_token=abc",
                "wss:///ndt/v7/download": "wss://ndt-mlab1-mia03.mlab-oti.measurement-lab.org/ndt/v7/download?access_token=abc",
                "wss:///ndt/v7/upload": "wss://ndt-mlab1-mia03.mlab-oti.measurement-lab.org/ndt/v7/upload?access_token=abc"
              }
            },
            {
              "machine": "ndt-mlab2-atl06.mlab-oti.measurement-lab.org",
              "location": {"city": "Atlanta", "country": "US"},
              "urls": {
                "wss:///ndt/v7/download": "wss://ndt-mlab2-atl06.mlab-oti.measurement-lab.org/ndt/v7/download?access_token=def",
                "wss:///ndt/v7/upload": "wss://ndt-mlab2-atl06.mlab-oti.measurement-lab.org/ndt/v7/upload?access_token=def"
              }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `candidates come back in the order the locate service returned them`() {
        val servers = parseLocateServers(twoServerResponse)

        assertEquals(2, servers.size)
        assertEquals("ndt-mlab1-mia03.mlab-oti.measurement-lab.org", servers[0].machine)
        assertEquals("Miami", servers[0].city)
        assertEquals("ndt-mlab2-atl06.mlab-oti.measurement-lab.org", servers[1].machine)
    }

    @Test
    fun `the label carries the city so the report says where the test actually ran`() {
        val servers = parseLocateServers(twoServerResponse)

        assertEquals("ndt-mlab1-mia03.mlab-oti.measurement-lab.org (Miami)", servers[0].label)
    }

    @Test
    fun `host is read from the download URL, not guessed from the machine name`() {
        val servers = parseLocateServers(twoServerResponse)

        assertEquals("ndt-mlab1-mia03.mlab-oti.measurement-lab.org", servers[0].host)
    }

    @Test
    fun `a candidate missing either websocket URL is dropped, not kept with a null`() {
        val onlyDownload = """
            {"results": [{"machine": "x", "urls": {"wss:///ndt/v7/download": "wss://x/ndt/v7/download"}}]}
        """.trimIndent()

        assertTrue(parseLocateServers(onlyDownload).isEmpty())
    }

    @Test
    fun `no results and unparseable JSON both come back empty rather than throwing`() {
        assertTrue(parseLocateServers("""{"results": []}""").isEmpty())
        assertTrue(parseLocateServers("not json").isEmpty())
        assertTrue(parseLocateServers("").isEmpty())
    }

    // ---- host extraction --------------------------------------------------------

    @Test
    fun `hostOf reads the host out of a wss URL that java-net-URL cannot parse at all`() {
        assertEquals(
            "ndt-mlab1-mia03.mlab-oti.measurement-lab.org",
            hostOf("wss://ndt-mlab1-mia03.mlab-oti.measurement-lab.org/ndt/v7/download?access_token=abc"),
        )
    }

    @Test
    fun `hostOf on garbage is null, not a crash`() {
        assertNull(hostOf("not a url"))
    }

    // ---- sender ramp --------------------------------------------------------------

    @Test
    fun `the message size doubles from the ndt7 minimum`() {
        assertEquals(1 shl 14, nextNdt7MessageSize(1 shl 13))
        assertEquals(1 shl 15, nextNdt7MessageSize(1 shl 14))
    }

    @Test
    fun `the message size is capped at 1 MiB rather than growing forever`() {
        assertEquals(1 shl 20, nextNdt7MessageSize(1 shl 20))
        assertEquals(1 shl 20, nextNdt7MessageSize(1 shl 19))
    }

    // ---- ping output --------------------------------------------------------------

    private val pingSample = """
        PING speed.example.com (203.0.113.5) 56(84) bytes of data.
        64 bytes from 203.0.113.5: icmp_seq=1 ttl=55 time=9.87 ms
        64 bytes from 203.0.113.5: icmp_seq=2 ttl=55 time=11.20 ms
        64 bytes from 203.0.113.5: icmp_seq=3 ttl=55 time=10.05 ms

        --- speed.example.com ping statistics ---
        3 packets transmitted, 3 received, 0% packet loss, time 2003ms
        rtt min/avg/max/mdev = 9.870/10.373/11.200/0.578 ms
    """.trimIndent()

    @Test
    fun `every round-trip time is read from one ping run, not opened as separate connections`() {
        val r = parsePingOutput(pingSample)

        assertEquals(10.05, r.medianMs!!, 0.001)
        assertEquals(9.87, r.minMs!!, 0.001)
        assertEquals(11.20, r.maxMs!!, 0.001)
        assertEquals(0.0, r.lossPct!!, 0.001)
    }

    @Test
    fun `loss is read from the same run a lost packet actually shows up in`() {
        val withLoss = """
            64 bytes from 203.0.113.5: icmp_seq=1 ttl=55 time=9.87 ms
            --- speed.example.com ping statistics ---
            5 packets transmitted, 4 received, 20% packet loss, time 4010ms
        """.trimIndent()

        assertEquals(20.0, parsePingOutput(withLoss).lossPct!!, 0.001)
    }

    @Test
    fun `unparseable ping output comes back as all-null rather than a crash`() {
        val r = parsePingOutput("permission denied")

        assertNull(r.medianMs)
        assertNull(r.lossPct)
    }
}
