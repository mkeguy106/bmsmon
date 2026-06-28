package dev.joely.bmsmon.data

import android.content.Context
import android.util.Log
import dev.joely.bmsmon.model.Telemetry
import java.io.File

/**
 * Appends telemetry samples to a CSV in the app's external files dir, for collecting
 * real-world usage (e.g. to find the chair's peak current/power draw). Pull with:
 *   adb pull /sdcard/Android/data/dev.joely.bmsmon/files/usage_log.csv
 *
 * The log is size-capped and rolling: once [MAX_LOG_BYTES] is reached the current file is rotated
 * to `usage_log.1.csv` (replacing any previous archive) and a fresh file is started, so background
 * 24/7 logging stays bounded at ~2× the cap on disk. Pull `usage_log.1.csv` too for older history.
 */
class TelemetryLogger(context: Context) {

    private val file = File(context.getExternalFilesDir(null), "usage_log.csv")
    private val archive = File(context.getExternalFilesDir(null), "usage_log.1.csv")
    private val lock = Any()

    val path: String get() = file.absolutePath

    /** Caller must hold [lock]. Roll the log over once it hits the cap, keeping one archive. */
    private fun rotateIfNeeded() {
        if (file.length() < MAX_LOG_BYTES) return
        if (archive.exists()) archive.delete()
        file.renameTo(archive)  // next append re-creates usage_log.csv with a fresh header
    }

    fun log(address: String, t: Telemetry, timestampMs: Long, regen: Boolean) {
        synchronized(lock) {
            try {
                rotateIfNeeded()
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

    /**
     * Record a BLE connection event (e.g. a stage pack dropping or returning) so a transient
     * link glitch is visible in the log and can be told apart from a real low/idle reading.
     * Telemetry columns are left blank; [state] carries "Connected"/"Disconnected".
     */
    fun event(address: String, name: String, state: String, timestampMs: Long) {
        synchronized(lock) {
            try {
                rotateIfNeeded()
                val header = !file.exists() || file.length() == 0L
                file.appendText(buildString {
                    if (header) append("timestamp_ms,name,address,state,soc,current_a,power_w,voltage_v,regen\n")
                    append(timestampMs).append(',')
                    append(name).append(',')
                    append(address).append(',')
                    append(state).append(',')
                    append(",,,,0\n")  // blank soc/current/power/voltage, regen=0
                })
            } catch (e: Exception) {
                Log.d("TelemetryLogger", "event: ${e.message}")
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            try {
                file.delete()
                archive.delete()
            } catch (e: Exception) { Log.d("TelemetryLogger", "clear: ${e.message}") }
        }
    }

    fun sizeBytes(): Long = synchronized(lock) {
        (if (file.exists()) file.length() else 0L) + (if (archive.exists()) archive.length() else 0L)
    }

    private companion object {
        /** Roll the log once the active file reaches this size (one archive kept => ~2x on disk). */
        const val MAX_LOG_BYTES = 50L * 1024 * 1024  // 50 MB
    }
}
