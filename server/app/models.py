from typing import Any, TypeVar

from pydantic import BaseModel, ValidationError, field_validator


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
    motion_activity: str | None = None
    motion_confidence: int | None = None
    motion_still: bool | None = None
    motion_at_ms: int | None = None

    @field_validator("cells")
    @classmethod
    def _clip_cells(cls, v: list[float] | None) -> list[float] | None:
        # The stored/REST representation is always exactly 4 cells (cell1_v..cell4_v),
        # so truncate here to keep the WS broadcast (raw model_dump()) in agreement
        # with fleet_snapshot instead of diverging on non-4-element uploads.
        return v[:4] if v else v

    @field_validator("motion_confidence")
    @classmethod
    def _clip_conf(cls, v: int | None) -> int | None:
        # Clamp rather than reject (Field(ge=0, le=100) would 422 the WHOLE batch on one bad
        # value, and the phone treats a 422 as Poison and DROPS it — losing real telemetry to
        # save one bogus confidence reading). insert_samples is one set-based statement per
        # batch, so an unbounded int here would otherwise reach `$N::smallint[]` and 500 the
        # entire batch instead of just this field.
        return v if v is not None and 0 <= v <= 100 else None

    @field_validator("motion_at_ms")
    @classmethod
    def _clip_motion_at(cls, v: int | None) -> int | None:
        # Clamp rather than reject, same rationale as _clip_conf: an unbounded int reaching
        # `$N::bigint[]` would 500 the whole batch, which the phone drops as Poison. Bound is
        # "plausible epoch ms": positive and before 2100-01-01 UTC.
        return v if v is not None and 0 < v < 4_102_444_800_000 else None


class IngestEnvelope(BaseModel):
    """The ingest body's ENVELOPE, the only part whose failure is a 422 (C3): not JSON,
    not an object, no `samples` list, or a bad batch_seq. Samples stay raw JSON values
    here and are validated ONE BY ONE in the router (validate_each), because the phone
    deletes a 4xx'd batch (PostResult.Poison) and one bad field must never cost the
    other rows (SRV-18). The phone always sends both keys (CloudJson.encodeBatch)."""
    # batch_seq semantics: a per-process counter on the phone (no ordering guarantee
    # across uploader restarts); -1 marks a historical-import batch, which the ingest
    # router does NOT fan out to the live WS. Echoed back as last_seq; reserved for
    # diagnostics — NOT used for dedup (the samples PK handles that).
    batch_seq: int
    samples: list[Any]


class IngestResponse(BaseModel):
    # accepted = rows actually inserted this batch (SRV-9); re-uploads already present
    # under the samples PK dedup to 0. dropped = samples refused server-side (C3): schema
    # rejects + ts_ms window + address rule. Duplicates are neither. Diagnostics only:
    # the phone keys retry/poison handling off the HTTP status, never off these counts.
    accepted: int
    dropped: int = 0
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
    # Raw here: each row is validated on its own (RangeConfigRow via validate_each in the
    # router), so one bad row is dropped instead of 422ing the whole push (C3).
    ranges: list[Any] | None = None


class NoteBody(BaseModel):
    base_id: str
    body: str

    @field_validator("body")
    @classmethod
    def _cap(cls, v: str) -> str:
        if len(v) > 4000:
            raise ValueError("body too long")
        return v

    @field_validator("base_id")
    @classmethod
    def _cap_base_id(cls, v: str) -> str:
        # base_id is a group_id (e.g. "2012") and the web_notes PRIMARY KEY — keep it small.
        if not v or len(v) > 64:
            raise ValueError("invalid base_id")
        return v


class OkResponse(BaseModel):
    ok: bool = True


class ConfigResponse(OkResponse):
    # dropped = ranges[] rows refused by validation (C3); diagnostics only.
    dropped: int = 0


M = TypeVar("M", bound=BaseModel)


def validate_each(model: type[M], items: list[Any]) -> tuple[list[M], list[tuple[int, str]]]:
    """C3: validate list items one at a time. Returns (valid models, rejects), where each
    reject is (index, "<loc>: <error type>") of that item's FIRST pydantic error. The
    location and type only, NEVER the value: samples carry GPS coordinates."""
    ok: list[M] = []
    bad: list[tuple[int, str]] = []
    for i, item in enumerate(items):
        try:
            ok.append(model.model_validate(item))
        except ValidationError as e:
            first = e.errors()[0]
            loc = ".".join(str(p) for p in first["loc"]) or "<item>"
            bad.append((i, f"{loc}: {first['type']}"))
    return ok, bad


class MintCodeResponse(BaseModel):
    code: str
    expires_at: str


class ApiKeyCreateBody(BaseModel):
    name: str

    @field_validator("name")
    @classmethod
    def _name(cls, v: str) -> str:
        v = v.strip()
        if not v or len(v) > 80:
            raise ValueError("name must be 1-80 characters")
        return v


class ApiKeyCreateResponse(BaseModel):
    id: str
    name: str
    key: str  # plaintext, returned ONCE — only its sha256 is stored


class ShareCreateBody(BaseModel):
    name: str
    duration: str  # "1h" | "1d" | "1w"

    @field_validator("name")
    @classmethod
    def _name(cls, v: str) -> str:
        v = v.strip()
        if not v or len(v) > 80:
            raise ValueError("invalid name")
        return v

    @field_validator("duration")
    @classmethod
    def _duration(cls, v: str) -> str:
        if v not in ("1h", "1d", "1w"):
            raise ValueError("duration must be 1h, 1d or 1w")
        return v


class ShareCreateResponse(BaseModel):
    id: int
    name: str
    expires_at: int
    path: str  # "/share/<token>" — the client prepends window.location.origin
