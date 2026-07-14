/** Guest feed client + pure state helpers for the public find-me page. */

export interface FeedPoint {
  t: number;
  lat: number;
  lon: number;
  /** Per-bucket discharge context (2026-07-14 trail-detail relaxation); nullable. */
  power_w: number | null;
  current_a: number | null;
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
  points: FeedPoint[];
  last: FeedPoint | null;
  expires_at: number;
  now: number;
  owner: string;
  status: GuestStatus | null;
}

export type FeedResult =
  | { kind: "ok"; feed: Feed }
  | { kind: "ended" }    // 404: revoked or never existed — indistinguishable by design
  | { kind: "expired" }  // 410
  | { kind: "error" };   // network / 5xx

export const FEED_POLL_MS = 10_000;
export const STALE_MS = 120_000; // mirrors v2 LIVE_STALE_MS

/** /share/<token> (optional trailing slash). Tokens are token_urlsafe(24) = 32 chars;
 *  require >=16 so /share/index.html and stray short paths never look like tokens. */
export function tokenFromPath(pathname: string): string | null {
  const m = pathname.match(/^\/share\/([A-Za-z0-9_-]{16,})\/?$/);
  return m ? m[1] : null;
}

export function isStale(last: FeedPoint | null, nowMs: number): boolean {
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

export async function fetchFeed(token: string): Promise<FeedResult> {
  try {
    const r = await fetch(`/share/${token}/feed`);
    if (r.status === 404) return { kind: "ended" };
    if (r.status === 410) return { kind: "expired" };
    if (!r.ok) return { kind: "error" };
    return { kind: "ok", feed: (await r.json()) as Feed };
  } catch {
    return { kind: "error" };
  }
}
