package com.nhnengineering.rftest.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BandLockCheckTest {

    @Test
    fun `no declaration means nothing to check`() {
        assertTrue(BandLockCheck.check(emptyList(), listOf("n41", "n71")).isEmpty())
    }

    @Test
    fun `a lock that held says nothing`() {
        // Silence when they agree. A confirmation on every report trains the reader to skip it.
        assertTrue(BandLockCheck.check(listOf("n41"), listOf("n41")).isEmpty())
    }

    @Test
    fun `a band outside the lock means the lock was not in force`() {
        // The worse of the two failures: the file is a free-running walk wearing a locked label.
        val f = BandLockCheck.check(listOf("n41"), listOf("n41", "n71"))

        val finding = f.single { it.headline.contains("excludes") }
        assertEquals(BandLockCheck.Severity.CHECK, finding.severity)
        assertTrue(finding.detail.contains("n71"))
        assertTrue(
            "must warn the statistics are not band-specific: ${finding.detail}",
            finding.detail.contains("free-running"),
        )
    }

    @Test
    fun `a locked band that never appeared is flagged`() {
        val f = BandLockCheck.check(listOf("n77"), listOf("n41"))

        assertTrue(f.any { it.headline.contains("never seen") })
    }

    @Test
    fun `an ambiguous observed label matches either alternative`() {
        // The app writes "n2/n25" where the channel is valid in both and the modem did not say
        // which. Treating that as a mismatch would fire on every locked walk over an overlapping
        // allocation.
        assertTrue(BandLockCheck.check(listOf("n25"), listOf("n2/n25")).isEmpty())
        assertTrue(BandLockCheck.check(listOf("n2"), listOf("n2/n25")).isEmpty())
    }

    @Test
    fun `a lock changed partway is not a contradiction`() {
        // One band per leg is a normal way to survey a multi-band system.
        assertTrue(BandLockCheck.check(listOf("n41", "n71"), listOf("n41", "n71")).isEmpty())
    }

    @Test
    fun `an empty session is not evidence of anything`() {
        // No observed bands means the walk recorded nothing to compare, not that the lock failed.
        assertTrue(BandLockCheck.check(listOf("n41"), emptyList()).isEmpty())
    }

    @Test
    fun `both failures can be reported together`() {
        val f = BandLockCheck.check(listOf("n77"), listOf("n41", "n71"))

        assertEquals(2, f.size)
    }
}
