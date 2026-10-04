package dev.joely.bmsmon.ui.settings

import dev.joely.bmsmon.ui.Confirmations
import dev.joely.bmsmon.ui.ConfirmDialog
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
import dev.joely.bmsmon.ui.CloudActions
import dev.joely.bmsmon.ui.theme.AlertCritical
import dev.joely.bmsmon.ui.theme.Bm
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** The Cloud sync status line for DATA-22 skips — null (no line at all) until at least one. */
internal fun serverFaultSkipsLine(skipped: Long): String? = when {
    skipped <= 0L -> null
    skipped == 1L -> "1 sample the server could not store was skipped"
    else -> "$skipped samples the server could not store were skipped"
}

/** The enroll section shows before enrollment, and again when an enrolled phone can't authenticate (no key, or the server rejects it). */
internal fun showReenroll(enrolled: Boolean, keyMissing: Boolean, authFailed: Boolean): Boolean =
    !enrolled || keyMissing || authFailed

@Composable
internal fun ColumnScope.CloudSyncContent(
    state: UiState,
    cloud: CloudActions,
) {
    val (onEnroll, onSetCloudEnabled, onForget, onSetGpsEnabled) = cloud
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
            val s = state.cloud
            Text(
                "Outbox: ${s.outboxDepth} samples",
                color = c.text2,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            // Most actionable first; the sign-in state outranks the hold (which can lag).
            authLine(s)?.let { StatusText(it, Bm.criticalText) }
            clockCorrectionLine(s)?.let { StatusText(it, c.text2) }
            holdLine(s)?.let { StatusText(it, Bm.warnText) }
            serverFaultSkipsLine(s.serverFaultSkips)?.let { StatusText(it, Bm.warnText) }
            evictedLine(s.outboxEvicted)?.let { StatusText(it, Bm.warnText) }
            resyncLine(s.resync, ::formatResyncTime, System.currentTimeMillis())?.let { StatusText(it, c.text2) }
        }
    }

    // --- Connection (shown only before enrollment) ---
    if (showReenroll(state.enrolled, state.cloud.keyMissing, state.cloud.authFailed)) {
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

        SectionLabel(if (state.enrolled) "Re-enroll this phone" else "Connection")
        PlainCard {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Bm.accent)
                    .clickable(enabled = !state.enrolling) { scanToEnroll() }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (state.enrolling) "Enrolling…" else "Scan QR to enroll", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
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
                    .clickable(enabled = !state.enrolling) { onEnroll(serverUrl, enrollCode) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (state.enrolling) "Enrolling…" else "Enroll", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            state.enrollError?.let { err ->
                Text(err, color = Bm.criticalText, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
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

    // --- GPS location toggle (enrolled only) ---
    if (state.enrolled) {
        GroupedCard {
            ToggleRow(
                "Send GPS location",
                "Attach the phone's location to each upload. Needs location permission.",
                state.gpsEnabled,
                onSetGpsEnabled,
            )
        }
        Text(
            "Location is attached to each upload while permitted. Used later for mapping.",
            color = c.text3,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp),
        )
    }

    // --- Forget device (enrolled only) ---
    if (state.enrolled) {
        SectionLabel("Danger zone")
        var confirmForget by remember { mutableStateOf(false) }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, AlertCritical.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                .clickable { confirmForget = true }
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
        if (confirmForget) {
            ConfirmDialog(Confirmations.forgetDevice, onConfirm = onForget, onDismiss = { confirmForget = false })
        }
    }
}

@Composable
private fun StatusText(text: String, color: Color) {
    Text(text, color = color, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
}

/** Where the re-send resumes, in the phone's own date and time format (the user reads European formats). */
private fun formatResyncTime(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))
