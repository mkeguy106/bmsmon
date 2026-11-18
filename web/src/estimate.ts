// Our estimated state of charge (see the estimated-SOC design spec). The server passes the
// BMS's raw soc/remaining_ah through untouched and adds est_* alongside. The swap happens
// ONCE, at the decode boundary (applyEstimate, called by decode.ts), so every consumer —
// stage seize, alerts, health tiles, recharge plan, dock, efficiency, v1 — reads `soc` /
// `remaining_ah` and gets our estimate. The BMS's own reading survives as bms_soc /
// bms_remaining_ah (decode-internal, never on the wire) for the muted always-present display.
import type { Sample } from "./types";

const finite = (v: unknown): v is number => typeof v === "number" && Number.isFinite(v);

/**
 * Swap in the estimate. A telemetry sample (finite soc) always gets bms_soc /
 * bms_remaining_ah, and est_* are normalised to null when absent so a merge in the store can
 * never inherit a stale estimate or source from an earlier sample. The estimate only ever
 * lowers: `soc`/`remaining_ah` are capped at the BMS's own values, whatever the wire says.
 * A telemetry sample with no soc gets bms_* and est_* as explicit nulls, so the store cannot
 * keep a stale BMS line or estimate beside the "—". Link events (no telemetry) are returned
 * unchanged.
 */
export function applyEstimate<T extends Sample>(s: T): T {
  if (!finite(s.soc)) {
    if (s.link_event != null) return s;
    return {
      ...s,
      bms_soc: null, bms_remaining_ah: null,
      est_soc: null, est_remaining_ah: null, est_source: null,
    };
  }
  const bmsSoc = s.soc;
  const bmsAh = s.remaining_ah ?? null;
  const estSoc = finite(s.est_soc) ? s.est_soc : null;
  const estAh = finite(s.est_remaining_ah) ? s.est_remaining_ah : null;
  const src = estSoc != null && (s.est_source === "counter" || s.est_source === "voltage")
    ? s.est_source : null;
  return {
    ...s,
    bms_soc: bmsSoc,
    bms_remaining_ah: bmsAh,
    soc: estSoc != null ? Math.min(estSoc, bmsSoc) : bmsSoc,
    ...(estAh != null ? { remaining_ah: finite(bmsAh) ? Math.min(estAh, bmsAh) : estAh } : null),
    est_soc: estSoc,
    est_remaining_ah: estAh,
    est_source: src,
  };
}

/** The headline number: "~58%" for a voltage-sourced estimate, "92%" otherwise, "—" without soc. */
export function socLabel(i: Sample): string {
  if (!finite(i.soc)) return "—";
  return `${i.est_source === "voltage" ? "~" : ""}${Math.round(i.soc)}%`;
}

/** The muted BMS reading, always rendered: "BMS 99%", "BMS —" without a soc. */
export function bmsLabel(i: Sample): string {
  const v = i.bms_soc ?? i.soc;
  return finite(v) ? `BMS ${Math.round(v)}%` : "BMS —";
}

/** The rail/board form: "bms 99" ("bms —" without a soc). */
export function bmsShort(i: Sample): string {
  const v = i.bms_soc ?? i.soc;
  return finite(v) ? `bms ${Math.round(v)}` : "bms —";
}
