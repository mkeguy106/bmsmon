from pydantic import BaseModel, field_validator


class EnrollBody(BaseModel):
    code: str
    install_uuid: str
    public_key_spki_b64: str
    device_label: str | None = None


class EnrollResponse(BaseModel):
    device_id: str


class SampleIn(BaseModel):
    ts_ms: int
    address: str
    advertised_name: str | None = None
    alias: str | None = None
    group_id: str | None = None
    state: str | None = None
    soc: float | None = None
    current_a: float | None = None
    power_w: float | None = None
    voltage_v: float | None = None
    temp_c: float | None = None
    mosfet_temp_c: int | None = None
    soh: int | None = None
    full_charge_ah: float | None = None
    remaining_ah: float | None = None
    cycles: int | None = None
    cell_min_v: float | None = None
    cell_max_v: float | None = None
    cells: list[float] | None = None
    regen: bool = False
    link_event: str | None = None
    lat: float | None = None
    lon: float | None = None
    gps_accuracy_m: float | None = None
    eta_full_min: float | None = None

    @field_validator("cells")
    @classmethod
    def _clip_cells(cls, v: list[float] | None) -> list[float] | None:
        # The stored/REST representation is always exactly 4 cells (cell1_v..cell4_v),
        # so truncate here to keep the WS broadcast (raw model_dump()) in agreement
        # with fleet_snapshot instead of diverging on non-4-element uploads.
        return v[:4] if v else v


class IngestBody(BaseModel):
    # batch_seq semantics: a per-process counter on the phone (no ordering guarantee
    # across uploader restarts); -1 marks a historical-import batch, which the ingest
    # router does NOT fan out to the live WS. Echoed back as last_seq; reserved for
    # diagnostics — NOT used for dedup (the samples PK handles that).
    batch_seq: int
    samples: list[SampleIn] = []


class IngestResponse(BaseModel):
    # accepted = rows actually inserted this batch (SRV-9): samples that failed server
    # validation (ts_ms window, address rule) are dropped before insert, and re-uploads
    # already present under the samples PK dedup to 0. Diagnostics only — the phone
    # keys retry/poison handling off the HTTP status, never off this count.
    accepted: int
    last_seq: int


class RangeConfigRow(BaseModel):
    address: str
    wh_per_day_lo: float
    wh_per_day_hi: float
    active_w_lo: float
    active_w_hi: float
    wh_per_mile_lo: float
    wh_per_mile_hi: float
    learned_days: int = 0
    updated_at_ms: int


class TempConfigBody(BaseModel):
    profile_id: str
    cold_caution_c: int
    hot_caution_c: int
    cold_crit_c: int
    hot_crit_c: int
    unit: str
    updated_at_ms: int
    # WEB-6c: optional profile envelope (BMS cutoffs + charge lock/resume points) so the
    # web mirror can render the exact envelope the phone alerts on instead of hardcoding
    # it. Optional (None when an older app pushes without them) — which also retro-fixes
    # the WEB-6b hazard for these fields: an old-shape body must keep validating, never
    # turn into a 422 the phone would re-POST forever.
    cutoff_cold_c: float | None = None
    cutoff_hot_c: float | None = None
    charge_lock_cold_c: float | None = None
    charge_lock_hot_c: float | None = None
    charge_resume_cold_c: float | None = None
    # Device-level capacity alert sync (parallel to temp config): the SOC threshold at
    # which a low pack should seize the WebUI main stage, and whether capacity alerts are
    # on. Optional — an older app pushing a temp-only body must keep validating (never a
    # 422 the phone re-POSTs forever), so both stay None-defaulted and backward compatible.
    seize_soc: int | None = None
    alerts_on: bool | None = None
    # Learned discharge-range bands, one row per pack (2026-07-11 design). Optional — an
    # older app pushing a temp-only body must keep validating (never a re-POSTed 422).
    ranges: list[RangeConfigRow] | None = None


class OkResponse(BaseModel):
    ok: bool = True


class MintCodeResponse(BaseModel):
    code: str
    expires_at: str
