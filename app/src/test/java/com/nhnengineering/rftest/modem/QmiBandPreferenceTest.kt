package com.nhnengineering.rftest.modem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Band-mask decode and write construction, pinned to values captured from the OnePlus 9 on
 * 2026-09-26 and to the exact argument lists that were driven by hand and watched working.
 *
 * Fixtures are assembled by [response] from the captured words rather than pasted as one long
 * hex string; a hand-copied 300-byte message is where transcription errors hide.
 */
class QmiBandPreferenceTest {

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "")
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun response(msgId: Int, vararg tlvs: Pair<Int, String>): ByteArray {
        val body = mutableListOf<Byte>()
        for ((id, h) in tlvs) {
            val v = hex(h)
            body += id.toByte()
            body += (v.size and 0xFF).toByte()
            body += ((v.size shr 8) and 0xFF).toByte()
            body += v.toList()
        }
        val head = listOf(
            0x02.toByte(), 0x01, 0x00,
            (msgId and 0xFF).toByte(), ((msgId shr 8) and 0xFF).toByte(),
            (body.size and 0xFF).toByte(), ((body.size shr 8) and 0xFF).toByte(),
        )
        return (head + body).toByteArray()
    }

    private val zeros48 = "00".repeat(48)

    // Captured baselines. Word 1 (bytes 8-15) of the NR masks carries bands 65-128.
    private val lteBaseline = "df180fabe0a10000"
    private val saBaseline = "5700080900810000" + "4230000000000000" + zeros48
    private val nsaBaseline = "d7000809a0810000" + "4230000000000000" + zeros48

    private val capturedGet = response(
        QmiSelectionPreference.MSG_GET,
        0x02 to "00 00 00 00",
        0x11 to "5f 00",
        0x15 to lteBaseline,
        0x2C to saBaseline,
        0x2D to nsaBaseline,
    )

    @Test
    fun `the captured baseline decodes to the bands the modem reported`() {
        val r = QmiSelectionPreference.parseGet(capturedGet)

        assertTrue(r.looksValid)
        assertEquals(0x5F, r.modePref)
        assertEquals(emptyList<String>(), r.notes)
        assertEquals(
            setOf(1, 2, 3, 4, 5, 7, 8, 12, 13, 17, 18, 19, 20, 25, 26, 28, 30, 32, 38, 39, 40, 41, 46, 48),
            QmiSelectionPreference.bandsOf(listOf(r.bands.lte!!)),
        )
        assertEquals(
            setOf(1, 2, 3, 5, 7, 20, 25, 28, 41, 48, 66, 71, 77, 78),
            QmiSelectionPreference.bandsOf(r.bands.nrSa!!),
        )
        assertEquals(
            setOf(1, 2, 3, 5, 7, 8, 20, 25, 28, 38, 40, 41, 48, 66, 71, 77, 78),
            QmiSelectionPreference.bandsOf(r.bands.nrNsa!!),
        )
    }

    @Test
    fun `bands above 64 land in the second word`() {
        // n66 and n71 are bits 1 and 6 of word 1: the convention checked against the live modem.
        val mask = QmiSelectionPreference.maskOf(setOf(66, 71), 8)
        assertEquals(0L, mask[0])
        assertEquals((1L shl 1) or (1L shl 6), mask[1])
    }

    @Test
    fun `mask and bands round trip`() {
        val bands = setOf(1, 41, 64, 65, 78, 512)
        assertEquals(bands, QmiSelectionPreference.bandsOf(QmiSelectionPreference.maskOf(bands, 8)))
    }

    @Test
    fun `a band outside the mask is refused rather than dropped`() {
        assertThrows(IllegalArgumentException::class.java) { QmiSelectionPreference.maskOf(setOf(65), 1) }
        assertThrows(IllegalArgumentException::class.java) { QmiSelectionPreference.maskOf(setOf(0), 1) }
    }

    @Test
    fun `a mask of the wrong length is noted and not read`() {
        val short = response(
            QmiSelectionPreference.MSG_GET,
            0x02 to "00 00 00 00", 0x11 to "5f 00", 0x2C to "5700080900810000",
        )
        val r = QmiSelectionPreference.parseGet(short)

        assertNull(r.bands.nrSa)
        assertTrue(r.notes.any { it.contains("SA Band Preference") })
    }

    // ---- writes, as driven by hand ---------------------------------------------

    @Test
    fun `the Band 4 LTE lock is built exactly as the one that moved the serving cell`() {
        // 15:0800000000000000 17:00 took the phone from EARFCN 1000 (B2) to EARFCN 2350 (B4).
        assertEquals(listOf("15:0800000000000000", "17:00"), QmiSelectionPreference.setLteBandArgs(setOf(4)))
    }

    @Test
    fun `the LTE baseline restore is the mask exactly as read`() {
        assertEquals(
            listOf("15:$lteBaseline", "17:00"),
            QmiSelectionPreference.restoreLteArgs(QmiSelectionPreference.parseGet(capturedGet).bands.lte!!),
        )
    }

    @Test
    fun `LTE selections the modem refused are refused before sending`() {
        // All-zero base mask and bands above 64 both returned error 48 on the handset.
        assertThrows(IllegalArgumentException::class.java) { QmiSelectionPreference.setLteBandArgs(emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { QmiSelectionPreference.setLteBandArgs(setOf(66)) }
    }

    @Test
    fun `the n41 SA lock carries mode preference, the SA mask and the NSA mask as read`() {
        // The write that was accepted (result 0, error 0). Without the leading 11: the modem
        // answered MissingArgument (error 17), and so it did with 2f: plus 30: but no 11:.
        val nsa = QmiSelectionPreference.parseGet(capturedGet).bands.nrNsa!!
        val args = QmiSelectionPreference.setNrSaBandArgs(0x5F, setOf(41), nsa)

        assertEquals(
            listOf(
                "11:5f00",
                "2f:0000000000010000" + "00".repeat(56),
                "30:$nsaBaseline",
                "17:00",
            ),
            args,
        )
    }

    @Test
    fun `an SA restore writes the captured SA and NSA masks back unchanged`() {
        val b = QmiSelectionPreference.parseGet(capturedGet).bands
        assertEquals(
            listOf("11:5f00", "2f:$saBaseline", "30:$nsaBaseline", "17:00"),
            QmiSelectionPreference.restoreNrSaArgs(0x5F, b.nrSa!!, b.nrNsa!!),
        )
    }

    @Test
    fun `an NR write keeps whatever technology lock is in force`() {
        // Written under an LTE-only lock, the mode preference must stay 0x0010, not be reset.
        val nsa = List(8) { 0L }
        assertEquals("11:1000", QmiSelectionPreference.setNrSaBandArgs(0x10, setOf(41), nsa)[0])
    }

    @Test
    fun `the NSA-only write is built exactly as driven by hand`() {
        // 11:5f00 with an all-zero 2f: and the NSA mask as read: accepted 2026-09-26, after which
        // the carriers under load were LTE primary + NR secondary. Restoring the captured SA mask
        // brought SA n25 back.
        val nsa = QmiSelectionPreference.parseGet(capturedGet).bands.nrNsa!!
        assertEquals(
            listOf("11:5f00", "2f:" + "00".repeat(64), "30:$nsaBaseline", "17:00"),
            QmiSelectionPreference.nsaOnlyArgs(0x5F, nsa),
        )
    }

    @Test
    fun `an empty NR selection is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            QmiSelectionPreference.setNrSaBandArgs(0x5F, emptySet(), List(8) { 0L })
        }
    }

    @Test
    fun `every band write carries change duration until power cycle`() {
        val nsa = List(8) { 0L }
        val all = listOf(
            QmiSelectionPreference.setLteBandArgs(setOf(4)),
            QmiSelectionPreference.restoreLteArgs(1L),
            QmiSelectionPreference.setNrSaBandArgs(0x5F, setOf(41), nsa),
            QmiSelectionPreference.restoreNrSaArgs(0x5F, nsa, nsa),
        )
        for (args in all) assertEquals("17:00", args.last())
    }
}
