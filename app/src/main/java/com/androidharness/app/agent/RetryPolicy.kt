package com.androidharness.app.agent

import com.androidharness.app.llm.ApiException
import java.io.IOException

/**
 * Decides which provider failures are worth retrying and how long to wait.
 * Transient conditions only, rate limits, server overload, network drops.
 * Permanent errors (bad request, auth, not found) surface to the user at once.
 */
internal object RetryPolicy {
    const val MAX_RETRIES = 3
    private const val BASE_DELAY_MS = 1_000L

    private val TRANSIENT_MESSAGE = Regex(
        "overloaded|rate.?limit|timeout|timed out|temporarily|try again|" +
            "connection reset|connection refused|ECONNRESET|socket closed|JSON error injected into SSE stream",
        RegexOption.IGNORE_CASE,
    )

    /** HTTP codes worth another attempt: request timeout, rate limit, server errors. */
    fun isRetryableCode(code: Int): Boolean =
        code == 408 || code == 429 || code in 500..599

    /**
     * Classifies a failure from its (optional) thrown cause and (optional)
     * in-stream message. [ApiException] is checked before [IOException]
     * because it subclasses it, an HTTP 400 must not retry even though it
     * arrives as an IOException. An explicit in-stream [code] also takes
     * precedence over message-based guesses.
     */
    fun isRetryable(cause: Throwable?, message: String?, code: Int? = null): Boolean {
        if (code != null) return isRetryableCode(code)
        var c = cause
        while (c != null) {
            if (c is ApiException) return isRetryableCode(c.code)
            if (c is IOException) return true
            c = c.cause
        }
        return message != null && TRANSIENT_MESSAGE.containsMatchIn(message)
    }

    /** Exponential backoff 1s / 2s / 4s with +/-30% jitter. [attempt] is 1-based. */
    fun delayMs(attempt: Int): Long {
        val exp = (attempt - 1).coerceIn(0, 4)
        val base = BASE_DELAY_MS shl exp
        val jitter = (base * 0.3 * (Math.random() * 2 - 1)).toLong()
        return base + jitter
    }

    enum class HttpErrorCategory(val terminal: Boolean, val summary: String) {
        INVALID_OR_MISSING_KEY(true, "HTTP 401 — Invalid or missing API key"),
        PAYMENT_OR_CREDITS_EXHAUSTED(true, "HTTP 402 — Payment required or exhausted credits"),
        ACCESS_DENIED(true, "HTTP 403 — Access denied"),
        MODEL_OR_ENDPOINT_UNAVAILABLE(true, "HTTP 404 — Model or endpoint unavailable"),
        REQUEST_TIMEOUT(false, "HTTP 408 — Request timeout"),
        RATE_LIMIT_OR_QUOTA(false, "HTTP 429 — Rate limit or quota exceeded"),
        PROVIDER_SERVER_ERROR(false, "HTTP 5xx — Provider-side server error"),
        CLIENT_OR_REQUEST_ERROR(true, "HTTP 4xx — Client request rejected"),
        UNKNOWN(false, "Network or transport failure"),
    }

    /** Explicitly classifies HTTP status codes for Phase 1 error handling and fallback decisions. */
    fun classifyHttpCode(code: Int?): HttpErrorCategory = when (code) {
        401 -> HttpErrorCategory.INVALID_OR_MISSING_KEY
        402 -> HttpErrorCategory.PAYMENT_OR_CREDITS_EXHAUSTED
        403 -> HttpErrorCategory.ACCESS_DENIED
        404 -> HttpErrorCategory.MODEL_OR_ENDPOINT_UNAVAILABLE
        408 -> HttpErrorCategory.REQUEST_TIMEOUT
        429 -> HttpErrorCategory.RATE_LIMIT_OR_QUOTA
        in 500..599 -> HttpErrorCategory.PROVIDER_SERVER_ERROR
        in 400..499 -> HttpErrorCategory.CLIENT_OR_REQUEST_ERROR
        else -> HttpErrorCategory.UNKNOWN
    }

    /** Terminal authentication or request errors must never be retried indefinitely. */
    fun isTerminalAuthOrRequestCode(code: Int?): Boolean =
        code == 400 || code == 401 || code == 403 || code == 422
}
