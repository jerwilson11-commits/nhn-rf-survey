package com.nhnengineering.rftest.modem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the lookup that replaced a hardcoded port.
 *
 * The Network Access Service answered on port 86 one morning and port 88 after a reboot the same
 * afternoon. Nothing about the handset changed; QRTR simply assigns ports at registration. The
 * hardcoded version failed in the worst available way — the modem answered, so the transport
 * looked healthy, and only the QMI result said the request had gone somewhere else.
 */
class QrtrServicesTest {

    /** Trimmed from the real listing, both reboots, plus the noise either side. */
    private val listing = """
          Service Version Instance Node  Port
               43       2       18    0     1 Subsystem control service
             4097     N/A        1    0    34 DIAG service (MODEM:CMD)
             4097     N/A        3    0    38 DIAG service (MODEM:DCI_CMD)
                3       1        0    0    88 Network Access Service
               10       2        0    0    80 Card Application Toolkit service (v2)
                1       1        0    0    91 Wireless Data Service
             4097     N/A        0    1 16445 DIAG service (MODEM:CNTL)
    """.trimIndent()

    @Test
    fun `the Network Access Service is found at whatever port it registered on`() {
        assertEquals(QrtrServices.Address(0, 88), QrtrServices.find(listing))
    }

    @Test
    fun `the morning listing resolves to the port it had then`() {
        // The same handset, the same service, a different port. This is the whole point.
        val morning = listing.replace("    0    88 Network Access", "    0    86 Network Access")

        assertEquals(QrtrServices.Address(0, 86), QrtrServices.find(morning))
    }

    @Test
    fun `it matches on the service id, not the display name`() {
        // Vendor builds print "<unknown>" for services they have no name for. The id is the
        // protocol; the name is decoration.
        val unnamed = listing.replace("Network Access Service", "<unknown>")

        assertEquals(QrtrServices.Address(0, 88), QrtrServices.find(unnamed))
    }

    @Test
    fun `another service is not mistaken for it`() {
        // Wireless Data Service is id 1 and sits two rows away. Matching loosely on any row
        // with a plausible shape would pick up whichever came first.
        assertEquals(QrtrServices.Address(0, 91), QrtrServices.find(listing, service = 1))
        assertEquals(QrtrServices.Address(0, 34), QrtrServices.find(listing, service = 4097))
    }

    @Test
    fun `a service that is not listed returns nothing rather than a guess`() {
        assertNull(QrtrServices.find(listing, service = 999))
    }

    @Test
    fun `the lowest node wins when a service is listed more than once`() {
        // DIAG appears on both nodes. The local modem is node 0 on this platform, and picking
        // arbitrarily would send requests to a different processor -- the mistake that cost a
        // day earlier in this project.
        assertEquals(0, QrtrServices.find(listing, service = 4097)?.node)
    }

    @Test
    fun `empty or unparseable output yields nothing`() {
        assertNull(QrtrServices.find(""))
        assertNull(QrtrServices.find("qrtr-lookup: command not found"))
        assertNull(QrtrServices.find("Service Version Instance Node  Port"))
    }
}
