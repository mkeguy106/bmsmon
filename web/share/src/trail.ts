// Guest trail rendering mode: "detail" = the real Journey semantics (discharge-colored
// line + dashed transit legs, via v2's classifySegment/dischargeColor), "plain" = the
// original uniform green line (discharge context stripped). Persisted per-browser.
import { classifySegment, haversineMi, type SegKind } from "../../src/v2/model/journey";
import type { TrackPoint } from "../../src/v2/track";

export type TrailMode = "detail" | "plain";

export const TRAIL_KEY = "bmsmon-share-trail";

type ReadableStorage = Pick<Storage, "getItem">;
type WritableStorage = Pick<Storage, "setItem">;

export function loadTrailMode(storage: ReadableStorage): TrailMode {
  try {
    return storage.getItem(TRAIL_KEY) === "plain" ? "plain" : "detail";
  } catch {
    return "detail";
  }
}

export function saveTrailMode(storage: WritableStorage, mode: TrailMode): void {
  try {
    storage.setItem(TRAIL_KEY, mode);
  } catch {
    // private mode / storage denied — the choice just won't persist
  }
}

export function trailProps(
  points: TrackPoint[],
  mode: TrailMode,
): { points: TrackPoint[]; segKinds: SegKind[] } {
  if (mode === "plain") {
    return {
      points: points.map((p) => ({ ...p, power_w: null, current_a: null })),
      segKinds: points.map(() => "active"),
    };
  }
  // Destination-indexed, idle head at 0 — mirrors JourneyView's segKinds memo.
  const segKinds = points.map((p, i) =>
    classifySegment(p, i > 0 ? haversineMi(points[i - 1], p) : 0));
  return { points, segKinds };
}
