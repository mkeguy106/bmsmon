import { describe, expect, it } from "vitest";
import { applyEstimate, bmsLabel, bmsShort, socLabel } from "./estimate";
import { decodeFleetItem, decodeSample } from "./decode";
import { createStore } from "./store";
import type { Sample } from "./types";

describe("applyEstimate", () => {
  it("applyEstimate_with_est_fields", () => {
    const s = applyEstimate<Sample>({
      address: "A", ts_ms: 1, soc: 99, remaining_ah: 111.84,
      est_soc: 92, est_remaining_ah: 96.84, est_source: "counter",
    });
    expect(s.soc).toBe(92);
    expect(s.remaining_ah).toBe(96.84);
    expect(s.bms_soc).toBe(99);
    expect(s.bms_remaining_ah).toBe(111.84);
    expect(s.est_source).toBe("counter");
  });

  it("applyEstimate_without_est_fields", () => {
    const s = applyEstimate<Sample>({ address: "A", ts_ms: 1, soc: 93, remaining_ah: 100 });
    expect(s.soc).toBe(93);
    expect(s.remaining_ah).toBe(100);
    expect(s.bms_soc).toBe(93);
    expect(s.bms_remaining_ah).toBe(100);
    expect(s.est_soc).toBeNull();
    expect(s.est_source).toBeNull();
  });

  it("applyEstimate_link_event_untouched", () => {
    const ev: Sample = { address: "A", ts_ms: 1, link_event: "Disconnected", soc: null, est_soc: null };
    expect(applyEstimate(ev)).toBe(ev);
    const bare: Sample = { address: "A", ts_ms: 1, link_event: "Connected" };
    expect(applyEstimate(bare)).toBe(bare);
  });

  it("an est_source without an est_soc is dropped, an unknown source too", () => {
    expect(applyEstimate<Sample>({ address: "A", ts_ms: 1, soc: 50, est_source: "voltage" }).est_source).toBeNull();
    const odd = { address: "A", ts_ms: 1, soc: 50, est_soc: 40, est_source: "bogus" } as unknown as Sample;
    expect(applyEstimate(odd).est_source).toBeNull();
  });
});

describe("labels", () => {
  it("socLabel: ~ for a voltage estimate only", () => {
    expect(socLabel({ address: "A", ts_ms: 1, soc: 58.4, est_source: "voltage" })).toBe("~58%");
    expect(socLabel({ address: "A", ts_ms: 1, soc: 92, est_source: "counter" })).toBe("92%");
    expect(socLabel({ address: "A", ts_ms: 1, soc: 92 })).toBe("92%");
    expect(socLabel({ address: "A", ts_ms: 1, soc: null })).toBe("—");
  });
  it("bmsLabel / bmsShort: always a string, falling back to soc", () => {
    expect(bmsLabel({ address: "A", ts_ms: 1, soc: 92, bms_soc: 99 })).toBe("BMS 99%");
    expect(bmsLabel({ address: "A", ts_ms: 1, soc: 92 })).toBe("BMS 92%");
    expect(bmsLabel({ address: "A", ts_ms: 1 })).toBe("BMS —");
    expect(bmsShort({ address: "A", ts_ms: 1, soc: 92, bms_soc: 99 })).toBe("bms 99");
    expect(bmsShort({ address: "A", ts_ms: 1 })).toBe("bms —");
  });
});

describe("decode swaps once at the boundary", () => {
  it("decode keeps est_* (whitelist) and swaps soc for sample and fleet item", () => {
    const wire = { address: "A", ts_ms: 5, soc: 99, remaining_ah: 111.84,
      est_soc: 60, est_remaining_ah: 64.1, est_source: "voltage" };
    for (const d of [decodeSample(wire), decodeFleetItem(wire)]) {
      expect(d).toMatchObject({ soc: 60, remaining_ah: 64.1, bms_soc: 99, bms_remaining_ah: 111.84,
        est_soc: 60, est_remaining_ah: 64.1, est_source: "voltage" });
    }
  });
  it("bms_* on the wire is ignored (decode-internal)", () => {
    const d = decodeSample({ address: "A", ts_ms: 5, soc: 70, bms_soc: 1, bms_remaining_ah: 2 });
    expect(d?.bms_soc).toBe(70);
    expect(d?.bms_remaining_ah).toBeNull();
  });
});

describe("store: a newer sample without est_* never inherits a stale estimate", () => {
  it("clears est_* and refreshes bms_*", () => {
    const s = createStore();
    s.applySample(decodeSample({ address: "A", ts_ms: 100, soc: 99, remaining_ah: 111,
      est_soc: 60, est_remaining_ah: 64, est_source: "voltage" })!);
    expect(s.getFleet()["A"].soc).toBe(60);
    s.applySample(decodeSample({ address: "A", ts_ms: 200, soc: 98, remaining_ah: 110 })!);
    const a = s.getFleet()["A"];
    expect(a.soc).toBe(98);
    expect(a.bms_soc).toBe(98);
    expect(a.bms_remaining_ah).toBe(110);
    expect(a.est_soc).toBeNull();
    expect(a.est_source).toBeNull();
  });
});
