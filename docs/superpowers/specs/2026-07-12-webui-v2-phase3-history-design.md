# WebUI v2 — Phase 3: History (Design Spec)

**Status:** approved design, ready for implementation plan · **Date:** 2026-07-12
**Roadmap:** `2026-07-12-webui-v2-roadmap.md` · **Design source:** `design_handoff_bmsmon_webui_v2/`
(view 04-history) · **Builds on:** Phases 1–2 (shipped + deployed).

Phase 3 turns the **History** placeholder into a live per-base trend hub: three trend charts
(capacity fade, cell imbalance, temperature) with base + A/B + time-range controls, a
charge-session log, and editable per-base notes. It adds three new read-only-ish `/web` endpoints
(`trends`, `charge-sessions`, `notes` — the last with the WebUI's first write path).

---

## 1. Goals & non-goals

**Goals**
- Live **History** view: base/AB/range controls; SOH-fade / cell-imbalance / temperature charts;
  charge-session log; editable notes (backend-persisted).
- New endpoints: `GET /web/trends`, `GET /web/charge-sessions`, `GET`/`POST /web/notes`.
- Server-side **charge-session detection** (testable Python over charging-row buckets).

**Non-goals (Phase 3)**
- Journey/GPS view (Phase 4; stays a placeholder).
- Any change to v1, or to Command/Health/Alerts/Settings behavior.
- New npm dependencies (charts are inline SVG). No chart library.
- Per-cell (C1–C4) history — cell **spread** (mV) only; individual-cell trend is out of scope.

---

## 2. Backend

### 2.1 `GET /web/trends?address=<mac>&from_ms=&to_ms=`
- `current_user`, read-only. One pack per call (the view fetches 1 or 2 addresses for Group vs A/B).
- **Adaptive bucket** from `span = to_ms - from_ms`: `≤2 days → 30 min` · `≤14 days → 6 h` ·
  `≤92 days → 1 day` · else `7 days`. Bucket width in ms is returned as `bucket_ms`.
- Query (new `trend_series` in `queries.py`): bucket by `(ts_ms / bucket) * bucket`, real telemetry
  only (`link_event IS NULL`), aggregate per bucket:
  `avg(soh)`, `avg((cell_max_v - cell_min_v) * 1000)` as `cell_spread_mv`,
  `avg(temp_c)`/`min(temp_c)`/`max(temp_c)`.
- Response: `{ "address", "bucket_ms", "first_ms", "points": [ { "t", "soh", "cell_spread_mv",
  "temp_avg", "temp_min", "temp_max" }, … ] }`. `first_ms` = `min(ts_ms)` for the address (drives the
  "All" default and the install/"replaced" year). Metrics may be null in a bucket that lacks them.
- Server tests: bucket sizing per span; aggregation across ≥2 buckets; nulls tolerated; `first_ms`.

### 2.2 `GET /web/charge-sessions?address=<mac>&days=30`
- `current_user`, read-only. `days` clamp 1..365.
- **Efficient source query** (`charge_session_buckets` in `queries.py`): 1-minute buckets of
  **charging rows only** (`current_a > 0.1`) since `now - days`: `avg(soc)`, `max(temp_c)`, per
  `(ts_ms / 60000) * 60000` bucket, ordered by time. (Charging is a fraction of runtime, so this is
  a small result set.)
- **Pure detection** (`server/app/charge_sessions.py`, `detect_charge_sessions(buckets, *, gap_ms=900_000, soc_full=99)`):
  group consecutive buckets into runs (a gap `> gap_ms` = 15 min splits a run); keep only runs whose
  `max(soc) >= soc_full`; for each emit
  `{ start_ms, end_ms, from_soc (first bucket's soc), duration_min, cv_tail_min (minutes with soc ≥ 98),
  peak_temp_c (max over the run) }`. Returned newest-first.
- Route assembles `{ "sessions": [...] }`. **This module is the unit-test centerpiece** (empty; one
  clean session; a sub-99 run dropped; a gap splitting two sessions; CV-tail counting).

### 2.3 `GET /web/notes` + `POST /web/notes`
- Additive table (schema.sql, `CREATE TABLE IF NOT EXISTS`):
  ```sql
  CREATE TABLE IF NOT EXISTS web_notes (
    base_id text PRIMARY KEY,
    body text NOT NULL,
    updated_at_ms bigint NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now()
  );
  ```
- `GET /web/notes` (`current_user`) → `{ "notes": [ { "base_id", "body", "updated_at_ms" }, … ] }`.
- `POST /web/notes` (`current_user`, body `NoteBody { base_id: str; body: str }`, `body` length-capped
  e.g. ≤ 4000): upsert (`INSERT … ON CONFLICT (base_id) DO UPDATE`), latest-wins, returns `OkResponse`.
- This is the WebUI's **first write path**; it's Authentik-gated (Traefik routes non-`/api` `/web/*`
  through Authentik SSO). Server tests: get-empty, post-then-get round-trip, upsert overwrites, 401
  without identity, over-length rejected (422).

### 2.4 Models (`server/app/models.py`)
- Add `class NoteBody(BaseModel): base_id: str; body: str` with a `field_validator("body")` capping length.

---

## 3. Frontend — data layer

- **New** `web/src/v2/trends.ts` (leave the Phase-2 `history.ts`/`HistPoint` UNTOUCHED — it still backs
  the Fleet Health Sparkline/`useHistory`):
  `TrendPoint { t; soh; cell_spread_mv; temp_avg; temp_min; temp_max }` (all nullable numbers),
  `TrendSeries { address; bucket_ms; first_ms; points: TrendPoint[] }`,
  `ChargeSession { start_ms; end_ms; from_soc; duration_min; cv_tail_min; peak_temp_c }`,
  `NoteRow { base_id; body; updated_at_ms }`.
- `web/src/decode.ts`: `decodeTrends`, `decodeChargeSessions`, `decodeNotes` (tolerant, drop malformed).
- `web/src/api.ts`: `getTrends(address, fromMs, toMs)`, `getChargeSessions(address, days)`,
  `getNotes()`, `putNote(baseId, body)`.
- Pure `web/src/v2/model/trends.ts` (tested):
  - `projectMonthsTo80(points): number | null` — least-squares linear fit of SOH vs time over
    non-null points; extrapolate to 80%; **null** when < N points or slope ≥ 0 (no meaningful decline)
    → UI shows "insufficient data".
  - `sohBand(soh): "good"|"fair"|"degraded"` (≥90 / 80–89 / <80) for the chart bands.

## 4. Frontend — components & view

- `web/src/v2/components/LineChart.tsx` — reusable inline-SVG chart: one or more series (A/B overlay
  or a single Group line), an optional shaded **y-band** set (SOH health bands), an optional
  horizontal **watch-line** (40 mV imbalance), a min/avg/max ribbon (temperature), a time x-axis, and
  a y-axis with range labels. Token-styled. Empty/insufficient state renders a muted "not enough data".
- `web/src/v2/views/HistoryView.tsx`:
  - **Controls:** base buttons (from `groupBases`), Group / A / B toggle (`Segmented`), time-range
    buttons (All · 24h · 7d · 1M · 1Y · Custom→two date inputs). Persist last selection per browser
    (`bmsmon-v2-hist`, session-or-local — small).
  - **Header:** per-pack install/first-data line derived from each series' `first_ms` year
    (e.g. "A since 2024 · B since 2024"; when the two packs' first years differ, read as replaced).
  - **Charts:** Capacity fade (SOH, green, `sohBand` shaded bands, `projectMonthsTo80` → "≈ N mo to
    80%" caption or "insufficient data"), Cell imbalance (cell_spread_mv, amber, 40 mV watch-line),
    Temperature (full-width, min/avg/max ribbon, unit from settings via `formatTemp`).
  - **Charge-session log** table (`getChargeSessions` for the selected pack(s)): date · from→100% ·
    duration · CV tail · peak temp. Group mode shows both packs' sessions merged (labeled A/B).
  - **Notes:** a textarea per base, loaded from `getNotes`, **debounced save** (~800 ms) to `putNote`.
    Shows a small "saved" indicator.
  - **Group vs A/B fetch:** Group blends the base's packs (fetch both `first_ms`-aligned series and
    render an averaged line, or overlay muted); A/B fetches/renders the one/both selected packs.
  - On `mobile`, charts stack full-width; controls wrap; the session table scrolls horizontally.

## 5. Shell wiring (`App.tsx`)

- Replace the `history` placeholder with `<HistoryView data={data} unit={settings.tempUnitPref}
  mobile={mobile} />`. History owns its own control state + fetch hooks (it does NOT need the live
  store beyond `groupBases(data.items, data.staleAddrs)` for the base list/labels). Journey stays a
  placeholder. No change to the single live store, nav, or other views.

---

## 6. Testing & verification

- **Web (vitest):** `trends.ts` (`projectMonthsTo80`: declining fit → months; flat/rising → null;
  too-few-points → null; `sohBand` thresholds); decoders (`decodeTrends`/`decodeChargeSessions`/
  `decodeNotes`: valid + malformed dropped + non-array→null).
- **Server (pytest):** `trend_series` bucket sizing + aggregation + `first_ms`;
  `detect_charge_sessions` (empty / one session / sub-99 dropped / gap-split / CV-tail count);
  `charge_session_buckets` charging-only filter; `web_notes` (empty/round-trip/upsert/401/over-length);
  the trends + charge-sessions routes (auth + shape).
- **End-to-end (verify skill):** build both bundles; drive `/v2/` (headless) — History renders the
  three charts against the real/seeded trends endpoint, base/AB/range controls refetch, the
  charge-session table lists sessions (or an empty state), notes save to the backend and reload;
  Command/Health/Alerts/Settings + v1 unaffected.

---

## 7. Out of scope / deferred

- Journey/GPS (Phase 4).
- Per-cell (C1–C4) history; only cell **spread** is charted.
- Real-time streaming of trends (History polls on control change, not via the WS).
- The Phase-2 follow-up cleanups (shared `v2/colors.ts`, alerts epsilon-compare) — may be folded in
  opportunistically if History touches those files, else stay separate.

---

## 8. Open items to confirm during implementation

- Group-mode chart rendering: **averaged single line** vs **A/B overlay muted** — pick whichever
  reads cleaner against real data (prefer averaged for Group, since Group is "blends A+B").
- Charge-session `from_soc` when the first charging bucket is already high (partial top-up) — report
  it as-is (the table shows "from NN%"); no filtering of top-ups beyond the `soc ≥ 99` completion gate.
- `projectMonthsTo80` minimum points / minimum time-span before it emits a number (avoid a wild
  extrapolation from 2 weeks of noise) — tune with the real data; default to requiring ≥ ~10 days span.
- Notes base list = the fleet's distinct `group_id`s (from `groupBases`); a base with no note yet
  shows an empty editable textarea.
