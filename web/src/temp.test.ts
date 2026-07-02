import { describe, it, expect } from "vitest";
import {
  DEFAULT_ENV, REDODO_DEFAULTS, tempZone, tempFillPct, cToF, formatTemp, formatDelta,
  marginToCutoffC, nextAckedKey, worstOf, zoneCopy,
  envelopeFromConfig, selectActiveConfig, thresholdsFromConfig, type TempConfig,
} from "./temp";

const T = REDODO_DEFAULTS;

const CFG: TempConfig = {
  device_id: "d1", profile_id: "redodo-beken-bk-ble-1.0",
  cold_caution_c: 5, hot_caution_c: 45, cold_crit_c: -12, hot_crit_c: 53,
  unit: "F", updated_at_ms: 1000, received_at: "2026-07-01T00:00:00Z",
};

describe("tempZone ladder", () => {
  it("cold side", () => {
    expect(tempZone(-25, T)).toMatchObject({ rank: 4, side: "cold", key: "cutoffCold" });
    expect(tempZone(-20, T)).toMatchObject({ rank: 4, side: "cold" });
    expect(tempZone(-12, T)).toMatchObject({ rank: 3, side: "cold", key: "critCold" }); // at threshold
    expect(tempZone(-5, T)).toMatchObject({ rank: 2, side: "cold", key: "warnCold" });  // charge lock
    expect(tempZone(0, T)).toMatchObject({ rank: 2, side: "cold" });                    // 0C lock edge
    expect(tempZone(5, T)).toMatchObject({ rank: 1, side: "cold", key: "cautionCold" });
  });
  it("safe", () => {
    expect(tempZone(24, T)).toMatchObject({ rank: 0, side: "safe", key: "safe" });
  });
  it("hot side", () => {
    expect(tempZone(45, T)).toMatchObject({ rank: 1, side: "hot", key: "cautionHot" });
    expect(tempZone(50, T)).toMatchObject({ rank: 2, side: "hot", key: "warnHot" });
    expect(tempZone(53, T)).toMatchObject({ rank: 3, side: "hot", key: "critHot" });
    expect(tempZone(60, T)).toMatchObject({ rank: 4, side: "hot", key: "cutoffHot" });
  });
});

describe("gauge + conversions", () => {
  it("fill maps -30..70 to 0..100 clamped", () => {
    expect(tempFillPct(-30)).toBe(0);
    expect(tempFillPct(0)).toBe(30);
    expect(tempFillPct(70)).toBe(100);
    expect(tempFillPct(-99)).toBe(0);
    expect(tempFillPct(99)).toBe(100);
  });
  it("cToF", () => {
    expect(cToF(0)).toBe(32);
    expect(cToF(-20)).toBe(-4);
    expect(cToF(-12)).toBe(10);
    expect(cToF(60)).toBe(140);
  });
  it("formatTemp", () => {
    expect(formatTemp(-12, "F")).toBe("10°F");
    expect(formatTemp(-12, "C")).toBe("-12°C");
  });
  it("formatDelta (difference, no offset)", () => {
    expect(formatDelta(8, "F")).toBe("14°F");
    expect(formatDelta(8, "C")).toBe("8°C");
  });
});

describe("margin + worst", () => {
  it("margin to cutoff", () => {
    expect(marginToCutoffC(-12, "cold")).toBe(8);   // -12 - (-20)
    expect(marginToCutoffC(55, "hot")).toBe(5);     // 60 - 55
  });
  it("worst pack wins by rank", () => {
    const a = { rank: 1 as const }, b = { rank: 3 as const }, c = { rank: 0 as const };
    expect(worstOf([a, b, c])).toBe(b);
    expect(worstOf([])).toBeUndefined();
  });
});

// WEB-7: overlay ack with recovery re-arm (pure decision used by MainStage —
// there is no component test harness, so the extracted function is tested).
describe("nextAckedKey (overlay ack re-arm)", () => {
  it("holds the ack while the condition persists at overlay severity", () => {
    expect(nextAckedKey("critHot", { key: "critHot", rank: 3 })).toBe("critHot");
    // Escalation keeps the old ack; the overlay flashes anyway because the key changed.
    expect(nextAckedKey("critHot", { key: "cutoffHot", rank: 4 })).toBe("critHot");
  });
  it("recovery re-arms: clears when worst is null or below overlay rank", () => {
    expect(nextAckedKey("critHot", null)).toBeNull();
    expect(nextAckedKey("critHot", { key: "warnHot", rank: 2 })).toBeNull();
    expect(nextAckedKey("critHot", { key: "safe", rank: 0 })).toBeNull();
  });
  it("re-entering the SAME zone after recovery is no longer suppressed", () => {
    const afterRecovery = nextAckedKey("critHot", { key: "safe", rank: 0 });
    expect(afterRecovery).toBeNull();
    // Same zone returns → ack is null ≠ "critHot" → overlay flashes again.
    expect(nextAckedKey(afterRecovery, { key: "critHot", rank: 3 })).toBeNull();
  });
  it("no-op when nothing is acked", () => {
    expect(nextAckedKey(null, { key: "critHot", rank: 3 })).toBeNull();
    expect(nextAckedKey(null, null)).toBeNull();
  });
});

// WEB-6: envelope from the synced config, per-field fallback + threaded consumers.
describe("envelopeFromConfig", () => {
  it("null config → all defaults", () => {
    expect(envelopeFromConfig(null)).toEqual(DEFAULT_ENV);
  });
  it("older config rows (fields absent/null) fall back per field", () => {
    expect(envelopeFromConfig(CFG)).toEqual(DEFAULT_ENV); // all absent
    expect(envelopeFromConfig({ ...CFG, cutoff_hot_c: null, charge_lock_cold_c: -2 }))
      .toEqual({ ...DEFAULT_ENV, lockColdC: -2 }); // null falls back, value applies
  });
  it("fully-populated config overrides every field", () => {
    expect(envelopeFromConfig({
      ...CFG, cutoff_cold_c: -25, cutoff_hot_c: 65,
      charge_lock_cold_c: -2, charge_lock_hot_c: 55, charge_resume_cold_c: 8,
    })).toEqual({ coldCutoffC: -25, hotCutoffC: 65, lockColdC: -2, lockHotC: 55, chargeResumeColdC: 8 });
  });
  it("tempZone and marginToCutoffC honor a synced envelope", () => {
    const env = envelopeFromConfig({ ...CFG, cutoff_cold_c: -25, charge_lock_cold_c: -2 });
    expect(tempZone(-22, T, env).key).toBe("critCold"); // default env would say cutoffCold
    expect(tempZone(-1, T, env).key).toBe("cautionCold"); // lock moved below −1
    expect(marginToCutoffC(-12, "cold", env)).toBe(13); // −12 − (−25)
  });
});

describe("zoneCopy cold-warning resume point (WEB-6 copy fix)", () => {
  it("uses charge_resume_cold_c, not the cold-caution threshold", () => {
    // Tuned caution (10 °C) must NOT leak into the resume copy; resume default is 5 °C.
    const tuned = { ...T, coldCautionC: 10 };
    expect(zoneCopy("warnCold", tuned, DEFAULT_ENV).msg).toContain("5°C / 41°F");
    // A synced resume point flows through.
    const env = envelopeFromConfig({ ...CFG, charge_resume_cold_c: 8 });
    expect(zoneCopy("warnCold", tuned, env).msg).toContain("8°C / 46°F");
  });
});

describe("thresholdsFromConfig", () => {
  it("null → Redodo defaults", () => {
    expect(thresholdsFromConfig(null)).toEqual(REDODO_DEFAULTS);
  });
  it("maps the synced fields", () => {
    expect(thresholdsFromConfig({ ...CFG, cold_caution_c: 7, hot_crit_c: 50 }))
      .toEqual({ coldCautionC: 7, hotCautionC: 45, coldCritC: -12, hotCritC: 50 });
  });
});

describe("selectActiveConfig (per-profile, newest wins)", () => {
  it("empty → null", () => {
    expect(selectActiveConfig([])).toBeNull();
  });
  it("newest per profile, then newest overall as the active one", () => {
    const a1 = { ...CFG, profile_id: "p-a", updated_at_ms: 100 };
    const a2 = { ...CFG, profile_id: "p-a", updated_at_ms: 300 };
    const b1 = { ...CFG, profile_id: "p-b", updated_at_ms: 200 };
    expect(selectActiveConfig([a1, b1, a2])).toBe(a2); // a2 beats a1 within p-a, beats b1 overall
    expect(selectActiveConfig([a1, b1])).toBe(b1);     // b1 newest overall
    expect(selectActiveConfig([b1])).toBe(b1);
  });
});
