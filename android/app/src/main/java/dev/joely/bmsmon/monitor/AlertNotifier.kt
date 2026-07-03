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
import dev.joely.bmsmon.model.TempRank
import dev.joely.bmsmon.model.TempSide
import dev.joely.bmsmon.model.reconcileFleetNotifications

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
    private var nextCapId = NOTIF_CAP_BASE
    private var lastTempKey: String? = null

    init { createChannels() }

    /**
     * Apply the latest fleet-wide capacity evaluation: post / escalate / stay quiet / cancel, per
     * pack. Every reachable low pack gets its own notification (deduped per address), so a second
     * low pack is never masked by the one currently on the stage. Packs absent from [evals] (gone
     * unreachable or recovered) are cancelled.
     */
    fun updateFleet(evals: Map<String, PackAlert>) {
        val plan = reconcileFleetNotifications(evals.mapValues { it.value.eval }, lastByAddr)
        plan.cancel.forEach { addr -> idByAddr[addr]?.let { nm.cancel(it) } }
        plan.notify.forEach { addr -> evals[addr]?.let { post(addr, it.eval, it.label) } }
        lastByAddr.clear()
        lastByAddr.putAll(plan.newLast)
    }

    /**
     * Headless temperature alert: posts on the critical channel when the worst stage pack is at
     * rank >= CRITICAL (loud — same urgency as a critical capacity alert), deduped by side+rank so it
     * fires once per crossing/escalation; cancels when temperature recovers below CRITICAL.
     */
    fun updateTemp(rank: TempRank, side: TempSide, stageLabel: String?, detail: String) {
        val key = if (rank.ordinal >= TempRank.CRITICAL.ordinal) "$side:$rank" else null
        if (key == null) {
            if (lastTempKey != null) { lastTempKey = null; nm.cancel(NOTIF_TEMP_ID) }
            return
        }
        if (key == lastTempKey) return  // same band — no repeat
        lastTempKey = key
        val where = stageLabel?.let { " · $it" } ?: ""
        val title = if (rank == TempRank.CUTOFF) "Temperature cutoff$where" else "Critical temperature$where"
        postCritical(NOTIF_TEMP_ID, title, detail)
    }

    /** Clear any active alert notification (e.g. monitoring stopped). */
    fun clear() {
        idByAddr.values.forEach { nm.cancel(it) }
        lastByAddr.clear()
        lastTempKey = null
        nm.cancel(NOTIF_TEMP_ID)
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
        const val NOTIF_TEMP_ID = 3  // temperature alert (single, stage-worst driven)
        // Per-pack capacity alerts get ids from here up (100, 101, …) — clear of the FGS ongoing
        // notification (1) and the temperature alert (3), so multiple low packs never collide.
        const val NOTIF_CAP_BASE = 100
    }
}
