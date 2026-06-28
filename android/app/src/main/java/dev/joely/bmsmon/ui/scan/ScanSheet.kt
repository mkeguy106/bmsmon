package dev.joely.bmsmon.ui.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.joely.bmsmon.ble.BleScanner
import dev.joely.bmsmon.ble.DiscoveredDevice
import dev.joely.bmsmon.ble.hasBlePermissions
import dev.joely.bmsmon.model.Roster
import dev.joely.bmsmon.model.batteryAt
import dev.joely.bmsmon.ui.theme.Bm
import dev.joely.bmsmon.ui.theme.MonoFont

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanSheet(
    roster: Roster,
    onAdd: (address: String, name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Bm.colors
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val found = remember { mutableStateMapOf<String, DiscoveredDevice>() }
    val hasPerms = hasBlePermissions(context)

    DisposableEffect(Unit) {
        val scanner = BleScanner(context)
        if (hasPerms) scanner.start { dev -> found[dev.address] = dev }
        onDispose { scanner.stop() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = c.card) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 28.dp)) {
            Text("Add a battery", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                if (!hasPerms) "Bluetooth permission needed — enable it, then reopen."
                else "Scanning for nearby batteries…",
                color = c.text3, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            val devices = found.values.sortedBy { it.name }
            if (devices.isEmpty()) {
                Text("No batteries found yet.", color = c.text3, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 16.dp))
            }
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                items(devices, key = { it.address }) { dev ->
                    val existing = roster.batteryAt(dev.address)
                    ScanRow(dev, existingAlias = existing?.alias, onAdd = { onAdd(dev.address, dev.name) })
                }
            }
        }
    }
}

@Composable
private fun ScanRow(dev: DiscoveredDevice, existingAlias: String?, onAdd: () -> Unit) {
    val c = Bm.colors
    val known = existingAlias != null
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(c.card2)
            .then(if (known) Modifier else Modifier.clickable(onClick = onAdd))
            .alpha(if (known) 0.45f else 1f)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.padding(end = 8.dp)) {
                Text(dev.name, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(dev.address, color = c.text3, fontFamily = MonoFont, fontSize = 11.sp)
            }
            Text(
                if (known) "Added as $existingAlias" else "+ Add",
                color = if (known) c.text3 else Bm.accent,
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
