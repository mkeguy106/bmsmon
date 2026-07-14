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
  PRIMARY KEY (device_id, address, ts_ms, ts)
) PARTITION BY RANGE (ts);

CREATE INDEX IF NOT EXISTS samples_addr_ts ON samples (address, ts DESC);

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
