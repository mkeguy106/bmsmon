import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from "vitest";
import { decodeHistory, decodeRangeConfigs, decodeSample, decodeSnapshot, decodeTempConfigs, decodeTrends, decodeChargeSessions, decodeNotes, decodeTrack } from "./decode";

let warn: MockInstance;
beforeEach(() => { warn = vi.spyOn(console, "warn").mockImplementation(() => {}); });
afterEach(() => { warn.mockRestore(); });

describe("decodeSample", () => {
  it("passes a valid sample through with unknown keys stripped (incl. the WS type tag)", () => {
    const s = decodeSample({
      type: "sample", address: "A", ts_ms: 5, soc: 50, current_a: -1.2,
      bogus: 1, batch_seq: 7,
    });
    expect(s).toEqual({ address: "A", ts_ms: 5, soc: 50, current_a: -1.2 });
  });

  it("tolerates null optionals without coercing them", () => {
    expect(decodeSample({ address: "A", ts_ms: 5, soc: null, eta_full_min: null, link_event: null }))
      .toEqual({ address: "A", ts_ms: 5, soc: null, eta_full_min: null, link_event: null });
  });

  it("rejects missing or invalid identity fields (no coercion)", () => {
    expect(decodeSample(null)).toBeNull();
    expect(decodeSample("nope")).toBeNull();
    expect(decodeSample({ ts_ms: 5, soc: 50 })).toBeNull();          // missing address
    expect(decodeSample({ address: 7, ts_ms: 5 })).toBeNull();       // non-string address
    expect(decodeSample({ address: "A" })).toBeNull();               // missing ts_ms
    expect(decodeSample({ address: "A", ts_ms: "5" })).toBeNull();   // string ts_ms not coerced
    expect(decodeSample({ address: "A", ts_ms: NaN })).toBeNull();
    expect(warn).toHaveBeenCalled();
  });

  it("keeps a valid cells array", () => {
    const s = decodeSample({ address: "AA", ts_ms: 1, cells: [3.31, 3.32, 3.34, 3.33] });
    expect(s?.cells).toEqual([3.31, 3.32, 3.34, 3.33]);
  });

  it("drops a cells array containing non-finite / null entries", () => {
    const s = decodeSample({ address: "AA", ts_ms: 1, cells: [3.31, null, 3.34, 3.33] });
    expect(s?.cells).toBeUndefined();
  });

  it("passes cell_min_v / cell_max_v through", () => {
    const s = decodeSample({ address: "AA", ts_ms: 1, cell_min_v: 3.30, cell_max_v: 3.35 });
    expect(s?.cell_min_v).toBe(3.30);
    expect(s?.cell_max_v).toBe(3.35);
  });
});

describe("decodeSnapshot", () => {
  it("decodes an array, preserves alias/group_id meta, drops bad items", () => {
    const out = decodeSnapshot([
      { address: "A", ts_ms: 1, soc: 50, alias: "2012 · A", group_id: "2012", junk: true },
      { address: 9, ts_ms: 2 }, // bad item — dropped, not fatal
    ]);
    expect(out).toEqual([{ address: "A", ts_ms: 1, soc: 50, alias: "2012 · A", group_id: "2012" }]);
  });

  it("malformed snapshot (not an array) → null", () => {
    expect(decodeSnapshot(null)).toBeNull();
    expect(decodeSnapshot({ fleet: [] })).toBeNull();
    expect(decodeSnapshot("x")).toBeNull();
    expect(warn).toHaveBeenCalled();
  });

  it("empty array is a valid (empty) snapshot", () => {
    expect(decodeSnapshot([])).toEqual([]);
  });
});

describe("decodeTempConfigs", () => {
  const good = {
    device_id: "d1", profile_id: "redodo_12v_100ah",
    cold_caution_c: 5, hot_caution_c: 45, cold_crit_c: -12, hot_crit_c: 53,
    unit: "F", updated_at_ms: 1719800000000, received_at: "2026-07-01T00:00:00Z",
  };

  it("valid configs pass with unknown keys stripped", () => {
    expect(decodeTempConfigs([{ ...good, extra: 1 }])).toEqual([good]);
    expect(decodeTempConfigs([])).toEqual([]);
  });

  it("any malformed config invalidates the whole response", () => {
    expect(decodeTempConfigs([{ ...good, hot_crit_c: "53" }])).toBeNull(); // no coercion
    expect(decodeTempConfigs([good, {}])).toBeNull();
    expect(decodeTempConfigs("x")).toBeNull();
    expect(decodeTempConfigs(null)).toBeNull();
    expect(warn).toHaveBeenCalled();
  });

  // WEB-6: the five envelope fields are optional (older rows omit them).
  it("carries the optional envelope fields through, tolerating null/absent", () => {
    const env = {
      cutoff_cold_c: -25, cutoff_hot_c: 65, charge_lock_cold_c: -2,
      charge_lock_hot_c: 55, charge_resume_cold_c: 8,
    };
    expect(decodeTempConfigs([{ ...good, ...env }])).toEqual([{ ...good, ...env }]);
    expect(decodeTempConfigs([{ ...good, cutoff_cold_c: null }]))
      .toEqual([{ ...good, cutoff_cold_c: null }]); // null preserved, not coerced
    expect(decodeTempConfigs([good])).toEqual([good]); // absent stays absent
  });

  it("a non-numeric envelope field invalidates the response (no coercion)", () => {
    expect(decodeTempConfigs([{ ...good, cutoff_hot_c: "65" }])).toBeNull();
    expect(decodeTempConfigs([{ ...good, charge_resume_cold_c: NaN }])).toBeNull();
    expect(warn).toHaveBeenCalled();
  });
});

describe("decodeRangeConfigs", () => {
  const good = {
    device_id: "d1", address: "A", wh_per_day_lo: 100, wh_per_day_hi: 180,
    active_w_lo: 70, active_w_hi: 100, wh_per_mile_lo: 18, wh_per_mile_hi: 24,
    learned_days: 10, updated_at_ms: 1719800000000,
  };

  it("valid rows pass with all fields intact (unknown keys stripped)", () => {
    expect(decodeRangeConfigs([{ ...good, extra: 1 }])).toEqual([good]);
    expect(decodeRangeConfigs([])).toEqual([]);
  });

  it("missing learned_days defaults to 0 (server column default)", () => {
    const { learned_days: _drop, ...noLearned } = good;
    expect(decodeRangeConfigs([noLearned])).toEqual([{ ...good, learned_days: 0 }]);
    expect(decodeRangeConfigs([{ ...good, learned_days: null }]))
      .toEqual([{ ...good, learned_days: 0 }]);
  });

  it("a non-numeric learned_days invalidates the whole response (no coercion)", () => {
    expect(decodeRangeConfigs([{ ...good, learned_days: "garbage" }])).toBeNull();
    expect(decodeRangeConfigs([{ ...good, learned_days: {} }])).toBeNull();
    expect(decodeRangeConfigs([good, {}])).toBeNull(); // one bad row poisons all
    expect(warn).toHaveBeenCalled();
  });

  it("malformed response (not an array) → null", () => {
    expect(decodeRangeConfigs(null)).toBeNull();
    expect(decodeRangeConfigs({ configs: [] })).toBeNull();
    expect(decodeRangeConfigs("x")).toBeNull();
    expect(warn).toHaveBeenCalled();
  });
});

describe("decodeHistory", () => {
  it("decodes a valid history payload", () => {
    const s = decodeHistory([{ address: "AA", points: [{ t: 1000, soc: 85 }, { t: 2000, soc: 70 }] }]);
    expect(s).toEqual([{ address: "AA", points: [{ t: 1000, soc: 85 }, { t: 2000, soc: 70 }] }]);
  });

  it("drops malformed points but keeps the series", () => {
    const s = decodeHistory([{ address: "AA", points: [{ t: 1000, soc: 85 }, { t: null, soc: 70 }] }]);
    expect(s).toEqual([{ address: "AA", points: [{ t: 1000, soc: 85 }] }]);
  });

  it("returns null for a non-array", () => {
    expect(decodeHistory({ nope: true })).toBeNull();
  });
});

describe("decodeTrends", () => {
  it("decodes a trend series (nullable metrics)", () => {
    const s = decodeTrends({ address: "AA", bucket_ms: 1800000, first_ms: 100,
      points: [{ t: 100, soh: 99, cell_spread_mv: 30, temp_avg: 20, temp_min: 18, temp_max: 22 },
               { t: 200, soh: null, cell_spread_mv: null, temp_avg: null, temp_min: null, temp_max: null }] });
    expect(s?.address).toBe("AA");
    expect(s?.points.length).toBe(2);
    expect(s?.points[1].soh).toBeNull();
  });
  it("decodeTrends returns null for a malformed root", () => {
    expect(decodeTrends({ address: 5 })).toBeNull();
  });
});

describe("decodeChargeSessions", () => {
  it("decodes charge sessions, dropping malformed rows", () => {
    const s = decodeChargeSessions([{ start_ms: 1, end_ms: 2, from_soc: 60, duration_min: 3, cv_tail_min: 1, peak_temp_c: 30 },
                                    { start_ms: "x" }]);
    expect(s?.length).toBe(1);
  });
});

describe("decodeNotes", () => {
  it("decodes notes", () => {
    expect(decodeNotes([{ base_id: "2012", body: "hi", updated_at_ms: 5 }])?.[0].base_id).toBe("2012");
  });
});

describe("decodeTrack", () => {
  it("decodes a track, dropping points missing lat/lon", () => {
    const t = decodeTrack({ address: "AA", points: [
      { t: 1, lat: 43.0, lon: -87.9, power_w: -60, current_a: -4, soc: 88 },
      { t: 2, lat: null, lon: -87.9, power_w: -60, current_a: -4, soc: 88 } ] });
    expect(t?.points.length).toBe(1);
    expect(t?.points[0].lat).toBe(43.0);
  });
  it("decodeTrack null for malformed root", () => {
    expect(decodeTrack({ address: 5 })).toBeNull();
  });
});
