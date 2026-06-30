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

  it("notifies subscribers on change", () => {
    const s = createStore();
    let calls = 0;
    s.subscribe(() => { calls++; });
    s.applySample({ address: "B", ts_ms: 1, soc: 10 });
    expect(calls).toBe(1);
  });
});
