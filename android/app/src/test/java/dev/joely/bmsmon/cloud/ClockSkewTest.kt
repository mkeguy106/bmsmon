package dev.joely.bmsmon.cloud

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DATA-20: the 2026-09-16 NAS clock step (585 s) halted every upload, and the phone blamed itself. */
class ClockSkewTest {
    private fun marked401(skewMs: Long?, reason: String? = AUTH_REASON_CLOCK_SKEW) =
        PostOutcome(PostResult.AuthFailed, code = 401, fromApi = true, skewMs = skewMs, authReason = reason)

    private fun markedOk(skewMs: Long?) = PostOutcome(PostResult.Ok, code = 200, fromApi = true, skewMs = skewMs)

    // --- the signing correction ---

    // This phone vs an independent clock (network time, else a satellite fix): its error, in ms.
    private val phoneAgrees: () -> Long? = { 2_000L }
    private val phoneAhead: () -> Long? = { 700_000L }
    private val noIndependentClock: () -> Long? = { null }
    private val notRead: () -> Long? = { throw AssertionError("the independent clock was read") }

    private fun next(prevMs: Long, o: PostOutcome, phone: () -> Long? = phoneAgrees) = nextSigningOffsetMs(prevMs, o, phone)

    @Test fun aMarkedClockSkewRejectSetsTheOffsetToTheSkew() {
        assertEquals(585_000L, next(0L, marked401(585_000L)))
        assertEquals(-700_000L, next(0L, marked401(-700_000L)))
    }

    // Review (Task 3, Important): a phone running AHEAD sees the same negative skew as a server running
    // behind. Correcting it would let future-dated samples into the cloud, where they read as live for
    // up to the skew. Only an independent clock can tell the two apart.
    @Test fun aServerBehindStartsACorrectionWhenAnIndependentClockAgreesWithThePhone() {
        assertEquals(-585_000L, next(0L, marked401(-585_000L), phoneAgrees))
        assertEquals(-585_000L, next(0L, marked401(-585_000L)) { SKEW_ATTRIBUTE_MS })     // tolerance is inclusive
        assertEquals(-585_000L, next(0L, marked401(-585_000L)) { -SKEW_ATTRIBUTE_MS })
    }

    @Test fun aPhoneAheadNeverStartsACorrection() {
        assertEquals(0L, next(0L, marked401(-700_000L), phoneAhead))
        assertEquals(0L, next(0L, marked401(-700_000L)) { SKEW_ATTRIBUTE_MS + 1 })
        assertEquals(0L, next(0L, marked401(585_000L)) { -585_000L })                     // or behind
        assertEquals(0L, next(0L, marked401(-700_000L, reason = null), phoneAhead))
    }

    @Test fun withNoIndependentClockNoCorrectionStarts() {
        assertEquals(0L, next(0L, marked401(-585_000L), noIndependentClock))
        assertEquals(0L, next(0L, marked401(585_000L, reason = null), noIndependentClock))
    }

    // An older server (or a 403) names no reason: a skew beyond the tolerance is the fallback trigger.
    @Test fun withNoReasonASkewBeyondToleranceIsTheFallbackTrigger() {
        assertEquals(585_000L, next(0L, marked401(585_000L, reason = null)))
        val marked403 = PostOutcome(PostResult.AuthFailed, code = 403, fromApi = true, skewMs = -90_000L)
        assertEquals(-90_000L, next(0L, marked403))
        assertEquals(0L, next(0L, marked401(SKEW_ATTRIBUTE_MS, reason = null)))
        assertEquals(SKEW_ATTRIBUTE_MS + 1, next(0L, marked401(SKEW_ATTRIBUTE_MS + 1, reason = null)))
    }

    @Test fun theOffsetIsCappedAtAnHour() {
        assertEquals(3_600_000L, SIGNING_OFFSET_CAP_MS)
        assertEquals(SIGNING_OFFSET_CAP_MS, next(0L, marked401(7_200_000L)))
        assertEquals(-SIGNING_OFFSET_CAP_MS, next(0L, marked401(-7_200_000L)))
        // A server time of Long.MAX_VALUE yields a ~9.2e18 ms skew: bounded, never overflowing.
        assertEquals(SIGNING_OFFSET_CAP_MS, next(0L, marked401(Long.MAX_VALUE)))
        assertEquals(-SIGNING_OFFSET_CAP_MS, next(0L, marked401(Long.MIN_VALUE)))
        assertEquals(SIGNING_OFFSET_CAP_MS, next(0L, marked401(Long.MAX_VALUE, reason = null)))
        assertEquals(0L, next(0L, marked401(Long.MAX_VALUE)) { Long.MIN_VALUE })          // an absurd error never confirms
    }

    @Test fun aClockRejectWithAgreeingClocksResetsTheOffset() {
        // The server says the token's time is wrong, yet its clock agrees with this phone's: the
        // correction itself is what is wrong (or the server clock was fixed). Drop it — clearing
        // needs no independent clock.
        assertEquals(0L, next(585_000L, marked401(10_000L), notRead))
        assertEquals(0L, next(585_000L, marked401(-10_000L, reason = null), notRead))
    }

    // The server named another cause (revoked device, bad signature…): the clock is not the problem.
    @Test fun aRejectForAnotherReasonNeverMovesTheOffset() {
        for (reason in listOf(
            "unknown_or_revoked_device", "bad_signature", "bad_token", "missing_bearer", "replay", "body_mismatch",
        )) {
            assertEquals(reason, 0L, next(0L, marked401(585_000L, reason), notRead))
            assertEquals(reason, 42_000L, next(42_000L, marked401(585_000L, reason), notRead))
            assertEquals(reason, 42_000L, next(42_000L, marked401(0L, reason), notRead))
        }
    }

    // Review focus 2: an intermediary (captive portal, proxy) must never steer the token clock.
    @Test fun onlyAnAppMarkedResponseCanMoveTheOffset() {
        val unmarked401 = PostOutcome(
            PostResult.AuthFailed, code = 401, fromApi = false, skewMs = 9_000_000L, authReason = AUTH_REASON_CLOCK_SKEW,
        )
        assertEquals(0L, next(0L, unmarked401, notRead))
        assertEquals(42_000L, next(42_000L, unmarked401, notRead))
        assertEquals(42_000L, next(42_000L, unmarked401.copy(authReason = null, skewMs = 0L), notRead))
        assertEquals(42_000L, next(42_000L, unmarked401.copy(code = 403, authReason = null), notRead))
        // Nor can an unmarked 2xx clear or move it.
        val unmarked2xx = PostOutcome(classifyPost(200, fromApi = false), code = 200, fromApi = false, skewMs = 0L)
        assertEquals(585_000L, next(585_000L, unmarked2xx, notRead))
    }

    // A 2xx proves the corrected token was accepted; the server clock it reports keeps the correction
    // current, and clears it once the clocks agree, so no single bad reading can pin it. Tracking and
    // clearing never consult the independent clock.
    @Test fun aSuccessfulUploadReAnchorsAnActiveCorrection() {
        assertEquals(590_000L, next(585_000L, markedOk(590_000L), notRead))
        assertEquals(585_000L + SKEW_ATTRIBUTE_MS, next(585_000L, markedOk(585_000L + SKEW_ATTRIBUTE_MS), notRead))
        assertEquals(0L, next(585_000L, markedOk(0L), notRead))
        assertEquals(0L, next(585_000L, markedOk(-SKEW_ATTRIBUTE_MS), notRead))
        assertEquals(585_000L, next(585_000L, markedOk(null), notRead))   // nothing measured
    }

    // A correction that would JUMP by more than the tolerance means a clock stepped. That is a new
    // start, held to the same evidence: else a phone that steps ahead under an active correction
    // would carry its future-dated samples straight in.
    @Test fun aJumpUnderAnActiveCorrectionNeedsTheSameEvidenceAsAStart() {
        assertEquals(SIGNING_OFFSET_CAP_MS, next(585_000L, markedOk(Long.MAX_VALUE), phoneAgrees))
        assertEquals(-100_000L, next(-700_000L, markedOk(-100_000L), phoneAgrees))
        assertEquals(0L, next(-700_000L, markedOk(-100_000L), phoneAhead))
        assertEquals(0L, next(-700_000L, markedOk(-100_000L), noIndependentClock))
        assertEquals(0L, next(-700_000L, marked401(-1_400_000L), phoneAhead))
        assertEquals(-1_400_000L, next(-700_000L, marked401(-1_400_000L), phoneAgrees))
    }

    @Test fun aSuccessNeverStartsACorrection() {
        assertEquals(0L, next(0L, markedOk(585_000L), notRead))
        assertEquals(0L, next(0L, markedOk(Long.MAX_VALUE), notRead))
    }

    @Test fun aOneOffBadServerTimeCannotPinTheCorrection() {
        fun fold(vararg outcomes: PostOutcome) = outcomes.fold(0L) { prev, o -> next(prev, o) }
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
            assertEquals("$r", 585_000L, next(585_000L, PostOutcome(r, fromApi = true, skewMs = 0L), notRead))
            assertEquals("$r", 0L, next(0L, PostOutcome(r, fromApi = true, skewMs = 585_000L), notRead))
        }
        assertEquals(585_000L, next(585_000L, marked401(null), notRead))
        assertEquals(585_000L, next(585_000L, marked401(null, reason = null), notRead))
    }

    @Test fun thePhoneClockIsConfirmedWithinTheToleranceOnly() {
        assertTrue(phoneClockConfirmed(0L))
        assertTrue(phoneClockConfirmed(SKEW_ATTRIBUTE_MS))
        assertTrue(phoneClockConfirmed(-SKEW_ATTRIBUTE_MS))
        assertFalse(phoneClockConfirmed(SKEW_ATTRIBUTE_MS + 1))
        assertFalse(phoneClockConfirmed(Long.MIN_VALUE))
        assertFalse(phoneClockConfirmed(null))
    }

    // --- whose clock a time reject blames, for the UI ("phone clock looks off") ---

    @Test fun aClockRejectIsBlamedOnWhicheverClockTheIndependentOneDisagreesWith() {
        assertEquals(ClockBlame.SERVER, nextClockBlame(null, marked401(-585_000L), phoneAgrees))
        assertEquals(ClockBlame.PHONE, nextClockBlame(null, marked401(-700_000L), phoneAhead))
        assertEquals(ClockBlame.UNKNOWN, nextClockBlame(null, marked401(-700_000L), noIndependentClock))
        assertEquals(ClockBlame.PHONE, nextClockBlame(null, marked401(-700_000L, reason = null), phoneAhead))
    }

    @Test fun theBlameIsSetAndClearedExactlyWhenTheShownSkewIs() {
        val outcomes = listOf(
            marked401(-700_000L), marked401(5_000L), marked401(null), marked401(-700_000L, reason = null),
            marked401(-700_000L, reason = "unknown_or_revoked_device"), markedOk(0L), PostOutcome(PostResult.Ok, fromApi = true),
            PostOutcome(PostResult.Transient), PostOutcome(PostResult.AuthFailed, code = 401, skewMs = -700_000L),
        )
        for (prev in listOf<Long?>(null, -700_000L)) {
            for (o in outcomes) {
                val prevBlame = prev?.let { ClockBlame.PHONE }
                assertEquals("$prev / $o", nextAuthSkewMs(prev, o) == null, nextClockBlame(prevBlame, o, phoneAhead) == null)
            }
        }
        // A plain upload never reads the independent clock.
        assertNull(nextClockBlame(ClockBlame.PHONE, markedOk(0L), notRead))
        assertEquals(ClockBlame.PHONE, nextClockBlame(ClockBlame.PHONE, PostOutcome(PostResult.Transient), notRead))
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
