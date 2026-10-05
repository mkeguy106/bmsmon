package dev.joely.bmsmon.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The phone-battery alarm levels on offer: 10, 15, … 95 (null = Off is offered separately). */
val PHONE_ALERT_OPTIONS: List<Int> = (10..95 step 5).toList()

/** [pct] is a level the server accepts for the phone-battery alarm. */
fun isPhoneAlertLevel(pct: Int): Boolean = pct in PHONE_ALERT_OPTIONS

/**
 * This phone's copy of the server's phone-battery alarm threshold. [lowPct] null = Off. [known] is
 * false until the first server answer or a local pick (the UI shows a dash until then). [changedMs]
 * is when the value was set (the server's change time when adopted, the phone's clock when picked
 * here); [dirty] means a local pick has not been acknowledged by the server yet.
 */
data class PhoneAlertLocal(
    val lowPct: Int? = null,
    val known: Boolean = false,
    val changedMs: Long = 0L,
    val dirty: Boolean = false,
)

/** What the server said: its threshold and when it was last changed (0 = never, the env default). */
data class PhoneAlertSync(val lowPct: Int?, val updatedAtMs: Long)

/**
 * Last change wins. Adopt the server's value unless the phone holds an un-pushed pick that is newer
 * than the server's change; that pick stays, its push is pending.
 */
fun reconcilePhoneAlert(local: PhoneAlertLocal, server: PhoneAlertSync): PhoneAlertLocal =
    if (!local.dirty || server.updatedAtMs >= local.changedMs) {
        PhoneAlertLocal(lowPct = server.lowPct, known = true, changedMs = server.updatedAtMs, dirty = false)
    } else {
        local
    }

/**
 * The `phone_alert` block of a response body, leniently: anything missing, malformed or out of range
 * is null (ignored), never an exception. `low_pct` JSON null means Off.
 */
fun parsePhoneAlert(body: String?): PhoneAlertSync? {
    if (body.isNullOrBlank()) return null
    val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
    val block = root["phone_alert"] as? JsonObject ?: return null
    val at = (block["updated_at_ms"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
        ?.takeIf { it >= 0 } ?: return null
    val low = block["low_pct"] ?: return null
    if (low is JsonNull) return PhoneAlertSync(null, at)
    val pct = (low as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull() ?: return null
    return if (isPhoneAlertLevel(pct)) PhoneAlertSync(pct, at) else null
}

/** The phone-alert change a pushed config body carried: [lowPct] (null = Off) set at [changedMs]. */
data class PhoneAlertSent(val lowPct: Int?, val changedMs: Long)

/** What [cfg] (a config push body) asked the server to apply, or null when it carried no change. */
fun sentPhoneAlert(cfg: String): PhoneAlertSent? {
    val root = runCatching { Json.parseToJsonElement(cfg) }.getOrNull() as? JsonObject ?: return null
    val at = (root["phone_low_pct_changed_ms"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return null
    val low = root["phone_low_pct"] ?: return null
    if (low is JsonNull) return PhoneAlertSent(null, at)
    return (low as? JsonPrimitive)?.content?.toIntOrNull()?.let { PhoneAlertSent(it, at) }
}

/** The Alerts picker's current-value text: a dash until the first answer, then "Off" or "NN%". */
fun phoneAlertLabel(p: PhoneAlertLocal): String =
    if (!p.known) "—" else p.lowPct?.let { "$it%" } ?: "Off"
