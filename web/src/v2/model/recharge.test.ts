import { describe, expect, it } from "vitest";
import type { FleetItem } from "../../types";
import { groupBases } from "../fleet";
import { minutesLeft, rechargePhase, rechargePlan, type RechargeRow } from "./recharge";

const NOW = 50_000_000;
const MIN = 60_000;
const pack = (address: string, group: string, o: Partial<FleetItem> = {}): FleetItem => ({
  address, group_id: group, alias: `${group} · ${address.slice(-1)}`, ts_ms: NOW - 10_000,
  soc: 70, current_a: 8, eta_full_min: 120, ...o,
});
const plan = (items: FleetItem[], stale: string[] = []) => rechargePlan(groupBases(items, new Set(stale)));
const row = (o: Partial<RechargeRow>): RechargeRow => ({
  address: "x", label: "Base 2016 · A", soc: 70, live: false, tsMs: NOW, etaMin: 60,
  readyAtMs: NOW + 60 * MIN, ...o,
});

describe("rechargePlan", () => {
  it("anchors the ready time to the sample, not the clock (WEB-18)", () => {
    const [r] = plan([pack("16A", "2016", { ts_ms: NOW - 80_000, eta_full_min: 120 })]);
    expect(r).toEqual({
      address: "16A", label: "Base 2016 · A", soc: 70, live: true,
      tsMs: NOW - 80_000, etaMin: 120, readyAtMs: NOW - 80_000 + 120 * MIN,
    });
  });

  it("keeps a charging pack that went out of range, marked not live", () => {
    const rows = plan([pack("16B", "2016", { ts_ms: NOW - 3 * 3_600_000, eta_full_min: 60 })], ["16B"]);
    expect(rows.map((r) => [r.address, r.live])).toEqual([["16B", false]]);
  });

  it("skips full packs and missing or garbage ETAs", () => {
    expect(plan([
      pack("1A", "1", { soc: 99 }),
      pack("2A", "2", { soc: null }),
      pack("3A", "3", { eta_full_min: null }),
      pack("4A", "4", { eta_full_min: 0 }),
      pack("5A", "5", { eta_full_min: Number.NaN }),
    ])).toEqual([]);
  });

  it("lists live rows first, then by ready time", () => {
    const rows = plan([
      pack("12A", "2012", { eta_full_min: 200 }),
      pack("16A", "2016", { eta_full_min: 10 }),
      pack("23A", "2023", { eta_full_min: 50 }),
    ], ["16A"]);
    expect(rows.map((r) => r.address)).toEqual(["23A", "12A", "16A"]);
  });
});

describe("rechargePhase", () => {
  // Review Focus: a spare last seen hours ago must read "was due" at its anchored time.
  it("is charging when live; due, then past-due, when last known", () => {
    expect(rechargePhase(row({ live: true, readyAtMs: NOW - MIN }), NOW)).toBe("charging");
    expect(rechargePhase(row({ readyAtMs: NOW + 1 }), NOW)).toBe("due");
    expect(rechargePhase(row({ readyAtMs: NOW }), NOW)).toBe("past-due");
    expect(rechargePhase(row({ readyAtMs: NOW - 5 * 3_600_000 }), NOW)).toBe("past-due");
  });
});

describe("minutesLeft", () => {
  it("counts down from the anchored ready time and never goes negative", () => {
    expect(minutesLeft(row({ readyAtMs: NOW + 90 * MIN }), NOW)).toBe(90);
    expect(minutesLeft(row({ readyAtMs: NOW + 90 * MIN }), NOW + 30 * MIN)).toBe(60);
    expect(minutesLeft(row({ readyAtMs: NOW - MIN }), NOW)).toBe(0);
  });
});
