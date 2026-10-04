package dev.joely.bmsmon.cloud

/** Everything the UI shows about cloud uploads, published by [TelemetryReporter.status]. */
data class UploadStatus(
    val outboxDepth: Int = 0,
    val lastUploadMs: Long = 0L,
    val kbps: Double = 0.0,
    /** Since the last ingest 401/403, until the server next accepts (or permanently rejects) a batch: samples are held. */
    val authFailed: Boolean = false,
    /** The device key is missing (DATA-17): uploads hold until the phone is re-enrolled. */
    val keyMissing: Boolean = false,
    /**
     * Server clock minus phone clock (ms) from the last app-marked sign-in rejected for its time,
     * outside the tolerance; else null. Bounded to ±[SIGNING_OFFSET_CAP_MS], so exactly the bound
     * means "an hour or more".
     */
    val authSkewMs: Long? = null,
    /** The clock correction currently applied to upload tokens (ms; 0 = none), within the same bound. */
    val signingOffsetMs: Long = 0L,
    /** Which clock that rejection blames; null when no sign-in is being rejected for its time. */
    val clockBlame: ClockBlame? = null,
    /** A breaker holding the queue for a server-side problem (display only; it can lag a narrowing bisection). */
    val hold: UploadHold = UploadHold.NONE,
    val resync: ResyncSummary = ResyncSummary(),
    /** All-time outbox rows evicted at the cap (DATA-19), each queued for a re-send from local history. */
    val outboxEvicted: Long = 0L,
    /** All-time samples skipped because the server kept crashing on them (DATA-22): rows actually deleted. */
    val serverFaultSkips: Long = 0L,
)
