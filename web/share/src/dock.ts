// Pure model for the guest dock's two lines — mirrors web/src/v2/model/dock.ts
// semantics, but operates on the feed's pre-aggregated `status` object.
import type { GuestStatus } from "./feed";

/** Same full scale as the mobile Journey dock: 2 × the 300 W per-pack calibration. */
export const PAIR_FLOW_FULL_W = 600;
/** Amps — matches DISCHARGE_EPS in v2 model/journey.ts and the android app. */
export const DISCHARGE_EPS = 0.1;

export type FlowKind = "out" | "regen" | "chg" | "idle";
export interface GuestCap { pct: number | null; detail: string; band: "ok" | "warn" | "crit" }
export interface GuestFlow { kind: FlowKind; watts: number; frac: number }

export function guestCap(status: GuestStatus | null): GuestCap {
  if (!status) return { pct: null, detail: "", band: "ok" };
  const detail = status.packs.length > 1
    ? status.packs.map((p) => `${p.label}${p.soc}`).join("·")
    : "";
  // Band from the server's ROUNDED soc (v2's dock uses the raw min) — intentional:
  // the feed only carries integers, and a ±0.5% band shift is invisible to a guest.
  const band = status.soc > 30 ? "ok" : status.soc > 15 ? "warn" : "crit";
  return { pct: status.soc, detail, band };
}

export function guestFlow(status: GuestStatus | null): GuestFlow {
  if (!status) return { kind: "idle", watts: 0, frac: 0 };
  const watts = Math.round(Math.abs(status.power_w));
  const frac = Math.min(1, watts / PAIR_FLOW_FULL_W);
  if (status.current_a < -DISCHARGE_EPS) return { kind: "out", watts, frac };
  if (status.current_a > DISCHARGE_EPS) {
    return { kind: status.regen ? "regen" : "chg", watts, frac };
  }
  return { kind: "idle", watts: 0, frac: 0 };
}
