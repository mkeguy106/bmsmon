package dev.joely.bmsmon.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dev.joely.bmsmon.UiState
import dev.joely.bmsmon.ui.theme.AlertCritical
import dev.joely.bmsmon.ui.theme.Bm
import org.json.JSONObject

@Composable
internal fun ColumnScope.CloudSyncContent(
    state: UiState,
    onEnroll: (String, String) -> Unit,
    onSetCloudEnabled: (Boolean) -> Unit,
    onForget: () -> Unit,
) {
    val c = Bm.colors
    val host = state.apiBaseUrl
        ?.removePrefix("https://")
        ?.removePrefix("http://")
        ?.trimEnd('/')

    // --- Status ---
    SectionLabel("Status", top = 2.dp)
    PlainCard {
        Text(
            if (state.enrolled && host != null) "Enrolled · $host" else "Not set up",
            color = c.text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (state.enrolled) {
            Text(
                "Outbox: ${state.cloudOutboxDepth} samples",
                color = c.text2,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                if (!state.importDone) "Importing history…" else "History imported",
                color = c.text2,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    // --- Connection (shown only before enrollment) ---
    if (!state.enrolled) {
        val context = LocalContext.current
        val scanToEnroll = {
            val opts = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
            GmsBarcodeScanning.getClient(context, opts).startScan()
                .addOnSuccessListener { bc ->
                    bc.rawValue?.let { raw ->
                        runCatching {
                            val o = JSONObject(raw)
                            onEnroll(o.getString("base"), o.getString("code"))
                        }
                    }
                }
        }

        SectionLabel("Connection")
        PlainCard {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Bm.accent)
                    .clickable { scanToEnroll() }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Scan QR to enroll", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "Open Cloud sync on the bmsmon web dashboard, tap Enroll device, and scan the QR.",
                color = c.text3,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 8.dp, bottom = 14.dp),
            )
            Text("Or enter manually", color = c.text2, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
            var serverUrl by remember { mutableStateOf("") }
            var enrollCode by remember { mutableStateOf("") }
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("Server URL") },
                placeholder = { Text("https://bmsmon.covert.life") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = enrollCode,
                onValueChange = { enrollCode = it },
                label = { Text("Enrollment code") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Bm.accent)
                    .clickable { onEnroll(serverUrl, enrollCode) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Enroll", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    // --- Reporting toggle ---
    SectionLabel("Reporting")
    GroupedCard {
        ToggleRow(
            "Report to cloud",
            "Send live telemetry to your bmsmon server.",
            state.cloudEnabled,
            onSetCloudEnabled,
        )
    }

    // --- Forget device (enrolled only) ---
    if (state.enrolled) {
        SectionLabel("Danger zone")
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, AlertCritical.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                .clickable(onClick = onForget)
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Forget device", color = AlertCritical, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "Removes the device key and enrollment. The server retains uploaded data.",
            color = c.text3,
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}
