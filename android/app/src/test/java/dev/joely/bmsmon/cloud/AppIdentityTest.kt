package dev.joely.bmsmon.cloud

import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** DATA-28: every upload names the exact build that sent it. */
class AppIdentityTest {
    @Test fun userAgentNamesVersionShaAndSdk() {
        assertEquals("bmsmon-android/1.0 (2e9b6fd1a2b3; sdk 37)", userAgent("1.0", "2e9b6fd1a2b3", 37))
    }

    @Test fun userAgentDropsCharactersAHeaderCannotCarry() {
        assertEquals("bmsmon-android/1.0-x (unknown; sdk 0)", userAgent("1.0-x\n", "unknown", 0))
    }

    @Test fun theUploadClientCarriesTheUserAgentOnlyWhenGivenOne() {
        assertTrue(uploadHttpClient().interceptors.isEmpty())
        assertEquals(1, uploadHttpClient("bmsmon-android/1.0 (x; sdk 1)").interceptors.size)
    }

    // The request that leaves the client carries exactly the build's User-Agent. A capturing
    // interceptor after it answers locally, so nothing touches the network.
    @Test fun theUploadClientSendsTheUserAgentOnTheRequest() {
        val ua = userAgent("1.0", "2e9b6fd1a2b3", 37)
        var sent: Request? = null
        val client = uploadHttpClient(ua).newBuilder()
            .addInterceptor(Interceptor { chain ->
                sent = chain.request()
                Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1).code(204).message("-")
                    .body("".toResponseBody(null))
                    .build()
            })
            .build()
        client.newCall(Request.Builder().url("https://bmsmon.test/api/v1/ingest").build()).execute().close()
        assertEquals("bmsmon-android/1.0 (2e9b6fd1a2b3; sdk 37)", sent!!.header("User-Agent"))
    }
}
