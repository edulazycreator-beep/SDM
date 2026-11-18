package com.sdm.engine.validator

/** Parsed Content-Range header: "bytes start-end/total" or "bytes * /total" (unsatisfied). */
data class ContentRange(val start: Long?, val end: Long?, val total: Long?) {
    companion object {
        private val full = Regex("""^\s*bytes\s+(\d+)-(\d+)/(\d+|\*)\s*$""", RegexOption.IGNORE_CASE)
        private val unsatisfied = Regex("""^\s*bytes\s+\*/(\d+)\s*$""", RegexOption.IGNORE_CASE)

        fun parse(header: String?): ContentRange? {
            if (header == null) return null
            full.find(header)?.let { m ->
                val s = m.groupValues[1].toLongOrNull() ?: return null
                val e = m.groupValues[2].toLongOrNull() ?: return null
                if (e < s) return null
                return ContentRange(s, e, m.groupValues[3].toLongOrNull())
            }
            unsatisfied.find(header)?.let { return ContentRange(null, null, it.groupValues[1].toLongOrNull()) }
            return null
        }
    }
}

sealed interface RangeCheck {
    data object Ok : RangeCheck
    data class Mismatch(val reason: String) : RangeCheck
}

object RangeValidator {
    /**
     * A segment response is accepted only if it is a 206 whose Content-Range starts exactly at the
     * requested byte, does not run past the requested end and reports the expected total size.
     */
    fun check(status: Int, contentRange: String?, contentEncoding: String?, expectedStart: Long, expectedEnd: Long, expectedTotal: Long?): RangeCheck {
        if (status != 206) return RangeCheck.Mismatch("Expected 206 Partial Content, got $status")
        if (contentEncoding != null && !contentEncoding.equals("identity", true)) return RangeCheck.Mismatch("Unexpected Content-Encoding $contentEncoding")
        val r = ContentRange.parse(contentRange) ?: return RangeCheck.Mismatch("Missing or invalid Content-Range: $contentRange")
        if (r.start != expectedStart) return RangeCheck.Mismatch("Range starts at ${r.start}, requested $expectedStart")
        if (r.end == null || r.end > expectedEnd) return RangeCheck.Mismatch("Range ends at ${r.end}, requested $expectedEnd")
        if (expectedTotal != null && r.total != null && r.total != expectedTotal) return RangeCheck.Mismatch("Total ${r.total} differs from $expectedTotal")
        return RangeCheck.Ok
    }
}
