package dev.joely.bmsmon.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadDecisionTest {

    /** Fold a response sequence through [decideUpload] exactly the way the upload loop does. */
    private fun steps(results: List<PostResult>, startAuthFailed: Boolean = false): List<BatchStep> {
        var skips = 0
        var auth = startAuthFailed
        return results.map { r ->
            val d = decideUpload(r, skips, auth)
            skips = d.poisonSkipsSinceOk
            auth = d.authFailed
            d.step
        }
    }

    private val deletes = setOf(BatchStep.DELETE_ACCEPTED, BatchStep.DELETE_POISON)

    @Test fun okDeletesAndClosesTheBreaker() {
        assertEquals(
            UploadDecision(BatchStep.DELETE_ACCEPTED, poisonSkipsSinceOk = 0, authFailed = false),
            decideUpload(PostResult.Ok, poisonSkipsSinceOk = 1, authFailed = true),
        )
    }

    @Test fun firstPoisonIsSkipped() {
        assertEquals(
            UploadDecision(BatchStep.DELETE_POISON, poisonSkipsSinceOk = 1, authFailed = false),
            decideUpload(PostResult.Poison, poisonSkipsSinceOk = 0, authFailed = false),
        )
    }

    @Test fun secondPoisonBeforeAnyOkIsHeldNotDeleted() {
        assertEquals(
            UploadDecision(BatchStep.BACK_OFF, poisonSkipsSinceOk = 1, authFailed = false),
            decideUpload(PostResult.Poison, poisonSkipsSinceOk = 1, authFailed = false),
        )
    }

    // A flapping deploy interleaves edge errors between app rejects. Transient and AuthFailed prove
    // nothing about whether the server would accept the rows — only a 2xx re-arms.
    @Test fun transientAndAuthInBetweenDoNotCloseTheBreaker() {
        val s = steps(
            listOf(
                PostResult.Poison, PostResult.Transient, PostResult.AuthFailed,
                PostResult.Transient, PostResult.Poison, PostResult.Poison,
            ),
        )
        assertEquals(
            listOf(
                BatchStep.DELETE_POISON, BatchStep.BACK_OFF, BatchStep.BACK_OFF_AUTH,
                BatchStep.BACK_OFF, BatchStep.BACK_OFF, BatchStep.BACK_OFF,
            ),
            s,
        )
    }

    @Test fun aTwoXxReArmsExactlyOneSkip() {
        val s = steps(listOf(PostResult.Poison, PostResult.Poison, PostResult.Ok, PostResult.Poison, PostResult.Poison))
        assertEquals(
            listOf(
                BatchStep.DELETE_POISON, BatchStep.BACK_OFF, BatchStep.DELETE_ACCEPTED,
                BatchStep.DELETE_POISON, BatchStep.BACK_OFF,
            ),
            s,
        )
    }

    @Test fun authFailedHoldsRowsAndRaisesTheBadge() {
        val d = decideUpload(PostResult.AuthFailed, poisonSkipsSinceOk = 1, authFailed = false)
        assertEquals(BatchStep.BACK_OFF_AUTH, d.step)
        assertTrue(d.authFailed)
        assertEquals(1, d.poisonSkipsSinceOk)
    }

    @Test fun transientKeepsTheAuthBadgeAsItWas() {
        assertTrue(decideUpload(PostResult.Transient, 0, authFailed = true).authFailed)
        assertFalse(decideUpload(PostResult.Transient, 0, authFailed = false).authFailed)
    }

    @Test fun anAppRejectClearsTheAuthBadge() {
        // A 4xx from the app means the JWT was accepted.
        assertFalse(decideUpload(PostResult.Poison, 0, authFailed = true).authFailed)
        assertFalse(decideUpload(PostResult.Poison, 1, authFailed = true).authFailed)
    }

    // The DATA-14 incident shape end to end: Traefik's unmarked 404 for the whole of a deploy or DB
    // outage, then 502/503, then redirects — hundreds of POSTs and not one row may be deleted.
    @Test fun anEdgeOutageNeverDeletesAnything() {
        val codes = List(300) { 404 } + List(50) { 502 } + List(50) { 503 } + listOf(301, 302, 307, 308)
        val s = steps(codes.map { classifyPost(it, fromApi = false) })
        assertTrue(s.none { it in deletes })
    }

    // A systematic app reject (a server bug 422-ing every batch WITH the marker) costs one batch.
    @Test fun aSystematicAppRejectCostsOneBatch() {
        val s = steps(List(500) { classifyPost(422, fromApi = true) })
        assertEquals(1, s.count { it == BatchStep.DELETE_POISON })
        assertEquals(0, s.count { it == BatchStep.DELETE_ACCEPTED })
    }

    @Test fun uploadClientNeverFollowsRedirects() {
        val c = uploadHttpClient()
        assertFalse(c.followRedirects)
        assertFalse(c.followSslRedirects)
    }
}
