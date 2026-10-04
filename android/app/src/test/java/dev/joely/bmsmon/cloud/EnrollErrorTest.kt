package dev.joely.bmsmon.cloud

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DATA-25: a failed enrollment used to be a silent no-op (or a crash in viewModelScope). */
class EnrollErrorTest {
    @Test fun eachFailureSaysWhatToDo() {
        assertTrue(enrollErrorMessage(EnrollException(null, null)).startsWith("Couldn't reach the server"))
        assertTrue(enrollErrorMessage(EnrollException(400, "invalid or expired code")).contains("invalid or has expired"))
        assertTrue(enrollErrorMessage(EnrollException(422, "invalid body")).contains("invalid or has expired"))
        assertTrue(enrollErrorMessage(EnrollException(429, null)).contains("Wait a few minutes"))
        assertTrue(enrollErrorMessage(EnrollException(503, null)).contains("server is having trouble"))
        assertEquals("Enrollment failed (HTTP 409).", enrollErrorMessage(EnrollException(409, null)))
        assertEquals("Enrollment failed (HTTP 403).", enrollErrorMessage(EnrollException(403, "other")))
    }

    @Test fun aRevokedPhoneIsToldWhoCanRestoreIt() {
        val m = enrollErrorMessage(EnrollException(403, "device revoked; restore it first"))
        assertTrue(m, m.startsWith("This phone was revoked."))
        assertTrue(m, m.contains("Settings › Devices"))
        assertTrue(m, m.contains("no new code is needed"))
    }

    @Test fun aBadKeyIsNotBlamedOnTheCode() {
        val m = enrollErrorMessage(EnrollException(400, "bad public key"))
        assertFalse(m, m.contains("code"))
        assertTrue(m, m.contains("upload key"))
    }

    @Test fun localFailuresAreNamedToo() {
        assertEquals("That server URL isn't valid.", enrollErrorMessage(IllegalArgumentException("bad url")))
        assertTrue(enrollErrorMessage(java.security.ProviderException("keystore")).contains("upload key"))
        assertTrue(enrollErrorMessage(java.security.KeyStoreException("ks")).contains("upload key"))
        assertTrue(enrollErrorMessage(RuntimeException("?")).startsWith("Enrollment failed"))
    }

    @Test fun theMessageNeverEchoesTheServerDetail() {
        val m = enrollErrorMessage(EnrollException(500, "SECRET-DETAIL"))
        assertFalse(m.contains("SECRET"))
    }

    private fun fake(code: Int, body: String, seen: MutableList<Request>): OkHttpClient =
        uploadHttpClient("bmsmon-android/test (abc; sdk 1)").newBuilder().addInterceptor(Interceptor { chain ->
            seen += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("m")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }).build()

    @Test fun clientCarriesTheUserAgentAndMapsStatuses() = runBlocking {
        val seen = mutableListOf<Request>()
        val failed = EnrollClient(fake(403, """{"detail":"device revoked; restore it first"}""", seen))
            .enroll("https://x.test", "code", "uuid", "key").exceptionOrNull() as EnrollException
        assertEquals(403, failed.code)
        assertEquals("device revoked; restore it first", failed.detail)
        assertEquals("d-1", EnrollClient(fake(200, """{"device_id":"d-1"}""", seen))
            .enroll("https://x.test", "code", "uuid", "key").getOrThrow())
        assertEquals("bmsmon-android/test (abc; sdk 1)", seen.last().header("User-Agent"))
    }

    @Test fun anUnreachableServerIsAnEnrollExceptionWithoutACode() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { throw IOException("offline") }).build()
        val e = EnrollClient(http).enroll("https://x.test", "c", "u", "k").exceptionOrNull()
        assertTrue(e is EnrollException && e.code == null && e.cause is IOException)
    }
}
