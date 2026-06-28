package dev.joely.bmsmon

import android.app.Application
import dev.joely.bmsmon.monitor.MonitorEngine

/**
 * Holds the process-lifetime [MonitorEngine] so BLE monitoring/logging survive Activity and
 * ViewModel recreation, kept alive in the background by the foreground MonitoringService.
 */
class BmsApp : Application() {
    val engine: MonitorEngine by lazy { MonitorEngine(applicationContext) }
}
