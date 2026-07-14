// Visibility-gated polling: no REST poller should burn network/CPU in a tab nobody can
// see. `visibleInterval` replaces the bare `setInterval(fn, ms)` in every poll effect —
// ticks are skipped while `document.hidden`, and when the tab becomes visible again the
// poll fires immediately IF at least one interval has elapsed since the last run (so a
// returning user sees fresh data at once, but a quick tab flip never double-fetches).
//
// The initial mount fetch stays the CALLER's job — only the recurring ticks are gated
// (simplest correct behavior; a mounted-hidden tab still gets its first load).
// The WS in ws.ts keeps its own richer visibilitychange handling; this is for REST polls.

/** The subset of `document` we need — injectable so node-side unit tests can fake it. */
export interface VisibilityDoc {
  readonly hidden: boolean;
  addEventListener(type: "visibilitychange", fn: () => void): void;
  removeEventListener(type: "visibilitychange", fn: () => void): void;
}

/**
 * Run `fn` every `ms` while the tab is visible; skip while hidden; catch up immediately
 * on hidden→visible when a run is overdue. Returns the cleanup function (clears the
 * timer and removes the visibility listener) — return it from a React effect.
 */
export function visibleInterval(
  fn: () => void,
  ms: number,
  doc: VisibilityDoc = document,
): () => void {
  let last = Date.now();
  const run = () => { last = Date.now(); fn(); };
  const timer = setInterval(() => { if (!doc.hidden) run(); }, ms);
  const onVisible = () => {
    if (!doc.hidden && Date.now() - last >= ms) run();
  };
  doc.addEventListener("visibilitychange", onVisible);
  return () => {
    clearInterval(timer);
    doc.removeEventListener("visibilitychange", onVisible);
  };
}
