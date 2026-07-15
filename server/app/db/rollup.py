"""30-minute analytics rollup for samples (SRV-14).

samples grows ~3.5-4M rows/month in prod, and history_series / trend_series used to
recompute every request from raw rows. samples_rollup keeps per-(address, 30-min
bucket) SUMS + COUNTS — never averages — so closed periods are served from ~48
rows/pack/day, and any coarser trend bucket that is a multiple of 30 min (6 h / 1 d /
7 d — everything trend_bucket_ms returns) re-aggregates EXACTLY (sum of sums / sum of
counts); averages of averages would not.

High-water mark (samples_rollup_state, single row): every bucket with
bucket_ms < high_water_ms is rolled up for ALL packs. It is a GLOBAL mark and each
pass upserts the full [lo, target) window for the whole fleet at once, so a sparse
pack (no recent samples) never stalls the others — its absence from the rollup below
the mark simply mirrors its absence from raw.

Late data handling:
- Only CLOSED buckets safely in the past are rolled (bucket end <= now - 5 min), so
  normal in-flight phone uploads always land in raw first.
- The phone's offline outbox can deliver samples HOURS late (a whole day offline), so
  every pass RE-ROLLS the trailing 48 h of buckets (a full-replace upsert — cheap on
  this small table): day-late data self-heals within one 15-min pass cadence.
- Samples arriving MORE than 48 h late are accepted-stale: they show up in raw-served
  tails/windows immediately but rolled buckets keep the old aggregate. Recovery lever:
  UPDATE samples_rollup_state SET high_water_ms = 0 — the next pass re-backfills
  everything from raw, chunked by UTC month so no giant transaction is held.

Ingest NEVER writes this table synchronously — only the background task in app.main
(mirroring the GPS scrub loop) calls run_rollup_pass().
"""
import time
from datetime import datetime, timezone

import asyncpg

ROLLUP_BUCKET_MS = 1_800_000            # 30 min — must match queries.HISTORY_BUCKET_MS
ROLLUP_SAFETY_LAG_MS = 5 * 60_000       # only roll buckets whose end is >= this far past
ROLLUP_REROLL_MS = 48 * 3_600_000       # trailing window re-upserted every pass

# Full-replace upsert of every 30-min bucket in [$1, $2): the GROUP BY recomputes the
# whole bucket from raw, so re-rolling is idempotent and folds in late arrivals.
# Filter semantics mirror the consumers exactly: link_event IS NULL rows only; per-metric
# count(col) carries per-metric NULLs (history additionally needs soc_n > 0 = its
# soc IS NOT NULL filter). Expressions match the raw queries verbatim, with sums
# accumulated in float8 like Postgres' own avg(float4) transition does; the cell spread
# subtraction stays float4 first, exactly like avg((cell_max_v - cell_min_v) * 1000).
# The redundant ts predicates exist purely for partition pruning (see history_series).
_UPSERT = f"""
INSERT INTO samples_rollup AS r
  (address, bucket_ms, n, soc_sum, soc_n, soh_sum, soh_n,
   spread_sum, spread_n, temp_sum, temp_n, temp_min, temp_max)
SELECT address,
       (ts_ms / {ROLLUP_BUCKET_MS}) * {ROLLUP_BUCKET_MS} AS bucket_ms,
       count(*)::int,
       sum(soc::float8), count(soc)::int,
       sum(soh), count(soh)::int,
       sum(((cell_max_v - cell_min_v) * 1000)::float8), count(cell_max_v - cell_min_v)::int,
       sum(temp_c::float8), count(temp_c)::int,
       min(temp_c), max(temp_c)
  FROM samples
 WHERE ts_ms >= $1 AND ts_ms < $2
   AND ts >= to_timestamp($1::double precision / 1000.0)
   AND ts < to_timestamp($2::double precision / 1000.0)
   AND link_event IS NULL
 GROUP BY address, bucket_ms
ON CONFLICT (address, bucket_ms) DO UPDATE SET
  n = EXCLUDED.n,
  soc_sum = EXCLUDED.soc_sum, soc_n = EXCLUDED.soc_n,
  soh_sum = EXCLUDED.soh_sum, soh_n = EXCLUDED.soh_n,
  spread_sum = EXCLUDED.spread_sum, spread_n = EXCLUDED.spread_n,
  temp_sum = EXCLUDED.temp_sum, temp_n = EXCLUDED.temp_n,
  temp_min = EXCLUDED.temp_min, temp_max = EXCLUDED.temp_max
"""


async def get_high_water_ms(conn: asyncpg.Connection) -> int:
    """Exclusive upper bound (ms, 30-min aligned) of fully-rolled buckets; 0 = none."""
    v = await conn.fetchval("SELECT high_water_ms FROM samples_rollup_state WHERE id = 1")
    return int(v) if v is not None else 0


async def _set_high_water_ms(conn: asyncpg.Connection, ms: int) -> None:
    # GREATEST keeps the mark monotone: the trailing re-roll walks chunks that START
    # below the current mark, and an intermediate chunk end must never regress it.
    await conn.execute(
        """INSERT INTO samples_rollup_state (id, high_water_ms) VALUES (1, $1)
           ON CONFLICT (id) DO UPDATE SET
             high_water_ms = GREATEST(samples_rollup_state.high_water_ms, EXCLUDED.high_water_ms)""",
        ms)


def _month_chunks(lo_ms: int, hi_ms: int) -> list[tuple[int, int]]:
    """Split [lo_ms, hi_ms) at UTC month starts (mirroring the monthly RANGE(ts)
    partitions) so a first-run backfill never holds one giant transaction. Month starts
    are midnight UTC, hence always 30-min-bucket aligned — no bucket straddles a chunk."""
    out: list[tuple[int, int]] = []
    a = lo_ms
    while a < hi_ms:
        d = datetime.fromtimestamp(a / 1000, tz=timezone.utc)
        ny, nm = (d.year + 1, 1) if d.month == 12 else (d.year, d.month + 1)
        b = min(int(datetime(ny, nm, 1, tzinfo=timezone.utc).timestamp() * 1000), hi_ms)
        out.append((a, b))
        a = b
    return out


async def run_rollup_pass(conn: asyncpg.Connection, now_ms: int | None = None) -> int:
    """Roll up all closed 30-min buckets between the high-water mark and now - 5 min,
    plus a 48 h trailing re-roll (late-arrival self-heal). First run on an existing DB
    backfills from the earliest raw sample, one UTC-month transaction at a time — the
    mark advances after each committed chunk, so an interrupted backfill resumes where
    it stopped and queries only ever trust fully-rolled buckets. Returns bucket-rows
    upserted."""
    if now_ms is None:
        now_ms = int(time.time() * 1000)
    b = ROLLUP_BUCKET_MS
    target = ((now_ms - ROLLUP_SAFETY_LAG_MS) // b) * b  # buckets < target are closed
    hw = await get_high_water_ms(conn)
    if hw <= 0:
        # Fresh mark: backfill from the true first sample. min(ts_ms) is a full scan,
        # but it runs exactly once, in the background task. An empty DB keeps hw at 0
        # (NOT vacuously advanced) so a later multi-day backlog still backfills fully.
        first = await conn.fetchval("SELECT min(ts_ms) FROM samples WHERE link_event IS NULL")
        if first is None:
            return 0
        lo = (int(first) // b) * b
    else:
        lo = max(0, min(hw, target - ROLLUP_REROLL_MS))
    if target <= lo:
        return 0
    total = 0
    for a, c in _month_chunks(lo, target):
        async with conn.transaction():
            status = await conn.execute(_UPSERT, a, c)
            total += int(status.rsplit(" ", 1)[-1])  # "INSERT 0 N"
            await _set_high_water_ms(conn, c)
    return total
