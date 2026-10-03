package dev.joely.bmsmon.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * HTTP outcome -> uploader action (contract C2, DATA-14). Deleting rows needs POSITIVE proof the
 * APP rejected them: Poison is only 400/413/422 carrying the X-Bmsmon-Api marker. Traefik's own
 * 404 (no router while bmsmon-api is starting/unhealthy/stopped — every deploy, every autoheal
 * restart, every DB outage) has no marker and must never delete the outbox. This test used to pin
 * `404 -> Poison`, which was the bug.
 */
class PostResultTest {

    private val both = listOf(true, false)

    @Test fun successIsOk() {
        assertEquals(PostResult.Ok, classifyPost(200, fromApi = true))
        assertEquals(PostResult.Ok, classifyPost(204, fromApi = true))
        assertEquals(PostResult.Ok, classifyPost(200, fromApi = false))   // C2: 2xx -> Ok regardless
    }

    @Test fun networkFailureIsTransient() {
        // null = the request never got an HTTP response (IOException etc.)
        assertEquals(PostResult.Transient, classifyPost(null, fromApi = false))
    }

    @Test fun serverErrorsAndThrottlingAreTransient() {
        for (m in both) {
            assertEquals(PostResult.Transient, classifyPost(500, m))
            assertEquals(PostResult.Transient, classifyPost(502, m))
            assertEquals(PostResult.Transient, classifyPost(503, m))
            assertEquals(PostResult.Transient, classifyPost(408, m))  // request timeout
            assertEquals(PostResult.Transient, classifyPost(429, m))  // throttled
        }
    }

    @Test fun authProblemsAreAuthFailedWithOrWithoutMarker() {
        // revoked device or >60 s clock skew: rows must be KEPT — holding is always safe
        for (m in both) {
            assertEquals(PostResult.AuthFailed, classifyPost(401, m))
            assertEquals(PostResult.AuthFailed, classifyPost(403, m))
        }
    }

    @Test fun appRejectsCarryingTheMarkerArePoison() {
        assertEquals(PostResult.Poison, classifyPost(400, fromApi = true))
        assertEquals(PostResult.Poison, classifyPost(413, fromApi = true))  // payload too large
        assertEquals(PostResult.Poison, classifyPost(422, fromApi = true))  // envelope validation
    }

    @Test fun edgeFourXxWithoutTheMarkerAreTransient() {
        assertEquals(PostResult.Transient, classifyPost(404, fromApi = false))  // Traefik "404 page not found"
        assertEquals(PostResult.Transient, classifyPost(400, fromApi = false))
        assertEquals(PostResult.Transient, classifyPost(413, fromApi = false))
        assertEquals(PostResult.Transient, classifyPost(422, fromApi = false))
    }

    @Test fun otherFourXxAreTransientEvenWithTheMarker() {
        for (code in listOf(404, 405, 409, 410, 415)) {
            assertEquals("code $code", PostResult.Transient, classifyPost(code, fromApi = true))
        }
    }

    @Test fun redirectsAreTransient() {
        // The upload client never follows redirects (uploadHttpClient), so a 3xx reaches the classifier.
        for (code in listOf(301, 302, 303, 307, 308)) for (m in both) {
            assertEquals("code $code", PostResult.Transient, classifyPost(code, m))
        }
    }

    @Test fun oddCodesAreTransient() {
        assertEquals(PostResult.Transient, classifyPost(100, fromApi = false))
    }
}
