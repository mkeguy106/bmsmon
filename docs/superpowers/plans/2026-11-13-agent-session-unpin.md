# Agent Session Unpin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A shell-only broadcast lets an adb-driven agent lift bmsmon's screen pin for a bounded session; bmsmon re-pins on `end`, on timeout, or on the owner's tap.

**Architecture:** A pure decision layer (`agent/AgentSession.kt` + the existing `ui/LockTask.kt`) holds parsing, clamping, the session clock and the pin decisions. A process-wide `AgentSession` StateFlow carries the deadline; a DUMP-protected receiver writes it; `ui/App.kt` reacts (unpin, timer, re-pin) and `HomeScreen` shows the banner.

**Tech Stack:** Kotlin, Jetpack Compose, kotlinx.coroutines StateFlow, JUnit4 JVM tests.

**Spec:** `docs/superpowers/specs/2026-11-13-agent-session-unpin-design.md`

## Global Constraints

- Receiver: `dev.joely.bmsmon.agent.AgentSessionReceiver`, `android:exported="true"`, `android:permission="android.permission.DUMP"`.
- Extras: `op` = `start` | `end`; `minutes` int. Missing or < 1 → 15; > 60 → 60.
- Session state is memory only (never persisted). Clock: `SystemClock.elapsedRealtime()`.
- Never change the `locked` setting, the locked stage UI, BLE, alerts or the seize.
- Banner copy: `● CLAUDE IS USING THE PHONE · mm:ss` and button `RE-PIN NOW`.
- Commit messages: no AI attribution (repo CLAUDE.md).

## Review Focus

1. An extend (`start` again) followed by the first timer firing: the old deadline must not end the new session → `expireOnlyEndsTheMatchingDeadline` (Task 1).
2. `start` with no/zero/negative/huge `minutes` → 15/15/15/60 → `minutesClamp` (Task 1).
3. Unknown or missing `op` → nothing changes → `unknownOpIsIgnored` (Task 1).
4. Session ends while bmsmon is not resumed → no crash, re-pin on next resume → guarded by the RESUMED check in Task 2 and the on-device run in Task 3.
5. Locked mode off → a session never pins anything on end → `neverPinsWhenLockedModeIsOff` keeps passing with the agent flag (Task 1).

---

### Task 1: Pure session logic + receiver + manifest

**Files:**
- Create: `android/app/src/main/java/dev/joely/bmsmon/agent/AgentSession.kt`
- Create: `android/app/src/main/java/dev/joely/bmsmon/agent/AgentSessionReceiver.kt`
- Modify: `android/app/src/main/java/dev/joely/bmsmon/ui/LockTask.kt`
- Modify: `android/app/src/main/AndroidManifest.xml` (new `<receiver>` after `BootRestoreReceiver`)
- Test: `android/app/src/test/java/dev/joely/bmsmon/agent/AgentSessionTest.kt`, `android/app/src/test/java/dev/joely/bmsmon/ui/LockTaskTest.kt`

**Interfaces (Produces):**
- `const val AGENT_DEFAULT_MIN = 15`, `const val AGENT_MAX_MIN = 60`
- `sealed interface AgentOp { data class Start(val minutes: Int) : AgentOp; data object End : AgentOp }`
- `fun parseAgentOp(op: String?, minutes: Int?): AgentOp?` (null = ignore)
- `fun agentActive(deadlineMs: Long?, nowMs: Long): Boolean` = non-null and `nowMs < deadlineMs`
- `object AgentSession { val deadline: StateFlow<Long?>; fun start(minutes: Int, nowMs: Long); fun end(); fun expire(deadlineMs: Long) }` — `expire` clears only if the current deadline equals `deadlineMs`.
- `fun formatAgentRemaining(ms: Long): String` → `mm:ss`, floored, never negative (`0` → `00:00`).
- `ui/LockTask.kt`: `fun shouldRepin(locked: Boolean, lockTaskModeState: Int, agentActive: Boolean = false): Boolean` = old rule `&& !agentActive`; `fun shouldUnpinForAgent(lockTaskModeState: Int, agentActive: Boolean): Boolean` = `agentActive && lockTaskModeState != LOCK_TASK_MODE_NONE`.

- [ ] **Step 1: Write failing tests** in `AgentSessionTest`:
  - `minutesClamp`: `parseAgentOp("start", null) == Start(15)`, `(…, 0) == Start(15)`, `(…, -5) == Start(15)`, `(…, 999) == Start(60)`, `(…, 7) == Start(7)`.
  - `endParses`: `parseAgentOp("end", null) == AgentOp.End`.
  - `unknownOpIsIgnored`: `parseAgentOp("stop", 5) == null`, `parseAgentOp(null, 5) == null`.
  - `activeUntilDeadline`: `agentActive(1000, 999)` true, `agentActive(1000, 1000)` false, `agentActive(null, 0)` false.
  - `startSetsDeadlineAndExtendReplacesIt`: `start(15, 0)` → deadline `900_000`; `start(15, 60_000)` → `960_000`.
  - `expireOnlyEndsTheMatchingDeadline`: after start at 0 then extend at 60 000, `expire(900_000)` leaves `960_000`; `expire(960_000)` → null.
  - `formatsRemaining`: `0 → "00:00"`, `-5 → "00:00"`, `61_999 → "01:01"`, `900_000 → "15:00"`.
  - Reset `AgentSession.end()` in `@Before`.
  In `LockTaskTest`: `noRepinDuringAgentSession` (`shouldRepin(true, NONE, agentActive = true)` false), `unpinsOnlyWhenPinnedAndActive` (`shouldUnpinForAgent(2, true)` true, `(LOCK_TASK_MODE_NONE, true)` false, `(2, false)` false). Existing tests stay unchanged except `theAppAsksOnEveryResume`'s string, which Task 2 updates.

- [ ] **Step 2: Run** `cd android && ./gradlew :app:testDebugUnitTest --tests '*AgentSessionTest' --tests '*LockTaskTest'` — expect compile failure (symbols missing).

- [ ] **Step 3: Implement** the Interfaces above. Receiver `onReceive`: `parseAgentOp(intent.getStringExtra("op"), if (intent.hasExtra("minutes")) intent.getIntExtra("minutes", 0) else null)`; `Start` → `AgentSession.start(m, SystemClock.elapsedRealtime())`; `End` → `AgentSession.end()`; log each with tag `AgentSession` (op + minutes only). Manifest receiver per Global Constraints, comment that DUMP is held by adb's shell uid and not grantable to installed apps.

- [ ] **Step 4: Run** the same command — expect PASS.

- [ ] **Step 5: Commit** `feat(android): shell-only agent session state and receiver`

### Task 2: App wiring + banner

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/ui/App.kt` (lock effects ~L149-180, `HomeScreen(...)` call ~L393)
- Create: `android/app/src/main/java/dev/joely/bmsmon/ui/home/AgentBanner.kt`
- Modify: `android/app/src/main/java/dev/joely/bmsmon/ui/home/HomeScreen.kt` (new params; banner directly under `StatusLine`)
- Test: `android/app/src/test/java/dev/joely/bmsmon/ui/LockTaskTest.kt`

**Interfaces:**
- Consumes: everything Task 1 produces.
- Produces: `@Composable fun AgentBanner(remainingMs: Long, onRepinNow: () -> Unit)`; `HomeScreen(..., agentRemainingMs: Long? = null, onAgentEnd: () -> Unit = {})`.

- [ ] **Step 1: Update** `theAppAsksOnEveryResume` to expect `if (shouldRepin(lockedNow, mode, agentNow)) startLockTaskCompat(act)` and add a textual assertion that App.kt contains `AgentSession.expire(` and `shouldUnpinForAgent(`. Run — expect FAIL.

- [ ] **Step 2: Implement in App.kt:**
  - `val agentDeadline by AgentSession.deadline.collectAsState()`; `val agentNow by rememberUpdatedState(agentActive(agentDeadline, SystemClock.elapsedRealtime()))`, used in the ON_RESUME observer's `shouldRepin(lockedNow, mode, agentNow)`.
  - `LaunchedEffect(activity, agentDeadline)`: if a deadline is set and the current mode satisfies `shouldUnpinForAgent`, `runCatching { act.stopLockTask() }`; then `delay(deadline - elapsedRealtime())` (coerced ≥ 0) and `AgentSession.expire(deadline)`. When `agentDeadline` is null: if `shouldRepin(state.locked, mode)` and the activity's lifecycle is at least `RESUMED`, `startLockTaskCompat(act)`; otherwise the ON_RESUME observer covers it.
  - Remaining time: a 1 s ticker only while active (`produceState` keyed on `agentDeadline`), passed as `agentRemainingMs` (null when inactive); `onAgentEnd = { AgentSession.end() }`.
- [ ] **Step 3: Implement `AgentBanner`:** one row, `fillMaxWidth`, horizontal padding 18.dp, bottom 6.dp, `MonoFont` 10.sp SemiBold letterSpacing 0.8.sp like the disconnected chip; text `● CLAUDE IS USING THE PHONE · ${formatAgentRemaining(remainingMs)}` in `Bm.warnText`; trailing `RE-PIN NOW` text button in `Bm.accent`, clickable regardless of `locked`. HomeScreen renders it only when `agentRemainingMs != null`.
- [ ] **Step 4: Run** `cd android && ./gradlew :app:testDebugUnitTest` — expect all PASS; `./gradlew :app:assembleDebug` succeeds.
- [ ] **Step 5: Commit** `feat(android): agent session unpins, re-pins on end or timeout, and shows a banner`

### Task 3: On-device verification + docs

**Files:**
- Modify: `CLAUDE.md` (Android section, after the lock-task install notes: one paragraph on the agent session, its commands, the DUMP gate, the timeout, and the end-of-task routine `op end` + `am start`)
- Modify: `docs/checkins/NEXT.md` only if a check is left owed.

- [ ] **Step 1: Install** per CLAUDE.md: if `mLockTaskModeState=PINNED`, `adb shell am task lock stop` first; `adb install -r app/build/outputs/apk/debug/app-debug.apk`; `adb shell am start -n dev.joely.bmsmon/.MainActivity`; owner taps Got it. Expect `PINNED`, bmsmon process up, prod uploads < 30 s old.
- [ ] **Step 2: Gate check:** `adb shell am broadcast -n dev.joely.bmsmon/.agent.AgentSessionReceiver --es op start --ei minutes 2` → banner visible, `mLockTaskModeState=NONE`.
- [ ] **Step 3: Drive:** phonedriver `launch Calculator` on the hidden display succeeds (no "Lock Task Mode violation" in logcat).
- [ ] **Step 4: End:** `--es op end`, then `am start -n dev.joely.bmsmon/.MainActivity` → banner gone, `PINNED` after Got it.
- [ ] **Step 5: Timeout:** start with `--ei minutes 1`, wait ~70 s → banner gone, re-pinned with no command sent.
- [ ] **Step 6: Re-check** uploads fresh for every pack in range; final state bmsmon in front and `PINNED`.
- [ ] **Step 7: Commit** `docs: agent session for adb-driven tasks` and push.
