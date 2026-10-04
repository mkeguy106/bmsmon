package dev.joely.bmsmon.cloud

import java.io.IOException
import java.time.Instant
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
import org.junit.Test

class PostOutcomeTest {

    @Test fun retryAfterIsDeltaSecondsCappedAtFiveMinutes() {
        assertEquals(30_000L, parseRetryAfterMs("30"))
        assertEquals(30_000L, parseRetryAfterMs(" 30 "))
        assertEquals(0L, parseRetryAfterMs("0"))
        assertEquals(RETRY_AFTER_CAP_MS, parseRetryAfterMs("86400"))
        // 17 digits fits a Long but would overflow when multiplied by 1000 — must still cap.
        assertEquals(RETRY_AFTER_CAP_MS, parseRetryAfterMs("99999999999999999"))
    }

    // Review focus 5: a hostile or unusual Retry-After never stalls uploads and never throws.
    @Test fun unusableRetryAfterValuesAreIgnored() {
        assertNull(parseRetryAfterMs(null))
        assertNull(parseRetryAfterMs(""))
        assertNull(parseRetryAfterMs("soon"))
        assertNull(parseRetryAfterMs("-5"))
        assertNull(parseRetryAfterMs("99999999999999999999"))           // beyond Long
        assertNull(parseRetryAfterMs("Sat, 03 Oct 2026 18:22:00 GMT"))  // HTTP-date form: not honoured
    }

    @Test fun retryAfterIsAFloorOnTheBackoffNeverAShortcut() {
        assertEquals(30_000L, retryDelayMs(backoffMs = 2_000L, retryAfterMs = 30_000L))
        assertEquals(60_000L, retryDelayMs(backoffMs = 60_000L, retryAfterMs = 1_000L))
        assertEquals(4_000L, retryDelayMs(backoffMs = 4_000L, retryAfterMs = null))
    }

    @Test fun backoffDoublesToAMinute() {
        assertEquals(2_000L, nextBackoffMs(INITIAL_BACKOFF_MS))
        assertEquals(MAX_BACKOFF_MS, nextBackoffMs(40_000L))
        assertEquals(MAX_BACKOFF_MS, nextBackoffMs(MAX_BACKOFF_MS))
    }

    @Test fun httpDateParsesToEpochMillis() {
        assertEquals(
            Instant.parse("2026-10-03T18:22:00Z").toEpochMilli(),
            parseHttpDateMs("Sat, 03 Oct 2026 18:22:00 GMT"),
        )
        assertNull(parseHttpDateMs(null))
        assertNull(parseHttpDateMs("yesterday"))
    }

    @Test fun skewIsServerTimeMinusTheRequestMidpoint() {
        assertEquals(585_000L, clockSkewMs(serverDateMs = 1_585_000L, sentAtMs = 999_000L, receivedAtMs = 1_001_000L))
        assertEquals(-10_000L, clockSkewMs(serverDateMs = 990_000L, sentAtMs = 1_000_000L, receivedAtMs = 1_000_000L))
        assertNull(clockSkewMs(serverDateMs = null, sentAtMs = 0L, receivedAtMs = 0L))
    }

    @Test fun detailIsTheAppsReasonStringOnly() {
        assertEquals("bad signature", apiDetail("""{"detail":"bad signature"}"""))
        assertNull(apiDetail("""{"detail":["x"]}"""))
        assertNull(apiDetail("""{"detail":"  "}"""))
        assertNull(apiDetail("404 page not found"))
        assertNull(apiDetail(null))
        assertEquals(200, apiDetail("""{"detail":"${"x".repeat(500)}"}""")!!.length)
    }

    // --- the server's own clock and reject reason (server contract) ---

    private val date = "Sat, 03 Oct 2026 18:22:00 GMT"
    private val dateMs = Instant.parse("2026-10-03T18:22:00Z").toEpochMilli()

    // Every app-generated /api/ response carries X-Bmsmon-Server-Time-Ms: millisecond resolution
    // beats Date's whole seconds, so it is preferred; Date is the fallback.
    @Test fun theServersMillisecondClockIsPreferredOverDate() {
        assertEquals(1_759_515_720_123L, serverClockMs(serverTimeMs = "1759515720123", date = date))
        assertEquals(1_759_515_720_123L, serverClockMs(serverTimeMs = " 1759515720123 ", date = null))
        assertEquals(dateMs, serverClockMs(serverTimeMs = null, date = date))
        assertNull(serverClockMs(serverTimeMs = null, date = null))
    }

    // An unusable millisecond header falls back to Date — never to a guess, never a throw.
    @Test fun anUnusableServerTimeFallsBackToDate() {
        for (junk in listOf("", "soon", "-5", "0", "1.7e12", "99999999999999999999")) {
            assertEquals("'$junk'", dateMs, serverClockMs(serverTimeMs = junk, date = date))
            assertNull("'$junk'", serverClockMs(serverTimeMs = junk, date = "yesterday"))
        }
    }

    @Test fun theAuthReasonIsAPlainTokenOrNothing() {
        assertEquals("clock_skew", parseAuthReason("clock_skew"))
        assertEquals("unknown_or_revoked_device", parseAuthReason(" unknown_or_revoked_device "))
        assertNull(parseAuthReason(null))
        assertNull(parseAuthReason(""))
        assertNull(parseAuthReason("clock skew"))
        assertNull(parseAuthReason("Clock_Skew"))
        assertNull(parseAuthReason("x".repeat(65)))
    }

    // --- one response, read into an outcome ---

    private fun resp(code: Int, headers: Map<String, String> = emptyMap(), body: String = ""): Response =
        Response.Builder()
            .request(Request.Builder().url("https://bmsmon.test/api/v1/ingest").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("-")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    private fun read(r: Response, sentAtMs: Long = 1_000_000L, receivedAtMs: Long = 1_000_000L) =
        r.use { outcomeOf(it, sentAtMs, receivedAtMs) }

    @Test fun aMarkedFiveOhThreeCarriesItsRetryAfter() {
        val o = read(resp(503, mapOf(API_MARKER_HEADER to "1", "Retry-After" to "30")))
        assertEquals(PostOutcome(PostResult.Transient, code = 503, fromApi = true, retryAfterMs = 30_000L), o)
    }

    @Test fun aMarked401CarriesTheServerClockTheReasonAndTheDetail() {
        val o = read(
            resp(
                401,
                mapOf(
                    API_MARKER_HEADER to "1",
                    SERVER_TIME_HEADER to "1585000",
                    "Date" to date,                 // ignored: the millisecond header wins
                    AUTH_REASON_HEADER to "clock_skew",
                ),
                body = """{"detail":"token outside the accepted window"}""",
            ),
            sentAtMs = 999_000L, receivedAtMs = 1_001_000L,
        )
        assertEquals(
            PostOutcome(
                PostResult.AuthFailed, code = 401, fromApi = true, skewMs = 585_000L,
                detail = "token outside the accepted window", authReason = "clock_skew",
            ),
            o,
        )
    }

    @Test fun anIntermediarys401FallsBackToItsDateAndHasNoDetail() {
        val o = read(resp(401, mapOf("Date" to date), body = "<html>sign in to the wifi</html>"), dateMs - 10_000L, dateMs - 10_000L)
        assertEquals(PostOutcome(PostResult.AuthFailed, code = 401, fromApi = false, skewMs = 10_000L), o)
    }

    // The reason and the detail mean something only on a rejected sign-in; nothing else reads the body.
    @Test fun theReasonAndDetailAreReadOnlyOnAnAuthFailure() {
        val headers = mapOf(API_MARKER_HEADER to "1", AUTH_REASON_HEADER to "clock_skew")
        val ok = read(resp(200, headers, body = """{"detail":"x"}"""))
        assertEquals(PostOutcome(PostResult.Ok, code = 200, fromApi = true), ok)
        val poison = read(resp(422, headers, body = """{"detail":"x"}"""))
        assertEquals(PostOutcome(PostResult.Poison, code = 422, fromApi = true), poison)
    }

    @Test fun aHuge401BodyIsNeverReadWhole() {
        val o = read(resp(401, mapOf(API_MARKER_HEADER to "1"), body = """{"detail":"${"x".repeat(100_000)}"}"""))
        assertNull(o.detail)
        assertEquals(PostResult.AuthFailed, o.result)
    }

    // C2: a 401 is AuthFailed even when its body cannot be read (connection reset mid-body) — the
    // reason string is optional, the classification is not.
    @Test fun a401WhoseBodyFailsToReadIsStillAnAuthFailure() {
        val broken = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = -1L
            override fun source(): BufferedSource = object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long = throw IOException("reset")
                override fun timeout(): Timeout = Timeout.NONE
                override fun close() {}
            }.buffer()
        }
        val r = resp(401, mapOf(API_MARKER_HEADER to "1", AUTH_REASON_HEADER to "replay")).newBuilder().body(broken).build()
        assertEquals(PostOutcome(PostResult.AuthFailed, code = 401, fromApi = true, authReason = "replay"), read(r))
    }

    // C2 is unchanged: the outcome's result is exactly classifyPost's, for every status and marker.
    @Test fun theOutcomeClassifiesExactlyLikeClassifyPost() {
        for (code in 100..599) for (marked in listOf(true, false)) {
            val headers = if (marked) mapOf(API_MARKER_HEADER to "1") else emptyMap()
            assertEquals("$code marked=$marked", classifyPost(code, marked), read(resp(code, headers)).result)
        }
    }
}
