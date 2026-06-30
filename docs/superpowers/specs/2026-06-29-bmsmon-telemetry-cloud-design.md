# bmsmon Telemetry Cloud — Design Spec

**Date:** 2026-06-29
**Host:** `bmsmon.covert.life` (QNAP NAS, deployed via the `qnap-nas-docker` CI workflow)
**Status:** Approved design, pending implementation plans

## 1. Overview & Goals

Add a cloud tier to bmsmon so the phone reports all battery telemetry to a
self-hosted API server, and a desktop WebUI shows the fleet live — a wide,
single-page view that combines what the phone shows on its **Main Stage** and
**All Batteries** screens.

Hard requirements from the user:

- The phone keeps logging locally **and** streams to the server when online.
- No internet → **fail silently**, buffer locally, and **flush everything** as
  soon as connectivity returns (no gaps in the server's record going forward).
- WebUI authentication via **Authentik** (same pattern as other NAS services).
- The API path must **bypass Authentik** and use its own device authentication.
- Secure device **enrollment + key exchange**.
- The WebUI shows **live** telemetry as it streams in.
- Follow the `qnap-nas-docker` deployment pattern (Traefik, Authentik, Uptime
  Kuma, Docker); the existing workflow auto-registers DNS.

### Locked decisions

| Axis | Decision |
|------|----------|
| Server storage | **Postgres**, full history retained (declarative monthly partitioning for scale) |
| Device auth | **ECDSA P-256 keypair**; phone self-signs each batch as an **ES256 JWT**; server stores only public keys |
| WebUI framework | **React** (built to static assets, served by the API container) |
| Offline behavior | Room **outbox** table on the phone; batched flush on reconnect; forward-going from enrollment (no bulk import of pre-enrollment history in v1) |
| Tenancy | **One fleet**; per-device keys so a replacement/second phone can enroll later. WebUI viewers = Authentik users |
| Control surface | **Strictly read-only** — nothing ever flows server → phone → BMS |

### Non-goals (v1)

- No bulk import of the phone's existing historical samples (only forward from
  enrollment). Can be added later as a one-time import job.
- No multi-tenant orgs/ACLs beyond "is an authenticated Authentik user."
- No commands/control of any kind reaching the batteries from the server.
- No downsampling/rollups in v1 (full-resolution rows + partition pruning is the
  scaling story; rollups are a later optimization).

## 2. Architecture

```
 Phone (process-lifetime MonitorEngine)            NAS · bmsmon.covert.life
 ┌─────────────────────────────────────┐          ┌──────────────────────────────────┐
 │ MonitorEngine.onPoll(addr,raw,t) ────┼─ tap ──▶ │ Traefik (proxy-net)              │
 │   • local logging (unchanged)        │  HTTPS   │  router A: Host && /api/  → NO    │
 │ TelemetryReporter                    │  POST    │            Authentik (device JWT) │
 │   • Room `outbox` table              │ signed   │  router B: Host (everything else) │
 │   • connectivity-gated uploader      │  (ES256) │            → Authentik SSO        │
 │   • batched flush + backoff retry    │─────────▶│              ▼                    │
 └─────────────────────────────────────┘          │   FastAPI (one container)         │
            ▲ every parsed sample                  │     /api/v1/enroll  (code)        │
            │ + link (connect/disconnect) events   │     /api/v1/ingest  (JWT)         │
                                                   │     /api/v1/health  (probe)       │
 Browser (you, via Authentik SSO)                  │     /web/*  (Authentik headers)   │
   ┌─────────────────────────────────┐  WebSocket  │     /ws     (live, Authentik)     │
   │ React WebUI: Main Stage +        │◀───/ws─────│     /       (React static)        │
   │ All Batteries, live on one page  │  snapshot  │              ▼                    │
   └─────────────────────────────────┘  + stream  │   Postgres (bmsmon-db)            │
                                                   └──────────────────────────────────┘
```

Single API process → in-process asyncio pub/sub is sufficient to fan live
samples out to all connected WebSocket clients.

## 3. Components

### 3.1 API server (`server/`, Python + FastAPI + asyncpg/SQLAlchemy)

Routes split by Traefik into two trust zones:

**Device zone — under `/api/` (no Authentik; Traefik higher-priority router):**

- `POST /api/v1/enroll` — body `{ code, install_uuid, public_key_spki_b64, device_label? }`.
  Validates a one-time enrollment code, stores the device public key, returns
  `{ device_id }`. No bearer auth (the code is the secret). Rate-limited.
- `POST /api/v1/ingest` — `Authorization: Bearer <ES256 JWT>`; body
  `{ batch_seq, samples: [ … ], link_events?: [ … ] }`. Verifies the JWT against
  the device's stored public key, upserts samples idempotently, publishes each
  new sample to the live bus, returns `{ accepted, last_seq }`.
- `GET /api/v1/health` — unauthenticated liveness/readiness probe (DB ping). Used
  by the container healthcheck and Uptime Kuma's internal monitor.

**Browser zone — everything not under `/api/` (Authentik forwardAuth):**

- `GET /` and static assets — the React build.
- `GET /web/fleet` — current snapshot: latest sample per battery + roster + device
  liveness.
- `GET /web/samples?address=&from=&to=&bucket=` — history for charts.
- `GET /web/devices` — enrolled devices + last-seen.
- `POST /web/enroll-codes` — mint a one-time enrollment code (admin action).
  Authorized from Authentik forwardAuth headers (require membership in a
  configured group, default any authenticated user).
- `DELETE /web/devices/{id}` — revoke a device (stops accepting its JWTs).
- `WS /ws` — on connect, send a full fleet snapshot, then stream each incoming
  sample/link-event as it is ingested.

The browser zone trusts Traefik's `X-authentik-*` headers for identity; it never
runs its own login.

### 3.2 Postgres (`bmsmon-db`)

Schema (mirrors the phone's `SampleEntity` so replay is 1:1):

- `devices(id uuid pk, install_uuid text unique, public_key_spki bytea,
  label text, created_at, last_seen_at, revoked bool default false)`
- `enrollment_codes(code_hash text pk, created_by text, created_at,
  expires_at, used_at nullable, device_id uuid nullable)` — code stored hashed;
  short TTL (default 10 min); single-use.
- `batteries(address text pk, advertised_name text, alias text, group_id text,
  first_seen, last_seen)` — server-side roster mirror, upserted from ingest
  metadata.
- `samples(id bigserial, device_id uuid, address text, ts_ms bigint,
  state text, soc real, current_a real, power_w real, voltage_v real,
  temp_c real, mosfet_temp_c int, soh int, full_charge_ah real,
  remaining_ah real, cycles int, cell_min_v real, cell_max_v real,
  cells jsonb null, regen bool, link_event text null, received_at timestamptz)`
  - **Idempotency:** unique `(device_id, address, ts_ms)`, insert
    `ON CONFLICT DO NOTHING`.
  - Indexes: `(address, ts_ms)`, `(ts_ms)`.
  - **Partitioning:** declarative range partition by month on the timestamp;
    keeps inserts/queries fast and makes any future pruning a partition drop.

Sessions are **derived server-side** by time-gap per address (the phone's own
heuristic), so the phone need not send a session id in v1.

### 3.3 WebUI (`web/`, React + Vite, built to static)

A single wide desktop page reusing the phone's visual language (Inter +
JetBrains Mono, the `Bm` dark tokens, the SOC ring + power ring + stat grid,
DISCONNECTED treatment):

- **Main Stage panel** — the in-use base (1–2 packs) rendered large, live.
- **All Batteries panel** — every pack as a compact live card, side-by-side with
  the stage (the desktop width is the whole point — no paging).
- **Live** via the `/ws` WebSocket: snapshot on connect, then per-sample updates;
  reconnect with backoff. A pack with no recent sample renders DISCONNECTED
  (dimmed), exactly like the phone.
- An **admin area**: "Enroll a device" (mint code, show it + a QR), device list,
  revoke.
- Polished pass uses the `frontend-design` skill at implementation time.

### 3.4 Phone reporter (`android/`, new code)

- `TelemetryReporter` — constructed in `BmsApp`, passed into `MonitorEngine`.
  Tapped from `MonitorEngine.onPoll()` (the single point every parsed sample
  flows through) and from BLE link-state transitions (connect/disconnect),
  independent of the `logging` flag (cloud reporting has its own enable flag).
- **Outbox** — a new Room table `outbox` in the existing `bms.db`, written through
  the same `Channel`-backed async queue pattern as `TelemetryRepository`. One row
  per sample/link-event with all `SampleEntity` fields + battery identity
  (address, advertised_name, alias, group_id) + a monotonic `seq`.
- **Uploader** — a coroutine on the engine's process-lifetime scope: gated on
  connectivity (`ConnectivityManager.NetworkCallback`) and a non-empty outbox;
  reads a batch (≤ N rows), builds the ES256 JWT, POSTs `/api/v1/ingest`, and on
  `200` deletes acked rows. Failures are silent: exponential backoff (capped),
  then wait for the next connectivity/enqueue signal. Online cadence flushes
  promptly (small debounce) to stay "live"; offline it simply accumulates.
- **Identity & keys** — at first run generate a per-install UUID (DataStore). At
  enrollment generate an **ECDSA P-256 keypair in the Android Keystore** (alias
  `bmsmon_device`, private key non-exportable).
- **Settings** — new DataStore keys: `cloud_enabled`, `api_base_url`, `device_id`,
  `enrolled`. A new **"Cloud sync"** category in the Settings Hub (the structure
  just shipped): status (Not enrolled / Enrolled to `<url>` / last upload), server
  URL + enrollment-code fields, **Enroll**, an enable toggle, outbox depth, and
  **Forget device**.
- **Networking deps** — add `INTERNET` permission; add OkHttp + a small JWT/JOSE
  helper (Keystore-backed ES256 signer) + kotlinx.serialization.

## 4. Security — enrollment & key exchange

1. **Mint code (admin, browser):** logged-in Authentik user hits "Enroll device"
   → `POST /web/enroll-codes` → server stores `sha256(code)` with a 10-min TTL and
   returns the plaintext code once (shown as text + QR).
2. **Enroll (phone):** user enters server URL + code (or scans QR). Phone
   generates its Keystore keypair and calls `POST /api/v1/enroll` with
   `{ code, install_uuid, public_key_spki_b64 }`. Server verifies the code is
   valid/unused/unexpired, creates the `devices` row with the public key, marks
   the code used, returns `{ device_id }`. Phone stores `device_id` + `enrolled`.
3. **Ingest (phone, ongoing):** each batch carries an `Authorization: Bearer <JWT>`
   where the JWT is **ES256-signed by the Keystore private key**, claims:
   `sub = device_id`, `iat`, `exp` (~60 s), `jti` (random), and `bh = base64url(sha256(body))`.
   The server verifies the signature with the stored public key, checks `exp`,
   rejects replayed `jti` (short-lived cache), and checks `bh` matches the request
   body — so a captured token can't be reused with different data. All over TLS
   (Let's Encrypt via Traefik). A server/DB compromise yields only **public** keys
   — it cannot forge a device.
4. **Revocation:** `DELETE /web/devices/{id}` sets `revoked=true`; ingest from a
   revoked device returns `401`. The phone treats persistent `401` by surfacing
   "Device removed — re-enroll" in Settings and pausing uploads (data stays in the
   outbox).

Traefik enforces the zone split: a higher-priority router matches
`Host(\`bmsmon.covert.life\`) && PathPrefix(\`/api/\`)` with **no** `authentik@docker`
middleware; the default-priority router matches the host for everything else
**with** `authentik@docker`.

## 5. Deployment (`qnap-nas-docker`)

New directory `bmsmon/` with `docker-compose.yml` + `stack.conf` (`ORDER=100`).
Two services following the repo conventions (YAML anchor for logging/restart/
security, external networks, no host port bindings, `autoheal: true`, `flame.*`
labels, healthcheck):

- **`bmsmon-api`** — image `ghcr.io/mkeguy106/bmsmon-server:latest`; on `proxy-net`
  (Traefik) + an internal network to reach the DB; `${CONFDIR}/bmsmon` for any app
  state; healthcheck `curl -f localhost:8000/api/v1/health`.
- **`bmsmon-db`** — `postgres:16`, internal network only (no Traefik labels), data
  in `${CONFDIR}/bmsmon/db`.

Traefik labels (per the verified pattern):

```
# API zone — no Authentik, device-JWT auth
traefik.http.routers.bmsmon-api.rule: Host(`bmsmon.covert.life`) && PathPrefix(`/api/`)
traefik.http.routers.bmsmon-api.entrypoints: websecure
traefik.http.routers.bmsmon-api.tls.certresolver: letsencrypt
traefik.http.routers.bmsmon-api.middlewares: "bmsmon-header@docker"
traefik.http.services.bmsmon-api.loadbalancer.server.port: 8000

# UI zone — Authentik SSO
traefik.http.routers.bmsmon-s.rule: Host(`bmsmon.covert.life`)
traefik.http.routers.bmsmon-s.entrypoints: websecure
traefik.http.routers.bmsmon-s.tls.certresolver: letsencrypt
traefik.http.routers.bmsmon-s.middlewares: "bmsmon-header@docker, authentik@docker"
# + the web→websecure redirect router, as in existing services
```

CI side-effects (already automated in the NAS repo):

- **DNS:** `dns_sync.py` reads `Host(\`bmsmon.covert.life\`)` → creates the AD DNS
  record (→ `192.168.101.5`). Public certs use the Cloudflare DNS challenge.
- **Authentik:** `authentik_sync.py` sees `authentik@docker` on the UI router →
  auto-creates the bmsmon Application + Proxy Provider in the embedded outpost.
- **Uptime Kuma:** manual — add a Docker monitor for `bmsmon-api` and an internal
  HTTP monitor `http://bmsmon-api:8000/api/v1/health` (bypasses Authentik).

**Image build:** server + web live in the **bmsmon repo**. A GitHub Action does a
multi-stage build (Node builds `web/` → static; Python image serves API + static)
and pushes `ghcr.io/mkeguy106/bmsmon-server`. The NAS repo only gains the compose
file; deploy pulls the image.

**Secrets / env:** add `BMSMON_DB_PASSWORD` (and DB name/user) to the NAS `.env` /
`.env.example`; the API reads DB creds from env. No app signing secret is needed
(device auth uses public keys; UI auth is Authentik).

## 6. Error handling & edge cases

- **Offline:** uploader no-ops without connectivity; samples accumulate in the
  outbox; never surfaces an error to the user.
- **Reconnect:** connectivity callback wakes the uploader; it drains the outbox
  oldest-first in batches until empty.
- **Duplicate delivery** (POST succeeded but ack lost): unique
  `(device_id, address, ts_ms)` + `ON CONFLICT DO NOTHING` makes re-send safe; the
  phone deletes acked rows only after a `200`.
- **Clock skew:** sample `ts_ms` is the phone's clock (source of truth for the
  series); JWT `exp` tolerates small skew (server allows ±2 min `iat`).
- **Outbox growth bound:** cap the outbox (e.g. matches the phone's existing
  sample retention); when full, drop oldest unsent and record a `gap` marker so
  the server timeline shows an explicit hole rather than silently losing time.
- **Revoked/unenrolled device:** persistent `401` pauses uploads and prompts
  re-enrollment; outbox is preserved.
- **WebUI live drop:** `/ws` reconnects with backoff and re-snapshots; a stale
  pack renders DISCONNECTED.
- **DB partition rollover:** monthly partitions created ahead of time by a small
  startup/maintenance task.

## 7. Testing strategy

- **Server:** pytest — enrollment (valid/expired/reused code), JWT verification
  (good sig, wrong key, expired, replayed `jti`, tampered `bh`), idempotent
  ingest, `/web/*` authorization via simulated Authentik headers, WS snapshot +
  live broadcast. Run against a disposable Postgres (testcontainers or a CI
  service).
- **Phone:** unit-test the outbox queue/flush state machine and the JWT signer
  (sign → verify with the public key) on the JVM; fake the HTTP transport to
  assert batching, ack-then-delete, backoff, and gap-marking. Manual on-device
  smoke: enroll, watch live, airplane-mode → buffer → re-enable → drain.
- **WebUI:** component tests for the live store reducer (snapshot + incoming
  sample → correct fleet state, DISCONNECTED on staleness); a mock WS feed for
  visual verification.
- **End-to-end (local):** a fake-telemetry feeder script hits `/api/v1/ingest`
  with a test device key so the WebUI can be built and seen live before the phone
  work begins.

## 8. Implementation phases

Each phase is its own spec→plan→build slice; this document is the shared design.

1. **Server + WebUI + fake feeder** — Postgres schema, enroll/ingest/JWT,
   `/web/*`, `/ws`, React live dashboard, Dockerfile + GHCR build. Demonstrable
   live with the fake feeder.
2. **Phone reporter** — outbox table, Keystore keypair + ES256 signer, uploader
   state machine, `onPoll`/link-event taps, "Cloud sync" settings page,
   `INTERNET` permission + HTTP deps.
3. **NAS deployment** — `bmsmon/` compose + Traefik/Authentik/DNS labels, `.env`
   additions, Uptime Kuma monitors; verify cert issuance, Authentik gate on the
   UI, and `/api/` bypass end-to-end.

## 9. Key source references

- Phone tap point: `android/app/src/main/java/dev/joely/bmsmon/monitor/MonitorEngine.kt` → `onPoll(addr, raw, t)`.
- Telemetry model: `android/app/src/main/java/dev/joely/bmsmon/model/Telemetry.kt`.
- Local logging pattern to mirror: `android/app/src/main/java/dev/joely/bmsmon/data/TelemetryRepository.kt`, `data/db/SampleEntity.kt`, `data/db/BmsDatabase.kt`.
- Settings pattern: `android/app/src/main/java/dev/joely/bmsmon/data/SettingsStore.kt`; Settings Hub: `android/app/src/main/java/dev/joely/bmsmon/ui/settings/SettingsScreen.kt`.
- App wiring: `android/app/src/main/java/dev/joely/bmsmon/BmsApp.kt`.
- Deploy pattern: `qnap-nas-docker/ombi/docker-compose.yml` (service template), `2.traefik/`, `3.authentik/`, `cicd/authentik_sync.py`, `cicd/dns_sync.py`.
