// "Point me there" panel state (WEB-20). The old panel showed "Locating…" whenever EITHER
// position was missing, so a guest whose own GPS was fine waited forever on a chair that
// had no fix to point to. The two waits are different problems with different fixes.

export type ArrowState = "idle" | "denied" | "no-chair" | "locating" | "ready";

export function arrowState(s: {
  on: boolean; denied: boolean; hasGuest: boolean; hasTarget: boolean;
}): ArrowState {
  if (!s.on) return "idle";
  if (s.denied) return "denied";
  // No chair fix outranks "locating you": even a perfect guest fix can't be pointed anywhere.
  if (!s.hasTarget) return "no-chair";
  if (!s.hasGuest) return "locating";
  return "ready";
}

/** Status line for the non-ready states ("" for idle/ready, which render their own UI). */
export function arrowMessage(state: ArrowState, waitingForGps: boolean): string {
  switch (state) {
    case "denied": return "Location permission denied — enable it in your browser to get directions.";
    case "no-chair": return "No recent location from the chair — nothing to point to yet.";
    case "locating": return waitingForGps ? "Locating you… (waiting for GPS)" : "Locating you…";
    default: return "";
  }
}
