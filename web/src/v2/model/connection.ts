// What v2 says about its own link to the server (WEB-26). The phone going quiet is shown per
// pack (DISCONNECTED, last seen) and by the SYNCED pill; this banner covers the cases where
// the WEB side is the problem, so a dead session is never mistaken for a dead phone. It
// renders on mobile too, where the header pills are hidden.
import type { Session } from "../../liveLink";

export interface Banner {
  tone: "warn" | "crit";
  title: string;
  detail: string;
  /** Offer a Reload button (a new sign-in needs a page load). */
  reload: boolean;
}

export function connectionBanner(session: Session): Banner | null {
  switch (session) {
    case "ok":
      return null;
    case "expired":
      return { tone: "crit", title: "Session expired",
        detail: "Sign in again for live data. Everything shown is last known.", reload: true };
    case "forbidden":
      return { tone: "crit", title: "Not authorized",
        detail: "This account can't view bmsmon. Sign in with an authorized account. Everything shown is last known.",
        reload: true };
    case "unreachable":
      return { tone: "warn", title: "Can't reach the server",
        detail: "Retrying. Everything shown is last known.", reload: false };
  }
}
