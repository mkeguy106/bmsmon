import type { Codec } from "../useLocalStorage";
import type { PackRange } from "../range";

export interface Trip { id: string; name: string; miles: number }
export type TripVerdict = "go" | "tight" | "no-go";

export function classifyTrip(miles: number, r: PackRange): TripVerdict {
  if (miles <= r.milesLo) return "go";
  if (miles <= r.milesHi) return "tight";
  return "no-go";
}

export const tripsCodec: Codec<Trip[]> = {
  decode: (raw) => {
    try {
      const a = JSON.parse(raw);
      if (!Array.isArray(a)) return null;
      return a.filter((t): t is Trip =>
        t && typeof t.id === "string" && typeof t.name === "string" && Number.isFinite(t.miles));
    } catch { return null; }
  },
  encode: (v) => JSON.stringify(v),
};
