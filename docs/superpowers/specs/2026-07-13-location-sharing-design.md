# Live Location Sharing — Design

**Date:** 2026-07-13
**Status:** Approved (brainstorm complete)

## Goal

From the WebUI, share a live "find me" view of the chair's location with a specific
person for a limited time (1 hour / 1 day / 1 week), via an unguessable link that
bypasses Authentik. Guests see only today's live location — never battery data,
never history. Shares are named (who they were sent to), listable, and revocable
from Settings.

## The link and its security

A share URL is `https://bmsmon.covert.life/share/<token>`.

- **Token:** `secrets.token_urlsafe(24)` — 192 bits of CSPRNG entropy (~32 URL-safe
  chars). Unguessable; brute force is not a realistic threat at this size.
- **Only a hash is stored.** The DB keeps `sha256(token)`, never the token. The full
  URL exists only at creation time, returned once to the sharer. A DB dump cannot
  reproduce live links.
- **Unknown and revoked tokens → silent bare 404**, byte-identical to any
  nonexistent path. No distinction for probers. Revocation = deliberate cutoff, so
  revoked links also go silent.
- **Expired tokens → friendly page:** "This location share has expired. Ask Joely to
  send you a new link." An expired token provably was valid once, so this leaks
  nothing useful to a scanner.
- **Re-validated on every poll.** The token is checked on each data request, not
  just page load — a revoke in Settings kills a guest's live feed within one poll
  cycle (~10 s). The open guest page shows "share ended" (revoked → generic ended;
  expired → ask-to-reshare) when polls fail.
- **Per-IP rate limiting** on the `/share/` endpoints via the existing
  `server/app/ratelimit.py`.
- **No leak side-channels:** `Referrer-Policy: no-referrer` on share responses (the
  token can't leak via map-tile attribution outlinks), `Cache-Control: no-store` on
  feed responses, noindex (the existing `X-Robots-Tag` response-header middleware
  already covers the share zone; the page adds a `<meta name="robots">` too).

## Infra (qnap-nas-docker repo)

One new Traefik router on `bmsmon-api` in `bmsmon/docker-compose.yml`:

- `bmsmon-share`: `Host(bmsmon.covert.life) && PathPrefix(/share/)`, priority 100,
  entrypoint websecure, TLS letsencrypt, service `bmsmon`.
- Middlewares: `bmsmon-header@docker` + `bmsmon-proxy-secret@docker` — no
  `authentik@docker`. (Final-review amendment: the proxy secret rides along ONLY so
  the app's rate limiter can trust `X-Forwarded-For` for true per-IP keying; the
  `/share` endpoints never read identity headers. Without it, all guests and
  scanners would share one global rate bucket keyed on Traefik's container IP.)

Deploys via the qnap-nas-docker repo's push-to-master self-hosted runner (compose
changes restart the changed service automatically).

## Server (FastAPI, `server/`)

### Schema (`server/app/db/schema.sql`, idempotent DDL as usual)

```sql
CREATE TABLE IF NOT EXISTS location_shares (
  id           BIGSERIAL PRIMARY KEY,
  token_hash   TEXT NOT NULL UNIQUE,     -- sha256 hex of the URL token
  name         TEXT NOT NULL,            -- who it was shared with
  created_at   BIGINT NOT NULL,          -- epoch ms
  expires_at   BIGINT NOT NULL,          -- epoch ms
  revoked_at   BIGINT,                   -- epoch ms, NULL = active
  created_by   TEXT,                     -- Authentik username of the sharer
  last_access_ms BIGINT,                 -- guest last opened/polled
  access_count BIGINT NOT NULL DEFAULT 0
);
```

### Public router (`server/app/routers/share.py` — new)

Lives in the unauthenticated `/share/` Traefik zone, therefore **no proxy-secret
dependency and no Authentik headers** (unlike `/web/*`).

- `GET /share/{token}` — validate token:
  - active → serve the guest HTML page.
  - expired → friendly "ask to reshare" page (HTTP 200 is fine; content differs).
  - unknown or revoked → bare 404, identical to any unknown path.
- `GET /share/{token}/feed` — validate the same way (unknown/revoked → 404,
  expired → 410 so the open page can render ask-to-reshare). Active → JSON:
  - `points`: today's GPS trail, 15 s-bucketed, **fields `t`/`lat`/`lon` only** —
    no power, current, SOC, voltage, temperature, or any battery field, ever.
  - `last`: freshest fix `{t, lat, lon}` for the live marker.
  - `expires_at`: for the guest page's countdown.
  - **Day clamp is server-side:** the window is `[start of today in the server TZ
    (TZ env), now]` regardless of share duration. Guests send no time parameters,
    so there is nothing to tamper with. A week-long share shows a fresh trail each
    day.
  - Updates `last_access_ms` / `access_count`.

GPS is phone-level (identical across packs), so the feed query selects bucketed
coordinates across all samples with non-null lat/lon — no address parameter.

### Management endpoints (existing `/web` router, Authentik zone)

**Admin-gated** (`require_admin`) — creating a share grants unauthenticated access,
same trust class as enroll codes:

- `POST /web/shares` `{name, duration: "1h"|"1d"|"1w"}` → creates the row, returns
  `{id, name, expires_at, url}` — the only time the full URL is ever returned.
- `GET /web/shares` → shares that are active, or expired/revoked within the last
  7 days, with name, expiry, last_access_ms, access_count. Older rows drop out of
  the listing (rows are kept in the table; only the listing filters).
- `DELETE /web/shares/{id}` → sets `revoked_at`.

## Guest "find me" page (`web/src/share/` — new Vite entry)

Third rollup input (`share/index.html` → `dist/share/`, same pattern as v2) so its
JS/CSS live under the public `/share/` path; the main app's Authentik-gated bundle
is untouched. `web/share/index.html` carries the viewport meta (v2 mobile lesson).

Content — deliberately minimal, stranger-safe:

- Full-screen Leaflet map, CARTO dark/light tiles, reusing v2's map styling and
  track-cleaning (`cleanTrack`).
- Today's trail in a single neutral color — **no discharge coloring**.
- The pulsing ♿ live marker with LIVE / last-seen-age badge; grey un-pulsed at the
  last-known position when the freshest fix is >120 s old (same semantics as
  Journey).
- Auto-follow camera; pan breaks follow; crosshair re-center button re-locks.
- Header: "Following Joely" + time remaining on the share.
- Polls `feed` every 10 s. On 404 → "This share has ended." On 410 → the
  ask-to-reshare message.
- No battery data, no fleet info, no nav, no links into the app.

### Bonus: direction-to-target arrow (progressive enhancement)

- **Tier 1 (all browsers):** a "Where am I?" affordance requests browser
  geolocation; the page computes great-circle distance + initial bearing to the
  chair's live position, shows "0.4 mi ↗ NE", and draws a bearing line from guest
  to chair on the map. Updates as either end moves.
- **Tier 2 (compass):** where device orientation is available (Android Chrome
  `deviceorientationabsolute`; iOS Safari `DeviceOrientationEvent.requestPermission()`
  behind a tap), a large on-screen arrow rotates live to keep pointing at the
  chair as the guest turns. Falls back to Tier 1 silently when unavailable.

## Share creation UX (Journey view, v2)

Share icon in the Journey toolbar → small dialog:

- **Name** (required — who it's for) and **duration** (1 h / 1 day / 1 week).
- Create → `POST /web/shares` → on success:
  - `navigator.share({title, text, url})` where supported (mobile share sheet —
    Messages is one tap away);
  - else clipboard copy + "copied" toast (desktop path).

## Management UX (Settings view, v2)

A **Location shares** panel alongside the Devices panel: one row per share — name,
time remaining (or EXPIRED), last opened (from `last_access_ms`, so you can see
whether the recipient ever clicked it), open count, and a **Revoke** button.
Expired shares age out.

## Testing

- **Pure logic (pytest / vitest, matching repo patterns):** token hash + lookup,
  expiry math, day-clamp window computation, bearing/distance math, dialog state.
- **Endpoint tests (server/tests):** unknown vs revoked vs expired response
  indistinguishability rules; the feed payload contains **no battery fields**
  (explicit key-set assertion); day clamp ignores share duration; revoke kills the
  feed; rate limiting engages.
- **Manual end-to-end:** share sheet on the phone, guest page on a second device,
  compass arrow on Android + iOS, revoke-while-open.

## Out of scope

- No guest access to history, other days, or any battery/fleet data.
- No v1 UI (share creation/management is v2-only; Journey exists only in v2).
- No SMS sending server-side — sharing is the phone's native share sheet.
- No per-guest auth beyond the capability URL — anyone holding the link can view
  while it's active (explicit requirement).
