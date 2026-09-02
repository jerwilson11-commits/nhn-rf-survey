package com.nhnengineering.rftest.speedtest

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins how one typed URL becomes a whole measurement configuration.
 *
 * This was derived inline in the dashboard with a Cloudflare-specific string edit. Pointed at any
 * other host it produced a nonsense upload URL, so an operator could set their own server, watch
 * downloads move to it, and never notice that uploads were still going somewhere else — or
 * failing. A throughput figure whose two directions were measured against different hosts is not a
 * measurement of anything.
 */
class EndpointConfigTest {

    @Test
    fun `NHN's own endpoint derives upload and ping from the same origin`() {
        val c = SpeedTestConfig.fromDownloadUrl("https://speed.nhn.example.com/down?bytes=")

        assertEquals("https://speed.nhn.example.com/down?bytes=", c.downloadUrl)
        assertEquals("https://speed.nhn.example.com/up", c.uploadUrl)
        assertEquals("https://speed.nhn.example.com/ping", c.latencyUrl)
        assertEquals("speed.nhn.example.com", c.pingHost)
        assertEquals("speed.nhn.example.com", c.serverLabel)
    }

    @Test
    fun `Cloudflare's shape is still recognised`() {
        // The existing default has to keep working, or every saved configuration breaks.
        val c = SpeedTestConfig.fromDownloadUrl("https://speed.cloudflare.com/__down?bytes=")

        assertEquals("https://speed.cloudflare.com/__up", c.uploadUrl)
        assertEquals("speed.cloudflare.com", c.pingHost)
    }

    @Test
    fun `a non-default port is carried into the derived endpoints`() {
        // A test endpoint on a spare port is exactly how this gets tried before it is deployed
        // properly, and dropping the port would silently send uploads to port 443.
        val c = SpeedTestConfig.fromDownloadUrl("http://192.168.1.50:8080/down?bytes=")

        assertEquals("http://192.168.1.50:8080/up", c.uploadUrl)
        assertEquals("http://192.168.1.50:8080/ping", c.latencyUrl)
    }

    @Test
    fun `both directions always resolve to the same host`() {
        // The property that actually matters: a download and an upload measured against different
        // hosts describe two different paths and cannot be reported as one result.
        val bases = listOf(
            "https://speed.cloudflare.com/__down?bytes=",
            "https://speed.nhn.example.com/down?bytes=",
            "http://10.0.0.5:8099/down?bytes=",
        )
        for (base in bases) {
            val c = SpeedTestConfig.fromDownloadUrl(base)
            val dl = java.net.URL(c.downloadUrl).host
            val ul = java.net.URL(c.uploadUrl).host
            assertEquals("upload and download must share a host for $base", dl, ul)
        }
    }

    @Test
    fun `an unparseable URL falls back to the defaults rather than throwing`() {
        // Typed by hand, mid-walk, on a phone. It will be wrong sometimes, and it must not take
        // the recording down when it is.
        val c = SpeedTestConfig.fromDownloadUrl("not a url")

        assertEquals(SpeedTestConfig().downloadUrl, c.downloadUrl)
        assertEquals(SpeedTestConfig().uploadUrl, c.uploadUrl)
    }
}
