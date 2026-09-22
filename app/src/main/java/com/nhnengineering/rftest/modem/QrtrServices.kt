package com.nhnengineering.rftest.modem

/**
 * Finds a QMI service's address on QRTR, because the address is not fixed.
 *
 * ## Why this exists
 *
 * QRTR ports are assigned when a service registers, not defined by it. The Network Access
 * Service answered on port 86 on 2026-09-22 morning and on port 88 after a reboot the same
 * afternoon, with nothing else changed. Code that hardcodes the port works until the next boot
 * and then quietly reads nothing:
 *
 * ```
 * $ qmi_probe 0 86 0034        # yesterday's port
 * TLV 0x02 result: FAILURE (result=1 error=5)
 * ```
 *
 * That failure is silent in the worst way — the modem answers, so the transport looks healthy,
 * and only the payload says it went to the wrong service. The app carried this for several
 * reboots without noticing, because the NR neighbour path comes from a different channel and was
 * enough to make the sample look populated.
 *
 * The service *id* is stable (3 is NAS); only the port moves. So the address is resolved by
 * asking, every time availability is established.
 */
object QrtrServices {

    /** QMI Network Access Service. */
    const val SERVICE_NAS = 3

    data class Address(val node: Int, val port: Int)

    /**
     * Parses `qrtr-lookup` output and returns where [service] is listening.
     *
     * The listing is columnar, with a display name that may be `<unknown>`:
     *
     * ```
     * Service Version Instance Node  Port
     *       3       1        0    0    88 Network Access Service
     * ```
     *
     * Matched on the numeric service id rather than the name, because the id is the protocol and
     * the name is decoration that differs between vendor builds.
     *
     * Where a service is listed more than once — several instances, or the same service on more
     * than one node — the lowest node wins, which is the local modem on this platform. Returns
     * null rather than a guess when nothing matches.
     */
    fun find(lookupOutput: String, service: Int = SERVICE_NAS): Address? {
        val row = Regex("""^\s*(\d+)\s+(\S+)\s+(\d+)\s+(\d+)\s+(\d+)\b""")
        return lookupOutput.lineSequence()
            .mapNotNull { line ->
                val m = row.find(line) ?: return@mapNotNull null
                val id = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                if (id != service) return@mapNotNull null
                val node = m.groupValues[4].toIntOrNull() ?: return@mapNotNull null
                val port = m.groupValues[5].toIntOrNull() ?: return@mapNotNull null
                Address(node, port)
            }
            .minByOrNull { it.node.toLong() * 100_000 + it.port }
    }
}
