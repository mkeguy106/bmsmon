// Decisions for the live WebSocket link (ws.ts), kept pure so they are tested: how long to
// wait before reconnecting, and what a failure says about the web session (WEB-26). One
// I/O helper, probeSession, sits at the bottom.

/** First reconnect step. Doubles per consecutive failure up to RECONNECT_MAX_MS. */
export const RECONNECT_BASE_MS = 1_500;
export const RECONNECT_MAX_MS = 60_000;
/** Sockets in a row that close without delivering a snapshot before the session is probed. */
export const PROBE_AFTER_FAILS = 3;
/** server/app/routers/ws.py close codes: no trustworthy identity / not allowed to view. */
export const WS_UNAUTHORIZED = 4401;
export const WS_FORBIDDEN = 4403;
const PROBE_URL = "/web/alert-config";
const PROBE_TIMEOUT_MS = 8_000;

/**
 * - "ok": the session is fine. Pack data may still be stale; that is the phone, not us.
 * - "expired": no valid sign-in.
 * - "forbidden": signed in, but this account may not view bmsmon.
 * - "unreachable": the server does not answer at all.
 * - "degraded": the server answers with its marked 503 (its database is down).
 */
export type Session = "ok" | "expired" | "forbidden" | "unreachable" | "degraded";

/** Exponential backoff with "equal jitter": half of each step is fixed and half is random,
 *  so tabs reconnecting after an outage spread out instead of arriving together.
 *  attempt 0 → 0.75–1.5 s, attempt 1 → 1.5–3 s, … capped at 30–60 s. [rand] is in [0, 1]. */
export function reconnectDelayMs(attempt: number, rand: number): number {
  const a = Number.isFinite(attempt) && attempt > 0 ? attempt : 0;
  const step = Math.min(RECONNECT_MAX_MS, RECONNECT_BASE_MS * 2 ** a);
  const r = Number.isFinite(rand) ? Math.min(1, Math.max(0, rand)) : 1;
  return Math.round(step / 2 + (step / 2) * r);
}

/** The session verdict a WebSocket close code carries, or null when it carries none. */
export function sessionFromClose(code: number): Session | null {
  if (code === WS_UNAUTHORIZED) return "expired";
  if (code === WS_FORBIDDEN) return "forbidden";
  return null;
}

/** What the HTTP probe saw: the response's type and status, or "network-error" when the
 *  fetch itself failed (offline, DNS, timeout). */
export type ProbeResult = { type: string; status: number; marked?: boolean } | "network-error";

/** The header every response from the app itself carries (server/app/middleware.py). */
const API_MARKER_HEADER = "X-Bmsmon-Api";

/** An expired Authentik session answers a same-origin fetch with a redirect to its sign-in
 *  page; with redirect: "manual" that arrives as an "opaqueredirect" response. The app
 *  itself answers 401 without an identity and 403 for an account that may not view. */
export function sessionFromProbe(p: ProbeResult): Session {
  if (p === "network-error") return "unreachable";
  if (p.type === "opaqueredirect" || p.status === 401) return "expired";
  if (p.status === 403) return "forbidden";
  if (p.status >= 200 && p.status < 300) return "ok";
  if (p.status === 503 && p.marked === true) return "degraded";
  return "unreachable";
}

/** One small viewer-gated GET that never follows a redirect, aborted after 8 s. */
export async function probeSession(): Promise<ProbeResult> {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), PROBE_TIMEOUT_MS);
  try {
    const r = await fetch(PROBE_URL, { redirect: "manual", cache: "no-store", signal: ctl.signal });
    return { type: r.type, status: r.status, marked: r.headers.get(API_MARKER_HEADER) === "1" };
  } catch {
    return "network-error";
  } finally {
    clearTimeout(timer);
  }
}
