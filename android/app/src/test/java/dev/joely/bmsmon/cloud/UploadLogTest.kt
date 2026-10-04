package dev.joely.bmsmon.cloud

import dev.joely.bmsmon.data.db.OutboxEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the upload loop logs about an ingest step: each held reason once per change, a skip with its id, seq and size. */
class UploadLogTest {
    private val meta = BatchMeta(firstId = 11, lastId = 210, size = 200, headTsMs = 5_000, minTsMs = 5_000, maxTsMs = 9_000)
    private val ok = PostOutcome(PostResult.Ok, code = 200, fromApi = true)
    private val poison = PostOutcome(PostResult.Poison, code = 422, fromApi = true)
    private val transient = PostOutcome(PostResult.Transient)
    private val fault = PostOutcome(PostResult.ServerFault, code = 500, fromApi = true)
    private val auth = PostOutcome(PostResult.AuthFailed, code = 401, fromApi = true)
    private val noKey = PostOutcome(PostResult.KeyMissing)

    /** Run [outcomes] through the real [ingestStep] and the loop's [heldLog]; return the lines logged. */
    private fun logged(vararg outcomes: PostOutcome): List<String> {
        var s = IngestLoopState()
        var last: String? = null
        val lines = mutableListOf<String>()
        for (o in outcomes) {
            val step = ingestStep(s, meta, o, nowElapsedMs = 0L, nowWallMs = 1_000_000L)
            s = step.state
            val (memory, line) = heldLog(last, step.log, o.result)
            last = memory
            line?.let(lines::add)
        }
        return lines
    }

    @Test fun aHeldPoisonBatchIsLoggedOnceThroughOutageBlips() {
        val lines = logged(poison, poison, poison, transient, poison, fault, poison)
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("permanently rejected"))   // the skip: an event
        assertTrue(lines[1].contains("poison breaker open"))    // the hold: once
    }

    @Test fun aHoldIsLoggedAgainAfterAnAcceptedBatchOrAnAuthReject() {
        assertEquals(4, logged(poison, poison, ok, poison, poison).size)   // skip, hold, (2xx), skip, hold
        assertEquals(3, logged(poison, poison, auth, poison).size)         // skip, hold, (401), hold again
    }

    @Test fun aMissingKeyIsLoggedOnceWhileItStaysMissing() {
        val lines = logged(noKey, noKey, noKey, transient, noKey)
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("no device key"))
        assertEquals(2, logged(noKey, noKey, auth, noKey).size)
    }

    @Test fun aSkipLineCarriesSeqAndSizeNeverThePayload() {
        val payload = """{"lat":43.0389,"lon":-87.9065}"""
        val rows = listOf(OutboxEntity(id = 42, payload = payload, enqueuedAt = 7_000), OutboxEntity(id = 43, payload = "{}", enqueuedAt = 7_500))
        val skip = listOf(OutboxEffect.Resync(ResyncWindow(7_000, 7_000)), OutboxEffect.SkipOne(42))
        val suffix = stepLogSuffix(7, rows, skip)
        assertEquals(" (seq=7, ${payload.toByteArray().size} bytes)", suffix)
        assertFalse(suffix.contains("43.0389"))
        assertEquals(" (seq=7)", stepLogSuffix(7, rows, listOf(OutboxEffect.DeleteThrough(43))))
        assertEquals(" (seq=7)", stepLogSuffix(7, rows, emptyList()))
    }
}
