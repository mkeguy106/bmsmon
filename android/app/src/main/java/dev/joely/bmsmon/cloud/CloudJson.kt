package dev.joely.bmsmon.cloud

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
        SampleJson(tsMs, address, advertisedName, alias, groupId, state, soc, currentA, powerW,
            voltageV, tempC, mosfetTempC, soh, fullChargeAh, remainingAh, cycles, cellMinV, cellMaxV,
            regen, linkEvent, lat, lon, gpsAccuracyM, etaFullMin),
    )

    /** Wrap pre-serialized sample JSON object strings into the ingest batch body bytes. */
    fun encodeBatch(seq: Int, rows: List<String>): ByteArray =
        ("""{"batch_seq":$seq,"samples":[""" + rows.joinToString(",") + "]}").toByteArray()

    /** One-way temperature-alert config push body (phone → cloud). [unit] is "C"/"F". */
    fun encodeTempConfig(profileId: String, t: TempThresholds, unit: String, updatedAtMs: Long): String =
        json.encodeToString(
            TempConfigJson.serializer(),
            TempConfigJson(profileId, t.coldCautionC, t.hotCautionC, t.coldCritC, t.hotCritC,
                unit, updatedAtMs),
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
)
