package com.sdm.core.common

/**
 * Removes secrets (cookies, authorization, tokens in query strings) from any text before it is
 * logged, shown in diagnostics or exported. Used everywhere text can leave the app.
 */
object Redactor {
    private val sensitiveHeaders = setOf(
        "cookie", "set-cookie", "authorization", "proxy-authorization", "x-api-key", "x-auth-token",
    )
    private val sensitiveQueryKeys = setOf(
        "token", "access_token", "auth", "key", "api_key", "apikey", "signature", "sig", "password",
        "pass", "secret", "session", "sid", "x-amz-signature", "x-amz-credential", "x-amz-security-token",
        "expires", "policy", "key-pair-id",
    )
    private const val MASK = "[REDACTED]"
    private val headerLine = Regex("(?im)^(\\s*)(${sensitiveHeaders.joinToString("|") { Regex.escape(it) }})(\\s*:\\s*)(.*)$")
    private val bearer = Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+")

    fun isSensitiveHeader(name: String): Boolean = name.trim().lowercase() in sensitiveHeaders

    fun headers(headers: Map<String, String>): Map<String, String> =
        headers.mapValues { (k, v) -> if (isSensitiveHeader(k)) MASK else v }

    /** Masks the values of sensitive query parameters and strips user-info from a URL. */
    fun url(url: String): String {
        var result = url.replace(Regex("(?i)^(\\w+://)[^/@]+@"), "$1$MASK@")
        val q = result.indexOf('?')
        if (q < 0) return result
        val base = result.substring(0, q)
        val fragmentIndex = result.indexOf('#', q)
        val query = if (fragmentIndex >= 0) result.substring(q + 1, fragmentIndex) else result.substring(q + 1)
        val fragment = if (fragmentIndex >= 0) result.substring(fragmentIndex) else ""
        val cleaned = query.split('&').joinToString("&") { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) part else {
                val key = part.substring(0, eq)
                if (key.lowercase() in sensitiveQueryKeys) "$key=$MASK" else part
            }
        }
        result = "$base?$cleaned$fragment"
        return result
    }

    /** Redacts header lines, bearer tokens and every URL found in free text. */
    fun text(text: String): String {
        var out = headerLine.replace(text) { m -> m.groupValues[1] + m.groupValues[2] + m.groupValues[3] + MASK }
        out = bearer.replace(out, "Bearer $MASK")
        out = Regex("https?://[^\\s\"'<>]+").replace(out) { url(it.value) }
        return out
    }
}
