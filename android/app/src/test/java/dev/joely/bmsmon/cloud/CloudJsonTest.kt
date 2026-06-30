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
}
