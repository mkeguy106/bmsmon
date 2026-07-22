# DB Retention Strategy — keep the cloud DB under 25 GB while preserving a decade of battery health

**Date:** 2026-07-22
**Status:** Approved design, pre-implementation
**Goal:** Bound the production Postgres DB at a comfortable steady state (~10 GB, hard budget 25 GB)
without ever losing the long-term battery-health record.

## Background / current state

- Prod DB (`bmsmon-db`, Postgres 16 on `ddnas02`) is **862 MB** today.
- The `samples` table is **partitioned by month** (`samples_YYYY_MM`, `RANGE(ts)`), grows
  **~130 k rows/day ≈ ~0.8 GB/month** (heap + index; e.g. `samples_2026_07` = 800 MB total =
  530 MB heap + 270 MB index). Ingest started **~28 June 2026**.
- **Nothing ever drops a partition today** (`partitions.py` comment) — raw grows unbounded. At the
  current rate the DB reaches 25 GB in ~22 months.
- `samples_rollup` (SRV-14) already keeps, **forever**, per-`(address, 30-min bucket)`
  **sums+counts** of SOC / SOH% / cell-spread / temperature (+ temp min/max). ~48 rows/pack/day →
  ~150 k rows/year for all 8 packs; negligible size. History/trend charts route through it.
- The rollup does **NOT** currently keep: absolute capacity (`full_charge_ah`), `cycles`,
  current/power, GPS, per-cell voltages. So dropping raw today would lose the two *best*
  long-term fade signals (absolute Ah + cycle count), keeping only integer SOH%.
- **Learners run off the phone's local 14-day Room DB, not the cloud** — pruning cloud raw does
  not affect any calibration/estimate learning.

## User priorities (decisive)

1. **Battery performance / capacity over time is the most important thing to keep** — batteries may
   last **5–10 years**; that record must survive.
2. **GPS and trip detail are disposable.**
3. Keep the DB **under 25 GB**.
4. Chosen raw window: **12 months** (~10 GB steady).

## Strategy (Approach A): enrich the permanent rollup, then prune raw at 12 months

The rollup is already the forever-tier. Make it a *complete* battery-health archive, then drop raw
monthly partitions older than 12 months. The two pieces interlock by a high-water safety gate so
raw is never dropped before its health data is archived.

### Component 1 — Enrich `samples_rollup` into a decade-long health archive

Add columns (idempotent `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`, applied on container start):

| Column | Aggregate of | Type | Rationale |
|--------|-------------|------|-----------|
| `cap_sum` | `sum(full_charge_ah::float8)` | `double precision` | absolute measured capacity (Ah) — truest fade signal, finer than integer SOH% |
| `cap_n` | `count(full_charge_ah)` | `int NOT NULL DEFAULT 0` | per-metric NULL count so coarser buckets re-aggregate exactly (sum-of-sums / sum-of-counts) |
| `cycles_max` | `max(cycles)` | `int` | cycle count is monotonic → max, and max-of-maxes re-aggregates exactly |

- **Do NOT add voltage or current/power** — out of scope; the user's priority is capacity + cycles.
- Update `rollup.py::_UPSERT` to compute the three new expressions and include them in the
  `ON CONFLICT DO UPDATE SET`. Expressions match the raw semantics verbatim (`link_event IS NULL`
  rows only, sums accumulated in `float8`), exactly like the existing metrics.
- Steady growth of the enriched rollup: still ~150 k rows/year → **tens of MB per decade.** This is
  the tier that survives 5–10 years.

### Component 2 — One-time enriched backfill (safety linchpin)

All raw is still present (data starts June 2026), so backfill the new columns across ALL existing
history **before any raw is ever dropped**:

- Add `rollup_schema_ver smallint NOT NULL DEFAULT 0` to `samples_rollup_state` (idempotent ALTER).
- Define a code constant `ROLLUP_SCHEMA_VER` (bump to 1 for this change).
- On startup, if `stored rollup_schema_ver < ROLLUP_SCHEMA_VER`: reset `high_water_ms = 0` and set
  `rollup_schema_ver = ROLLUP_SCHEMA_VER` (single transaction). The existing background re-roll then
  recomputes **every** bucket WITH cap/cycles, month-by-month from raw (already chunked by UTC month
  — see `run_rollup_pass`). Self-healing; works identically on fresh installs and prod.
- **Manual fallback** (matches the documented recovery lever): `UPDATE samples_rollup_state SET
  high_water_ms = 0;` after deploy.

### Component 3 — Raw retention task (drop old monthly partitions)

New module `app/db/retention.py` + a daily background loop in `app/main.py` (mirroring the GPS-scrub
and rollup loops: one pass shortly after startup, then every 24 h).

- `drop_expired_partitions(conn, keep_months, now_ms) -> list[str]`:
  1. Compute `cutoff` = start of month `(now − keep_months)` (UTC), reusing the month arithmetic
     style in `partitions.py` / `rollup.py`.
  2. Enumerate real partitions of `samples` from the catalog (`pg_inherits` → child relnames
     matching `samples_YYYY_MM`); parse `(year, month)` from each name.
  3. A partition is a **drop candidate** only if its entire range is strictly older than `cutoff`.
  4. **Hard safety gate:** only `DROP TABLE` when the partition's `end_ms ≤ high_water_ms` — i.e. it
     is fully folded into the enriched permanent archive. After the Component-2 re-roll, high-water
     climbs from 0, so retention physically cannot drop a partition before its health data is
     archived. The two components interlock by construction.
  5. `DROP TABLE samples_YYYY_MM` — instant, reclaims space cleanly (no delete-bloat, no vacuum).
  6. Return dropped partition names for logging.
- Config `BMSMON_RAW_RETENTION_MONTHS` (in `config.py`), **default 12**; `<= 0` **disables**
  (keep raw forever), mirroring `gps_retention_days` semantics. Loop is skipped entirely when
  disabled.
- Late-data caveat: only months ≥12 months old are ever dropped; the phone outbox realistically
  never buffers a year, so a very-late sample for a dropped month is a non-concern. Document it; do
  not engineer around it.

### Component 4 — WebUI / feature impact

- **History + Fleet Health trends** (capacity fade, cell imbalance, temperature): rollup-served →
  **fully preserved forever**, now additionally backed by absolute-Ah + cycles. (Upgrading the
  capacity-fade chart to plot absolute Ah is a **future** improvement, explicitly out of scope here.)
- **Journey trails, charge-session log, efficiency card** for dates >12 months old: no fine-grained
  data (by design — GPS/trip detail is disposable). These endpoints already degrade to empty
  gracefully; verify no crash on an empty/absent-partition window.

### Component 5 — Testing

- Extend `tests/test_rollup.py`: `cap_sum`/`cap_n`/`cycles_max` are computed from raw and
  **re-aggregate exactly** to coarser buckets (sum-of-sums for cap, max-of-maxes for cycles) —
  identical to a pure-raw computation. Version-gated re-roll runs once and backfills the columns.
- New `tests/test_retention.py`:
  - only fully-rolled partitions strictly older than `keep_months` are dropped;
  - recent partitions are kept;
  - the high-water gate **blocks** dropping an old-but-not-yet-rolled partition;
  - `keep_months <= 0` disables (drops nothing);
  - `DROP TABLE` actually reclaims (partition gone from catalog), other partitions intact.

## Rollout (prod, manual — matches existing deploy discipline)

1. Merge → image build (GitHub Actions) → **pull + recreate `bmsmon-api`** on `ddnas02` (per the
   CLAUDE.md deploy runbook). On start: schema ALTERs add the columns; the version bump resets
   high-water; the background re-roll enriches ALL history from still-present raw.
2. Watch the re-roll complete (high-water climbs back to present; logs report upserted rows).
3. The daily retention loop then runs, but **drops nothing until ~mid-2027** — the June 2026
   partition (range `[Jun 1, Jul 1) 2026`) only becomes entirely older than the rolling
   `now − 12 months` cutoff around **July 2027**. This deploy is **preventive** — it installs the
   guardrail now; it caps the DB permanently once history reaches 12 months.

## Steady-state math

- Raw: 12 full months × ~0.8 GB ≈ **~9.6 GB**.
- Rollup archive: tens of MB per decade — negligible.
- Other tables: <10 MB.
- **Total steady state ≈ ~10 GB**, hard-bounded well under the 25 GB budget, forever.

## Non-goals (YAGNI)

- No raw down-sampling/thinning tier (the rollup already IS the 30-min tier).
- No voltage/current/power in the rollup.
- No capacity-fade chart rework to absolute Ah (future).
- No change to the phone, ingest path, or learners.

## Documentation to update at the end of the work

- **`CLAUDE.md`** (bmsmon): document the retention strategy — rollup enrichment (cap/cycles), the
  `BMSMON_RAW_RETENTION_MONTHS` config + daily retention loop, the high-water safety gate, and the
  "nothing drops until ~June 2027" preventive note. Update the `partitions.py` "nothing ever drops a
  partition" statement, the schema/rollup section, and the Cloud Server section.
- **`~/GoogleDrive/obsidian/notes/Bmsmon.md`** — reflect the new retention/archive posture.
- **Deploy runbook** note (in CLAUDE.md): the one-time re-roll on this deploy and how to watch it.
