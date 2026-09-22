package com.nhnengineering.rftest.modem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the decode against the field values actually observed on the handset.
 *
 * `mode_pref = 0x005F` is the genuine baseline read back on 2026-09-22, and the LTE-lock and
 * baseline-restore writes are the literal argument lists that were driven by hand that same day
 * and confirmed by watching `getRilDataRadioTechnology` actually move. The surrounding QMI
 * envelope is built by [response] rather than typed out as one long hex string: a
 * hand-transcribed multi-field message is exactly the kind of arithmetic this project has been
 * burned by before, and a builder makes the header and TLV lengths correct by construction
 * instead of by careful counting.
 */
class QmiSelectionPreferenceTest {

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "")
        return ByteArray(clean.length / 2) {
            clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    /** Wraps TLVs in a QMI response header for the given message id, as the socket delivers it. */
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

    private val resultOk = 0x02 to "00 00 00 00"

    // ---- the values actually observed ----------------------------------------

    @Test
    fun `the captured baseline reads back as 0x005F`() {
        val get = response(QmiSelectionPreference.MSG_GET, resultOk, 0x11 to "5f 00")
        val r = QmiSelectionPreference.parseGet(get)

        assertTrue(r.looksValid)
        assertEquals(true, r.success)
        assertEquals(0x005F, r.modePref)
        assertEquals("A clean capture needs no notes: ${r.notes}", 0, r.notes.size)
    }

    @Test
    fun `a SET response is read as a plain result, not as a mode_pref carrier`() {
        // The literal shape of the reply to "lock to LTE" and to "restore baseline" -- both
        // driven by hand, both confirmed by watching the radio actually move. A SET response
        // carries only the result TLV, so modePref must be null here without that being treated
        // as a failure to parse the message.
        val setReply = response(QmiSelectionPreference.MSG_SET, resultOk)

        assertEquals(true, QmiSelectionPreference.parseSetResult(setReply))

        val asGet = QmiSelectionPreference.parseGet(setReply)
        assertFalse(asGet.looksValid)
        assertNull(asGet.modePref)
    }

    @Test
    fun `a failure result is reported as failure, not read as an empty success`() {
        // result=1 error=5 (INVALID_ARG_VALUE) -- the shape of the port-mismatch failure this
        // project actually hit earlier, when the NAS port had moved and the app was still asking
        // the old one.
        val failed = response(QmiSelectionPreference.MSG_SET, 0x02 to "01 00 05 00")

        assertEquals(false, QmiSelectionPreference.parseSetResult(failed))
    }

    @Test
    fun `something too short to be a QMI header is refused`() {
        val r = QmiSelectionPreference.parseGet(byteArrayOf(0x02, 0x01, 0x00))
        assertFalse(r.looksValid)
        assertTrue(r.notes.any { it.contains("shorter than a QMI header") })
    }

    @Test
    fun `a response for a different message is refused`() {
        val wrongMessage = response(QmiSelectionPreference.MSG_SET, resultOk, 0x11 to "5f 00")
        val r = QmiSelectionPreference.parseGet(wrongMessage)

        assertFalse(r.looksValid)
    }

    @Test
    fun `a malformed mode preference TLV is noted rather than silently misread`() {
        // One byte short of the two this field always is. Reading it anyway risks reporting a
        // RAT mask that is half of some other field.
        val tampered = response(QmiSelectionPreference.MSG_GET, resultOk, 0x11 to "5f")
        val r = QmiSelectionPreference.parseGet(tampered)

        assertNull(r.modePref)
        assertTrue(r.notes.any { it.contains("not the expected 2") })
    }

    @Test
    fun `a truncated TLV is not read past`() {
        val raw = response(QmiSelectionPreference.MSG_GET, resultOk, 0x11 to "5f 00")
        val cut = raw.copyOfRange(0, raw.size - 1)
        val r = QmiSelectionPreference.parseGet(cut)

        // The dangling TLV is simply not included -- not part-read into a wrong mode_pref.
        assertNull(r.modePref)
    }

    // ---- building the write ---------------------------------------------------

    @Test
    fun `the LTE-only write is built exactly as driven by hand`() {
        // 11:1000 17:00 is the literal argument list that moved the radio to LTE in under five
        // seconds on 2026-09-22.
        assertEquals(listOf("11:1000", "17:00"), QmiSelectionPreference.setModePrefArgs(0x0010))
    }

    @Test
    fun `the baseline restore write is built exactly as driven by hand`() {
        assertEquals(listOf("11:5f00", "17:00"), QmiSelectionPreference.setModePrefArgs(0x005F))
    }

    @Test
    fun `every write carries change duration until power cycle`() {
        // The one non-negotiable property: nothing this app writes may outlive a reboot or an
        // airplane-mode toggle, both confirmed as an unconditional way out of a stuck lock.
        for (mask in listOf(0x0000, 0x0010, 0x005F, 0xFFFF)) {
            val args = QmiSelectionPreference.setModePrefArgs(mask)
            assertTrue(
                "mask 0x%04x must include the duration TLV: $args".format(mask),
                args.contains("%02x:%02x".format(0x17, QmiSelectionPreference.DURATION_UNTIL_POWER_CYCLE)),
            )
        }
    }

    @Test
    fun `the mode preference bytes are little endian`() {
        // 0x1234 must serialise as "34 12", not "12 34" -- QMI is little-endian throughout, and
        // getting this backwards would write a completely different RAT mask than requested.
        val args = QmiSelectionPreference.setModePrefArgs(0x1234)
        assertEquals("11:3412", args[0])
    }

    @Test
    fun `change duration until power cycle is zero`() {
        assertEquals(0x00, QmiSelectionPreference.DURATION_UNTIL_POWER_CYCLE)
    }
}
