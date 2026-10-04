package dev.joely.bmsmon

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.joely.bmsmon.ui.App

class MainActivity : ComponentActivity() {
    private val vm: BatteryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The "keep screen on" window flag is applied reactively from Compose (App) so the
        // Settings toggle takes effect immediately.
        setContent {
            App(vm)
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back to the app => retry the stage's batteries at once and out-of-range spares at
        // most once per 5 min (BLE-16).
        vm.onAppForeground()
    }

    override fun onPause() {
        super.onPause()
        // Flush the latest readings so they survive a process kill (restored next launch).
        vm.onAppBackground()
    }
}
