package dev.joely.bmsmon.model

/*
 * Phone-side freshness model (UI-16 / UI-23 / BLE-18, 2026-10-02 review).
 *
 * The phone is the user's only accurate remaining-charge reading while out (the chair's own gauge
 * lies on LiFePO4), yet "connected" used to mean only "GATT up + some telemetry, any age": the
 * persisted seed (hours or days old) rendered and alerted as live for up to ~22 s after every
 * connect, and a pack whose frames all failed to decode stayed "connected" with a frozen SOC
 * forever. Every surface — stage, alerts, seize, the fleet notifier, All Batteries, Detail — now
 * asks [freshness] instead.
 *
 * Ages are MONOTONIC ([BatteryStatus.lastFrameAtElapsedMs] = elapsedRealtime at the parsed frame),
 * so a wall-clock step can't make old data look new; nothing persists the stamp.
 *
 * Alerting rule (decided 2026-10-02): a reading drives alerts, the seize and the stage while it is
 * from THIS monitoring session — LIVE, or STALE with a known age ([drivesAlerts]). A STALE reading
 * cannot change, so it can only HOLD an alert its own LIVE frame already raised (it never
 * escalates one); dropping it instead would cancel and re-arm a critical alarm every time a low
 * pack missed two polls. The persisted seed and DISCONNECTED packs (unreachable, or silent past
 * [STALE_MAX_MS]) never drive anything — "we alert on live data, not on absence" still stands.
 */

/** Grace past a pack's poll interval before its reading stops counting as LIVE: two missed polls
 *  (2 × (4 s poll timeout + 0.5 s retry breather)). One routine missed notification on the stage
 *  — a ~6 s gap — must not flicker the stage to STALE. */
const val LIVE_GRACE_MS = 9_000L

/** Backstop: a pack still "reachable" with no parsed frame for this long reads DISCONNECTED.
 *  Normal paths drop the link well before this (5 misses ≈ 24 s, or within one poll of a
 *  STATE_DISCONNECTED since BLE-18); this bounds any path that doesn't. */
const val STALE_MAX_MS = 60_000L

sealed interface Freshness {
    /** A frame parsed within the pack's live window. */
    data object Live : Freshness

    /** Reachable with a reading, but none within the live window. [ageMs] = age of this
     *  session's last parsed frame; null = none yet this session (a carried seed, or nothing). */
    data class Stale(val ageMs: Long?) : Freshness

    /** Not reachable, or silent past [STALE_MAX_MS]. [ageMs] = age of this session's last parsed
     *  frame, for "last seen"; null when there is none (seed only, or never heard). */
    data class Disconnected(val ageMs: Long?) : Freshness
}

/**
 * Classify one pack's reading at [nowElapsedMs] (same clock as the frame stamp). [pollIntervalMs]
 * defaults to the cadence the pack was last polled at.
 */
fun freshness(
    status: BatteryStatus?,
    nowElapsedMs: Long,
    pollIntervalMs: Long = status?.frameIntervalMs ?: SLOW_POLL_MS,
): Freshness {
    // coerceAtLeast: the UI clock can be read a moment before the engine stamps a frame.
    val age = status?.lastFrameAtElapsedMs?.let { (nowElapsedMs - it).coerceAtLeast(0L) }
    if (status == null || !status.reachable) return Freshness.Disconnected(age)
    if (status.telemetry == null || age == null) return Freshness.Stale(null)
    if (age > STALE_MAX_MS) return Freshness.Disconnected(age)
    return if (age <= pollIntervalMs + LIVE_GRACE_MS) Freshness.Live else Freshness.Stale(age)
}

/** True when this reading may drive alerts, the seize and stage activity (see the file header). */
fun Freshness.drivesAlerts(): Boolean =
    this is Freshness.Live || (this is Freshness.Stale && ageMs != null)

/**
 * The fleet as decisions see it: every pack whose reading can't drive alerts (seed, silent,
 * unreachable) is marked unreachable, so the existing reachable-only pure functions
 * ([resolveStage], [groupActivity], the alert evaluators) need no change to respect freshness.
 * Telemetry is kept — only its standing to drive decisions is removed.
 */
fun decisionView(fleet: Map<String, BatteryStatus>, nowElapsedMs: Long): Map<String, BatteryStatus> =
    fleet.mapValues { (_, s) ->
        if (s.reachable && !freshness(s, nowElapsedMs).drivesAlerts()) s.copy(reachable = false) else s
    }

/** Compact age for "updated 14s ago" / "last seen 3 min ago". */
fun formatAge(ageMs: Long): String = when {
    ageMs < 60_000L -> "${ageMs / 1_000L}s"
    ageMs < 3_600_000L -> "${ageMs / 60_000L} min"
    ageMs < 86_400_000L -> "${ageMs / 3_600_000L} h"
    else -> "${ageMs / 86_400_000L} d"
}

/** The user-facing freshness phrase for a list/detail row, or null when LIVE (render the BMS
 *  state instead). [monitoring] distinguishes "out of range now" from "monitoring is off". */
fun freshnessLabel(f: Freshness, monitoring: Boolean): String? = when (f) {
    Freshness.Live -> null
    is Freshness.Stale -> f.ageMs?.let { "Updated ${formatAge(it)} ago" } ?: "Connecting…"
    is Freshness.Disconnected -> when {
        monitoring -> f.ageMs?.let { "Out of range · seen ${formatAge(it)} ago" } ?: "Out of range"
        else -> f.ageMs?.let { "Last seen ${formatAge(it)} ago" } ?: "Last known"
    }
}

/** Every pack's rendered label — the ViewModel's ticker publishes a new UI clock only when this
 *  map changes, so an all-LIVE fleet costs no recomposition at all (UI-26). */
fun freshnessLabels(
    fleet: Map<String, BatteryStatus>,
    nowElapsedMs: Long,
    monitoring: Boolean,
): Map<String, String?> =
    fleet.mapValues { (_, s) -> freshnessLabel(freshness(s, nowElapsedMs), monitoring) }
