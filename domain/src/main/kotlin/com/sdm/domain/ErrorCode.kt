package com.sdm.domain

/** Diagnostics catalogue (spec section 25): every failure maps to an explanation and a suggested fix. */
enum class ErrorCode(val title: String, val description: String, val suggestion: String, val retryable: Boolean) {
    NETWORK_UNAVAILABLE("Network unavailable", "The device has no usable internet connection.", "Check Wi-Fi or mobile data. SDM resumes automatically when the network returns.", true),
    TIMEOUT("Timeout", "The server did not answer within the configured timeout.", "Try again later or raise the timeouts in Network settings.", true),
    CONNECTION_FAILED("Connection failed", "The connection to the server was refused or reset.", "Check the address, proxy and DNS settings, then retry.", true),
    UNSUPPORTED_RANGE("Range not supported", "The server does not support resuming or multi-connection downloads.", "SDM continues with one connection. A paused download from this server must restart from the beginning.", false),
    RANGE_MISMATCH("Invalid server response", "The server returned a different part of the file than requested.", "SDM switched to one connection to protect the file. Retry if it persists.", true),
    RESOURCE_CHANGED("File changed on server", "The file on the server changed while it was downloading.", "Restart the download to get the new version.", false),
    LINK_EXPIRED("Link expired", "The server refused a link that worked before. Signed or temporary links expire.", "Open the source page again and use Refresh link to keep the downloaded part.", false),
    ACCESS_DENIED("Access denied", "The server refused the request (HTTP 401/403).", "Sign in through the SDM browser so the download carries your session, or check the link.", false),
    NOT_FOUND("File not found", "The server says the file does not exist (HTTP 404/410).", "Check the link or find a new one on the source page.", false),
    RANGE_NOT_SATISFIABLE("Range not satisfiable", "The server rejected the resume position (HTTP 416).", "Restart the download from the beginning.", false),
    TOO_MANY_REQUESTS("Too many requests", "The server is rate limiting (HTTP 429).", "SDM waits as the server asks and uses fewer connections for this host.", true),
    SERVER_ERROR("Server error", "The server reported an internal error (HTTP 5xx).", "Usually temporary; SDM retries with backoff.", true),
    REQUIRES_PREMIUM("Requires Premium", "This download uses a feature included in SDM Premium.", "Unlock it in Settings › SDM Premium, then retry.", false),
    INVALID_RESPONSE("Invalid server response", "The server response could not be used (unexpected status or headers).", "Retry; if it repeats, the link may not point to a downloadable file.", false),
    INSUFFICIENT_STORAGE("Insufficient storage", "There is not enough free space for this file.", "Free some space or choose another destination, then resume.", false),
    FILE_TOO_LARGE("File too large for destination", "The destination file system cannot hold a file this large (FAT32 has a 4 GB limit).", "Choose internal storage or an exFAT-formatted card.", false),
    STORAGE_ERROR("Storage error", "The file could not be written.", "Check that the destination folder still exists and SDM still has access.", false),
    CHECKSUM_MISMATCH("Checksum mismatch", "The downloaded file does not match the expected checksum.", "Restart the download; if it repeats, the source file differs from the published checksum.", false),
    INTERRUPTED("Interrupted download", "The download stopped unexpectedly (app closed or system stopped it).", "Resume it; SDM keeps the verified part.", true),
    CANCELLED_BY_SYSTEM("Stopped by Android", "Android stopped background work for SDM (time limit or battery saving).", "Open SDM to continue, or exempt SDM from battery optimization.", true),
    UNSUPPORTED_SOURCE("Unsupported source", "This kind of link cannot be downloaded directly (for example a form submission or protected stream).", "Download it from the site itself.", false),
    UNKNOWN("Unknown error", "An unexpected error occurred.", "Retry. If it persists, copy the diagnostic report.", true);

    companion object {
        fun forHttpStatus(code: Int, wasResumed: Boolean = false): ErrorCode = when (code) {
            401 -> ACCESS_DENIED
            403 -> if (wasResumed) LINK_EXPIRED else ACCESS_DENIED
            404, 410 -> NOT_FOUND
            416 -> RANGE_NOT_SATISFIABLE
            429 -> TOO_MANY_REQUESTS
            408 -> TIMEOUT
            in 500..599 -> SERVER_ERROR
            else -> INVALID_RESPONSE
        }

        fun parse(name: String?): ErrorCode? = name?.let { n -> entries.firstOrNull { it.name == n } }
    }
}
