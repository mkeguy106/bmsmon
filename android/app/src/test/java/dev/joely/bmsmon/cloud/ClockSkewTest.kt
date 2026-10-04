package dev.joely.bmsmon.cloud

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DATA-20: the 2026-09-16 NAS clock step (585 s) halted every upload, and the phone blamed itself. */
class ClockSkewTest {
    private fun marked401(skewMs: Long?, reason: String? = AUTH_REASON_CLOCK_SKEW) =
        PostOutcome(PostResult.AuthFailed, code = 401, fromApi = true, skewMs = skewMs, authReason = reason)

    private fun markedOk(skewMs: Long?) = PostOutcome(PostResult.Ok, code = 200, fromApi = true, skewMs = skewMs)

    // --- the signing correction ---

    @Test fun aMarkedClockSkewRejectSetsTheOffsetToTheSkew() {
        assertEquals(585_000L, nextSigningOffsetMs(0L, marked401(585_000L)))
        assertEquals(-700_000L, nextSigningOffsetMs(0L, marked401(-700_000L)))
    }

    // An older server (or a 403) names no reason: a skew beyond the tolerance is the fallback trigger.
    @Test fun withNoReasonASkewBeyondToleranceIsTheFallbackTrigger() {
        assertEquals(585_000L, nextSigningOffsetMs(0L, marked401(585_000L, reason = null)))
        val marked403 = PostOutcome(PostResult.AuthFailed, code = 403, fromApi = true, skewMs = -90_000L)
        assertEquals(-90_000L, nextSigningOffsetMs(0L, marked403))
        assertEquals(0L, nextSigningOffsetMs(0L, marked401(SKEW_ATTRIBUTE_MS, reason = null)))
        assertEquals(SKEW_ATTRIBUTE_MS + 1, nextSigningOffsetMs(0L, marked401(SKEW_ATTRIBUTE_MS + 1, reason = null)))
    }

    @Test fun theOffsetIsCappedAtAnHour() {
        assertEquals(3_600_000L, SIGNING_OFFSET_CAP_MS)
        assertEquals(SIGNING_OFFSET_CAP_MS, nextSigningOffsetMs(0L, marked401(7_200_000L)))
        assertEquals(-SIGNING_OFFSET_CAP_MS, nextSigningOffsetMs(0L, marked401(-7_200_000L)))
        // A server time of Long.MAX_VALUE yields a ~9.2e18 ms skew: bounded, never overflowing.
        assertEquals(SIGNING_OFFSET_CAP_MS, nextSigningOffsetMs(0L, marked401(Long.MAX_VALUE)))
        assertEquals(-SIGNING_OFFSET_CAP_MS, nextSigningOffsetMs(0L, marked401(Long.MIN_VALUE)))
        assertEquals(SIGNING_OFFSET_CAP_MS, nextSigningOffsetMs(0L, marked401(Long.MAX_VALUE, reason = null)))
    }

    @Test fun aClockRejectWithAgreeingClocksResetsTheOffset() {
        // The server says the token's time is wrong, yet its clock agrees with this phone's: the
        // correction itself is what is wrong (or the server clock was fixed). Drop it.
        assertEquals(0L, nextSigningOffsetMs(585_000L, marked401(10_000L)))
        assertEquals(0L, nextSigningOffsetMs(585_000L, marked401(-10_000L, reason = null)))
    }

    // The server named another cause (revoked device, bad signature…): the clock is not the problem.
    @Test fun aRejectForAnotherReasonNeverMovesTheOffset() {
        for (reason in listOf(
            "unknown_or_revoked_device", "bad_signature", "bad_token", "missing_bearer", "replay", "body_mismatch",
        )) {
            assertEquals(reason, 0L, nextSigningOffsetMs(0L, marked401(585_000L, reason)))
            assertEquals(reason, 42_000L, nextSigningOffsetMs(42_000L, marked401(585_000L, reason)))
            assertEquals(reason, 42_000L, nextSigningOffsetMs(42_000L, marked401(0L, reason)))
        }
    }

    // Review focus 2: an intermediary (captive portal, proxy) must never steer the token clock.
    @Test fun onlyAnAppMarkedResponseCanMoveTheOffset() {
        val unmarked401 = PostOutcome(
            PostResult.AuthFailed, code = 401, fromApi = false, skewMs = 9_000_000L, authReason = AUTH_REASON_CLOCK_SKEW,
        )
        assertEquals(0L, nextSigningOffsetMs(0L, unmarked401))
        assertEquals(42_000L, nextSigningOffsetMs(42_000L, unmarked401))
        assertEquals(42_000L, nextSigningOffsetMs(42_000L, unmarked401.copy(authReason = null, skewMs = 0L)))
        assertEquals(42_000L, nextSigningOffsetMs(42_000L, unmarked401.copy(code = 403, authReason = null)))
        // Nor can an unmarked 2xx clear or move it.
        val unmarkedOk = PostOutcome(PostResult.Ok, code = 200, fromApi = false, skewMs = 0L)
        assertEquals(585_000L, nextSigningOffsetMs(585_000L, unmarkedOk))
    }

    // A 2xx proves the corrected token was accepted; the server clock it reports keeps the correction
    // current, and clears it once the clocks agree, so no single bad reading can pin it.
    @Test fun aSuccessfulUploadReAnchorsAnActiveCorrection() {
        assertEquals(590_000L, nextSigningOffsetMs(585_000L, markedOk(590_000L)))
        assertEquals(0L, nextSigningOffsetMs(585_000L, markedOk(0L)))
        assertEquals(0L, nextSigningOffsetMs(585_000L, markedOk(-SKEW_ATTRIBUTE_MS)))
        assertEquals(SIGNING_OFFSET_CAP_MS, nextSigningOffsetMs(585_000L, markedOk(Long.MAX_VALUE)))
        assertEquals(585_000L, nextSigningOffsetMs(585_000L, markedOk(null)))   // nothing measured
    }

    @Test fun aSuccessNeverStartsACorrection() {
        assertEquals(0L, nextSigningOffsetMs(0L, markedOk(585_000L)))
        assertEquals(0L, nextSigningOffsetMs(0L, markedOk(Long.MAX_VALUE)))
    }

    @Test fun aOneOffBadServerTimeCannotPinTheCorrection() {
        fun fold(vararg outcomes: PostOutcome) = outcomes.fold(0L) { prev, o -> nextSigningOffsetMs(prev, o) }
        // A bogus clock on one reject: the next token is an hour off, the server rejects it with its
        // real clock, and the correction is gone.
        assertEquals(0L, fold(marked401(Long.MAX_VALUE), marked401(1_200L)))
        // Or the server accepts the next token anyway: its 2xx carries the real clock.
        assertEquals(0L, fold(marked401(500_000L), markedOk(800L)))
        // A real step is held across later successes.
        assertEquals(-700_400L, fold(marked401(-700_000L), markedOk(-700_200L), markedOk(-700_400L)))
    }

    @Test fun everythingElseKeepsTheOffset() {
        for (r in listOf(PostResult.Transient, PostResult.ServerFault, PostResult.Poison, PostResult.KeyMissing)) {
            assertEquals("$r", 585_000L, nextSigningOffsetMs(585_000L, PostOutcome(r, fromApi = true, skewMs = 0L)))
            assertEquals("$r", 0L, nextSigningOffsetMs(0L, PostOutcome(r, fromApi = true, skewMs = 585_000L)))
        }
        assertEquals(585_000L, nextSigningOffsetMs(585_000L, marked401(null)))
        assertEquals(585_000L, nextSigningOffsetMs(585_000L, marked401(null, reason = null)))
    }

    // --- where the correction lands: the token's iat/exp, bounded at the addition ---

    @Test fun theTokenTimeIsTheSendTimePlusTheBoundedCorrection() {
        val now = 1_759_515_720_123L
        assertEquals(now, tokenTimeMs(now, 0L))
        assertEquals(now + 585_000L, tokenTimeMs(now, 585_000L))
        assertEquals(now - 700_000L, tokenTimeMs(now, -700_000L))
        assertEquals(now + SIGNING_OFFSET_CAP_MS, tokenTimeMs(now, Long.MAX_VALUE))
        assertEquals(now - SIGNING_OFFSET_CAP_MS, tokenTimeMs(now, Long.MIN_VALUE))
    }

    // The correction must reach the JWT and nothing else: never a sample's ts_ms, never the skew
    // measurement (which must stay against this phone's own clock, or the correction would chase itself).
    @Test fun theCorrectionTouchesOnlyTheTokenTime() {
        val code = listOf("src/main/java", "app/src/main/java")
            .map { File(it, "dev/joely/bmsmon/cloud/TelemetryReporter.kt") }
            .first { it.isFile }
            .readLines()
            .map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
        // The offset is never added to anything directly: it reaches a timestamp only via tokenTimeMs…
        val arithmetic = Regex("[-+]=?\\s*signingOffsetMs\\b|\\bsigningOffsetMs\\s*[-+]")
        assertEquals(emptyList<String>(), code.filter { arithmetic.containsMatchIn(it) })
        // …and tokenTimeMs dates the JWT only.
        assertEquals(
            listOf("val token = Jwt.signEs256(key, deviceId, body, tokenTimeMs(sentAt, signingOffsetMs))"),
            code.filter { "tokenTimeMs(" in it },
        )
        assertTrue(code.contains("http.newCall(req).execute().use { outcomeOf(it, sentAt, System.currentTimeMillis()) }"))
        // Every signed POST's outcome is folded in: the config push, ingest and the import.
        val joined = code.joinToString(" ")
        assertEquals(4, Regex("postSigned\\(").findAll(joined).count())   // the definition + 3 calls
        assertEquals(3, Regex("val outcome = postSigned\\(").findAll(joined).count())
        assertEquals(3, Regex("noteOutcome\\(outcome\\)").findAll(joined).count())
    }

    // --- the skew shown to the user ---

    @Test fun theShownSkewIsSetByAMarkedClockRejectAndClearedByA2xx() {
        assertEquals(585_000L, nextAuthSkewMs(null, marked401(585_000L)))
        assertEquals(585_000L, nextAuthSkewMs(null, marked401(585_000L, reason = null)))
        assertNull(nextAuthSkewMs(585_000L, marked401(5_000L)))       // within tolerance: not a clock problem
        assertEquals(585_000L, nextAuthSkewMs(585_000L, PostOutcome(PostResult.Transient)))
        assertNull(nextAuthSkewMs(585_000L, PostOutcome(PostResult.Ok, fromApi = true)))
    }

    @Test fun theShownSkewNeverBlamesTheClockForAnotherReason() {
        assertNull(nextAuthSkewMs(null, marked401(585_000L, reason = "unknown_or_revoked_device")))
        assertNull(nextAuthSkewMs(585_000L, marked401(585_000L, reason = "bad_signature")))
    }

    @Test fun anIntermediaryNeverChangesTheShownSkew() {
        val unmarked = PostOutcome(
            PostResult.AuthFailed, code = 401, fromApi = false, skewMs = 9_000_000L, authReason = AUTH_REASON_CLOCK_SKEW,
        )
        assertNull(nextAuthSkewMs(null, unmarked))
        assertEquals(585_000L, nextAuthSkewMs(585_000L, unmarked))
    }

    @Test fun theShownSkewIsBounded() {
        assertEquals(SIGNING_OFFSET_CAP_MS, nextAuthSkewMs(null, marked401(Long.MAX_VALUE)))
        assertEquals(-SIGNING_OFFSET_CAP_MS, nextAuthSkewMs(null, marked401(Long.MIN_VALUE)))
    }
}
