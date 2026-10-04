package dev.joely.bmsmon.monitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.joely.bmsmon.MainActivity
import dev.joely.bmsmon.R
import dev.joely.bmsmon.model.AlertEval
import dev.joely.bmsmon.model.NOTIFY_VANISH_GRACE_MS
import dev.joely.bmsmon.model.PackTemp
import dev.joely.bmsmon.model.TempAlarm
import dev.joely.bmsmon.model.TempRank
import dev.joely.bmsmon.model.reconcileFleetNotifications
import dev.joely.bmsmon.model.reconcileTempNotifications

/**
 * Headless low-SOC alert notifications. Driven by the engine on each fleet update; the dedup
 * decision is the pure [nextNotifyDecision]. Critical alerts use a high-importance channel
 * (heads-up + sound + vibration); warnings use a silent low-importance channel. Distinct from the
 * foreground-service ongoing notification.
 */
/** One pack's capacity evaluation plus its display label, fed to [AlertNotifier.updateFleet]. */
data class PackAlert(val eval: AlertEval, val label: String?)

class AlertNotifier(private val context: Context) {

    private val nm = NotificationManagerCompat.from(context)
    /** Per-address notification baseline (last band notified) — one entry per low pack. */
    private val lastByAddr = mutableMapOf<String, Int?>()
    /** Stable notification id per address so two low packs show two distinct notifications. */
    private val idByAddr = mutableMapOf<String, Int>()
    /** When each low pack held through an absence dropped out of the evaluations (BLE-24), on the
     *  engine's monotonic clock. Like every field here, confined to MonitorEngine's lock (BLE-20):
     *  all calls come from its @Synchronized reevaluate() or stop()'s synchronized block. */
    private val vanishedAt = mutableMapOf<String, Long>()
    private var nextCapId = NOTIF_CAP_BASE
    /** Per-pack temperature baseline (side + rank notified) and hold clock — same lock confinement. */
    private val lastTempByAddr = mutableMapOf<String, TempAlarm>()
    private val tempHeldSince = mutableMapOf<String, Long>()
    private val tempIdByAddr = mutableMapOf<String, Int>()
    private var nextTempId = NOTIF_TEMP_BASE

    init {
        createChannels()
        // The single temperature notification of earlier versions: per-pack ids replace it, so an
        // update must not leave it behind. Still alarming → re-posted on the first live reading.
        runCatching { nm.cancel(NOTIF_TEMP_LEGACY_ID) }
    }

    /**
     * Apply the latest fleet-wide capacity evaluation: post / escalate / stay quiet / cancel, per
     * pack. Every reachable low pack gets its own notification (deduped per address), so a second
     * low pack is never masked by the one currently on the stage. A recovered or charging pack is
     * cancelled at once; one that drops out of [evals] keeps its notification for
     * [NOTIFY_VANISH_GRACE_MS] if it is in [holdable] (BLE-24), then is cancelled.
     */
    fun updateFleet(evals: Map<String, PackAlert>, holdable: Set<String>, nowElapsedMs: Long) {
        val plan = reconcileFleetNotifications(
            evals.mapValues { it.value.eval }, lastByAddr, vanishedAt, holdable, nowElapsedMs, NOTIFY_VANISH_GRACE_MS,
        )
        plan.cancel.forEach { addr -> idByAddr[addr]?.let { nm.cancel(it) } }
        plan.notify.forEach { addr -> evals[addr]?.let { post(addr, it.eval, it.label) } }
        lastByAddr.clear()
        lastByAddr.putAll(plan.newLast)
        vanishedAt.clear()
        vanishedAt.putAll(plan.vanishedAt)
    }

    /**
     * Headless temperature alerts (BLE-24): one notification per stage pack at CRITICAL or worse, on
     * the critical channel (loud — same urgency as a critical capacity alert), deduped per pack by
     * side + rank so each fires once per crossing / escalation, and cancelled when that pack recovers.
     * [temps] are the packs with an alert-driving reading. A notified pack that goes silent on the
     * stage — or leaves it while still read that hot — keeps its notification for
     * [NOTIFY_VANISH_GRACE_MS] if it is in [holdable] ([reconcileTempNotifications]). [label] names a
     * pack's base for the title; [detail] is the body.
     */
    fun updateTemp(
        temps: Map<String, PackTemp>,
        stage: Set<String>,
        holdable: Set<String>,
        nowElapsedMs: Long,
        label: (String) -> String?,
        detail: (PackTemp) -> String,
    ) {
        val plan = reconcileTempNotifications(
            temps.mapValues { it.value.zone }, stage, lastTempByAddr, tempHeldSince, holdable, nowElapsedMs,
            NOTIFY_VANISH_GRACE_MS,
        )
        plan.cancel.forEach { addr -> tempIdByAddr[addr]?.let { nm.cancel(it) } }
        plan.notify.forEach { addr ->
            val t = temps[addr] ?: return@forEach
            val where = label(addr)?.let { " · $it" } ?: ""
            val title = if (t.zone.rank == TempRank.CUTOFF) "Temperature cutoff$where" else "Critical temperature$where"
            postCritical(tempIdByAddr.getOrPut(addr) { nextTempId++ }, title, detail(t))
        }
        lastTempByAddr.clear()
        lastTempByAddr.putAll(plan.newLast)
        tempHeldSince.clear()
        tempHeldSince.putAll(plan.heldSince)
    }

    /** Clear any active alert notification (e.g. monitoring stopped). */
    fun clear() {
        idByAddr.values.forEach { nm.cancel(it) }
        lastByAddr.clear()
        vanishedAt.clear()
        tempIdByAddr.values.forEach { nm.cancel(it) }
        lastTempByAddr.clear()
        tempHeldSince.clear()
    }

    private fun postCritical(id: Int, title: String, text: String) {
        val open = PendingIntent.getActivity(
            context, 3,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CH_CRITICAL)
            .setContentTitle(title).setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentIntent(open).setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            try { nm.notify(id, n) } catch (_: SecurityException) {}
        }
    }

    private fun post(addr: String, eval: AlertEval, packLabel: String?) {
        val where = packLabel?.let { " · $it" } ?: ""
        val title = if (eval.critical) "Critical battery${where}" else "Low battery${where}"
        val text = "${packLabel ?: "Pack"} at ${eval.lowSoc}% (alert level ${eval.activeThreshold}%)"
        val open = PendingIntent.getActivity(
            context, 2,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, if (eval.critical) CH_CRITICAL else CH_WARNING)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(if (eval.critical) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .build()
        // POST_NOTIFICATIONS may be denied; notify() is a no-op then (the in-app flash still shows).
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            try { nm.notify(idFor(addr), n) } catch (_: SecurityException) {}
        }
    }

    /** Stable per-address notification id (assigned on first use), so each low pack owns a slot. */
    private fun idFor(addr: String): Int = idByAddr.getOrPut(addr) { nextCapId++ }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val mgr = context.getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CH_CRITICAL, "Critical battery alerts", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = "Loud alert when a stage pack drops to your critical level."
                    enableVibration(true)
                },
        )
        mgr.createNotificationChannel(
            NotificationChannel(CH_WARNING, "Battery warnings", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Quiet alert when a stage pack drops to a warning level." },
        )
    }

    private companion object {
        const val CH_CRITICAL = "alerts_critical"
        const val CH_WARNING = "alerts_warning"
        const val NOTIF_TEMP_LEGACY_ID = 3  // the old single temperature alert (cancelled at start-up)
        // Per-pack capacity alerts get ids from here up (100, 101, …) — clear of the FGS ongoing
        // notification (1) and the legacy temperature alert (3), so multiple low packs never collide.
        const val NOTIF_CAP_BASE = 100
        // Per-pack temperature alerts from here up (200, 201, …), clear of the capacity range.
        const val NOTIF_TEMP_BASE = 200
    }
}
