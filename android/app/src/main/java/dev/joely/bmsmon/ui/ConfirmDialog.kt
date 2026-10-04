package dev.joely.bmsmon.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import dev.joely.bmsmon.ui.theme.Bm

/** The words of one destructive-action confirmation (UI-22, UI-28): what happens, and what is lost. */
data class ConfirmCopy(val title: String, val body: String, val confirm: String)

object Confirmations {
    val stopMonitoring = ConfirmCopy(
        title = "Stop monitoring?",
        body = "Every pack disconnects, and low-battery and temperature alerts stop until you start monitoring again.",
        confirm = "Stop",
    )

    fun disconnectStagePack(name: String) = ConfirmCopy(
        title = "Disconnect $name?",
        body = "$name is on the main stage. It stops reporting and alerting until you reconnect it, even after a restart.",
        confirm = "Disconnect",
    )

    fun disconnectAll(count: Int) = ConfirmCopy(
        title = if (count == 1) "Disconnect 1 pack?" else "Disconnect all $count packs?",
        body = "A disconnected pack stops reporting and alerting until you reconnect it, even after a restart.",
        confirm = "Disconnect",
    )

    val clearData = ConfirmCopy(
        title = "Clear all logged history?",
        body = "Deletes every logged sample, session and debug frame on this phone. History and Review start " +
            "empty. Samples already queued for upload still send, but anything cleared here can no longer " +
            "be re-sent to the cloud. This can't be undone.",
        confirm = "Clear data",
    )

    val forgetDevice = ConfirmCopy(
        title = "Forget this device?",
        body = "Deletes this phone's upload key and enrollment. Uploads stop until you enroll again with a new " +
            "code from the web dashboard. Data already on the server is kept.",
        confirm = "Forget",
    )
}

@Composable
fun ConfirmDialog(copy: ConfirmCopy, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = Bm.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        title = { Text(copy.title, color = c.text) },
        text = { Text(copy.body, color = c.text2) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) { Text(copy.confirm, color = Bm.criticalText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.text2) } },
    )
}
