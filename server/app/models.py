from pydantic import BaseModel


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


class IngestBody(BaseModel):
    batch_seq: int
    samples: list[SampleIn] = []


class IngestResponse(BaseModel):
    accepted: int
    last_seq: int


class TempConfigBody(BaseModel):
    profile_id: str
    cold_caution_c: int
    hot_caution_c: int
    cold_crit_c: int
    hot_crit_c: int
    unit: str
    updated_at_ms: int


class OkResponse(BaseModel):
    ok: bool = True


class MintCodeResponse(BaseModel):
    code: str
    expires_at: str
