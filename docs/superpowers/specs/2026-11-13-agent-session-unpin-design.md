# Agent session: let an adb-driven agent unpin bmsmon, then re-pin it

Approved 2026-11-13. Small change; short design.

## Intent

The owner uses phonedriver (an MCP server on the PC that drives the Pixel over adb) to have
Claude do tasks in other apps on the chair's Pixel, starting with ordering coffee from the Anodyne
app. bmsmon normally holds a screen pin (locked mode), and Android refuses every other app launch
and every tap on another app while it does, including on phonedriver's hidden virtual display
(measured 2026-11-13: "Attempted Lock Task Mode violation"). The agent needs a sanctioned way to
lift the pin for a task, and the pin must come back afterwards even if the PC loses the phone
mid-task.

## Goals

- A shell-only trigger starts and ends an "agent session" during which bmsmon is unpinned and
  does not re-pin itself.
- The session ends by an explicit `end`, by a timeout, or by the owner tapping Re-pin now.
- When a task is done, bmsmon is back in front and pinned (owner's rule: "whenever you're done
  performing a task with phonedriver, you should bring bmsmon back up pinned").
- A visible banner while a session runs.

## Non-goals

- Keeping bmsmon on the real screen during a session. The owner said it may leave the screen
  when needed; the hidden display is the default, not a requirement.
- Device-owner kiosk allow-lists, or any change to phonedriver.
- Remote (off-home-Wi-Fi) access.

## Anti-goals

- Any other app on the phone being able to start a session.
- A session that outlives its timeout or survives a process restart.
- Anything that touches BLE monitoring, alerts, the stage's locked UI, or the `locked` setting.

## Approaches

1. **Unpin/re-pin by hand over adb** (`am task lock stop`, then `am start` to trigger the
   on-resume re-pin). No code, but nothing restores the pin if the PC drops mid-task.
2. **App-side agent session with timeout (chosen).** One protected receiver, an in-memory
   session, a banner. The timeout bounds an unpinned window to the session length.
3. **Change phonedriver.** Rejected: the block is Android's lock-task enforcement, not phonedriver.

## Design

- **Trigger:** `AgentSessionReceiver`, exported, `android:permission="android.permission.DUMP"`
  (adb's shell uid holds it; third-party apps cannot be granted it at install).
  - `am broadcast -n dev.joely.bmsmon/.agent.AgentSessionReceiver --es op start --ei minutes 15`
  - `--es op end` ends it. A repeated `start` restarts the timer (extend). Minutes clamp to
    1..60; missing or invalid means 15.
- **State:** a process-wide `StateFlow` of the session deadline (`elapsedRealtime`), memory only.
  Process death ends the session; the existing on-resume re-pin then applies.
- **Start:** if locked mode is on and the task is pinned, the activity calls `stopLockTask()`.
  The `locked` setting and the locked stage UI are unchanged.
- **Re-pin decision:** a pure function extends `shouldRepin` with the session: no re-pin while a
  session is active; on end, re-pin if `locked` and nothing is pinned. Evaluated on resume, on
  session end, and on the timeout.
- **End:** explicit `end`, the deadline passing (a coroutine timer in the activity, re-checked
  on every resume), or the banner's Re-pin now. If bmsmon is in front it re-pins at once; if not,
  it re-pins on its next resume (Android won't let it bring itself to the front). The agent's
  end-of-task routine is `op end` then `am start -n dev.joely.bmsmon/.MainActivity`, and the
  owner taps Got it on the system's "App is pinned" prompt.
- **Banner:** only while a session is active, a thin strip under the top bar that never covers
  the stage: `● CLAUDE IS USING THE PHONE · mm:ss` plus a `RE-PIN NOW` button.
- **Measured basis:** `lock_to_app_exit_locked = 0` on the Pixel, so unpinning does not drop to
  the lock screen.

## Left to the builder

File layout, banner styling within the existing theme tokens, logging.

## Verification

Unit tests for the session clock and the re-pin decision. On device: pinned → start a session →
phonedriver launches an app on the hidden display → end → bmsmon in front and pinned; plus a
timeout run, with BLE uploads checked fresh throughout.
