package dev.joely.bmsmon.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Thin wrapper over the ambient light sensor (TYPE_LIGHT). Emits lux via [start]'s callback.
 * Idempotent: [start] re-registers cleanly; [stop] is safe to call when not running.
 */
class AmbientLightSensor(context: Context) {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_LIGHT)
    private var listener: SensorEventListener? = null

    fun hasSensor(): Boolean = sensor != null

    fun start(onLux: (Float) -> Unit) {
        val m = manager ?: return
        val s = sensor ?: return
        stop()
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.isNotEmpty()) onLux(event.values[0])
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        listener = l
        m.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        val m = manager ?: return
        listener?.let { m.unregisterListener(it) }
        listener = null
    }
}
