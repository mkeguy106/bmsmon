package dev.joely.bmsmon.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudJsonTest {
    @Test fun config_builds_endpoints_trimming_slash() {
        val c = CloudConfig("https://bmsmon.covert.life/")
        assertEquals("https://bmsmon.covert.life/api/v1/ingest", c.ingestUrl)
        assertEquals("https://bmsmon.covert.life/api/v1/enroll", c.enrollUrl)
    }

    @Test fun sampleJson_emits_server_field_names() {
        val s = CloudJson.sampleJson(
            tsMs = 1719686400000, address = "C8:47:80:15:67:44", advertisedName = "R-12100",
            alias = "2012 · A", groupId = "2012", state = "Discharging", soc = 87f, currentA = -2.5f,
            powerW = 127.5f, voltageV = 51f, tempC = 25f, mosfetTempC = 28, soh = 98,
            fullChargeAh = 100f, remainingAh = 87.5f, cycles = 342, cellMinV = 3.17f, cellMaxV = 3.19f,
            regen = false, linkEvent = null)
        assertTrue(s.contains("\"ts_ms\":1719686400000"))
        assertTrue(s.contains("\"current_a\":-2.5"))
        assertTrue(s.contains("\"group_id\":\"2012\""))
        assertTrue(!s.contains("\"link_event\""))   // explicitNulls=false drops nulls
    }

    @Test fun encodeBatch_wraps_rows() {
        val body = CloudJson.encodeBatch(7, listOf("""{"ts_ms":1,"address":"A"}"""))
        val str = String(body)
        assertEquals("""{"batch_seq":7,"samples":[{"ts_ms":1,"address":"A"}]}""", str)
    }

    @Test fun sampleJson_includes_gps_when_present() {
        val s = CloudJson.sampleJson(
            tsMs = 1L, address = "A", advertisedName = null, alias = null, groupId = null,
            state = null, soc = null, currentA = null, powerW = null, voltageV = null, tempC = null,
            mosfetTempC = null, soh = null, fullChargeAh = null, remainingAh = null, cycles = null,
            cellMinV = null, cellMaxV = null, regen = false, linkEvent = null,
            lat = 41.8781, lon = -87.6298, gpsAccuracyM = 7.5f)
        assertTrue(s.contains("\"lat\":41.8781"))
        assertTrue(s.contains("\"lon\":-87.6298"))
        assertTrue(s.contains("\"gps_accuracy_m\":7.5"))
    }

    @Test fun sampleJson_sanitizes_non_finite_floats_without_throwing() {
        // kotlinx JSON forbids NaN/Infinity; a garbage reading must not kill the poll path.
        val s = CloudJson.sampleJson(
            tsMs = 1L, address = "A", advertisedName = null, alias = null, groupId = null,
            state = "Charging", soc = Float.NaN, currentA = 1.2f,
            powerW = Float.POSITIVE_INFINITY, voltageV = 13.2f, tempC = Float.NEGATIVE_INFINITY,
            mosfetTempC = null, soh = null, fullChargeAh = null, remainingAh = null, cycles = null,
            cellMinV = null, cellMaxV = null, regen = false, linkEvent = null,
            lat = Double.NaN, lon = -87.6298, gpsAccuracyM = null, etaFullMin = Float.NaN)
        // Non-finite fields are dropped (mapped to null; explicitNulls=false omits them)…
        assertTrue(!s.contains("\"soc\""))
        assertTrue(!s.contains("\"power_w\""))
        assertTrue(!s.contains("\"temp_c\""))
        assertTrue(!s.contains("\"lat\""))
        assertTrue(!s.contains("\"eta_full_min\""))
        // …while finite fields survive untouched.
        assertTrue(s.contains("\"current_a\":1.2"))
        assertTrue(s.contains("\"voltage_v\":13.2"))
        assertTrue(s.contains("\"lon\":-87.6298"))
    }

    @Test fun tempConfig_includes_profile_envelope_fields() {
        // WEB-6c: the config push carries the profile's fixed envelope so the web mirror renders
        // the exact cutoffs / charge-lock points the phone alerts on (server fields optional).
        val s = CloudJson.encodeTempConfig(
            profileId = "redodo-beken-12v100",
            t = dev.joely.bmsmon.model.TempThresholds(),          // Redodo defaults 5/45/-12/53
            env = dev.joely.bmsmon.model.TempEnvelope(),          // Redodo defaults -20/60/0/5/50
            unit = "F", updatedAtMs = 123L,
        )
        assertTrue(s.contains("\"profile_id\":\"redodo-beken-12v100\""))
        assertTrue(s.contains("\"cold_crit_c\":-12"))
        assertTrue(s.contains("\"cutoff_cold_c\":-20"))
        assertTrue(s.contains("\"cutoff_hot_c\":60"))
        assertTrue(s.contains("\"charge_lock_cold_c\":0"))
        assertTrue(s.contains("\"charge_lock_hot_c\":50"))
        assertTrue(s.contains("\"charge_resume_cold_c\":5"))
        assertTrue(s.contains("\"unit\":\"F\""))
        assertTrue(s.contains("\"updated_at_ms\":123"))
    }

    @Test fun tempConfig_includes_ranges_when_present() {
        val s = CloudJson.encodeTempConfig(
            profileId = "redodo-beken-12v100",
            t = dev.joely.bmsmon.model.TempThresholds(),
            env = dev.joely.bmsmon.model.TempEnvelope(),
            unit = "F", updatedAtMs = 123L,
            ranges = mapOf(
                "C8:47:80:15:25:01" to dev.joely.bmsmon.model.RangeParams(
                    whPerDay = dev.joely.bmsmon.model.Band(78f, 182f),
                    activeW = dev.joely.bmsmon.model.Band(52.5f, 97.5f),
                    whPerMile = dev.joely.bmsmon.model.Band(15f, 25f),
                    learnedDays = 6, updatedMs = 456L,
                ),
            ),
        )
        assertTrue(s.contains("\"address\":\"C8:47:80:15:25:01\""))
        assertTrue(s.contains("\"wh_per_day_lo\":78.0"))
        assertTrue(s.contains("\"wh_per_day_hi\":182.0"))
        assertTrue(s.contains("\"active_w_lo\":52.5"))
        assertTrue(s.contains("\"active_w_hi\":97.5"))
        assertTrue(s.contains("\"wh_per_mile_lo\":15.0"))
        assertTrue(s.contains("\"wh_per_mile_hi\":25.0"))
        assertTrue(s.contains("\"learned_days\":6"))
        assertTrue(s.contains("\"updated_at_ms\":456"))
    }

    @Test fun tempConfig_omits_ranges_when_null_or_empty() {
        val s1 = CloudJson.encodeTempConfig(
            profileId = "redodo-beken-12v100",
            t = dev.joely.bmsmon.model.TempThresholds(),
            env = dev.joely.bmsmon.model.TempEnvelope(),
            unit = "F", updatedAtMs = 123L,
        )
        assertTrue(!s1.contains("\"ranges\""))
        val s2 = CloudJson.encodeTempConfig(
            profileId = "redodo-beken-12v100",
            t = dev.joely.bmsmon.model.TempThresholds(),
            env = dev.joely.bmsmon.model.TempEnvelope(),
            unit = "F", updatedAtMs = 123L,
            ranges = emptyMap(),
        )
        assertTrue(!s2.contains("\"ranges\""))
    }

    @Test fun sampleJson_omits_gps_when_null() {
        val s = CloudJson.sampleJson(
            tsMs = 1L, address = "A", advertisedName = null, alias = null, groupId = null,
            state = null, soc = null, currentA = null, powerW = null, voltageV = null, tempC = null,
            mosfetTempC = null, soh = null, fullChargeAh = null, remainingAh = null, cycles = null,
            cellMinV = null, cellMaxV = null, regen = false, linkEvent = null)
        assertTrue(!s.contains("\"lat\""))
        assertTrue(!s.contains("\"lon\""))
        assertTrue(!s.contains("\"gps_accuracy_m\""))
    }
}
