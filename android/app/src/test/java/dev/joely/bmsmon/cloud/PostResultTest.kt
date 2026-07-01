package dev.joely.bmsmon.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * HTTP outcome -> uploader action classification (fix for poison-batch head-of-line blocking):
 *  - Ok        -> delete the uploaded rows, advance
 *  - Transient -> back off and retry the SAME rows
 *  - AuthFailed-> keep the rows (never delete user data), back off, surface auth-failed in the UI
 *  - Poison    -> skip past the batch (server will never accept it) so the queue keeps draining
 */
class PostResultTest {

    @Test fun successIsOk() {
        assertEquals(PostResult.Ok, classifyPost(200))
        assertEquals(PostResult.Ok, classifyPost(204))
    }

    @Test fun networkFailureIsTransient() {
        // null = the request never got an HTTP response (IOException etc.)
        assertEquals(PostResult.Transient, classifyPost(null))
    }

    @Test fun serverErrorsAndThrottlingAreTransient() {
        assertEquals(PostResult.Transient, classifyPost(500))
        assertEquals(PostResult.Transient, classifyPost(502))
        assertEquals(PostResult.Transient, classifyPost(503))
        assertEquals(PostResult.Transient, classifyPost(408))  // request timeout
        assertEquals(PostResult.Transient, classifyPost(429))  // throttled
    }

    @Test fun authProblemsAreAuthFailed() {
        // revoked device or >60 s clock skew: rows must be kept, not retried-blindly-forever
        assertEquals(PostResult.AuthFailed, classifyPost(401))
        assertEquals(PostResult.AuthFailed, classifyPost(403))
    }

    @Test fun permanentRejectsArePoison() {
        assertEquals(PostResult.Poison, classifyPost(400))
        assertEquals(PostResult.Poison, classifyPost(404))
        assertEquals(PostResult.Poison, classifyPost(413))  // payload too large
        assertEquals(PostResult.Poison, classifyPost(422))  // validation reject
    }

    @Test fun oddCodesAreTransient() {
        // anything unexpected (3xx reaching us, weird 1xx) -> safe default is retry
        assertEquals(PostResult.Transient, classifyPost(302))
        assertEquals(PostResult.Transient, classifyPost(100))
    }
}
