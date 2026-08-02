import { afterEach, describe, expect, it, vi } from "vitest";
import { FEED_POLL_MS, fetchFeed, isStale, remainingLabel, tokenFromPath } from "./feed";

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
    expect(isStale({ t: 1_000_000 - 119_000, lat: 0, lon: 0, power_w: null, current_a: null }, 1_000_000)).toBe(false);
    expect(isStale({ t: 1_000_000 - 121_000, lat: 0, lon: 0, power_w: null, current_a: null }, 1_000_000)).toBe(true);
  });

  it("remainingLabel formats h/m/d", () => {
    expect(remainingLabel(1_000_000, 1_000_001)).toBe("expired");
    expect(remainingLabel(90_000 + 0, 0)).toBe("1m left");
    expect(remainingLabel(2 * 3_600_000 + 5 * 60_000, 0)).toBe("2h 5m left");
    expect(remainingLabel(3 * 86_400_000, 0)).toBe("3d left");
  });
});

describe("fetchFeed", () => {
  afterEach(() => { vi.unstubAllGlobals(); });

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
});
