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
 * app permanently rejects so it cannot head-of-line block the queue forever ([Poison]). A
 * [ServerFault] backs off like a Transient; on the ingest stream it also feeds [stepHeadFault].
 */
sealed class PostResult {
    /** A 2xx carrying [API_MARKER_HEADER] — the app accepted the batch. */
    object Ok : PostResult()

    /**
     * Network/IO failure, an unmarked 2xx, 3xx, an unmarked 5xx or a marked 503, 408/429, or any 4xx
     * the app did not provably send — back off and retry the SAME rows.
     */
    object Transient : PostResult()

    /**
     * A 5xx other than 503 carrying [API_MARKER_HEADER] — the app itself crashed on this request
     * (DATA-22). Backs off like [Transient]; never deletes anything on its own. Only the ingest
     * stream's [stepHeadFault] acts on a persistent streak of these.
     */
    object ServerFault : PostResult()

    /** 401/403 — revoked device or >60 s clock skew. Rows are kept; needs user attention. */
    object AuthFailed : PostResult()

    /**
     * The device key is gone from the Keystore — a device-to-device transfer copies the enrollment but
     * never the key (DATA-17). Nothing was sent; the rows are kept until the phone is re-enrolled.
     * Never produced by [classifyPost]: no HTTP response can mean this.
     */
    object KeyMissing : PostResult()

    /** 400/413/422 carrying [API_MARKER_HEADER] — the app itself permanently rejects this batch. */
    object Poison : PostResult()
}

/**
 * Map an HTTP status ([code]; null = no response at all) and whether the response carried
 * [API_MARKER_HEADER] ([fromApi]) to a [PostResult] (DATA-14, contract C2). Deleting user data needs
 * positive proof of an APP-level permanent reject, so Poison is only 400/413/422 WITH the marker;
 * every other 4xx, and any 4xx without it (Traefik's 404 during a deploy), is Transient. 401/403
 * hold the rows regardless of the marker — holding is always safe.
 *
 * 2xx: only a MARKED 2xx is [PostResult.Ok], since Ok deletes the batch. The app marks every response
 * it sends, so an unmarked 2xx came from something in front of it (a captive portal, a misrouted
 * proxy) that never saw the rows — Transient, retried.
 *
 * 5xx (DATA-22): only a MARKED 5xx other than 503 is a [PostResult.ServerFault]. A marked 503 is the
 * server's "database unavailable" answer (with Retry-After) — an outage, so Transient. An unmarked
 * 5xx came from Traefik or anything else in front of the app — an outage too, so Transient.
 */
fun classifyPost(code: Int?, fromApi: Boolean): PostResult = when {
    code == null -> PostResult.Transient
    code in 200..299 && fromApi -> PostResult.Ok
    code == 401 || code == 403 -> PostResult.AuthFailed
    fromApi && (code == 400 || code == 413 || code == 422) -> PostResult.Poison
    fromApi && code in 500..599 && code != 503 -> PostResult.ServerFault
    else -> PostResult.Transient // unmarked 2xx, 3xx, 408/429, other or unmarked 4xx, unmarked 5xx or marked 503, 1xx: retry with backoff
}
