CREATE TABLE IF NOT EXISTS devices (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  install_uuid text UNIQUE NOT NULL,
  public_key_spki bytea NOT NULL,
  label text,
  created_at timestamptz NOT NULL DEFAULT now(),
  last_seen_at timestamptz,
  revoked boolean NOT NULL DEFAULT false
);

CREATE TABLE IF NOT EXISTS enrollment_codes (
  code_hash text PRIMARY KEY,
  created_by text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  used_at timestamptz,
  device_id uuid REFERENCES devices(id)
);

CREATE TABLE IF NOT EXISTS batteries (
  address text PRIMARY KEY,
  advertised_name text,
  alias text,
  group_id text,
  first_seen timestamptz NOT NULL DEFAULT now(),
  last_seen timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS samples (
  device_id uuid NOT NULL,
  address text NOT NULL,
  ts_ms bigint NOT NULL,
  ts timestamptz NOT NULL,
  state text,
  soc real, current_a real, power_w real, voltage_v real,
  temp_c real, mosfet_temp_c int, soh int,
  full_charge_ah real, remaining_ah real, cycles int,
  cell_min_v real, cell_max_v real,
  cell1_v real, cell2_v real, cell3_v real, cell4_v real,
  regen boolean NOT NULL DEFAULT false,
  link_event text,
  received_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (address, ts, ts_ms, device_id)
) PARTITION BY RANGE (ts);

-- SRV-15 index diet: the PK used to be (device_id, address, ts_ms, ts) — device_id-first
-- made it useless for every address+time access path, so a near-duplicate secondary index
-- samples_addr_ts (address, ts DESC) (~190 MB/month in prod, indexes > heap) was carried
-- on top. Same column SET reordered to (address, ts, ts_ms, device_id) — dedup semantics
-- identical, and the (address, ts) prefix now serves fleet_snapshot's LATERAL latest-row
-- probe (backward scan), first_sample_ms, samples_range and the analytics raw tails, so
-- samples_addr_ts is dropped outright. Fresh installs get the new PK from CREATE TABLE
-- above; the DO block migrates a legacy DB exactly once (detected via pg_index column
-- order), in ONE transaction. NOTE: the ALTERs take ACCESS EXCLUSIVE on samples while
-- every partition's PK index is rebuilt — seconds-to-minutes at prod volume (~2M rows);
-- the prod deploy is done manually, watching it.
DO $$
DECLARE
  pk_name text;
  pk_cols text;
BEGIN
  SELECT c.relname,
         (SELECT string_agg(a.attname, ',' ORDER BY k.ord)
            FROM unnest(i.indkey::int2[]) WITH ORDINALITY AS k(attnum, ord)
            JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum)
    INTO pk_name, pk_cols
    FROM pg_index i
    JOIN pg_class c ON c.oid = i.indexrelid
   WHERE i.indrelid = 'samples'::regclass AND i.indisprimary;
  IF pk_cols = 'device_id,address,ts_ms,ts' THEN
    EXECUTE format('ALTER TABLE samples DROP CONSTRAINT %I', pk_name);
    ALTER TABLE samples ADD PRIMARY KEY (address, ts, ts_ms, device_id);
    DROP INDEX IF EXISTS samples_addr_ts;
  END IF;
END $$;

-- SRV-14: 30-minute analytics rollup of samples. history_series serves closed 30-min
-- buckets straight from here (~48 rows/pack/day instead of ~57,600 raw rows), and
-- trend_series re-aggregates it for any bucket that is a MULTIPLE of 30 min — which is
-- why the table stores per-metric SUMS + COUNTS, never averages: sums re-aggregate
-- exactly, averages of averages don't. Per-metric counts carry each consumer's NULL
-- semantics (history's soc IS NOT NULL filter = soc_n > 0); n counts every real-telemetry
-- row (link_event IS NULL) so a bucket whose metrics are all NULL still exists, exactly
-- like a raw GROUP BY. Small table (fleet x 48/day), no partitioning, plain PK.
-- Written ONLY by the background rollup task (app/db/rollup.py) — never by ingest.
CREATE TABLE IF NOT EXISTS samples_rollup (
  address text NOT NULL,
  bucket_ms bigint NOT NULL,
  n int NOT NULL,
  soc_sum double precision, soc_n int NOT NULL,
  soh_sum bigint, soh_n int NOT NULL,
  spread_sum double precision, spread_n int NOT NULL,
  temp_sum double precision, temp_n int NOT NULL,
  temp_min real, temp_max real,
  PRIMARY KEY (address, bucket_ms)
);

-- Rollup high-water mark (single row): every bucket with bucket_ms < high_water_ms has
-- been rolled up for ALL packs. An explicit state row, deliberately NOT MAX(bucket_ms):
-- MAX conflates "processed through here" with "data exists" — a quiet fleet would leave
-- the mark stale and force queries to raw-scan known-empty buckets, and a fresh backfill
-- could not distinguish an idle pack from an unprocessed one.
CREATE TABLE IF NOT EXISTS samples_rollup_state (
  id smallint PRIMARY KEY DEFAULT 1 CHECK (id = 1),
  high_water_ms bigint NOT NULL
);

ALTER TABLE samples ADD COLUMN IF NOT EXISTS lat double precision;
ALTER TABLE samples ADD COLUMN IF NOT EXISTS lon double precision;
ALTER TABLE samples ADD COLUMN IF NOT EXISTS gps_accuracy_m real;
ALTER TABLE samples ADD COLUMN IF NOT EXISTS eta_full_min real;
ALTER TABLE samples ADD COLUMN IF NOT EXISTS cell1_v real;
ALTER TABLE samples ADD COLUMN IF NOT EXISTS cell2_v real;
ALTER TABLE samples ADD COLUMN IF NOT EXISTS cell3_v real;
ALTER TABLE samples ADD COLUMN IF NOT EXISTS cell4_v real;

-- WEB-5: the jsonb `cells` column was dead contract cruft — never sent by the phone
-- (CloudJson.kt has no such field), never read by the web. Dropping on the partitioned
-- parent cascades to every partition; IF EXISTS keeps this idempotent on every start.
ALTER TABLE samples DROP COLUMN IF EXISTS cells;

-- One-way temperature-alert config pushed from the phone (latest-wins per device+profile). The
-- webui reads these to alert on exactly what the phone does; there is no write path back from web.
CREATE TABLE IF NOT EXISTS device_temp_config (
  device_id uuid NOT NULL,
  profile_id text NOT NULL,
  cold_caution_c int NOT NULL,
  hot_caution_c int NOT NULL,
  cold_crit_c int NOT NULL,
  hot_crit_c int NOT NULL,
  unit text NOT NULL,
  updated_at_ms bigint NOT NULL,
  received_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (device_id, profile_id)
);

-- WEB-6c: optional profile envelope (BMS cutoffs + charge lock/resume) pushed by newer
-- app builds; NULL when an older app pushed the config. Mirrored read-only by the web.
ALTER TABLE device_temp_config ADD COLUMN IF NOT EXISTS cutoff_cold_c real;
ALTER TABLE device_temp_config ADD COLUMN IF NOT EXISTS cutoff_hot_c real;
ALTER TABLE device_temp_config ADD COLUMN IF NOT EXISTS charge_lock_cold_c real;
ALTER TABLE device_temp_config ADD COLUMN IF NOT EXISTS charge_lock_hot_c real;
ALTER TABLE device_temp_config ADD COLUMN IF NOT EXISTS charge_resume_cold_c real;

-- One-way capacity alert config pushed from the phone (latest-wins per device, device-level
-- not per-profile). Tells the webui the SOC threshold at which a low pack seizes the main
-- stage, plus whether capacity alerts are on. Read-only mirror on the web; no write path back.
CREATE TABLE IF NOT EXISTS device_alert_config (
  device_id text PRIMARY KEY,
  seize_soc int,
  alerts_on boolean,
  updated_at_ms bigint
);

-- Learned discharge-range parameter bands pushed from the phone (one-way, latest-wins per
-- device+pack). The webui's range.ts formula twin reads these; no write path back from web.
CREATE TABLE IF NOT EXISTS device_range_config (
  device_id uuid NOT NULL,
  address text NOT NULL,
  wh_per_day_lo real NOT NULL,
  wh_per_day_hi real NOT NULL,
  active_w_lo real NOT NULL,
  active_w_hi real NOT NULL,
  wh_per_mile_lo real NOT NULL,
  wh_per_mile_hi real NOT NULL,
  learned_days int NOT NULL DEFAULT 0,
  updated_at_ms bigint NOT NULL,
  received_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (device_id, address)
);

-- WebUI-authored free-text notes per base (first WebUI write path). One row per base_id,
-- latest-wins on upsert; no phone involvement.
CREATE TABLE IF NOT EXISTS web_notes (
  base_id text PRIMARY KEY,
  body text NOT NULL,
  updated_at_ms bigint NOT NULL,
  received_at timestamptz NOT NULL DEFAULT now()
);

-- Time-limited public location-share links (capability URLs; the /share/ Traefik zone
-- bypasses Authentik). Only sha256(token) is stored — the full URL is returned once at
-- creation and never again. Guests get today's GPS trail only; feed queries clamp
-- server-side and never return battery fields. revoked_at NULL = not revoked.
CREATE TABLE IF NOT EXISTS location_shares (
  id bigserial PRIMARY KEY,
  token_hash text NOT NULL UNIQUE,
  name text NOT NULL,
  created_at bigint NOT NULL,
  expires_at bigint NOT NULL,
  revoked_at bigint,
  created_by text,
  last_access_ms bigint,
  access_count bigint NOT NULL DEFAULT 0
);
