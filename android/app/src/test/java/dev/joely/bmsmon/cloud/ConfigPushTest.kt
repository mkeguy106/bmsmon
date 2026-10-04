package dev.joely.bmsmon.cloud

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The config push's breaker and retry gate: the drop is committed only once the config is cleared. */
class ConfigPushTest {
    private val cfg = """{"profile":"redodo"}"""
    private val now = 50_000L
    private val poison = PostOutcome(PostResult.Poison, code = 422, fromApi = true)
    private val ok = PostOutcome(PostResult.Ok, code = 200, fromApi = true)
    private val logs = mutableListOf<String>()
    private var pending: String? = cfg
    private var clearFailures = 0

    private suspend fun step(s: ConfigPushState, o: PostOutcome) =
        configPushStep(s, cfg, o, authFailed = false, nowElapsedMs = now, clear = {
            if (clearFailures > 0) { clearFailures--; throw IOException("clear failed") }
            pending = null
        }, warn = { m, _ -> logs += m })

    @Test fun aFailedClearNeverSpendsTheDrop() = runBlocking {
        clearFailures = 1
        val first = step(ConfigPushState(), poison)
        assertEquals(0, first.poisonSkips)                  // the drop is still armed
        assertEquals(cfg, pending)
        assertEquals(now + INITIAL_BACKOFF_MS, first.retryAtMs)
        assertTrue(logs.single().contains("could not clear"))
        val second = step(first, poison)                    // the retry drops it for real
        assertEquals(1, second.poisonSkips)
        assertEquals(null, pending)
        assertTrue(logs.last().contains("dropped it"))
    }

    @Test fun anAcceptedConfigIsClearedAndResetsTheBackoff() = runBlocking {
        val s = step(ConfigPushState(poisonSkips = 1, backoffMs = 16_000L, retryAtMs = 1L), ok)
        assertEquals(ConfigPushState(poisonSkips = 0, backoffMs = INITIAL_BACKOFF_MS, retryAtMs = 1L), s)
        assertEquals(null, pending)
    }

    @Test fun aHeldConfigIsLoggedOnceAndBacksOffWithRetryAfterAsAFloor() = runBlocking {
        val held = step(ConfigPushState(poisonSkips = 1), poison)
        val again = step(held, poison)
        assertEquals(cfg, again.held)
        assertEquals(cfg, pending)
        assertEquals(1, logs.count { "poison breaker open" in it })
        val outage = step(again, PostOutcome(PostResult.Transient, code = 503, fromApi = true, retryAfterMs = 30_000L))
        assertEquals(now + 30_000L, outage.retryAtMs)
        assertEquals(1, outage.poisonSkips)
    }

    // DATA-18: the clear is compare-and-clear. When a newer config arrived mid-flight the clear finds
    // the value changed and removes nothing; that is a successful no-op, not a failure: the newer
    // config stays pending, the drop/accept is committed, and the push gate is not pushed out.
    @Test fun aClearThatFindsANewerConfigIsASuccessfulNoOp() = runBlocking {
        pending = """{"profile":"newer"}"""
        val sent = cfg
        val s = configPushStep(
            ConfigPushState(poisonSkips = 0, backoffMs = 8_000L), sent, ok, authFailed = false, nowElapsedMs = now,
            clear = { if (pending == sent) pending = null },
            warn = { m, _ -> logs += m },
        )
        assertEquals("""{"profile":"newer"}""", pending)
        assertEquals(ConfigPushState(poisonSkips = 0, backoffMs = INITIAL_BACKOFF_MS, retryAtMs = 0L), s)
        assertTrue(logs.isEmpty())
        val dropped = configPushStep(
            ConfigPushState(), sent, poison, authFailed = false, nowElapsedMs = now,
            clear = { if (pending == sent) pending = null },
            warn = { m, _ -> logs += m },
        )
        assertEquals(1, dropped.poisonSkips)
        assertEquals(0L, dropped.retryAtMs)
        assertEquals("""{"profile":"newer"}""", pending)
    }
}
