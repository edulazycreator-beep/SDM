package com.sdm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StateMachineTest {
    private val legal = mapOf(
        DownloadState.QUEUED to mapOf(DownloadEvent.START to DownloadState.PREPARING, DownloadEvent.PAUSE to DownloadState.PAUSED, DownloadEvent.CANCEL to DownloadState.CANCELLED, DownloadEvent.NETWORK_LOST to DownloadState.WAITING_FOR_NETWORK),
        DownloadState.PREPARING to mapOf(DownloadEvent.PROBED to DownloadState.DOWNLOADING, DownloadEvent.VERIFY to DownloadState.VERIFYING, DownloadEvent.RETRY_LATER to DownloadState.RETRYING, DownloadEvent.NETWORK_LOST to DownloadState.WAITING_FOR_NETWORK, DownloadEvent.PAUSE to DownloadState.PAUSED, DownloadEvent.FAIL to DownloadState.FAILED, DownloadEvent.CANCEL to DownloadState.CANCELLED, DownloadEvent.INTERRUPTED to DownloadState.QUEUED),
        DownloadState.DOWNLOADING to mapOf(DownloadEvent.PAUSE to DownloadState.PAUSED, DownloadEvent.NETWORK_LOST to DownloadState.WAITING_FOR_NETWORK, DownloadEvent.RETRY_LATER to DownloadState.RETRYING, DownloadEvent.VERIFY to DownloadState.VERIFYING, DownloadEvent.FAIL to DownloadState.FAILED, DownloadEvent.CANCEL to DownloadState.CANCELLED, DownloadEvent.INTERRUPTED to DownloadState.QUEUED),
        DownloadState.RETRYING to mapOf(DownloadEvent.RETRY_NOW to DownloadState.PREPARING, DownloadEvent.PAUSE to DownloadState.PAUSED, DownloadEvent.NETWORK_LOST to DownloadState.WAITING_FOR_NETWORK, DownloadEvent.FAIL to DownloadState.FAILED, DownloadEvent.CANCEL to DownloadState.CANCELLED, DownloadEvent.INTERRUPTED to DownloadState.QUEUED),
        DownloadState.WAITING_FOR_NETWORK to mapOf(DownloadEvent.NETWORK_RESTORED to DownloadState.QUEUED, DownloadEvent.PAUSE to DownloadState.PAUSED, DownloadEvent.CANCEL to DownloadState.CANCELLED, DownloadEvent.RESUME to DownloadState.QUEUED),
        DownloadState.PAUSED to mapOf(DownloadEvent.RESUME to DownloadState.QUEUED, DownloadEvent.CANCEL to DownloadState.CANCELLED, DownloadEvent.RESTART to DownloadState.QUEUED),
        DownloadState.VERIFYING to mapOf(DownloadEvent.VERIFIED to DownloadState.COMPLETED, DownloadEvent.FAIL to DownloadState.FAILED, DownloadEvent.CANCEL to DownloadState.CANCELLED, DownloadEvent.INTERRUPTED to DownloadState.QUEUED),
        DownloadState.COMPLETED to mapOf(DownloadEvent.RESTART to DownloadState.QUEUED),
        DownloadState.FAILED to mapOf(DownloadEvent.RESUME to DownloadState.QUEUED, DownloadEvent.RESTART to DownloadState.QUEUED, DownloadEvent.CANCEL to DownloadState.CANCELLED),
        DownloadState.CANCELLED to mapOf(DownloadEvent.RESTART to DownloadState.QUEUED),
    )

    /** Every state x every event: legal pairs give the expected state, all others are rejected. */
    @Test fun exhaustiveTransitionTable() {
        var checked = 0
        for (state in DownloadState.entries) for (event in DownloadEvent.entries) {
            val expected = legal[state]?.get(event)
            val result = StateMachine.transition(state, event)
            if (expected == null) {
                assertTrue("$state + $event must be illegal", result.isFailure)
                assertNull(StateMachine.next(state, event))
            } else {
                assertEquals("$state + $event", expected, result.getOrThrow())
            }
            checked++
        }
        assertEquals(DownloadState.entries.size * DownloadEvent.entries.size, checked)
        assertEquals(10, DownloadState.entries.size)
    }

    @Test fun terminalStatesOnlyRestart() {
        assertFalse(StateMachine.canTransition(DownloadState.COMPLETED, DownloadEvent.START))
        assertTrue(StateMachine.canTransition(DownloadState.COMPLETED, DownloadEvent.RESTART))
    }

    @Test fun interruptedStatesRecoverToQueued() {
        for (s in StateMachine.INTERRUPTIBLE) assertEquals(DownloadState.QUEUED, StateMachine.next(s, DownloadEvent.INTERRUPTED))
    }
}
