package dev.joely.bmsmon

import android.app.Application
import dev.joely.bmsmon.cloud.TelemetryReporter
import dev.joely.bmsmon.data.SettingsStore
import dev.joely.bmsmon.data.db.BmsDatabase
import dev.joely.bmsmon.monitor.MonitorEngine

/**
 * Holds the process-lifetime [MonitorEngine] so BLE monitoring/logging survive Activity and
 * ViewModel recreation, kept alive in the background by the foreground MonitoringService.
 *
 * A single [BmsDatabase] instance is shared between the [MonitorEngine] (for telemetry/session
 * logging) and [TelemetryReporter] (for the cloud outbox), so the same `bms.db` is never opened
 * twice.
 */
class BmsApp : Application() {
    val settings by lazy { SettingsStore(this) }
    val db by lazy { BmsDatabase.create(this) }
    val reporter by lazy { TelemetryReporter(applicationContext, db, settings) }
    val engine by lazy { MonitorEngine(applicationContext, db, reporter, settings) }
}
