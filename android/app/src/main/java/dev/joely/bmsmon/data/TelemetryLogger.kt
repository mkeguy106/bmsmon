package dev.joely.bmsmon.data

import android.content.Context
import android.util.Log
import dev.joely.bmsmon.model.Telemetry
import java.io.File

/**
 * Appends telemetry samples to a CSV in the app's external files dir, for collecting
 * real-world usage (e.g. to find the chair's peak current/power draw). Pull with:
 *   adb pull /sdcard/Android/data/dev.joely.bmsmon/files/usage_log.csv
 */
class TelemetryLogger(context: Context) {

    private val file = File(context.getExternalFilesDir(null), "usage_log.csv")
    private val lock = Any()

    val path: String get() = file.absolutePath

    fun log(address: String, t: Telemetry, timestampMs: Long, regen: Boolean) {
        synchronized(lock) {
            try {
                val header = !file.exists() || file.length() == 0L
                file.appendText(buildString {
                    if (header) append("timestamp_ms,name,address,state,soc,current_a,power_w,voltage_v,regen\n")
                    append(timestampMs).append(',')
                    append(t.name).append(',')
                    append(address).append(',')
                    append(t.state.name).append(',')
                    append(t.soc.toInt()).append(',')
                    append("%.3f".format(t.current)).append(',')
                    append("%.2f".format(t.powerW)).append(',')
                    append("%.3f".format(t.voltage)).append(',')
                    append(if (regen) 1 else 0).append('\n')
                })
            } catch (e: Exception) {
                Log.d("TelemetryLogger", "log: ${e.message}")
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            try { file.delete() } catch (e: Exception) { Log.d("TelemetryLogger", "clear: ${e.message}") }
        }
    }

    fun sizeBytes(): Long = synchronized(lock) { if (file.exists()) file.length() else 0L }
}
