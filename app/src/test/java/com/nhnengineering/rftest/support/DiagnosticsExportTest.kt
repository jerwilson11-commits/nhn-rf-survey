package com.nhnengineering.rftest.support

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two pure pieces of the diagnostics bundle: telling root from not-root out of `su -c id`'s
 * own output, and the plain-text report format support actually reads. Collection itself (root
 * check, the QMI probe, logcat) needs a real device and is not covered here, the same way
 * [com.nhnengineering.rftest.modem.QmiNasClient] isn't.
 */
class DiagnosticsExportTest {

    // ---- root detection ---------------------------------------------------------

    @Test
    fun `a genuine root shell is recognised`() {
        assertTrue(isRootedIdOutput("uid=0(root) gid=0(root) groups=0(root),1004(input),1007(log)"))
    }

    @Test
    fun `a normal app shell is not mistaken for root`() {
        assertFalse(isRootedIdOutput("uid=10234(u0_a234) gid=10234(u0_a234)"))
    }

    @Test
    fun `su not found or refused is not root`() {
        assertFalse(isRootedIdOutput("/system/bin/sh: su: not found"))
        assertFalse(isRootedIdOutput("Permission denied"))
        assertFalse(isRootedIdOutput(""))
    }

    // ---- report rendering ---------------------------------------------------------

    private val okBundle = DiagnosticsBundle(
        generatedAtUtcMillis = 1_800_000_000_000L,
        appVersionName = "1.0",
        appVersionCode = 1,
        tier = "PRO",
        device = DeviceInfo(
            manufacturer = "OnePlus",
            model = "LE2115",
            device = "OnePlus9",
            board = "kona",
            hardware = "qcom",
            fingerprint = "OnePlus/instantnoodlep/OnePlus9:14/...",
            androidRelease = "14",
            sdkInt = 34,
        ),
        root = RootInfo(suAvailable = true, idOutput = "uid=0(root) gid=0(root)"),
        qmi = QmiProbe(
            helperPresent = true,
            getSucceeded = true,
            modePref = 0x005F,
            lteMaskPresent = true,
            nrSaMaskPresent = true,
            nrNsaMaskPresent = false,
            parserNotes = emptyList(),
        ),
        recentLog = "09-29 12:00:00.000  I BandLock: requested LTE=[4] NR-SA=[] NR-NSA=[]",
    )

    @Test
    fun `a healthy bundle names the device, the root state, and the mode preference in hex`() {
        val text = renderDiagnosticsText(okBundle)

        assertTrue(text.contains("OnePlus / LE2115"))
        assertTrue(text.contains("su available: yes"))
        assertTrue(text.contains("Mode preference: 0x005f"))
        assertTrue(text.contains("NR SA band mask present: yes"))
        assertTrue(text.contains("NR NSA band mask present: no"))
    }

    @Test
    fun `a failed GET reports why, not a null-shaped mode preference`() {
        val failed = okBundle.copy(
            qmi = QmiProbe(
                helperPresent = true,
                getSucceeded = false,
                failureReason = "The modem helper timed out.",
            ),
        )
        val text = renderDiagnosticsText(failed)

        assertTrue(text.contains("GET succeeded: no"))
        assertTrue(text.contains("Failure reason: The modem helper timed out."))
        // The success-only fields must not appear at all when there was nothing to report them from.
        assertFalse(text.contains("Mode preference"))
    }

    @Test
    fun `a parser note from a malformed TLV survives into the report`() {
        val withNote = okBundle.copy(
            qmi = okBundle.qmi.copy(parserNotes = listOf("Mode Preference TLV is 1 bytes, not the expected 2.")),
        )

        assertTrue(renderDiagnosticsText(withNote).contains("Mode Preference TLV is 1 bytes"))
    }

    @Test
    fun `no root and no log are reported plainly, not as blank fields`() {
        val bare = okBundle.copy(
            root = RootInfo(suAvailable = false, idOutput = null),
            qmi = QmiProbe(helperPresent = false, getSucceeded = false, failureReason = "No root."),
            recentLog = null,
        )
        val text = renderDiagnosticsText(bare)

        assertTrue(text.contains("su available: no"))
        assertTrue(text.contains("su could not be run at all"))
        assertTrue(text.contains("(not available)"))
    }
}
