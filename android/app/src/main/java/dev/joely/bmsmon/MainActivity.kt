package dev.joely.bmsmon

import android.os.Bundle
import android.view.WindowManager
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
        // Wheelchair-mounted monitor: hold the screen on at the user's brightness so it
        // never enters the pre-timeout dim. Independent of the "Stay awake" dev option.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            App(vm)
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back to the app => immediately retry out-of-range batteries.
        vm.onAppForeground()
    }
}
