import { afterEach, describe, expect, it, vi } from "vitest";
import { decodeCartoKey, fetchCartoKey, onceForSession, tileUrl } from "./basemap";

const KEY = "test_key_123456"; // obviously fake: the real key is never in the repo

describe("tileUrl", () => {
  const DARK = "https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png";
  const LIGHT = "https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png";

  it("is exactly the keyless URL without a key", () => {
    expect(tileUrl("dark")).toBe(DARK);
    expect(tileUrl("light")).toBe(LIGHT);
    expect(tileUrl("dark", null)).toBe(DARK);
    expect(tileUrl("light", "")).toBe(LIGHT);
  });

  it("appends ?key= for each theme", () => {
    expect(tileUrl("dark", KEY)).toBe(`${DARK}?key=${KEY}`);
    expect(tileUrl("light", KEY)).toBe(`${LIGHT}?key=${KEY}`);
  });

  it("URL-encodes the key", () => {
    expect(tileUrl("dark", "a b&c=d/é")).toBe(`${DARK}?key=a%20b%26c%3Dd%2F%C3%A9`);
  });
});

describe("decodeCartoKey", () => {
  it("takes a non-empty string key and nothing else", () => {
    expect(decodeCartoKey({ carto_key: KEY })).toBe(KEY);
    for (const body of [{ carto_key: null }, { carto_key: "" }, { carto_key: 42 }, {}, null,
      "nope", [KEY]]) {
      expect(decodeCartoKey(body)).toBeNull();
    }
  });
});

describe("fetchCartoKey", () => {
  afterEach(() => { vi.unstubAllGlobals(); });

  it("returns the key from a 200", async () => {
    const spy = vi.fn(async (_url: string) => ({ ok: true, status: 200, json: async () => ({ carto_key: KEY }) }));
    vi.stubGlobal("fetch", spy);
    expect(await fetchCartoKey("/web/map-config")).toBe(KEY);
    expect(spy).toHaveBeenCalledWith("/web/map-config");
  });

  it("is null, never a throw, on any failure", async () => {
    const failures = [
      async () => ({ ok: false, status: 401, json: async () => ({ carto_key: KEY }) }),
      async () => ({ ok: false, status: 410, json: async () => ({}) }),
      async () => { throw new TypeError("network down"); },
      async () => ({ ok: true, status: 200, json: async () => { throw new SyntaxError("html"); } }),
      async () => ({ ok: true, status: 200, json: async () => ({ carto_key: null }) }),
    ];
    for (const impl of failures) {
      vi.stubGlobal("fetch", vi.fn(impl));
      await expect(fetchCartoKey("/share/tok/map-config")).resolves.toBeNull();
    }
  });
});

describe("onceForSession", () => {
  it("fetches once and shares the answer with concurrent and later callers", async () => {
    const fetchKey = vi.fn(async () => KEY);
    const loader = onceForSession(fetchKey);
    expect(loader.current()).toBeNull();
    const [a, b] = await Promise.all([loader.load(), loader.load()]);
    expect(await loader.load()).toBe(KEY);
    expect([a, b]).toEqual([KEY, KEY]);
    expect(loader.current()).toBe(KEY);
    expect(fetchKey).toHaveBeenCalledTimes(1);
  });

  it("settles to null on failure and does not refetch (no polling)", async () => {
    const fetchKey = vi.fn(async (): Promise<string | null> => { throw new Error("boom"); });
    const loader = onceForSession(fetchKey);
    expect(await loader.load()).toBeNull();
    expect(await loader.load()).toBeNull();
    expect(loader.current()).toBeNull();
    expect(fetchKey).toHaveBeenCalledTimes(1);
  });
});
