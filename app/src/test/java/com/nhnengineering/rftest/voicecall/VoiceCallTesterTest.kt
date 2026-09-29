package com.nhnengineering.rftest.voicecall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [isPossibleDrop], the one pure decision in [VoiceCallTester]. Everything else needs a
 * real telephony stack and a real call and is not covered here, the same way this project's
 * other live testers aren't.
 */
class VoiceCallTesterTest {

    @Test
    fun `a call lasting well past the floor is not flagged`() {
        assertFalse(isPossibleDrop(callDurationMs = 5_000))
    }

    @Test
    fun `a call ending almost immediately after connecting is flagged`() {
        assertTrue(isPossibleDrop(callDurationMs = 500))
    }

    @Test
    fun `the floor itself is not flagged, only strictly under it`() {
        assertFalse(isPossibleDrop(callDurationMs = 2_000, minExpectedMs = 2_000))
        assertTrue(isPossibleDrop(callDurationMs = 1_999, minExpectedMs = 2_000))
    }

    @Test
    fun `zero duration is not treated as a drop -- that failure mode is reported separately`() {
        assertFalse(isPossibleDrop(callDurationMs = 0))
    }

    @Test
    fun `a custom floor is honoured`() {
        assertEquals(true, isPossibleDrop(callDurationMs = 4_000, minExpectedMs = 5_000))
        assertEquals(false, isPossibleDrop(callDurationMs = 4_000, minExpectedMs = 3_000))
    }
}
