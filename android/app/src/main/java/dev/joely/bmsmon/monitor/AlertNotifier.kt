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
import dev.joely.bmsmon.model.nextNotifyDecision

/**
 * Headless low-SOC alert notifications. Driven by the engine on each fleet update; the dedup
 * decision is the pure [nextNotifyDecision]. Critical alerts use a high-importance channel
 * (heads-up + sound + vibration); warnings use a silent low-importance channel. Distinct from the
 * foreground-service ongoing notification.
 */
class AlertNotifier(private val context: Context) {

    private val nm = NotificationManagerCompat.from(context)
    private var lastNotified: Int? = null
    private var lastTempKey: String? = null

    init { createChannels() }

    /** Apply the latest stage evaluation: post / escalate / stay quiet / cancel. */
    fun update(eval: AlertEval, stageLabel: String?) {
        val d = nextNotifyDecision(eval, lastNotified)
        lastNotified = d.newLastNotified
        when {
            d.cancel -> nm.cancel(NOTIF_ID)
            d.notify -> post(eval, stageLabel)
        }
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
        lastNotified = null
        lastTempKey = null
        nm.cancel(NOTIF_ID)
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

    private fun post(eval: AlertEval, stageLabel: String?) {
        val where = stageLabel?.let { " · $it" } ?: ""
        val title = if (eval.critical) "Critical battery${where}" else "Low battery${where}"
        val text = "Stage at ${eval.lowSoc}% (alert level ${eval.activeThreshold}%)"
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
            try { nm.notify(NOTIF_ID, n) } catch (_: SecurityException) {}
        }
    }

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
        const val NOTIF_ID = 2       // capacity alert; distinct from the FGS ongoing notification (1)
        const val NOTIF_TEMP_ID = 3  // temperature alert
    }
}
