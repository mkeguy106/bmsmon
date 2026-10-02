import { afterEach, describe, expect, it, vi } from "vitest";
import {
  FEED_POLL_MS, FETCH_TIMEOUT_MS, fetchFeed, isStale, remainingLabel, tokenFromPath,
} from "./feed";

describe("feed model", () => {
  it("tokenFromPath accepts /share/<token> only", () => {
    expect(tokenFromPath("/share/AbC123xyz_-AbC123xyz_-AbC123xyz")).toBe(
      "AbC123xyz_-AbC123xyz_-AbC123xyz");
    expect(tokenFromPath("/share/AbC123xyz_-AbC123xyz_-AbC123xyz/")).toBe(
      "AbC123xyz_-AbC123xyz_-AbC123xyz");
    expect(tokenFromPath("/share/")).toBeNull();
    expect(tokenFromPath("/share/short")).toBeNull();
    expect(tokenFromPath("/share/index.html")).toBeNull();
    expect(tokenFromPath("/v2/")).toBeNull();
  });

  it("isStale after 120s or with no fix", () => {
    expect(isStale(null, 1_000_000)).toBe(true);
    expect(isStale({ t: 1_000_000 - 119_000, lat: 0, lon: 0 }, 1_000_000)).toBe(false);
    expect(isStale({ t: 1_000_000 - 121_000, lat: 0, lon: 0 }, 1_000_000)).toBe(true);
  });

  it("remainingLabel formats h/m/d", () => {
    expect(remainingLabel(1_000_000, 1_000_001)).toBe("expired");
    expect(remainingLabel(90_000 + 0, 0)).toBe("1m left");
    expect(remainingLabel(2 * 3_600_000 + 5 * 60_000, 0)).toBe("2h 5m left");
    expect(remainingLabel(3 * 86_400_000, 0)).toBe("3d left");
  });
});

describe("fetchFeed", () => {
  afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });

  const stubOk = () => {
    const spy = vi.fn(async (_url: string) => ({ ok: true, status: 200, json: async () => ({}) }));
    vi.stubGlobal("fetch", spy);
    return spy;
  };

  it("omits ?since on a full fetch and sends the seam bucket on an incremental one", async () => {
    const spy = stubOk();
    await fetchFeed("tok");
    expect(spy.mock.calls[0][0]).toBe("/share/tok/feed");
    await fetchFeed("tok", 1_785_705_360_000);
    expect(spy.mock.calls[1][0]).toBe("/share/tok/feed?since=1785705360000");
    // 0 is a real bucket start, not "absent" — it must still be sent.
    await fetchFeed("tok", 0);
    expect(spy.mock.calls[2][0]).toBe("/share/tok/feed?since=0");
  });

  it("maps terminal statuses without reading a body", async () => {
    for (const [code, kind] of [[404, "ended"], [410, "expired"], [500, "error"]] as const) {
      vi.stubGlobal("fetch", vi.fn(async () => ({ ok: false, status: code })));
      expect((await fetchFeed("tok")).kind).toBe(kind);
    }
  });

  it("polls fast enough to beat the old 10 s cadence", () => {
    expect(FEED_POLL_MS).toBeLessThan(10_000);
  });

  // WEB-25: a request that never answers must not leave the page claiming LIVE forever.
  it("aborts a hung request at the timeout and reports an error", async () => {
    vi.useFakeTimers();
    vi.stubGlobal("fetch", vi.fn((_url: string, init?: RequestInit) =>
      new Promise((_resolve, reject) => {
        init?.signal?.addEventListener("abort", () => reject(new Error("aborted")));
      })));
    let settled = false;
    const p = fetchFeed("tok", undefined, 8_000).then((r) => { settled = true; return r; });
    await vi.advanceTimersByTimeAsync(7_999);
    expect(settled).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    expect((await p).kind).toBe("error");
  });

  it("clears its abort timer when the response lands in time", async () => {
    vi.useFakeTimers();
    stubOk();
    await fetchFeed("tok");
    expect(vi.getTimerCount()).toBe(0);
  });

  // Ruling C5: assert the timeout sits inside the 15 s window rather than pinning 8 000.
  it("times out well inside the 15 s connection-lost window", () => {
    expect(FETCH_TIMEOUT_MS).toBeLessThan(15_000);
    expect(FETCH_TIMEOUT_MS).toBeGreaterThan(FEED_POLL_MS);
  });
});
