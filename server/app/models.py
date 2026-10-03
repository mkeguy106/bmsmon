import math
from typing import Annotated, Any, TypeVar

from pydantic import AfterValidator, BaseModel, ValidationError, field_validator

# Postgres storage bounds for device-pushed values. A value can pass the pydantic type and
# still be unstorable: an int4 or float4 overflow (asyncpg's binary codecs refuse it) or a
# NUL byte (Postgres text cannot hold 0x00). insert_samples is ONE set-based statement per
# batch, so a single such value used to 500 the WHOLE batch, and the phone retries a 500
# forever: its upload queue stayed blocked behind that batch. So: OPTIONAL fields degrade
# to None (clamp, like _clip_conf), NaN/±inf included since nothing downstream can use
# them; REQUIRED config fields fail validation instead (the range row is dropped, or the
# config envelope 422s), because None would violate their NOT NULL; NUL is stripped from
# text.
INT4_MIN, INT4_MAX = -(2**31), 2**31 - 1
INT8_MIN, INT8_MAX = -(2**63), 2**63 - 1
FLOAT4_MAX = 3.4028234663852886e38  # largest finite float4 (real)


def _int4_ok(v: int) -> bool:
    return INT4_MIN <= v <= INT4_MAX


def _real_ok(v: float) -> bool:
    return math.isfinite(v) and abs(v) <= FLOAT4_MAX


def _int4_or_none(v: int | None) -> int | None:
    return v if v is None or _int4_ok(v) else None


def _real_or_none(v: float | None) -> float | None:
    return v if v is None or _real_ok(v) else None


def _finite_or_none(v: float | None) -> float | None:
    return v if v is None or math.isfinite(v) else None


def _strip_nul(v: str | None) -> str | None:
    return v.replace("\x00", "") if v else v


def _require_int4(v: int) -> int:
    if not _int4_ok(v):
        raise ValueError("outside the int4 range")
    return v


def _require_int8(v: int) -> int:
    if not INT8_MIN <= v <= INT8_MAX:
        raise ValueError("outside the int8 range")
    return v


def _require_real(v: float) -> float:
    if not _real_ok(v):
        raise ValueError("not a finite float4")
    return v


# Named after the column type each field is stored in.
Int4OrNone = Annotated[int | None, AfterValidator(_int4_or_none)]
RealOrNone = Annotated[float | None, AfterValidator(_real_or_none)]
Float8OrNone = Annotated[float | None, AfterValidator(_finite_or_none)]
TextOrNone = Annotated[str | None, AfterValidator(_strip_nul)]
Int4 = Annotated[int, AfterValidator(_require_int4)]
Int8 = Annotated[int, AfterValidator(_require_int8)]
Real = Annotated[float, AfterValidator(_require_real)]
Text = Annotated[str, AfterValidator(_strip_nul)]


def _reject_nul(v: str) -> str:
    # Postgres text cannot hold U+0000 (asyncpg refuses to encode it: a 500). A NUL in a
    # browser- or enroll-supplied string is never legitimate, so these inputs reject it with
    # a 422. Device telemetry strips it instead (TextOrNone): the phone deletes a 4xx'd batch.
    if "\x00" in v:
        raise ValueError("must not contain NUL characters")
    return v


NulFreeStr = Annotated[str, AfterValidator(_reject_nul)]


class EnrollBody(BaseModel):
    code: NulFreeStr
    install_uuid: NulFreeStr
    public_key_spki_b64: NulFreeStr
    device_label: NulFreeStr | None = None


class EnrollResponse(BaseModel):
    device_id: str


class SampleIn(BaseModel):
    # ts_ms is bounded by the router's window filter and address by _address_ok, both
    # before any row is built; every other field is clamped to its column (see above).
    ts_ms: int
    address: str
    advertised_name: TextOrNone = None
    alias: TextOrNone = None
    group_id: TextOrNone = None
    state: TextOrNone = None
    soc: RealOrNone = None
    current_a: RealOrNone = None
    power_w: RealOrNone = None
    voltage_v: RealOrNone = None
    temp_c: RealOrNone = None
    mosfet_temp_c: Int4OrNone = None
    soh: Int4OrNone = None
    full_charge_ah: RealOrNone = None
    remaining_ah: RealOrNone = None
    cycles: Int4OrNone = None
    cell_min_v: RealOrNone = None
    cell_max_v: RealOrNone = None
    cells: list[float] | None = None
    regen: bool = False
    link_event: TextOrNone = None
    lat: Float8OrNone = None
    lon: Float8OrNone = None
    gps_accuracy_m: RealOrNone = None
    eta_full_min: RealOrNone = None
    motion_activity: TextOrNone = None
    motion_confidence: int | None = None
    motion_still: bool | None = None
    motion_at_ms: int | None = None

    @field_validator("cells")
    @classmethod
    def _clip_cells(cls, v: list[float] | None) -> list[float | None] | None:
        # The stored/REST representation is always exactly 4 cells (cell1_v..cell4_v),
        # so truncate here to keep the WS broadcast (raw model_dump()) in agreement
        # with fleet_snapshot instead of diverging on non-4-element uploads. Cells are
        # positional, so an unstorable item becomes None IN PLACE rather than shifting
        # the rest (the web drops a cells array holding a null and falls back to
        # cell_min_v/cell_max_v).
        return [_real_or_none(x) for x in v[:4]] if v else v

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
    # Every column is NOT NULL, so an unstorable value invalidates (drops) the row.
    address: Text
    wh_per_day_lo: Real
    wh_per_day_hi: Real
    active_w_lo: Real
    active_w_hi: Real
    wh_per_mile_lo: Real
    wh_per_mile_hi: Real
    learned_days: Int4 = 0
    updated_at_ms: Int8


class TempConfigBody(BaseModel):
    # The required fields are NOT NULL columns: an unstorable value is a malformed
    # envelope (422). The optional ones degrade to None.
    profile_id: Text
    cold_caution_c: Int4
    hot_caution_c: Int4
    cold_crit_c: Int4
    hot_crit_c: Int4
    unit: Text
    updated_at_ms: Int8
    # WEB-6c: optional profile envelope (BMS cutoffs + charge lock/resume points) so the
    # web mirror can render the exact envelope the phone alerts on instead of hardcoding
    # it. Optional (None when an older app pushes without them) — which also retro-fixes
    # the WEB-6b hazard for these fields: an old-shape body must keep validating, never
    # turn into a 422 the phone would re-POST forever.
    cutoff_cold_c: RealOrNone = None
    cutoff_hot_c: RealOrNone = None
    charge_lock_cold_c: RealOrNone = None
    charge_lock_hot_c: RealOrNone = None
    charge_resume_cold_c: RealOrNone = None
    # Device-level capacity alert sync (parallel to temp config): the SOC threshold at
    # which a low pack should seize the WebUI main stage, and whether capacity alerts are
    # on. Optional — an older app pushing a temp-only body must keep validating (never a
    # 422 the phone re-POSTs forever), so both stay None-defaulted and backward compatible.
    seize_soc: Int4OrNone = None
    alerts_on: bool | None = None
    # Learned discharge-range bands, one row per pack (2026-07-11 design). Optional — an
    # older app pushing a temp-only body must keep validating (never a re-POSTed 422).
    # Raw here: each row is validated on its own (RangeConfigRow via validate_each in the
    # router), so one bad row is dropped instead of 422ing the whole push (C3).
    ranges: list[Any] | None = None


class NoteBody(BaseModel):
    base_id: NulFreeStr
    body: NulFreeStr

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
    name: NulFreeStr

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
    name: NulFreeStr
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
