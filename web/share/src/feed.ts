/** Guest feed client + pure state helpers for the public find-me page. */

export interface FeedPoint {
  t: number;
  lat: number;
  lon: number;
  /** Per-bucket discharge context (2026-07-14 trail-detail relaxation); nullable. */
  power_w: number | null;
  current_a: number | null;
}

/** A bare chair fix — all the guest page needs for the marker, the badge and "Point me
 *  there". The feed's `last` is typed as this, not FeedPoint: it is the newest fix from a
 *  bounded server lookback (C5), and only t/lat/lon are promised for it. */
export interface FeedFix {
  t: number;
  lat: number;
  lon: number;
}

export interface GuestPack { label: string; soc: number }

/** Deliberately minimal battery surface (2026-07-14 spec amendment): the active
 *  base's SOC + net flow so a guest can see active discharge — nothing else. */
export interface GuestStatus {
  ts: number;
  soc: number;
  packs: GuestPack[];
  current_a: number;
  power_w: number;
  regen: boolean;
}

export interface Feed {
  /** The whole day on a full poll; only buckets at/after `since` on an incremental one. */
  points: FeedPoint[];
  /** The chair's newest fix from the server's bounded lookback (48 h), INDEPENDENT of the
   *  today-only trail — so after local midnight it can be yesterday evening's fix while
   *  `points` is empty. Never sliced by `since`, so a no-news poll can't blank the marker.
   *  Null only when there is no fix in the lookback at all. */
  last: FeedFix | null;
  expires_at: number;
  now: number;
  /** Start of the server's day window. A change means midnight rolled over mid-session,
   *  so the accumulated trail must be replaced rather than appended to. */
  day_start: number;
  owner: string;
  status: GuestStatus | null;
}

export type FeedResult =
  | { kind: "ok"; feed: Feed }
  | { kind: "ended" }    // 404: revoked or never existed — indistinguishable by design
  | { kind: "expired" }  // 410
  | { kind: "error" };   // network / 5xx

// 4 s. The server's data is only as fresh as the phone's upload batching (measured on
// prod: a batch every ~12 s median), so polling faster than this buys little — but at 4 s
// the dock and marker pick up a landed batch ~6 s sooner on average than at 10 s. It is
// affordable only because polls are INCREMENTAL: a full poll is ~56 KB gzipped (the whole
// day's trail, 19.7 MB per guest-hour at the old 10 s rate), an incremental one ~135 B.
export const FEED_POLL_MS = 4_000;
/** Full-window refetch cadence — heals anything an increment can't see (see useTrack). */
export const FULL_REFRESH_MS = 5 * 60_000;
export const STALE_MS = 120_000; // mirrors v2 LIVE_STALE_MS
/** A poll that hasn't answered in this long is aborted and counted as a failure (WEB-25).
 *  Twice the poll period: a slow-but-working mobile link still gets through, a hung one
 *  can't keep the page claiming LIVE. */
export const FETCH_TIMEOUT_MS = 8_000;

/** /share/<token> (optional trailing slash). Tokens are token_urlsafe(24) = 32 chars;
 *  require >=16 so /share/index.html and stray short paths never look like tokens. */
export function tokenFromPath(pathname: string): string | null {
  const m = pathname.match(/^\/share\/([A-Za-z0-9_-]{16,})\/?$/);
  return m ? m[1] : null;
}

export function isStale(last: FeedFix | null, nowMs: number): boolean {
  return last == null || nowMs - last.t > STALE_MS;
}

export function remainingLabel(expiresAt: number, nowMs: number): string {
  const left = expiresAt - nowMs;
  if (left <= 0) return "expired";
  const h = Math.floor(left / 3_600_000);
  if (h >= 48) return `${Math.floor(h / 24)}d left`;
  if (h >= 1) return `${h}h ${Math.floor((left % 3_600_000) / 60_000)}m left`;
  return `${Math.max(1, Math.floor(left / 60_000))}m left`;
}

/** [since] is the newest bucket the caller already holds — its START, not one past it:
 *  that bucket is still filling server-side, so it gets re-sent and replaced. Omit it to
 *  fetch the whole day. The request — body included — is aborted after [timeoutMs] and
 *  reported as an error, so a hung connection can never leave a poll pending forever. */
export async function fetchFeed(
  token: string, since?: number, timeoutMs: number = FETCH_TIMEOUT_MS,
): Promise<FeedResult> {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), timeoutMs);
  try {
    const q = since != null ? `?since=${since}` : "";
    const r = await fetch(`/share/${token}/feed${q}`, { signal: ctl.signal });
    if (r.status === 404) return { kind: "ended" };
    if (r.status === 410) return { kind: "expired" };
    if (!r.ok) return { kind: "error" };
    return { kind: "ok", feed: (await r.json()) as Feed };
  } catch {
    return { kind: "error" };
  } finally {
    clearTimeout(timer);
  }
}
