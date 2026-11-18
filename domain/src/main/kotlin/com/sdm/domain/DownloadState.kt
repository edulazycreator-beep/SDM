package com.sdm.domain

/** The ten persisted download states from spec section 6.3. Stored by name, never ordinal. */
enum class DownloadState {
    QUEUED, PREPARING, DOWNLOADING, PAUSED, WAITING_FOR_NETWORK, RETRYING, VERIFYING, COMPLETED, FAILED, CANCELLED;

    val isActive: Boolean get() = this == PREPARING || this == DOWNLOADING || this == RETRYING || this == VERIFYING
    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED
}
