import { describe, expect, it } from "vitest";
import { PHONE_ALERT_OPTIONS, phoneAlertFromValue, phoneAlertStatus, phoneAlertValue } from "./phoneAlert";

const NOW = 10_000_000_000;

describe("PHONE_ALERT_OPTIONS", () => {
  it("is Off then 10..95 in steps of 5", () => {
    expect(PHONE_ALERT_OPTIONS[0]).toEqual({ value: "off", label: "Off" });
    const nums = PHONE_ALERT_OPTIONS.slice(1).map((o) => Number(o.value));
    expect(nums).toEqual([10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 95]);
    expect(PHONE_ALERT_OPTIONS[1].label).toBe("10%");
  });
});

describe("select value mapping", () => {
  it("maps null to off and back", () => {
    expect(phoneAlertValue(null)).toBe("off");
    expect(phoneAlertFromValue("off")).toBeNull();
  });
  it("maps numbers both ways", () => {
    expect(phoneAlertValue(75)).toBe("75");
    expect(phoneAlertFromValue("75")).toBe(75);
  });
  it("shows an out-of-list value as its own entry rather than lying", () => {
    expect(phoneAlertValue(12)).toBe("12");
  });
});

describe("phoneAlertStatus", () => {
  it("phone", () => {
    expect(phoneAlertStatus({ low_pct: 50, updated_at_ms: NOW - 3 * 3_600_000, updated_by: "phone" }, NOW))
      .toBe("Set on the phone 3h ago");
  });
  it("web", () => {
    expect(phoneAlertStatus({ low_pct: null, updated_at_ms: NOW - 10_000, updated_by: "web" }, NOW))
      .toBe("Set in the WebUI just now");
  });
  it("default", () => {
    expect(phoneAlertStatus({ low_pct: 75, updated_at_ms: 0, updated_by: "default" }, NOW)).toBe("Default");
  });
});
