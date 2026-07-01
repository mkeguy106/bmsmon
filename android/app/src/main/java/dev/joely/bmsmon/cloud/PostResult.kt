package dev.joely.bmsmon.cloud

/**
 * Outcome of one signed upload POST, classified so the uploader can pick the right recovery:
 * back off and retry the same rows ([Transient]), keep the rows but surface an auth problem
 * ([AuthFailed]), or skip a permanently-rejected batch so it cannot head-of-line block the
 * queue forever ([Poison]).
 */
sealed class PostResult {
    /** HTTP 2xx — the batch was accepted. */
    object Ok : PostResult()

    /** Network/IO failure, 5xx, 408 or 429 — back off and retry the SAME rows. */
    object Transient : PostResult()

    /** 401/403 — revoked device or >60 s clock skew. Rows are kept; needs user attention. */
    object AuthFailed : PostResult()

    /** Any other 4xx (400/413/422 …) — the server will never accept this batch; skip past it. */
    object Poison : PostResult()
}

/** Map an HTTP status code ([code]; null = the request never got a response) to a [PostResult]. */
fun classifyPost(code: Int?): PostResult = when {
    code == null -> PostResult.Transient
    code in 200..299 -> PostResult.Ok
    code == 401 || code == 403 -> PostResult.AuthFailed
    code == 408 || code == 429 -> PostResult.Transient
    code in 400..499 -> PostResult.Poison
    else -> PostResult.Transient // 5xx and anything unexpected: retry with backoff
}
