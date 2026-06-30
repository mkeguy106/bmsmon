package dev.joely.bmsmon

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.joely.bmsmon.model.BatteryState
import dev.joely.bmsmon.model.GaugeSide
import dev.joely.bmsmon.model.StageItem
import dev.joely.bmsmon.model.Telemetry
import dev.joely.bmsmon.model.TempEnvelope
import dev.joely.bmsmon.model.TempThresholds
import dev.joely.bmsmon.ui.home.DangerOverlay
import dev.joely.bmsmon.ui.home.StageScreen
import dev.joely.bmsmon.ui.theme.Bm
import dev.joely.bmsmon.ui.theme.BmTheme

/**
 * Debug-only visual harness for emulator screenshots — the live stage gauge + alert overlay need
 * BLE telemetry the emulator can't provide, so this renders them with synthetic packs. Launch:
 *   adb shell am start -n dev.joely.bmsmon/.TempPreviewActivity --ez dark true --ez overlay true
 * Not present in release builds. Verifies the gauge, TEMP stat, and DangerOverlay headline/detail.
 */
class TempPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = intent.getBooleanExtra("dark", true)
        val overlay = intent.getBooleanExtra("overlay", false)
        setContent { TempPreview(dark = dark, showOverlay = overlay) }
    }
}

private fun tel(name: String, soc: Float, tempC: Float, state: BatteryState = BatteryState.Discharging) =
    Telemetry(name = name, soc = soc, powerW = 110f, current = -8.4f, voltage = 13.2f,
        capacityAh = 100f, cellV = 3.3f, temp = tempC, state = state)

@Composable
private fun TempPreview(dark: Boolean, showOverlay: Boolean) {
    BmTheme(dark = dark, accent = Color(0xFFE67E22), power = Color(0xFFC85A1A)) {
        val items = remember {
            listOf(
                StageItem(tel("2012 · A", 78f, -14f), regen = false, connected = true),  // cold-critical
                StageItem(tel("2012 · B", 80f, 24f), regen = false, connected = true),    // safe
            )
        }
        Box(Modifier.fillMaxSize().background(Bm.colors.bg)) {
            StageScreen(
                items = items,
                tempInF = true,
                isEmpty = false,
                onAddScan = {},
                showTempGauge = true,
                tempGaugeSide = GaugeSide.LEFT,
                thresholds = TempThresholds(),
                envelope = TempEnvelope(),
                modifier = Modifier.padding(top = 8.dp),
            )
            if (showOverlay) {
                val alert by remember {
                    mutableStateOf(
                        StageAlert(
                            flashing = true, critical = true, lowSoc = 78, activeThreshold = null,
                            ackEffective = emptySet(),
                            kind = dev.joely.bmsmon.model.AlertKind.TEMPERATURE,
                            headline = "TEMPERATURE",
                            detail = "CRITICAL · COLD · 2012·A · 11°F TO CUTOFF",
                            present = true,
                        ),
                    )
                }
                DangerOverlay(alert) {}
            }
        }
    }
}
