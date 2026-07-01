import { describe, expect, it } from "vitest";
import { createStore } from "./store";

describe("store", () => {
  it("keeps the newest sample per address", () => {
    const s = createStore();
    s.applySnapshot([{ address: "A", ts_ms: 100, soc: 50, alias: "2012 · A" }]);
    s.applySample({ address: "A", ts_ms: 200, soc: 60 });
    expect(s.getFleet()["A"].soc).toBe(60);
    s.applySample({ address: "A", ts_ms: 150, soc: 99 }); // older -> ignored
    expect(s.getFleet()["A"].soc).toBe(60);
    expect(s.getFleet()["A"].alias).toBe("2012 · A"); // meta preserved
  });

  it("preserves last-known telemetry on link_event samples", () => {
    const s = createStore();
    s.applySample({
      address: "A", ts_ms: 100, soc: 72, voltage_v: 13.28, temp_c: 21,
      current_a: -4.1, power_w: 54, eta_full_min: null,
    });
    // Link transition: server rematerializes all telemetry as explicit nulls.
    s.applySample({
      address: "A", ts_ms: 200, link_event: "Disconnected",
      state: null, soc: null, current_a: null, power_w: null, voltage_v: null,
      temp_c: null, soh: null, cycles: null, eta_full_min: null,
    });
    const a = s.getFleet()["A"];
    expect(a.ts_ms).toBe(200); // staleness tracking still advances
    expect(a.link_event).toBe("Disconnected");
    expect(a.soc).toBe(72); // last-known telemetry preserved
    expect(a.voltage_v).toBe(13.28);
    expect(a.temp_c).toBe(21);
    expect(a.power_w).toBe(54);
  });

  it("clears eta_full_min when a normal sample carries an explicit null", () => {
    const s = createStore();
    s.applySample({ address: "A", ts_ms: 100, soc: 90, eta_full_min: 42 });
    s.applySample({ address: "A", ts_ms: 200, soc: 91, eta_full_min: null });
    expect(s.getFleet()["A"].eta_full_min).toBeNull(); // charging stopped -> ETA cleared
    expect(s.getFleet()["A"].soc).toBe(91);
  });

  it("still ignores out-of-order samples, including link events", () => {
    const s = createStore();
    s.applySample({ address: "A", ts_ms: 200, soc: 60 });
    s.applySample({ address: "A", ts_ms: 150, soc: 99 }); // older normal -> ignored
    expect(s.getFleet()["A"].soc).toBe(60);
    s.applySample({ address: "A", ts_ms: 150, link_event: "Disconnected", soc: null }); // older link event -> ignored
    expect(s.getFleet()["A"].link_event).toBeUndefined();
    expect(s.getFleet()["A"].ts_ms).toBe(200);
  });

  it("notifies subscribers on change", () => {
    const s = createStore();
    let calls = 0;
    s.subscribe(() => { calls++; });
    s.applySample({ address: "B", ts_ms: 1, soc: 10 });
    expect(calls).toBe(1);
  });
});
