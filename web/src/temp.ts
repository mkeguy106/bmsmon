// Read-only mirror of the Android temperature zone logic (model/TempAlerts.kt) + the WebUI mock's
// zone copy. The phone is the source of truth for thresholds; the webui only evaluates + displays.

export type TempUnit = "C" | "F";

export interface TempThresholds {
  coldCautionC: number;
  hotCautionC: number;
  coldCritC: number;
  hotCritC: number;
}

export const REDODO_DEFAULTS: TempThresholds = {
  coldCautionC: 5, hotCautionC: 45, coldCritC: -12, hotCritC: 53,
};

// Fixed Redodo envelope (BMS cutoffs + charge lock/resume points).
export const ENV = { coldCutoffC: -20, hotCutoffC: 60, lockColdC: 0, lockHotC: 50 };

export type TempRank = 0 | 1 | 2 | 3 | 4; // SAFE, CAUTION, WARNING, CRITICAL, CUTOFF
export type TempSide = "cold" | "hot" | "safe";
export type ZoneKey =
  | "safe"
  | "cautionCold" | "warnCold" | "critCold" | "cutoffCold"
  | "cautionHot" | "warnHot" | "critHot" | "cutoffHot";

export interface Zone { key: ZoneKey; rank: TempRank; side: TempSide }

const z = (key: ZoneKey, rank: TempRank, side: TempSide): Zone => ({ key, rank, side });

/** Classify a cell temperature severity-first (cold→hot). Mirrors the phone. */
export function tempZone(tempC: number, t: TempThresholds): Zone {
  const { coldCutoffC, hotCutoffC, lockColdC, lockHotC } = ENV;
  if (tempC <= coldCutoffC) return z("cutoffCold", 4, "cold");
  if (tempC <= t.coldCritC) return z("critCold", 3, "cold");
  if (tempC <= lockColdC) return z("warnCold", 2, "cold");
  if (tempC <= t.coldCautionC) return z("cautionCold", 1, "cold");
  if (tempC >= hotCutoffC) return z("cutoffHot", 4, "hot");
  if (tempC >= t.hotCritC) return z("critHot", 3, "hot");
  if (tempC >= lockHotC) return z("warnHot", 2, "hot");
  if (tempC >= t.hotCautionC) return z("cautionHot", 1, "hot");
  return z("safe", 0, "safe");
}

/** Mercury/marker height: the −30…+70°C span maps to 0…100%. */
export const tempFillPct = (tempC: number): number =>
  Math.max(0, Math.min(100, Math.round((tempC + 30) * 100) / 100));

export const cToF = (c: number): number => Math.round(c * 9 / 5 + 32);

export const formatTemp = (c: number, unit: TempUnit): string =>
  unit === "F" ? `${cToF(c)}°F` : `${Math.round(c)}°C`;

/** A temperature *difference* (no +32 offset). */
export const formatDelta = (dC: number, unit: TempUnit): string =>
  unit === "F" ? `${Math.round(dC * 9 / 5)}°F` : `${dC}°C`;

/** "c°C / f°F" for the profile panel / zone copy. */
export const dualStr = (c: number): string => `${c}°C / ${cToF(c)}°F`;

export const marginToCutoffC = (tempC: number, side: TempSide): number =>
  side === "cold" ? tempC - ENV.coldCutoffC : side === "hot" ? ENV.hotCutoffC - tempC : 0;

export const worstOf = <T extends { rank: TempRank }>(items: T[]): T | undefined =>
  items.reduce<T | undefined>((a, b) => (a === undefined || b.rank > a.rank ? b : a), undefined);

/** Zone rank at or above which the full-screen overlay flashes. */
export const OVERLAY_RANK: TempRank = 3;

/**
 * Overlay acknowledgement with recovery re-arm (mirrors the Android fix): an
 * ack survives only while the worst condition is still at overlay severity.
 * Once it recovers (no worst, or rank below OVERLAY_RANK) the ack clears, so
 * re-entering the SAME zone later flashes the overlay again.
 */
export const nextAckedKey = (
  acked: string | null,
  worst: { key: string; rank: TempRank } | null,
): string | null => (worst != null && worst.rank >= OVERLAY_RANK ? acked : null);

/** CSS custom property for a zone's marker/label color. */
export function zoneColorVar(key: ZoneKey): string {
  switch (key) {
    case "safe": return "var(--safe)";
    case "cautionCold": return "var(--cool)";
    case "warnCold": return "var(--cold)";
    case "cautionHot": return "var(--warm)";
    case "warnHot": return "var(--hot)";
    default: return "var(--critical)"; // crit + cutoff, both sides
  }
}

export const zoneLabel = (key: ZoneKey): string => ({
  safe: "SAFE",
  cautionCold: "CAUTION · COLD", warnCold: "WARNING · COLD",
  critCold: "CRITICAL · COLD", cutoffCold: "CUTOFF · COLD",
  cautionHot: "CAUTION · HOT", warnHot: "WARNING · HOT",
  critHot: "CRITICAL · HOT", cutoffHot: "CUTOFF · HOT",
}[key]);

/** Human title + message for the alert banner (from the design's Z table). */
export function zoneCopy(key: ZoneKey, t: TempThresholds): { title: string; msg: string } {
  const { coldCutoffC, hotCutoffC, lockColdC, lockHotC } = ENV;
  switch (key) {
    case "safe":
      return { title: "All packs within the safe window", msg: "No temperature action required." };
    case "cautionCold":
      return { title: "Getting cold",
        msg: `Charging locks out below ${dualStr(lockColdC)}. Warm the pack before charging.` };
    case "warnCold":
      return { title: "Charge locked (low temp)",
        msg: `BMS has paused charging. It resumes once cells warm above ${dualStr(t.coldCautionC)}.` };
    case "critCold":
      return { title: "Approaching cold cutoff",
        msg: `Heading for the ${dualStr(coldCutoffC)} discharge cutoff. Get to warmth or shelter NOW — you still have power.` };
    case "cutoffCold":
      return { title: "LOAD DISCONNECTED (cold)",
        msg: `BMS has cut the load below ${dualStr(coldCutoffC)} to protect the cells. The pack is offline.` };
    case "cautionHot":
      return { title: "Running warm",
        msg: `Approaching the ${dualStr(lockHotC)} charge limit. Improve airflow.` };
    case "warnHot":
      return { title: "Charge locked (high temp)",
        msg: `Above the ${dualStr(lockHotC)} charge limit. Stop charging and cool the pack.` };
    case "critHot":
      return { title: "Approaching hot cutoff",
        msg: `Heading for the ${dualStr(hotCutoffC)} discharge cutoff. Reduce load and cool the pack NOW — you still have power.` };
    case "cutoffHot":
      return { title: "LOAD DISCONNECTED (hot)",
        msg: `BMS has cut the load at ${dualStr(hotCutoffC)} over-temp. The pack is offline.` };
  }
}

/** Config as returned by GET /web/temp-config. */
export interface TempConfig {
  device_id: string;
  profile_id: string;
  cold_caution_c: number;
  hot_caution_c: number;
  cold_crit_c: number;
  hot_crit_c: number;
  unit: string;
  updated_at_ms: number;
  received_at: string;
}

export const thresholdsFromConfig = (c: TempConfig | null): TempThresholds =>
  c == null ? REDODO_DEFAULTS : {
    coldCautionC: c.cold_caution_c, hotCautionC: c.hot_caution_c,
    coldCritC: c.cold_crit_c, hotCritC: c.hot_crit_c,
  };
