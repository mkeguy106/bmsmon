package dev.joely.bmsmon.cloud

import dev.joely.bmsmon.model.TempEnvelope
import dev.joely.bmsmon.model.TempThresholds
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SampleJson(
    val ts_ms: Long,
    val address: String,
    val advertised_name: String? = null,
    val alias: String? = null,
    val group_id: String? = null,
    val state: String? = null,
    val soc: Float? = null,
    val current_a: Float? = null,
    val power_w: Float? = null,
    val voltage_v: Float? = null,
    val temp_c: Float? = null,
    val mosfet_temp_c: Int? = null,
    val soh: Int? = null,
    val full_charge_ah: Float? = null,
    val remaining_ah: Float? = null,
    val cycles: Int? = null,
    val cell_min_v: Float? = null,
    val cell_max_v: Float? = null,
    val regen: Boolean = false,
    val link_event: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val gps_accuracy_m: Float? = null,
    val eta_full_min: Float? = null,
)

object CloudJson {
    val json = Json { encodeDefaults = true; explicitNulls = false }

    // kotlinx JSON forbids NaN/Infinity (throws at encode time), and sampleJson runs synchronously
    // on the poll path — one garbage reading must not kill a pack's poller. Non-finite values are
    // mapped to null, which explicitNulls=false then omits from the payload entirely.
    private fun Float?.finiteOrNull(): Float? = this?.takeIf { it.isFinite() }
    private fun Double?.finiteOrNull(): Double? = this?.takeIf { it.isFinite() }

    @Suppress("LongParameterList")
    fun sampleJson(
        tsMs: Long, address: String, advertisedName: String?, alias: String?, groupId: String?,
        state: String?, soc: Float?, currentA: Float?, powerW: Float?, voltageV: Float?, tempC: Float?,
        mosfetTempC: Int?, soh: Int?, fullChargeAh: Float?, remainingAh: Float?, cycles: Int?,
        cellMinV: Float?, cellMaxV: Float?, regen: Boolean, linkEvent: String?,
        lat: Double? = null, lon: Double? = null, gpsAccuracyM: Float? = null,
        etaFullMin: Float? = null,
    ): String = json.encodeToString(
        SampleJson.serializer(),
        SampleJson(tsMs, address, advertisedName, alias, groupId, state,
            soc.finiteOrNull(), currentA.finiteOrNull(), powerW.finiteOrNull(),
            voltageV.finiteOrNull(), tempC.finiteOrNull(), mosfetTempC, soh,
            fullChargeAh.finiteOrNull(), remainingAh.finiteOrNull(), cycles,
            cellMinV.finiteOrNull(), cellMaxV.finiteOrNull(), regen, linkEvent,
            lat.finiteOrNull(), lon.finiteOrNull(), gpsAccuracyM.finiteOrNull(),
            etaFullMin.finiteOrNull()),
    )

    /** Wrap pre-serialized sample JSON object strings into the ingest batch body bytes. */
    fun encodeBatch(seq: Int, rows: List<String>): ByteArray =
        ("""{"batch_seq":$seq,"samples":[""" + rows.joinToString(",") + "]}").toByteArray()

    /**
     * One-way temperature-alert config push body (phone → cloud). [unit] is "C"/"F". Includes the
     * profile's fixed envelope (WEB-6c) so the web mirror renders the exact cutoffs / charge-lock
     * points the phone alerts on instead of hardcoding them.
     */
    fun encodeTempConfig(
        profileId: String, t: TempThresholds, env: TempEnvelope, unit: String, updatedAtMs: Long,
        seizeSoc: Int? = null, alertsOn: Boolean? = null,
    ): String =
        json.encodeToString(
            TempConfigJson.serializer(),
            TempConfigJson(
                profileId, t.coldCautionC, t.hotCautionC, t.coldCritC, t.hotCritC,
                unit, updatedAtMs,
                cutoff_cold_c = env.coldCutoffC, cutoff_hot_c = env.hotCutoffC,
                charge_lock_cold_c = env.chargeLockColdC, charge_lock_hot_c = env.chargeLockHotC,
                charge_resume_cold_c = env.chargeResumeColdC,
                seize_soc = seizeSoc, alerts_on = alertsOn,
            ),
        )
}

@Serializable
data class TempConfigJson(
    val profile_id: String,
    val cold_caution_c: Int,
    val hot_caution_c: Int,
    val cold_crit_c: Int,
    val hot_crit_c: Int,
    val unit: String,
    val updated_at_ms: Long,
    // Profile envelope (WEB-6c) — optional server-side; the server mirrors them when present.
    val cutoff_cold_c: Int? = null,
    val cutoff_hot_c: Int? = null,
    val charge_lock_cold_c: Int? = null,
    val charge_lock_hot_c: Int? = null,
    val charge_resume_cold_c: Int? = null,
    // Device-level capacity alert seize threshold (highest enabled ladder rung) + master on/off.
    // Rides the same one-way config push; the server upserts it into device_alert_config and the
    // WebUI mirrors it to drive its own low-pack stage seize. Null on temp-only pushes.
    val seize_soc: Int? = null,
    val alerts_on: Boolean? = null,
)
