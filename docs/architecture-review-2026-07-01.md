# Architectural Review — 2026-07-01

Full-stack review of the Android app (`android/`), cloud server (`server/`), and web
dashboard (`web/`), performed by six parallel review passes (Android BLE/engine, Android
data/cloud, Android UI/model, server, web + API contract, security/deployment). This doc
tracks findings and fix status.

**Safety verdict (BLE):** clean. The only GATT write in the app is the 0x13 status frame;
the six safe read opcodes are the only constructible commands; the whitelist is
regression-locked by `BmsProtocolTest.commandWhitelistIsReadOnly`. No code path can emit a
destructive byte.

Status legend: `OPEN` · `IN PROGRESS` · `FIXED` · `WONTFIX`

---

## Fix roadmap

### Tier 1 — safety / security / data-loss

| # | Item | Findings | Status |
|---|------|----------|--------|
| T1.1 | Alert ack re-arm + worst-*unacked* arbitration | UI-1, UI-2 | FIXED |
| T1.2 | WS auth + admin-gate `/web/samples`+`/web/devices` + proxy shared secret | SRV-2, SRV-3, SRV-4 | FIXED |
| T1.3 | Body-size + gzip-decompression caps on `/api/*` | SRV-1 | FIXED |
| T1.4 | Poison-batch discrimination in the phone uploader | DATA-1 | FIXED |
| T1.5 | Link-event samples wiping last-known telemetry (server snapshot + web store) | WEB-1 | FIXED |

### Tier 2 — real bugs, lower blast radius

| # | Item | Findings | Status |
|---|------|----------|--------|
| T2.1 | Length-driven BLE frame completion (80 < minBytes 100) + atomic buffer swap | BLE-1, BLE-2 | FIXED |
| T2.2 | Disabled-while-connecting hole (pack held/polled after user disconnect) | BLE-3 | FIXED |
| T2.3 | Validate device-supplied `ts_ms` (unbounded partition DDL / 500s) | SRV-5 | FIXED |
| T2.4 | Re-enrollment silently un-revokes a device | SRV-7 | FIXED |
| T2.5 | Bound `fleet_snapshot` (full-partition scan per page load / WS connect) | SRV-6 | FIXED |
| T2.6 | Crash orphans open-session rollups (startup finalize-sweep) | DATA-2 | FIXED |
| T2.7 | Web WS client socket leak on visibility recovery | WEB-2 | FIXED |
| T2.8 | Android privacy: `allowBackup`, cleartext, release signing; server GPS retention | SEC-7, SEC-8, SEC-9, SEC-12 | FIXED |

### Tier 3 — hygiene batch

| # | Item | Findings | Status |
|---|------|----------|--------|
| T3.1 | Headless temp notification hardcodes °F; web unit default dead code; web ack never re-arms | UI-4, WEB-3, WEB-7 | FIXED |
| T3.2 | CancellationException swallowed; NaN-float guard in CloudJson; MonitorEngine default-db footgun | DATA-3, DATA-4, DATA-9 | FIXED |
| T3.3 | Connection priority on stage change; monotonic clocks; BT-state receiver; START_STICKY; notif distinctUntilChanged | BLE-4, BLE-9, BLE-10, BLE-11 | FIXED |
| T3.4 | Contract cruft: dead `cells` column, decorative `batch_seq`, import batches flooding WS, runtime WS/REST validation | WEB-4, WEB-5 | FIXED |
| T3.5 | Ops: non-root container, HEALTHCHECK, Python lockfile, pinned bases/actions, single-worker constraint docs, dev-trust guard | SRV-12, SEC-10, SEC-11, SRV-8 | FIXED |
| T3.6 | Docs drift: CLAUDE.md usage_log.csv paragraph (logging is Room-only now) | DATA-12 | FIXED |
| T3.7 | Remaining engine/VM cleanups: dual fleet writers, ETA computed twice, reporter.onStatus VM leak, UiState split/memoization | UI-3, UI-5, UI-6, UI-7 | FIXED |
| T3.8 | Leftover server correctness: WS subscribe-before-snapshot + gap signal; bound `/web/samples`; per-batch battery upsert; shared `_jsonable` | SRV-10, SRV-11, SRV-13 | FIXED |

---

## Findings — Android BLE / engine layer

| ID | Sev | Where | Finding |
|----|-----|-------|---------|
| BLE-1 | Med | `BleSession.kt:147` vs `BatteryProfile.kt:8` | Response completes at ≥80 bytes but parser needs ≥100 (`minBytes`); a stack chunking at 20-byte ATT fragments would hit exactly 80 → permanent `decode_fail` on a healthy link. Fix: derive expected length from the header payload-length byte. |
| BLE-2 | Med | `BleSession.kt:55-62` | `poll()` arms the response deferred before clearing the buffer — a late fragment from the previous response can complete the new poll with stale telemetry. Fix: clear buffer + swap deferred atomically in one `synchronized` block. |
| BLE-3 | Med | `BmsRepository.kt:184-189, 242-255` | Pack disabled while a connect is in flight still gets held + polled on `ConnectSuccess` (no check vs `disabledAddrs`) — briefly violates "free the pack for the phone app" (single-client Beken). Fix: guard clause in `drainEvents`. |
| BLE-4 | Med | `BleSession.kt:29-31,108-110`; `BmsRepository.kt:197-199` | Connection priority (BALANCED vs LOW_POWER) is fixed at connect time; a pack pinned to stage later polls at 1.5 s on a LOW_POWER interval → elevated miss rates on the pack the user is watching. Fix: `setHighPriority()` re-issue on stage change. |
| BLE-5 | Low/Med | `BmsRepository.kt:55,74-88,137-142,240` | Control loop reads shared `resultChannel`/`wakeDeferred` properties, not loop-local captures — stop→start races can steal events/close the new loop's sessions. Fix: pass channel + wake slot as loop parameters. |
| BLE-6 | Low/Med | `BmsRepository.kt:319,219` | No `wake()` after `PollFrame` — frames wait for the next 1 s control tick: 0–1 s added latency/jitter on stage telemetry and alert evaluation. |
| BLE-7 | Low | `BatteryProfile.kt:46-47`; `BleSession.kt:73` | Command frames are shared mutable `ByteArray`s; one accidental in-place write changes the opcode sent to live batteries. Fix: defensive copy + assert `frame[4]==0x13` before write. `firmwareFrame` is dead weight. |
| BLE-8 | Low | `BmsRepository.kt:184-189`; `FleetPlanner.kt:76-81` | Planner overflow rotation marks a healthy pack unreachable (UI DISCONNECTED + link-down log). Dormant while fleet ≤ `maxHeldConnections`. |
| BLE-9 | Low | `BmsRepository.kt:157` etc. | Wall-clock (`currentTimeMillis`) drives backoff/rotation; clock steps distort backoffs. Use `SystemClock.elapsedRealtime()`. |
| BLE-10 | Low | (missing) | No `BluetoothAdapter.ACTION_STATE_CHANGED` receiver — after BT off→on, reconnect can lag up to 2 min per pack while backgrounded. Fix: `kickAll()` on STATE_ON. |
| BLE-11 | Low | `MonitoringService.kt:61-71,73` | Notification re-posted on every state emission (churn); `START_NOT_STICKY` means an OS kill silently ends monitoring. Fix: `distinctUntilChanged` on derived text; consider `START_STICKY` + engine restore. |
| BLE-12 | Info | `Profiles.kt:29` | `slowPollMs = 10_000` is ~8× the measured official-app background rate (~85 s/pack). Structure matches the proven-gentle model; rate is a conscious-decision item. |
| BLE-13 | Info | `BleSession.kt:50,89-99`; `BleScanner.kt:41-43` | Minor: no handler overload on `connectGatt`; GATT `status` codes ignored (fine for retry, bad for field diagnosis); scan failures logcat-only. |

## Findings — Android data / cloud layer

| ID | Sev | Where | Finding |
|----|-----|-------|---------|
| DATA-1 | High | `TelemetryReporter.kt:175-187, 227-242` | `postSigned` collapses every failure (IOException, 401 revoked, 422 reject) into `false`; the loop retries the same oldest 200 rows forever → one poison batch halts all cloud telemetry until the 200k-row cap grinds it out. Fix: 4xx (except 408/429) = permanent → skip/dead-letter; 401/403 = hold + surface auth-failed status. |
| DATA-2 | Med | `TelemetryRepository.kt:79-95`; `Daos.kt:39,42` | Process death mid-run leaves the open session as a `sampleCount=0` stub forever; its samples prune after 14 d and the run vanishes from history. Fix: finalize-sweep at repository construction (`computeRollup` is pure). |
| DATA-3 | Med | `CloudJson.kt:36` | `sampleJson` throws on NaN/Inf floats (no `allowSpecialFloatingPointValues`), inside `MonitorEngine.onPoll` → silently dead poller. Fix: sanitize floats. |
| DATA-4 | Med | `TelemetryReporter.kt:153,239`; `TelemetryRepository.kt:34` | `catch (Exception)` / `runCatching` swallow `CancellationException` in three loops. Fix: rethrow. |
| DATA-5 | Low/Med | `TelemetryReporter.kt:46,192-196,212-213` | `reportingEnabled` refreshed only by the upload loop; samples before first pass are dropped; `OUTBOX_MAX` enforced only inside the loop. Fix: settings Flow + cap on enqueue path. |
| DATA-6 | Low | `TelemetryRepository.kt:113-120` | Retention prune only triggers from `ingest` (never `ingestRawOnly`, never at startup); `RAW_FRAME_MAX_BYTES` counts hex chars = ~half the real bytes. |
| DATA-7 | Low | `TelemetryReporter.kt:167,195` | Full settings blob decoded every ~1.5 s (twice per loop). Fix: collect `dataStore.data` into a cached snapshot. |
| DATA-8 | Low | `TelemetryRepository.kt:154-171` | CSV backfill inserts row-by-row, no transaction, bypasses ops channel. Fix: chunked `insertAll` in `withTransaction`. |
| DATA-9 | Low | `MonitorEngine.kt:81` | Default `db = BmsDatabase.create(...)` param can silently open a second Room instance. Fix: drop the default. |
| DATA-10 | Low | `BmsDatabase.kt` | `exportSchema=false` (no migration tests possible); no index on `samples.tsMs` (retention/paging scans). |
| DATA-11 | Info | manifest / `SettingsStore` / JWT | TLS enforced only by platform cleartext default; `apiBaseUrl` accepts any scheme; no `aud` claim; 60 s TTL means >60 s clock skew bricks uploads indistinguishably from network failure. |
| DATA-12 | Info | `CLAUDE.md` | Doc says "usage_log.csv logging is intentionally ON" but the CSV writer no longer exists — logging is Room-only (`TelemetryRepository` replaced `TelemetryLogger`). Update before the 2026-07-15 accuracy check-in. |
| DATA-13 | Info | `TelemetryReporter.kt:191,224` | `batch_seq` is per-process, resets to 0 on restart, server merely echoes it — decorative. Persist or drop. |

## Findings — Android UI / model layer

| ID | Sev | Where | Finding |
|----|-----|-------|---------|
| UI-1 | High | `BatteryViewModel.kt:146,945-953,284-286` | `acknowledgedTempKeys` is add-only; keys are stable per side+rank (`"temp:HOT:CRITICAL"`), so an acked temp alert **never flashes again** for the process lifetime, even after full recovery and re-entry hours later. Headless notifier re-arms (`AlertNotifier.kt:49-51`); the stage does not. Fix: prune ack keys when the worst temp rank drops below CRITICAL (mirror the notifier). |
| UI-2 | High | `BatteryViewModel.kt:269-291` | Worst-of arbitration compares raw severity before ack state and the temp branch early-returns with capacity fields nulled → an **acked temp alert masks a live unacked capacity alert** (which then can't even be acked). Fix: select the worst *unacked* alert. |
| UI-3 | Med | `BatteryViewModel.kt:438-442,984-990` | App-lifetime `reporter.onStatus` lambda captures the VM; never cleared in `onCleared()` → dead-VM retention. Fix: null in `onCleared` or surface upload stats via `MonitorState`. |
| UI-4 | Med | `MonitorEngine.kt:219` | Headless temp notification hardcodes `TempUnit.F`; °C users get °F margins. Fix: mirror unit via `setTempAlertConfig`. |
| UI-5 | Med | `BatteryViewModel.kt:108-175` | Monolithic ~65-field `UiState` → full-app recomposition every 1.5 s poll; All-Batteries pipeline unmemoized. Fix: memoize rows now; split UiState later. |
| UI-6 | Med | `BatteryViewModel.kt:664-699,399-426` | Fleet has two writers (VM mutates reachability; engine mirror wholesale-replaces) → transient just-disconnected-shows-reachable. Fix: `engine.setDisabled` marks unreachable in `MonitorState`; VM only mirrors. |
| UI-7 | Med | `BatteryViewModel.kt:210-213`; `MonitorEngine.kt:284-286` | ETA computed independently in VM and engine from separately-sourced inputs — can diverge by a sample; double-maintenance. Fix: engine attaches ETA; stage displays. |
| UI-8 | Med | `BatteryViewModel.kt:243-245` | `stageAlert()` filters `reachable` only, ignores `disabled` (brief alert eligibility after user disconnect); a stage pack dropping BLE at 5% SOC yields **no** alert at all (documented behavior, worth stating). |
| UI-9 | Low | `Alerts.kt:33-41` | Charging suppression keys on the lowest pack's flag with no hysteresis — Idle/Charging flapping at the charger strobes the overlay. |
| UI-10 | Low | `MonitorEngine.kt:327`; `ChargeTailLearn.kt:26` | `learnTail` maps null SOC to −1 → null-SOC charging rows count as "below 98%" evidence. Fix: drop null-SOC samples. |
| UI-11 | Low | `HealthReviewScreen.kt:176-184` | Chart series colors cached in `remember(pack)` — stale after theme/accent change. |
| UI-12 | Low | `BatteryViewModel.kt:255-270` | Temp-vs-capacity severity coupling via magic numbers aligned to `TempRank.ordinal` — reordering the enum silently breaks arbitration. |
| UI-13 | Low | various | `Row` data class shadows Compose `Row` (AllBatteriesScreen.kt:83); `zone!!` asserts (StageScreen.kt:127,131); "BELOW null%" fallback (HomeScreen.kt:175); `setStage` forwards non-uppercased set to BLE (MonitorEngine.kt:163-166); 34-lambda `SettingsScreen` signature. |
| UI-14 | Low | tests | Seam coverage gaps: no tests for temp-vs-capacity arbitration (would have caught UI-2), temp ack lifecycle (UI-1), DISCONNECTED `stageItems()`, `stageAlert` with disabled packs. |

## Findings — Server (FastAPI / Postgres)

| ID | Sev | Where | Finding |
|----|-----|-------|---------|
| SRV-1 | High | `api_device.py:53-60,100-105` | Unbounded `await request.body()` + `gzip.decompress()` **before any auth** on the un-SSO'd `/api/` zone; no Content-Length cap, no decompressed cap → gzip-bomb OOM of the single-process container. Fix: cap body size; streaming decompress with hard ceiling. |
| SRV-2 | High | `ws.py:29-36` | `/ws` accepts any socket, no auth of any kind, immediately streams full fleet snapshot incl. `lat`/`lon` + all live samples. Fix: same identity check as `/web` on the handshake; close 4401. |
| SRV-3 | Med/High | `authentik.py:18-24`; `config.py:18` | `X-Authentik-*` headers trusted verbatim, no proxy secret — direct container access (shared NAS docker network, misrouted rule) = full admin + all GPS. Unverified whether the edge strips client-supplied headers. Also `BMSMON_DEV_TRUST_HEADERS=1` grants synthetic admin to every request. Fix: shared-secret header verified server-side; guard dev-trust. |
| SRV-4 | Med | `web.py:28-38` | `/web/samples` + `/web/devices` gated by `current_user`, not `require_admin` — contradicts documented design; any SSO user can dump full GPS history + enumerate devices. Fix: `require_admin` + tests. |
| SRV-5 | Med | `queries.py:39-40`; `partitions.py:33-35`; `models.py:16` | Device-controlled `ts_ms` (unvalidated int) drives partition DDL: epoch-0 → ~678 `CREATE TABLE`s in one request; huge values → unhandled 500; concurrent create can race on the catalog. Fix: validate `ts_ms` window; try/except DDL. |
| SRV-6 | Med | `queries.py:97-108` | `fleet_snapshot` = unbounded `DISTINCT ON` over **all** partitions, runs per `/web/fleet` call + per WS connect; ~19 M rows/yr at current rates. Fix: lateral-join latest per pack from `batteries`, or a latest-samples upsert table. |
| SRV-7 | Med | `queries.py:134-140` | Re-enroll `ON CONFLICT (install_uuid) DO UPDATE ... revoked=false` — revocation not durable; a fresh code + known install_uuid = device takeover/key rotation. Fix: refuse enrollment for revoked install_uuids. |
| SRV-8 | Low | `device_jwt.py:19-29`; `Dockerfile:18` | jti replay cache + LiveBus are process-local: restart clears replay protection; `--workers >1` silently breaks both. Document the single-worker constraint. |
| SRV-9 | Low | `queries.py:36-49` | `accepted` counts deduped rows as accepted — misleading diagnostics. |
| SRV-10 | Low | `ws.py:34-37`; `bus.py:20-21` | Snapshot sent before `subscribe()` → gap window; silent drop on full queue leaves a slow client permanently stale. Fix: subscribe→snapshot→drain; gap signal. |
| SRV-11 | Low | `web.py:28-32`; `queries.py:111-117` | `/web/samples` unbounded (`SELECT *`, no LIMIT, caller-chosen range) — a year-long range materializes millions of rows. |
| SRV-12 | Low | `Dockerfile`; `docker-compose.dev.yml` | Root user, no HEALTHCHECK, `npm ci \|\| npm install` fallback, `pip install .` fragility, no Python lockfile. |
| SRV-13 | Info | misc | Per-sample `upsert_battery` in a loop; no `SampleIn` range validation; `_jsonable` imported from `ws.py` into `web.py`; no rate limiting; test gaps mirror findings (no WS-auth, bomb, extreme-ts, non-admin-samples tests). |

## Findings — Web UI + cross-system contract

| ID | Sev | Where | Finding |
|----|-----|-------|---------|
| WEB-1 | High | `TelemetryReporter.kt:94-99` → `api_device.py:89` → `store.ts:11`; `fleet_snapshot` | Link-event samples (all telemetry null) round-trip as explicit nulls (pydantic `model_dump()`); web spread-merge lets them **overwrite last-known soc/voltage/temp**, and `fleet_snapshot` doesn't filter link rows, so after any disconnect every page load shows the pack empty. Defeats "disconnected packs keep last-known telemetry, muted". Fix: server filters `link_event IS NULL` in snapshot; web merges link-event samples as ts/link-only. NOTE: blanket null-skip would break `eta_full_min` clearing — null-omission→explicit-null is load-bearing for normal samples. |
| WEB-2 | Med | `ws.ts:55-60` vs `:42` | Visibility-recovery `close(); open()` lets the old socket's async `onclose` schedule a reconnect over the healthy new socket → duplicate streams + socket leak per visibility cycle. Fix: per-socket handler guards (`if (ws !== sock) return`). |
| WEB-3 | Med | `App.tsx:44-47` | Unit default "phone's synced unit" is dead code — lazy initializer runs before `tempConfig` arrives; first visit is always °F. Fix: effect that applies `tempConfig.unit` when localStorage is empty. |
| WEB-4 | Med | `api.ts:4-12`; `ws.ts:35-41`; `types.ts` | No runtime validation of any REST/WS payload; raw WS frames (incl. `type` key) spread into the store; `FleetItem` under-describes both schemas so drift is invisible. Fix: small decoder at the two entry points. |
| WEB-5 | Med | `models.py:34`/`schema.sql:38` vs `CloudJson.kt`; `api_device.py:88-89` | `cells` column: accepted, stored, returned — never sent by Android, never read by web (and asyncpg would return jsonb as a string). `batch_seq` decorative. Historical-import batches (`seq=-1`) flood the live WS with thousands of stale frames. Fix: drop or wire `cells`; skip bus publish for `batch_seq < 0`. |
| WEB-6 | Low/Med | `App.tsx:36`; `models.py:53-61`; `temp.ts:18` | Temp-config: `configs[0]` ignores `profile_id` (multi-device/profile silently wrong); `TempConfigBody` all-required + no version → future field = phone re-POSTs a 422 forever; envelope (incl. `chargeResumeColdC=5`) hardcoded separately on web — banner copy already wrong if coldCaution tuned. |
| WEB-7 | Low | `MainStage.tsx:25,47,125-135` | Web ack (`ackedKey`) never re-arms on recurrence of the same zone (same flaw as UI-1); `ConditionsSimulator` ships in the production dashboard and can trigger the full CRITICAL overlay. |
| WEB-8 | Low | `App.tsx` | `force`-counter re-render of whole tree per sample + per 1 s tick (fine at 8 packs; `useSyncExternalStore` + memoized cards when it grows); triplicated localStorage-useState pattern; `api.getFleet` dead code (or promote to WS-down fallback); unused `unit` prop in BatteryProfilePanel. |
| WEB-9 | Low | `web/src/*.test.ts` | Coverage is exactly-inverted vs risk: pure functions tested; `ws.ts` reconnect machine, stage selection, and null-merge (WEB-1) untested. |
| WEB-10 | Info | `App.tsx:37`; `AdminDevices.tsx` | Silent-forever error UX: swallowed fetch errors; `alert("Not authorized")` for any failure; no loading state before first snapshot. |
| WEB-11 | Info | = SRV-10 | WS subscribe-after-snapshot gap (server side). |

Contract-drift notes (full table in review transcript): `advertised_name`, `soh`,
`mosfet_temp_c`, `full_charge_ah`/`remaining_ah`, `cell_min/max_v`, `cycles`, `regen`,
`state` are all uploaded + stored but never surfaced on the web; WS `sample` frames lack
`ts`/`received_at` that snapshot rows carry; no shared schema source across
`CloudJson.kt` / `models.py` / `types.ts`.

## Findings — Security / privacy / build / deploy

| ID | Sev | Where | Finding |
|----|-----|-------|---------|
| SEC-1 | High | = SRV-3 | Blind `X-authentik-*` header trust. |
| SEC-2 | High | = SRV-2 | Unauthenticated `/ws` streaming live GPS. |
| SEC-3 | High | = SRV-1 | Pre-auth gzip bomb / unbounded body. |
| SEC-4 | Med | `main.py:22-36` | No rate limiting, size guard, or TrustedHost middleware anywhere; `/api/enroll` + `/api/ingest` open to abuse at the edge. |
| SEC-5 | Med | = SRV-7 | Re-enroll un-revokes. |
| SEC-6 | Low | = SRV-8 | Process-local jti cache. |
| SEC-7 | Med | `AndroidManifest.xml:40` | `allowBackup="true"`, no backup rules → GPS-tagged `bms.db` + DataStore (deviceId, apiBaseUrl) extractable via backup. Fix: `allowBackup=false` or exclusion rules. |
| SEC-8 | Low | `AndroidManifest.xml:41`; `CloudSyncPage.kt:46-47` | `usesCleartextTraffic="true"` app-wide; user can enroll `http://` and ship signed GPS telemetry in plaintext. |
| SEC-9 | Low | `build.gradle.kts:21-26` | Release build: no signingConfig (debug-signed), no minify/R8. |
| SEC-10 | Low/Med | `pyproject.toml`; `Dockerfile:2,10`; workflows | Supply chain: `>=`-floor deps, no lockfile; tag-pinned (not digest-pinned) base images; major-tag (not SHA) actions; no Gradle dependency verification. |
| SEC-11 | Low | `Dockerfile` | Container runs as root (compose `no-new-privileges` mitigates). |
| SEC-12 | Med | `server/app/db/` | No server-side GPS retention/expiry — location history of a person's mobility device accumulates indefinitely and is exposed over `/web/samples` for arbitrary ranges. Fix: retention/coarsening job. |

**Positive security notes:** Keystore-backed non-extractable P-256 device key; ES256
pinned (no alg confusion); body-hash-bound short-TTL JWTs with jti replay cache; 100-bit
CSPRNG enroll codes, hashed at rest, 10-min TTL, atomically single-use; stolen device key
is upload-only (cannot read fleet/GPS); no hardcoded secrets in any repo component.

**Not verifiable from repo:** whether Traefik/Authentik strips inbound client-supplied
`X-authentik-*` headers at the edge (decides whether SRV-3 is edge-exploitable or
direct-access-only); NAS `.env` secret strength; prod uvicorn staying single-process.

---

## Change log

- 2026-07-01 — review completed; doc created; Tier 1 work started (T1.1–T1.5).
- 2026-07-01 — T1.5 web half fixed: `store.ts` merges link-event samples as ts/link-only
  (preserves last-known telemetry); `ws.ts` strips the `type` key before store merge; tests
  added incl. a guard that explicit `eta_full_min: null` on normal samples still clears the
  ETA. 15/15 vitest, `tsc --noEmit` clean.
- 2026-07-01 — T1.2 + T1.3 + T1.5 server half fixed (50/50 pytest vs real Postgres):
  - `/ws` resolves identity from handshake headers via shared `resolve_user()` before
    streaming anything; unauthenticated sockets closed with 4401.
  - `/web/samples` + `/web/devices` now `require_admin`.
  - New `BMSMON_PROXY_SECRET` (default off): when set, `/web/*` + `/ws` require
    `X-Bmsmon-Proxy-Secret` (constant-time compare) before any `X-Authentik-*` trust,
    including the dev-trust path. `/api/*` unaffected by design.
  - `/api/v1/ingest`+`/config`: `BMSMON_MAX_BODY_BYTES` (1 MiB) enforced pre-read and
    while streaming; gzip via `zlib.decompressobj` capped at `BMSMON_MAX_GUNZIP_BYTES`
    (8 MiB) → 413; corrupt gzip stays 400. All checks run before JWT parsing.
  - `fleet_snapshot` adds `WHERE link_event IS NULL` — dashboard/WS snapshot returns the
    latest real telemetry row per pack.
  - **Deploy notes:** fixes A/B/D/E need no config — normal image rebuild + pull. To turn
    on the proxy secret: set `BMSMON_PROXY_SECRET` in the qnap-nas-docker `.env` + compose
    env for `bmsmon-api`, and add a Traefik `headers.customRequestHeaders` middleware
    injecting `X-Bmsmon-Proxy-Secret` on the Authentik-routed (non-`/api`) router only.
    Leave unset until Traefik is updated (feature is off by default).
- 2026-07-01 — T1.1 + T1.4 fixed (Android; 166/166 unit tests, 16 added, fail-before
  verified on the regression tests):
  - T1.1: worst-of arbitration extracted to pure `pickStageAlert()` in `model/Alerts.kt` —
    a flashing (un-acked) alert always beats a present-but-acked one in either direction;
    ties still go to temperature. `UiState.withTempAcksPruned()` clears
    `acknowledgedTempKeys` when a *live* stage reading shows worst temp rank < CRITICAL
    (mirrors `AlertNotifier`; BLE dropouts deliberately do NOT clear acks). Wired into
    `refresh()`. New `StageAlertArbitrationTest` (10 tests) covers masking both ways,
    full ack→recover→recur re-arm cycle, CUTOFF-past-acked-CRITICAL, charging
    suppression, and dropout no-prune.
  - T1.4: `postSigned` returns sealed `PostResult` via pure `classifyPost()`
    (IO/5xx/408/429 → Transient retry; 401/403 → AuthFailed: rows kept + red
    "↑ auth failed" badge via new `UiState.cloudAuthFailed`; other 4xx → Poison: batch
    skipped with log, queue unblocked — data recoverable from Room via historical
    import). Same treatment applied to `runImport`. `CancellationException` now rethrown
    in the touched catch blocks (partial DATA-4). New `PostResultTest` (6 tests).
- 2026-07-01 — Tier 1 committed per subsystem (081306e android, 878edb9 server,
  6ca5cf0 web, 1702c48 docs) and pushed; image build green; deployed to ddnas02
  (pull + recreate bmsmon-api). Prod verified: `/api/v1/health` ok, unauthenticated
  ingest → 401, 1.5 MB body → 413, `/web/*` still 302s to Authentik.
  `BMSMON_PROXY_SECRET` intentionally NOT set yet (needs the Traefik middleware +
  `.env` change in ~/qnap-nas-docker — see deploy notes above).
- 2026-07-01 — Tier 2 work started: T2.1/T2.2/T2.6 + SEC-7/SEC-8 (Android),
  T2.3/T2.4/T2.5 (server), T2.7 (web). SEC-9 (release signing) and SEC-12 (GPS
  retention) deferred pending user decisions.
- 2026-07-01 — T2.7 fixed: `ws.ts` uses per-socket handler guards (`if (ws !== sock)
  return`), detach-then-close on replacement, simplified visibility recovery, hardened
  teardown; watchdog reviewed (no change needed). New `ws.test.ts` (5 fake-timer tests,
  fail-before verified on the pre-fix code). 20/20 vitest, `tsc --noEmit` clean.
- 2026-07-01 — T2.3 + T2.4 + T2.5 fixed (server; 56/56 pytest vs real Postgres):
  - T2.3: ingest filters samples to `BMSMON_INGEST_TS_MIN_MS` (2020-01-01Z default) ≤
    ts_ms ≤ now + `BMSMON_INGEST_TS_MAX_FUTURE_MS` (48 h default) before row-building,
    battery upsert, partition DDL, and WS publish; drops logged at WARNING with device
    id. Never a 4xx (would poison-skip on the phone) — all-invalid batch → 200
    `accepted: 0`. Partition `CREATE TABLE IF NOT EXISTS` wrapped in a savepoint +
    catches the concurrent-create catalog race.
  - T2.4: `create_device` upsert no-ops (`WHERE devices.revoked = false`, no more
    `revoked=false` reset) → enroll of a revoked install_uuid returns 403 "device
    revoked; delete it first" BEFORE the code is claimed (code stays usable elsewhere).
    Admin must DELETE the device row before that phone can re-enroll.
  - T2.5: `fleet_snapshot` rewritten as batteries-driven `JOIN LATERAL ... ORDER BY ts
    DESC LIMIT 1` — EXPLAIN-verified per-pack index descents instead of all-partition
    scans; identical output shape; link-event filter kept; packs with no real telemetry
    still absent (no ghost packs).
  - Ops notes: no schema change; new env knobs optional; watch container logs for
    `ingest: dropped N/M sample(s) with out-of-range ts_ms`.
- 2026-07-01 — T2.1 + T2.2 + T2.6 + SEC-7/SEC-8 fixed (Android; 176/176 tests, 10
  added; command bytes verified byte-identical — only response assembly + connection
  management changed):
  - T2.1: pure `BmsProtocol.expectedStatusResponseLen()`/`statusFrameComplete()` —
    completion realigns to the `01 93 55 AA` header exactly like the parser and uses
    the header payload-length byte (verified 105 on the real capture; 512-byte safety
    cap). `BleSession.append()` uses it instead of `size >= 80`; `poll()` now swaps the
    deferred + clears the buffer atomically in one `synchronized` block (stale-frame
    race closed).
  - T2.2: `drainEvents` `ConnectSuccess` guard — a no-longer-desired pack's session is
    closed immediately, never held/polled/marked reachable.
  - T2.6: startup sweep (first op on the serialized ops channel) finalizes zero-count
    session stubs via pure `orphanedSessionAction()` (rollup if telemetry samples
    exist, delete if empty); new `SessionSweepTest`.
  - SEC-7: `dataExtractionRules` + legacy `fullBackupContent` exclude bms.db + DataStore
    from **cloud backup** while keeping device-to-device transfer complete (API 31+;
    on API ≤30 legacy transfers also lose the exclusions — unavoidable). `allowBackup`
    stays true — no migration data loss.
  - SEC-8: `usesCleartextTraffic="false"` — no http/emulator endpoints existed, so no
    debug carve-out needed.
- Tier 2 committed per subsystem (8ab30b2 android, d29acf2 server, a52f405 web).
- 2026-07-01 — SEC-12 fixed (user chose **3-year** GPS retention; 60/60 pytest):
  `BMSMON_GPS_RETENTION_DAYS` (default 1095, <=0 disables); daily background task +
  startup pass NULLs `lat`/`lon`/`gps_accuracy_m` on samples older than the window.
  Telemetry rows are NEVER deleted — UPDATE-only by construction, asserted in tests.
- 2026-07-01 — SEC-9 fixed (user chose dedicated release keystore):
  `~/.android-keys/bmsmon-release.jks` (RSA-4096, 30 y validity, CN=bmsmon); credentials
  in `~/.gradle/gradle.properties` (never committed); conditional `signingConfig` in
  `app/build.gradle.kts` (absent properties → unsigned release, debug/tests unaffected).
  `assembleRelease` verified signed via apksigner (cert SHA-256 7fee951f…).
  **BACK UP** the keystore + gradle.properties — losing them means future release
  builds can't update an installed release app. **Transition caveat:** the currently
  installed app is debug-signed; a release-signed APK will NOT install over it
  (signature mismatch). Day-to-day debug `adb install -r` keeps working; switching the
  phone to release builds requires a one-time reinstall (history is recoverable from
  the cloud DB / historical import).
- 2026-07-01 — Proxy secret ROLLED OUT (user approved): `BMSMON_PROXY_SECRET` added to
  the NAS master `.env`; `~/qnap-nas-docker/bmsmon/docker-compose.yml` passes it to
  `bmsmon-api` and adds the `bmsmon-proxy-secret` Traefik middleware
  (customRequestHeaders, UI-zone router only) — direct-container requests to `/web/*`
  and `/ws` now 401 without the header.
- 2026-07-01 — Tier 3 BLE/service batch fixed (BLE-4/9/10/11; 201/201 tests, 18 added;
  command bytes untouched): `BleSession.setHighPriority()` re-issues connection priority
  when a held pack's stage membership changes (control-loop step, idempotent);
  repository scheduling state now uses monotonic `elapsedRealtime()` (persisted/sample
  timestamps stay wall-clock); BT off→on triggers `kickAll()` via an engine-owned
  `ACTION_STATE_CHANGED` receiver; notification posts de-churned via
  `distinctUntilChanged`; `START_STICKY` + `engine.restoreFromPersisted()` (pure
  `restorePlan()` in new MonitorRestore.kt) resurrects monitoring headlessly after an
  OS process kill — user Stop / swipe-from-Recents still fully end it. On-device
  checks noted: BALANCED param update on pin, BT-toggle fast reconnect, sticky
  restart via `am kill`, no notification flicker.
- 2026-07-01 — Tier 3 server batch fixed (WEB-5 server parts, SRV-8/10/11/13, SEC-10/11,
  SRV-12; 68/68 pytest; hardened Docker image built + run-verified locally):
  dead `cells` column removed end-to-end (idempotent DROP COLUMN, cascade verified on
  real partitions); historical-import batches (`batch_seq < 0`) no longer hit the live
  WS; WS now subscribes before snapshotting and closes slow/overflowed clients (4408)
  so they reconnect fresh; `/web/samples` clamped to 7 days + 100k rows; per-batch
  battery upsert dedupe; shared `util.jsonable()`; dev-trust refused against non-local
  DBs; single-worker constraint documented at all three load-bearing spots; Dockerfile:
  non-root uid 10001 + HEALTHCHECK + `npm ci` (no fallback) + pinned
  `requirements.lock` + explicit PYTHONPATH (bare `pip install .` footgun removed);
  base images digest-pinned; workflow actions SHA-pinned.
- 2026-07-01 — Tier 3 Android engine/VM batch fixed (UI-3/4/5/6/7, DATA-3/4/9; 183/183
  tests, 7 added): ETA computed once in the engine and carried on
  `BatteryStatus.etaFullMin`; reachability single-writer (`applyDisabled` in Fleet.kt,
  engine marks disabled packs unreachable synchronously; VM no longer mutates fleet);
  upload status flows through `MonitorState` (reporter hook now lives in the engine —
  VM-leak gone); temp unit mirrored to the engine (`setTempAlertConfig(..., unit)`) and
  °C users get °C notification margins — a unit change now also re-pushes cloud temp
  config; CloudJson sanitizes non-finite floats; ops-channel rethrows
  CancellationException; MonitorEngine default-db param removed; All-Batteries pipeline
  memoized via `remember(...)`.
- 2026-07-01 — Tier 3 web half fixed (WEB-3, WEB-7, WEB-4; 33/33 vitest, tsc + prod
  build clean): temp-unit default now applies the phone's synced unit when no valid
  localStorage choice exists; MainStage ack re-arms on recovery via pure
  `nextAckedKey()` (temp.ts) so same-zone recurrences flash again; ConditionsSimulator
  gated to dev builds / `?sim=1`; new zero-dep `decode.ts` whitelist decoders on every
  WS frame + consumed REST response (malformed frames warn + drop, socket survives).
- 2026-07-01 — GPS-retention image deployed to ddnas02; proxy secret live and verified:
  `/api/v1/health` ok, `/web/*` 302s to Authentik, logged-in dashboard traffic flows
  (temp-config 200s via the full chain), and direct-container requests with spoofed
  `X-Authentik-*` admin headers now get **401** (SRV-3 attack path closed). GPS scrub
  task running (0 rows scrubbed — no data older than 3 years yet).
- 2026-07-02 (overnight) — Tier 3 committed per subsystem (b798a3d android, c15e3ff
  server, da5ff91 web, 4e18a21 docs), image built, deployed to ddnas02, and verified:
  health ok, unauth ingest 401, `/web` 302→Authentik, container healthy (new Dockerfile
  HEALTHCHECK) and running as uid 10001 (non-root). Pixel updated in place
  (`adb install -r` over wireless adb), relaunched, no crashes, MonitoringService
  running, GATT registered, and **35 ingest POSTs in 2 min on the new container** —
  the full phone→BLE→cloud pipeline confirmed live post-update. All review tiers
  (T1–T3) are now FIXED and deployed. Remaining findings are the untiered
  informational/low items (e.g. BLE-5/6/7/8/12/13, DATA-5/6/7/8/10/11/13, UI-8..14,
  SRV-9, WEB-6/8/9/10, temp-config contract edges) — none user-facing-critical.
