package dev.joely.bmsmon.cloud

/**
 * Response header the bmsmon API stamps on EVERY response it generates — success, HTTPException,
 * 422 validation, 413, 429 (server contract C1). Its presence is the only proof a status code came
 * from the app and not an intermediary: Traefik answers its own `404 page not found` whenever
 * bmsmon-api has no router (starting / unhealthy / stopped), plus 502/503, and none carry it.
 */
const val API_MARKER_HEADER = "X-Bmsmon-Api"

/**
 * Outcome of one signed upload POST, classified so the uploader can pick the right recovery:
 * back off and retry the same rows ([Transient]), keep the rows but surface an auth problem
 * ([AuthFailed]), or — subject to the poison circuit breaker in [decideUpload] — skip a batch the
 * app permanently rejects so it cannot head-of-line block the queue forever ([Poison]).
 */
sealed class PostResult {
    /** HTTP 2xx — the batch was accepted. */
    object Ok : PostResult()

    /** Network/IO failure, 3xx, 5xx, 408/429, or any 4xx the app did not provably send — back off and retry the SAME rows. */
    object Transient : PostResult()

    /** 401/403 — revoked device or >60 s clock skew. Rows are kept; needs user attention. */
    object AuthFailed : PostResult()

    /** 400/413/422 carrying [API_MARKER_HEADER] — the app itself permanently rejects this batch. */
    object Poison : PostResult()
}

/**
 * Map an HTTP status ([code]; null = no response at all) and whether the response carried
 * [API_MARKER_HEADER] ([fromApi]) to a [PostResult] (DATA-14, contract C2). Deleting user data needs
 * positive proof of an APP-level permanent reject, so Poison is only 400/413/422 WITH the marker;
 * every other 4xx, and any 4xx without it (Traefik's 404 during a deploy), is Transient. 401/403
 * hold the rows regardless of the marker — holding is always safe.
 */
fun classifyPost(code: Int?, fromApi: Boolean): PostResult = when {
    code == null -> PostResult.Transient
    code in 200..299 -> PostResult.Ok
    code == 401 || code == 403 -> PostResult.AuthFailed
    fromApi && (code == 400 || code == 413 || code == 422) -> PostResult.Poison
    else -> PostResult.Transient // 3xx, 408/429, other or unmarked 4xx, 5xx, 1xx: retry with backoff
}
