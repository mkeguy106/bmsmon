import { useEffect, useState } from "react";
import { MOBILE_MAX } from "./settings";

/**
 * Window width for the mobile/desktop breakpoint. Its ONLY consumer is
 * `resolveMobile(deviceMode, width)` (v2 App), which compares against MOBILE_MAX —
 * so re-render only when a resize CROSSES the breakpoint, not on every pixel of a
 * drag-resize. If a future consumer needs the raw width, switch this to an
 * rAF-throttled update instead.
 */
export function useWinWidth(): number {
  const [w, setW] = useState(() => window.innerWidth);
  useEffect(() => {
    const on = () => setW((prev) => {
      const next = window.innerWidth;
      return (next < MOBILE_MAX) === (prev < MOBILE_MAX) ? prev : next;
    });
    window.addEventListener("resize", on);
    return () => window.removeEventListener("resize", on);
  }, []);
  return w;
}
