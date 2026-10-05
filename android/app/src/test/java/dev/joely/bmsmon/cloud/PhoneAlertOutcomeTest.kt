package dev.joely.bmsmon.cloud

import dev.joely.bmsmon.model.PhoneAlertSync
import java.io.IOException
import org.junit.Test
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

/** `outcomeOf` reads `phone_alert` off a marked 2xx only, and never lets that change the result. */
class PhoneAlertOutcomeTest {
    private val block = """{"accepted":3,"phone_alert":{"low_pct":60,"updated_at_ms":42,"updated_by":"web"}}"""

    private fun resp(code: Int, marked: Boolean, body: String): Response =
        Response.Builder()
            .request(Request.Builder().url("https://bmsmon.test/api/v1/ingest").build())
            .protocol(Protocol.HTTP_1_1).code(code).message("-")
            .apply { if (marked) header(API_MARKER_HEADER, "1") }
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    private fun read(r: Response) = r.use { outcomeOf(it, 1_000L, 1_000L) }

    @Test fun aMarkedOkCarriesThePhoneAlert() {
        val o = read(resp(200, true, block))
        assertEquals(PostResult.Ok, o.result)
        assertEquals(PhoneAlertSync(60, 42), o.phoneAlert)
    }

    @Test fun anUnmarkedOkOrAnErrorCarriesNone() {
        assertNull(read(resp(200, false, block)).phoneAlert)
        assertNull(read(resp(500, true, block)).phoneAlert)
        assertNull(read(resp(422, true, block)).phoneAlert)
    }

    @Test fun junkOrMissingIsIgnoredAndTheResultIsUnchanged() {
        for (b in listOf("", "<html>", """{"accepted":1}""", """{"phone_alert":{"low_pct":7,"updated_at_ms":1}}""")) {
            val o = read(resp(200, true, b))
            assertEquals(PostResult.Ok, o.result)
            assertNull(o.phoneAlert)
        }
    }

    @Test fun aBodyThatFailsToReadIsIgnored() {
        val broken = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = -1L
            override fun source(): BufferedSource = object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long = throw IOException("reset")
                override fun timeout(): Timeout = Timeout.NONE
                override fun close() {}
            }.buffer()
        }
        val o = read(resp(200, true, "").newBuilder().body(broken).build())
        assertEquals(PostResult.Ok, o.result)
        assertNull(o.phoneAlert)
    }

    @Test fun onlyTheFirstFourKiBIsRead() {
        val big = """{"pad":"${"x".repeat(5000)}","phone_alert":{"low_pct":60,"updated_at_ms":42}}"""
        assertNull(read(resp(200, true, big)).phoneAlert)
    }
}
