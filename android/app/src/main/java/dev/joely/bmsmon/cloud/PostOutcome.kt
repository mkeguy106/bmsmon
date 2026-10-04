package dev.joely.bmsmon.cloud

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Response

/** The upload backoff: 1 s doubling to 60 s (unchanged values, now shared by every stream). */
internal const val INITIAL_BACKOFF_MS = 1_000L
internal const val MAX_BACKOFF_MS = 60_000L

/** The longest Retry-After honoured: a misconfigured server must not park uploads for hours. */
internal const val RETRY_AFTER_CAP_MS = 5 * 60_000L

/** The server's clock at response time, epoch ms, on every response the app generates. Preferred over `Date`. */
internal const val SERVER_TIME_HEADER = "X-Bmsmon-Server-Time-Ms"

/** Why the app rejected a device's sign-in (a token such as `clock_skew`), on a device-route 401. */
internal const val AUTH_REASON_HEADER = "X-Bmsmon-Auth-Reason"

/** The most of a 401/403 body ever read for its reason string; the app's is far shorter. */
private const val DETAIL_PEEK_BYTES = 4_096L

private val AUTH_REASON_TOKEN = Regex("[a-z0-9_]{1,64}")

/**
 * One signed POST's outcome: the classified [result] plus what the response said beyond its status.
 * [fromApi] = it carried [API_MARKER_HEADER] (the app answered, not an intermediary). [retryAfterMs]
 * is the response's Retry-After (the app sends one with a marked 503). [skewMs] is the server's clock
 * ([SERVER_TIME_HEADER], else `Date`) minus this phone's clock at the request's midpoint (null when
 * neither is usable). [detail] is the app's reason string and [authReason] its [AUTH_REASON_HEADER]
 * token, both read only on a 401/403. Nothing here ever holds a token or a payload.
 */
internal data class PostOutcome(
    val result: PostResult,
    val code: Int? = null,
    val fromApi: Boolean = false,
    val retryAfterMs: Long? = null,
    val skewMs: Long? = null,
    val detail: String? = null,
    val authReason: String? = null,
)

/**
 * Read one HTTP response into a [PostOutcome]. [sentAtMs]/[receivedAtMs] are this phone's wall clock
 * either side of the round trip. The result is exactly [classifyPost]'s, and nothing read beyond the
 * status can change it or throw: the body is read only on a 401/403, only its first
 * [DETAIL_PEEK_BYTES] bytes, and a failed read just leaves [PostOutcome.detail] null.
 */
internal fun outcomeOf(resp: Response, sentAtMs: Long, receivedAtMs: Long): PostOutcome {
    val fromApi = resp.header(API_MARKER_HEADER) != null
    val result = classifyPost(resp.code, fromApi)
    val authFailed = result == PostResult.AuthFailed
    return PostOutcome(
        result = result,
        code = resp.code,
        fromApi = fromApi,
        retryAfterMs = parseRetryAfterMs(resp.header("Retry-After")),
        skewMs = clockSkewMs(serverClockMs(resp.header(SERVER_TIME_HEADER), resp.header("Date")), sentAtMs, receivedAtMs),
        detail = if (authFailed) apiDetail(runCatching { resp.peekBody(DETAIL_PEEK_BYTES).string() }.getOrNull()) else null,
        authReason = if (authFailed) parseAuthReason(resp.header(AUTH_REASON_HEADER)) else null,
    )
}

internal fun nextBackoffMs(backoffMs: Long): Long = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)

/** Wait at least the backoff; a Retry-After can lengthen it, never shorten it. */
internal fun retryDelayMs(backoffMs: Long, retryAfterMs: Long?): Long = maxOf(backoffMs, retryAfterMs ?: 0L)

/** Retry-After in delta-seconds, capped at [RETRY_AFTER_CAP_MS]; anything else (HTTP-date, junk) → null. */
internal fun parseRetryAfterMs(value: String?): Long? {
    val secs = value?.trim()?.toLongOrNull() ?: return null
    if (secs < 0) return null
    return secs.coerceAtMost(RETRY_AFTER_CAP_MS / 1000) * 1000
}

/** An RFC 1123 `Date` header as epoch ms, or null. */
internal fun parseHttpDateMs(value: String?): Long? = value?.let {
    runCatching { ZonedDateTime.parse(it.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
        .getOrNull()
}

/**
 * The server's clock for skew attribution: its [SERVER_TIME_HEADER] (millisecond resolution) when that
 * is a positive integer, else its `Date` header (whole seconds), else null.
 */
internal fun serverClockMs(serverTimeMs: String?, date: String?): Long? =
    serverTimeMs?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: parseHttpDateMs(date)

/** Server clock minus phone clock, judged at the midpoint of the round trip. */
internal fun clockSkewMs(serverDateMs: Long?, sentAtMs: Long, receivedAtMs: Long): Long? =
    serverDateMs?.let { it - (sentAtMs + receivedAtMs) / 2 }

/** The app's `{"detail": "<reason>"}` string (≤ 200 chars), or null for anything else. */
internal fun apiDetail(body: String?): String? {
    if (body.isNullOrBlank()) return null
    val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
    val detail = (root["detail"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    return detail.takeIf { it.isNotBlank() }?.take(200)
}

/** The [AUTH_REASON_HEADER] token (lower-case letters, digits, `_`; ≤ 64 chars), or null for anything else. */
internal fun parseAuthReason(value: String?): String? = value?.trim()?.takeIf { AUTH_REASON_TOKEN.matches(it) }
