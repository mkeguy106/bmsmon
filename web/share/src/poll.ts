// Guest-page poll health: the single-flight feed poller and the pure "what may the page
// claim right now" derivation (WEB-13 / WEB-25 / WEB-20). Everything here is DOM-free so
// the failure paths — hung polls, failed polls, a tab coming back, midnight — are pinned by
// node tests rather than by eye.
import { relAgo } from "../../src/util";
import { FULL_REFRESH_MS, isStale, type Feed, type FeedFix, type FeedResult } from "./feed";

/** Consecutive failed polls (error OR timeout) that make the page say CONNECTION LOST. */
export const LOST_AFTER_FAILS = 2;
/** ...or a single failure once this long has passed since the last success. */
export const LOST_AFTER_MS = 15_000;

export interface PollHealth {
  /** CLIENT clock (Date.now()) when the last successful feed was applied; null before one. */
  lastOkClientMs: number | null;
  /** Failed polls since the last success. */
  fails: number;
}

export const INITIAL_HEALTH: PollHealth = { lastOkClientMs: null, fails: 0 };

/** The server's clock "now", estimated: the applied feed's `now` advanced by however much
 *  CLIENT time has passed since it landed. This is what makes a fix go stale while polls are
 *  failing — the old code compared two values from the same (last successful) response, so
 *  nothing ever aged during an outage. */
export function serverNowEstimate(feedNow: number, lastOkClientMs: number | null, clientNow: number): number {
  return feedNow + (lastOkClientMs == null ? 0 : Math.max(0, clientNow - lastOkClientMs));
}

/** Two failures in a row, or one failure with no success for LOST_AFTER_MS. Deliberately NOT
 *  "15 s without a success" on its own: polling pauses while the tab is hidden, so that rule
 *  would flash CONNECTION LOST every time a guest switches back from their messages, before
 *  the catch-up poll has had a chance to land. */
export function connectionLost(h: PollHealth, clientNow: number): boolean {
  if (h.fails >= LOST_AFTER_FAILS) return true;
  return h.fails >= 1 && h.lastOkClientMs != null && clientNow - h.lastOkClientMs > LOST_AFTER_MS;
}

export type BadgeTone = "ok" | "warn" | "lost";

/** Guest-page age: whole seconds under a minute ("18 s ago"), then the shared relAgo. The
 *  shared one says "just now" under 45 s, which reads as a contradiction beside CONNECTION
 *  LOST — and v1 depends on it, so the guest page keeps its own short-range format. */
export function ageLabel(ms: number, now: number): string {
  const s = Math.max(0, Math.round((now - ms) / 1000));
  return s < 60 ? `${s} s ago` : relAgo(ms, now);
}

export interface GuestBadge {
  tone: BadgeTone;
  /** The state ("LIVE", "CONNECTION LOST", …). The page announces ONLY this to screen
   *  readers, so a ticking age is not re-read every second. */
  state: string;
  /** Shown after the state ("last update 18 s ago", "10h ago"); null when there is none. */
  detail: string | null;
}

export interface GuestView {
  /** Server-clock "now" estimate — the reference for every age shown on the page. */
  serverNow: number;
  lost: boolean;
  /** Grey, un-pulsed chair marker: the fix is old OR we can no longer reach the server. */
  markerStale: boolean;
  badge: GuestBadge;
  /** "last known · 10h ago" beside the Point-me-there distance whenever the target isn't live. */
  targetNote: string | null;
}

/** Everything the guest page may truthfully claim, from the applied feed + poll health. */
export function guestView(
  last: FeedFix | null, feedNow: number, h: PollHealth, clientNow: number,
): GuestView {
  const serverNow = serverNowEstimate(feedNow, h.lastOkClientMs, clientNow);
  const lost = connectionLost(h, clientNow);
  const fixStale = isStale(last, serverNow);
  const age = last ? ageLabel(last.t, serverNow) : null;
  const badge: GuestBadge = lost
    ? { tone: "lost", state: "CONNECTION LOST",
        detail: h.lastOkClientMs == null ? null : `last update ${ageLabel(h.lastOkClientMs, clientNow)}` }
    : last == null ? { tone: "warn", state: "NO RECENT LOCATION", detail: null }
    : fixStale ? { tone: "warn", state: "LAST KNOWN", detail: age }
    : { tone: "ok", state: "LIVE", detail: null };
  const markerStale = lost || fixStale;
  return { serverNow, lost, markerStale, badge, targetNote: markerStale && age ? `last known · ${age}` : null };
}

export interface PollOk {
  feed: Feed;
  /** Midnight rolled over mid-session: replace the accumulated trail instead of splicing. */
  replace: boolean;
  /** The seam bucket this request was made from (null = a full fetch). */
  seam: number | null;
  health: PollHealth;
}

export interface PollerDeps {
  fetchFeed: (since?: number) => Promise<FeedResult>;
  /** START of the newest bucket the caller holds, or null when its trail is empty. */
  seam: () => number | null;
  now: () => number;
  onOk: (r: PollOk) => void;
  onFail: (health: PollHealth) => void;
  /** 404/410: the share is over. The poller has stopped itself; the caller stops its timer. */
  onTerminal: (kind: "ended" | "expired") => void;
}

export interface FeedPoller {
  /** Poll now — a no-op while a request is in flight or after stop()/a terminal answer. */
  tick: () => void;
  /** Stop for good: anything still in flight is ignored when it lands. */
  stop: () => void;
}

/**
 * Single-flight feed poller. At most ONE request is outstanding, so on a slow link the 4 s
 * ticks can't stack up and an older response can never land after a newer one (WEB-25);
 * the only "late" responses left are ones that land after stop(), and those are dropped.
 * Seam rule unchanged from the inline version it replaces: incremental from the newest
 * bucket's START, full on first load / empty trail / every FULL_REFRESH_MS / after midnight.
 */
export function createFeedPoller(d: PollerDeps): FeedPoller {
  let stopped = false;
  let inFlight = false;
  let lastFullMs = 0;
  let dayStart: number | null = null;
  let health: PollHealth = INITIAL_HEALTH;

  const fail = () => {
    if (stopped) return;
    health = { ...health, fails: health.fails + 1 };
    d.onFail(health);
  };

  const tick = () => {
    if (stopped || inFlight) return;
    inFlight = true;
    const held = d.seam();
    const seam = held != null && d.now() - lastFullMs < FULL_REFRESH_MS ? held : null;
    if (seam == null) lastFullMs = d.now();
    d.fetchFeed(seam ?? undefined)
      .then((r) => {
        if (stopped) return;
        if (r.kind === "ok") {
          const rolled = dayStart != null && r.feed.day_start !== dayStart;
          const next: PollHealth = { lastOkClientMs: d.now(), fails: 0 };
          // A throw in onOk (e.g. a malformed feed) skips the commits below and is counted
          // as a failed poll by the catch — the poller never wedges on bad data.
          d.onOk({ feed: r.feed, replace: rolled, seam, health: next });
          health = next;
          dayStart = r.feed.day_start;
          if (rolled) lastFullMs = 0;
        } else if (r.kind === "error") {
          fail();
        } else {
          stopped = true;
          d.onTerminal(r.kind);
        }
      })
      .catch((e: unknown) => {
        // Logged, not swallowed: a throwing onOk is a bug worth seeing in the console even
        // though the page degrades gracefully by treating it as a failed poll.
        console.error("guest feed poll failed:", e);
        fail();
      })
      .finally(() => { inFlight = false; });
  };

  return { tick, stop: () => { stopped = true; } };
}
