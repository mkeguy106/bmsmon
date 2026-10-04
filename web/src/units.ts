// Distance display units. Every model in the app computes in MILES (range bands, track
// miles, Wh per mile); only display converts, so the learned constants and the Android
// twins stay in one unit. Anything that is not "km" displays as miles.

export type DistUnit = "mi" | "km";

export const KM_PER_MI = 1.609344;

export const distLabel = (u: DistUnit): string => (u === "km" ? "km" : "mi");

/** Miles → the display unit. */
export const toDist = (mi: number, u: DistUnit): number => (u === "km" ? mi * KM_PER_MI : mi);

/** The display unit → miles (the trip editor stores miles). */
export const fromDist = (d: number, u: DistUnit): number => (u === "km" ? d / KM_PER_MI : d);

/** "3.2 mi" / "5.1 km". */
export const fmtDist = (mi: number, u: DistUnit, digits = 1): string =>
  `${toDist(mi, u).toFixed(digits)} ${distLabel(u)}`;

/** A cost per mile (Wh/mi) → per display unit (Wh/km): a per-distance rate divides. */
export const perDist = (perMi: number, u: DistUnit): number => (u === "km" ? perMi / KM_PER_MI : perMi);
