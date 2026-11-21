package com.sdm.domain

import com.sdm.domain.DownloadState.CANCELLED
import com.sdm.domain.DownloadState.COMPLETED
import com.sdm.domain.DownloadState.DOWNLOADING
import com.sdm.domain.DownloadState.FAILED
import com.sdm.domain.DownloadState.PAUSED
import com.sdm.domain.DownloadState.PREPARING
import com.sdm.domain.DownloadState.QUEUED
import com.sdm.domain.DownloadState.RETRYING
import com.sdm.domain.DownloadState.VERIFYING
import com.sdm.domain.DownloadState.WAITING_FOR_NETWORK

/** Events that can move a download between states (spec section 6.3). */
enum class DownloadEvent {
    START, PROBED, RETRY_LATER, RETRY_NOW, NETWORK_LOST, NETWORK_RESTORED, PAUSE, RESUME, VERIFY, VERIFIED, FAIL, CANCEL, RESTART, INTERRUPTED,
}

class IllegalTransitionException(val from: DownloadState, val event: DownloadEvent) :
    IllegalStateException("Illegal transition: $from on $event")

/**
 * Pure transition table. Every state change in SDM goes through [transition]; the repository then
 * persists it with compare-and-set on the previous state, so two writers can never both win.
 */
object StateMachine {
    private val table: Map<DownloadState, Map<DownloadEvent, DownloadState>> = mapOf(
        QUEUED to mapOf(DownloadEvent.START to PREPARING, DownloadEvent.PAUSE to PAUSED, DownloadEvent.CANCEL to CANCELLED, DownloadEvent.NETWORK_LOST to WAITING_FOR_NETWORK),
        PREPARING to mapOf(
            DownloadEvent.PROBED to DOWNLOADING, DownloadEvent.VERIFY to VERIFYING, DownloadEvent.RETRY_LATER to RETRYING,
            DownloadEvent.NETWORK_LOST to WAITING_FOR_NETWORK, DownloadEvent.PAUSE to PAUSED, DownloadEvent.FAIL to FAILED,
            DownloadEvent.CANCEL to CANCELLED, DownloadEvent.INTERRUPTED to QUEUED,
        ),
        DOWNLOADING to mapOf(
            DownloadEvent.PAUSE to PAUSED, DownloadEvent.NETWORK_LOST to WAITING_FOR_NETWORK, DownloadEvent.RETRY_LATER to RETRYING,
            DownloadEvent.VERIFY to VERIFYING, DownloadEvent.FAIL to FAILED, DownloadEvent.CANCEL to CANCELLED, DownloadEvent.INTERRUPTED to QUEUED,
        ),
        RETRYING to mapOf(
            DownloadEvent.RETRY_NOW to PREPARING, DownloadEvent.PAUSE to PAUSED, DownloadEvent.NETWORK_LOST to WAITING_FOR_NETWORK,
            DownloadEvent.FAIL to FAILED, DownloadEvent.CANCEL to CANCELLED, DownloadEvent.INTERRUPTED to QUEUED,
        ),
        WAITING_FOR_NETWORK to mapOf(DownloadEvent.NETWORK_RESTORED to QUEUED, DownloadEvent.PAUSE to PAUSED, DownloadEvent.CANCEL to CANCELLED, DownloadEvent.RESUME to QUEUED),
        PAUSED to mapOf(DownloadEvent.RESUME to QUEUED, DownloadEvent.CANCEL to CANCELLED, DownloadEvent.RESTART to QUEUED),
        VERIFYING to mapOf(DownloadEvent.VERIFIED to COMPLETED, DownloadEvent.FAIL to FAILED, DownloadEvent.CANCEL to CANCELLED, DownloadEvent.INTERRUPTED to QUEUED),
        COMPLETED to mapOf(DownloadEvent.RESTART to QUEUED),
        FAILED to mapOf(DownloadEvent.RESUME to QUEUED, DownloadEvent.RESTART to QUEUED, DownloadEvent.CANCEL to CANCELLED),
        CANCELLED to mapOf(DownloadEvent.RESTART to QUEUED),
    )

    fun next(from: DownloadState, event: DownloadEvent): DownloadState? = table[from]?.get(event)

    fun transition(from: DownloadState, event: DownloadEvent): Result<DownloadState> =
        next(from, event)?.let { Result.success(it) } ?: Result.failure(IllegalTransitionException(from, event))

    fun canTransition(from: DownloadState, event: DownloadEvent): Boolean = next(from, event) != null

    /** States that are left over from a killed process and must be recovered at start-up. */
    val INTERRUPTIBLE: Set<DownloadState> = setOf(PREPARING, DOWNLOADING, RETRYING, VERIFYING)
}
