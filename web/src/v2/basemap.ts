/** CARTO basemap tiles and the runtime key they need.
 *
 *  CARTO answers keyless raster tile requests with "API KEY REQUIRED" placeholders. The
 *  repo and the server image are public, so the key is never built in: the server reads
 *  it from its environment and hands it out at runtime — /web/map-config to signed-in
 *  viewers (v2 Journey), /share/<token>/map-config to an active share link (guest page).
 *  Without it the map still renders, just with the placeholder tiles. */

export function tileUrl(theme: "dark" | "light", key?: string | null): string {
  const style = theme === "dark" ? "dark_all" : "light_all";
  const url = `https://{s}.basemaps.cartocdn.com/${style}/{z}/{x}/{y}{r}.png`;
  return key ? `${url}?key=${encodeURIComponent(key)}` : url;
}

/** `{"carto_key": "<key>" | null}` -> the key; null for a null key or any other shape. */
export function decodeCartoKey(body: unknown): string | null {
  if (typeof body !== "object" || body === null) return null;
  const key = (body as { carto_key?: unknown }).carto_key;
  return typeof key === "string" && key.length > 0 ? key : null;
}

/** How long the map holds off its tile layer while the key request is pending, so a fresh
 *  page doesn't flash a screenful of keyless placeholder tiles. Capped so the map never
 *  waits on a slow server: after this it loads keyless tiles, and setUrl upgrades them
 *  whenever the key does arrive. */
export const KEY_WAIT_MS = 3_000;
/** One request is aborted after this long and counted as a transient failure. */
export const KEY_FETCH_TIMEOUT_MS = 8_000;
/** Waits before each retry of a transient failure; after the last one, give up (null). */
export const KEY_RETRY_DELAYS_MS: readonly number[] = [5_000, 15_000, 45_000];

/** One request's outcome. `settled` is definitive and never retried: a 200 (key or null),
 *  401/403/404/410 and any other 4xx, or a 200 whose body isn't JSON (a sign-in page) —
 *  asking again can't change them, and on the share page 404/410 mean the link is over.
 *  `transient` may succeed later: a network error, the timeout, 408, 429 or a 5xx. */
export type KeyAttempt = { kind: "settled"; key: string | null } | { kind: "transient" };

const TRANSIENT: KeyAttempt = { kind: "transient" };

export function isTransientStatus(status: number): boolean {
  return status === 408 || status === 429 || status >= 500;
}

/** One GET of a map-config endpoint, aborted after [timeoutMs] or when [signal] fires.
 *  Never throws. */
export async function attemptCartoKey(
  url: string, signal?: AbortSignal, timeoutMs: number = KEY_FETCH_TIMEOUT_MS,
): Promise<KeyAttempt> {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), timeoutMs);
  const relay = () => ctl.abort();
  signal?.addEventListener("abort", relay, { once: true });
  try {
    const r = await fetch(url, { signal: ctl.signal });
    if (!r.ok) return isTransientStatus(r.status) ? TRANSIENT : { kind: "settled", key: null };
    return { kind: "settled", key: decodeCartoKey(await r.json()) };
  } catch (e) {
    // A body that isn't JSON won't parse any better next time; anything else (network,
    // timeout, a cut-off body) might.
    return e instanceof SyntaxError ? { kind: "settled", key: null } : TRANSIENT;
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener("abort", relay);
  }
}

/** Resolves after [ms], or at once when [signal] fires. */
function sleepUnlessAborted(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const done = () => {
      clearTimeout(timer);
      signal?.removeEventListener("abort", done);
      resolve();
    };
    const timer = setTimeout(done, ms);
    signal?.addEventListener("abort", done, { once: true });
  });
}

/** Runs [attempt] until it settles, retrying only transient failures after each of
 *  [delays], then gives up with null. Sequential, so at most one request is in flight.
 *  Once [signal] fires it stops (null) without another request — the share page aborts
 *  on unmount and when the link turns out ended or expired. */
export async function retryTransient(
  attempt: () => Promise<KeyAttempt>,
  { delays = KEY_RETRY_DELAYS_MS, sleep = sleepUnlessAborted, signal }: {
    delays?: readonly number[];
    sleep?: (ms: number, signal?: AbortSignal) => Promise<void>;
    signal?: AbortSignal;
  } = {},
): Promise<string | null> {
  for (let i = 0; ; i++) {
    if (signal?.aborted) return null;
    const result = await attempt();
    if (result.kind === "settled") return result.key;
    if (i >= delays.length) return null;
    await sleep(delays[i], signal);
  }
}

/** A map-config endpoint -> the key, or null: bounded retry of transient failures, never
 *  throws. A missing key costs only the basemap imagery, so no failure here may break the
 *  page around the map. */
export function fetchCartoKey(url: string, signal?: AbortSignal): Promise<string | null> {
  return retryTransient(() => attemptCartoKey(url, signal), { signal });
}

/** One fetch chain per page session: concurrent and later callers all share its answer,
 *  null included (a definitive "no key", or transient failures that outlasted the
 *  retries). `current()` is `undefined` while it is pending — the map holds its tiles
 *  briefly (KEY_WAIT_MS) — and the answer once settled, readable synchronously so a
 *  remounted map starts with the key rather than waiting or flashing placeholders. */
export function onceForSession(fetchKey: () => Promise<string | null>): {
  load: () => Promise<string | null>; current: () => string | null | undefined;
} {
  let pending: Promise<string | null> | null = null;
  let settled: string | null | undefined = undefined;
  return {
    load: () => {
      if (!pending) {
        pending = fetchKey().catch(() => null).then((key) => { settled = key; return key; });
      }
      return pending;
    },
    current: () => settled,
  };
}
