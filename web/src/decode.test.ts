import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from "vitest";
import { decodeSample, decodeSnapshot, decodeTempConfigs } from "./decode";

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
