import { describe, expect, it, vi } from "vitest";
import { FULL_REFRESH_MS, type Feed, type FeedFix, type FeedResult } from "./feed";
import {
  INITIAL_HEALTH, LOST_AFTER_MS, ageLabel, connectionLost, createFeedPoller, guestView,
  serverNowEstimate, type PollHealth, type PollOk, type PollerDeps,
} from "./poll";

const fix = (t: number): FeedFix => ({ t, lat: 43.04, lon: -87.9 });
const feed = (o: Partial<Feed> = {}): Feed => ({
  points: [], last: null, expires_at: 9e15, now: 1_000_000, day_start: 0,
  owner: "Joely", status: null, ...o,
});

describe("serverNowEstimate", () => {
  it("advances the applied feed's server time by client time elapsed since it landed", () => {
    expect(serverNowEstimate(1_000_000, 50_000, 80_000)).toBe(1_030_000);
  });
  it("never runs backwards on a client clock step", () => {
    expect(serverNowEstimate(1_000_000, 50_000, 40_000)).toBe(1_000_000);
  });
  it("is the feed's own now before any success is recorded", () => {
    expect(serverNowEstimate(1_000_000, null, 80_000)).toBe(1_000_000);
  });
});

describe("connectionLost", () => {
  const h = (fails: number, lastOkClientMs: number | null = 0): PollHealth => ({ fails, lastOkClientMs });
  it("after two consecutive failed polls", () => {
    expect(connectionLost(h(1), 1_000)).toBe(false);
    expect(connectionLost(h(2), 1_000)).toBe(true);
  });
  it("after one failure once 15 s have passed without a success", () => {
    expect(connectionLost(h(1), LOST_AFTER_MS)).toBe(false);
    expect(connectionLost(h(1), LOST_AFTER_MS + 1)).toBe(true);
  });
  // Review focus: polling pauses while the tab is hidden. A guest who flips to their
  // messages for five minutes and back has had NO failure — the catch-up poll is about to
  // land — so the page must not flash CONNECTION LOST.
  it("not on a tab coming back after minutes hidden with no failure", () => {
    expect(connectionLost(h(0), 5 * 60_000)).toBe(false);
  });
});

describe("guestView", () => {
  const ok = (lastOkClientMs: number): PollHealth => ({ lastOkClientMs, fails: 0 });

  it("LIVE with a fresh fix and a healthy link", () => {
    const v = guestView(fix(990_000), 1_000_000, ok(0), 0);
    expect(v.badge).toEqual({ tone: "ok", state: "LIVE", detail: null });
    expect(v.markerStale).toBe(false);
    expect(v.targetNote).toBeNull();
  });

  // WEB-13: the old page compared feed.last with feed.now from the SAME last-good
  // response, so a fix never aged while polls failed.
  it("ages the fix on the client clock while polls fail, then says CONNECTION LOST", () => {
    const failing: PollHealth = { lastOkClientMs: 0, fails: 2 };
    const v = guestView(fix(990_000), 1_000_000, failing, 3 * 60_000);
    expect(v.lost).toBe(true);
    expect(v.badge).toEqual({ tone: "lost", state: "CONNECTION LOST", detail: "last update 3m ago" });
    expect(v.markerStale).toBe(true);
    expect(v.serverNow).toBe(1_180_000);
    expect(v.targetNote).toBe("last known · 3m ago");
  });

  it("goes LAST KNOWN once the fix passes 120 s on the estimated server clock alone", () => {
    // No failure recorded (e.g. polls slow but answering): staleness still advances.
    const v = guestView(fix(990_000), 1_000_000, ok(0), 131_000);
    expect(v.lost).toBe(false);
    expect(v.badge).toEqual({ tone: "warn", state: "LAST KNOWN", detail: "2m ago" });
    expect(v.markerStale).toBe(true);
  });

  // WEB-20 / C5: after local midnight `last` is yesterday evening's fix and the trail is
  // empty. The page must show it as a greyed LAST KNOWN marker with its age.
  it("shows yesterday's fix as LAST KNOWN with its age", () => {
    const tenHours = 10 * 3_600_000;
    const v = guestView(fix(1_000_000 - tenHours), 1_000_000, ok(0), 0);
    expect(v.badge).toEqual({ tone: "warn", state: "LAST KNOWN", detail: "10h ago" });
    expect(v.markerStale).toBe(true);
    expect(v.targetNote).toBe("last known · 10h ago");
  });

  // Final review: the shared relAgo says "just now" under 45 s, so the badge used to read
  // "CONNECTION LOST · last update just now" for the first ~30 s of every real outage.
  it("gives a lost link's age in seconds under a minute, never \"just now\"", () => {
    const failing: PollHealth = { lastOkClientMs: 0, fails: 2 };
    const v = guestView(fix(990_000), 1_000_000, failing, 18_000);
    expect(v.badge).toEqual({ tone: "lost", state: "CONNECTION LOST", detail: "last update 18 s ago" });
    // Same formatter for the Point-me-there note: the fix was 10 s old when the link dropped.
    expect(v.targetNote).toBe("last known · 28 s ago");
  });

  it("says plain CONNECTION LOST when no poll has ever succeeded", () => {
    const v = guestView(fix(990_000), 1_000_000, { lastOkClientMs: null, fails: 2 }, 18_000);
    expect(v.badge).toEqual({ tone: "lost", state: "CONNECTION LOST", detail: null });
  });

  it("says NO RECENT LOCATION when the lookback holds no fix at all", () => {
    const v = guestView(null, 1_000_000, ok(0), 0);
    expect(v.badge).toEqual({ tone: "warn", state: "NO RECENT LOCATION", detail: null });
    expect(v.targetNote).toBeNull();
  });
});

describe("ageLabel", () => {
  it("counts seconds under a minute, then hands over to relAgo's minutes/hours/days", () => {
    expect(ageLabel(0, 0)).toBe("0 s ago");
    expect(ageLabel(0, 18_000)).toBe("18 s ago");
    expect(ageLabel(0, 59_400)).toBe("59 s ago");
    expect(ageLabel(0, 60_000)).toBe("1m ago");
    expect(ageLabel(0, 3 * 3_600_000)).toBe("3h ago");
  });
  it("never runs negative on a clock step", () => {
    expect(ageLabel(5_000, 0)).toBe("0 s ago");
  });
});

function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}
const lastOf = <T,>(a: T[]): T => a[a.length - 1];
/** Let the poller's then/catch/finally chain run. */
const flush = () => new Promise((r) => setTimeout(r, 0));

function harness(over: Partial<PollerDeps> = {}) {
  let clock = 1_000_000;
  let seamT: number | null = null;
  const pending: Array<ReturnType<typeof deferred<FeedResult>>> = [];
  const fetchFeed = vi.fn((_since?: number) => {
    const d = deferred<FeedResult>();
    pending.push(d);
    return d.promise;
  });
  const oks: PollOk[] = [];
  const fails: PollHealth[] = [];
  const terminal: string[] = [];
  const poller = createFeedPoller({
    fetchFeed, seam: () => seamT, now: () => clock,
    onOk: (r) => { oks.push(r); }, onFail: (h) => { fails.push(h); },
    onTerminal: (k) => { terminal.push(k); },
    ...over,
  });
  return {
    poller, fetchFeed, pending, oks, fails, terminal,
    setClock: (t: number) => { clock = t; },
    setSeam: (t: number | null) => { seamT = t; },
    last: () => pending[pending.length - 1],
  };
}

describe("createFeedPoller", () => {
  // WEB-25: on a slow link the 4 s ticks used to stack up concurrent requests.
  it("skips a tick while a request is in flight", async () => {
    const t = harness();
    t.poller.tick();
    t.poller.tick();
    t.poller.tick();
    expect(t.fetchFeed).toHaveBeenCalledTimes(1);
    t.last().resolve({ kind: "ok", feed: feed() });
    await flush();
    t.poller.tick();
    expect(t.fetchFeed).toHaveBeenCalledTimes(2);
  });

  it("records the client time of each success and resets the failure count", async () => {
    const t = harness();
    t.poller.tick();
    t.last().resolve({ kind: "error" });
    await flush();
    expect(lastOf(t.fails)).toEqual({ lastOkClientMs: null, fails: 1 });
    t.setClock(1_004_000);
    t.poller.tick();
    t.last().resolve({ kind: "ok", feed: feed() });
    await flush();
    expect(lastOf(t.oks).health).toEqual({ lastOkClientMs: 1_004_000, fails: 0 });
  });

  it("counts consecutive failures across ticks", async () => {
    const t = harness();
    for (let n = 0; n < 3; n++) {
      t.poller.tick();
      t.last().resolve({ kind: "error" });
      await flush();
    }
    expect(t.fails.map((h) => h.fails)).toEqual([1, 2, 3]);
  });

  it("drops a response that lands after stop()", async () => {
    const t = harness();
    t.poller.tick();
    t.poller.stop();
    t.last().resolve({ kind: "ok", feed: feed() });
    await flush();
    expect(t.oks).toHaveLength(0);
    t.poller.tick();
    expect(t.fetchFeed).toHaveBeenCalledTimes(1);
  });

  it("stops for good on ended/expired", async () => {
    const t = harness();
    t.poller.tick();
    t.last().resolve({ kind: "expired" });
    await flush();
    expect(t.terminal).toEqual(["expired"]);
    t.poller.tick();
    expect(t.fetchFeed).toHaveBeenCalledTimes(1);
  });

  // Review focus: WEB-30's malformed feed used to throw inside .then and leave the page
  // stuck. The poller must count it as a failure and keep polling. Ruling M8: the
  // exception is logged, not swallowed.
  it("counts a throwing onOk as a failed poll, logs it, and does not wedge", async () => {
    const err = new TypeError("feed.points is undefined");
    const spy = vi.spyOn(console, "error").mockImplementation(() => {});
    try {
      const t = harness({ onOk: () => { throw err; } });
      t.poller.tick();
      t.last().resolve({ kind: "ok", feed: feed() });
      await flush();
      expect(lastOf(t.fails).fails).toBe(1);
      expect(spy).toHaveBeenCalledTimes(1);
      expect(spy.mock.calls[0]).toContain(err);
      t.poller.tick();
      expect(t.fetchFeed).toHaveBeenCalledTimes(2);
    } finally {
      spy.mockRestore();
    }
  });

  it("fetches full first, then incrementally from the seam, then full every FULL_REFRESH_MS", async () => {
    const t = harness();
    t.poller.tick();
    expect(t.fetchFeed.mock.calls[0][0]).toBeUndefined();
    t.last().resolve({ kind: "ok", feed: feed() });
    await flush();
    expect(t.oks[0].seam).toBeNull();

    t.setSeam(1_000_000 - 15_000);
    t.setClock(1_004_000);
    t.poller.tick();
    expect(t.fetchFeed.mock.calls[1][0]).toBe(985_000);
    t.last().resolve({ kind: "ok", feed: feed() });
    await flush();
    expect(t.oks[1].seam).toBe(985_000);

    t.setClock(1_000_000 + FULL_REFRESH_MS);
    t.poller.tick();
    expect(t.fetchFeed.mock.calls[2][0]).toBeUndefined();
  });

  // Review focus: midnight with the page open. The day window moved, so the trail must be
  // REPLACED (not spliced) and the very next poll must be a full one.
  it("replaces the trail and forces a full fetch after midnight", async () => {
    const t = harness();
    t.poller.tick();
    t.last().resolve({ kind: "ok", feed: feed({ day_start: 100 }) });
    await flush();
    t.setSeam(999_000);
    t.poller.tick();
    t.last().resolve({ kind: "ok", feed: feed({ day_start: 200, last: fix(500) }) });
    await flush();
    expect(t.oks[1].replace).toBe(true);
    expect(t.oks[1].feed.last).toEqual(fix(500)); // C5: yesterday's fix survives the rollover
    t.poller.tick();
    expect(t.fetchFeed.mock.calls[2][0]).toBeUndefined();
  });

  it("starts from INITIAL_HEALTH", () => {
    expect(INITIAL_HEALTH).toEqual({ lastOkClientMs: null, fails: 0 });
  });
});
