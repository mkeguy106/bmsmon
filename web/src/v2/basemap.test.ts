import { afterEach, describe, expect, it, vi } from "vitest";
import {
  KEY_FETCH_TIMEOUT_MS, KEY_RETRY_DELAYS_MS, attemptCartoKey, decodeCartoKey, fetchCartoKey,
  isTransientStatus, onceForSession, retryTransient, tileUrl, type KeyAttempt,
} from "./basemap";

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

// ── one request: definitive vs transient ───────────────────────────────────────────

type FetchInit = { signal?: AbortSignal };
const respond = (status: number, body: unknown = { carto_key: KEY }) =>
  vi.fn(async (_url: string, _init?: FetchInit) => ({
    ok: status >= 200 && status < 300, status, json: async () => body,
  }));
/** A request that never answers until it is aborted (a hung connection). */
const hang = () => vi.fn((_url: string, init?: FetchInit) => new Promise((_res, rej) => {
  init?.signal?.addEventListener("abort", () => rej(new DOMException("aborted", "AbortError")));
}));

describe("isTransientStatus", () => {
  it("retries only 408, 429 and 5xx", () => {
    for (const s of [408, 429, 500, 502, 503, 504]) expect(isTransientStatus(s)).toBe(true);
    for (const s of [200, 400, 401, 403, 404, 410]) expect(isTransientStatus(s)).toBe(false);
  });
});

describe("attemptCartoKey", () => {
  afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });

  it("settles on a 200, key or null", async () => {
    const spy = respond(200);
    vi.stubGlobal("fetch", spy);
    expect(await attemptCartoKey("/web/map-config")).toEqual({ kind: "settled", key: KEY });
    expect(spy.mock.calls[0][0]).toBe("/web/map-config");
    vi.stubGlobal("fetch", respond(200, { carto_key: null }));
    expect(await attemptCartoKey("/web/map-config")).toEqual({ kind: "settled", key: null });
  });

  it("settles to null on 401/403/404/410: retrying can't change them", async () => {
    for (const s of [401, 403, 404, 410]) {
      vi.stubGlobal("fetch", respond(s));
      expect(await attemptCartoKey("/x")).toEqual({ kind: "settled", key: null });
    }
  });

  it("settles to null on a 200 whose body isn't JSON (e.g. a sign-in page)", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => ({
      ok: true, status: 200, json: async () => { throw new SyntaxError("<html>"); } })));
    expect(await attemptCartoKey("/x")).toEqual({ kind: "settled", key: null });
  });

  it("is transient on 429, 5xx and a network error", async () => {
    for (const s of [429, 500, 503]) {
      vi.stubGlobal("fetch", respond(s));
      expect(await attemptCartoKey("/x")).toEqual({ kind: "transient" });
    }
    vi.stubGlobal("fetch", vi.fn(async () => { throw new TypeError("network down"); }));
    expect(await attemptCartoKey("/x")).toEqual({ kind: "transient" });
  });

  it("aborts a hung request at the timeout and calls it transient", async () => {
    vi.useFakeTimers();
    vi.stubGlobal("fetch", hang());
    const p = attemptCartoKey("/x");
    await vi.advanceTimersByTimeAsync(KEY_FETCH_TIMEOUT_MS - 1);
    let done = false;
    void p.then(() => { done = true; });
    await Promise.resolve();
    expect(done).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    expect(await p).toEqual({ kind: "transient" });
    expect(KEY_FETCH_TIMEOUT_MS).toBeGreaterThanOrEqual(5_000);
    expect(KEY_FETCH_TIMEOUT_MS).toBeLessThanOrEqual(10_000);
  });

  it("aborts the request when the caller's signal fires", async () => {
    vi.stubGlobal("fetch", hang());
    const ctl = new AbortController();
    const p = attemptCartoKey("/x", ctl.signal);
    ctl.abort();
    expect(await p).toEqual({ kind: "transient" });
  });
});

// ── the bounded retry ─────────────────────────────────────────────────────────────

describe("retryTransient", () => {
  const settled = (key: string | null): KeyAttempt => ({ kind: "settled", key });
  const transient: KeyAttempt = { kind: "transient" };
  const script = (...seq: KeyAttempt[]) => {
    const attempt = vi.fn(async () => seq.shift() ?? transient);
    const sleeps: number[] = [];
    const sleep = vi.fn(async (ms: number) => { sleeps.push(ms); });
    return { attempt, sleep, sleeps };
  };

  it("returns a settled first answer without retrying", async () => {
    for (const key of [KEY, null]) {
      const { attempt, sleep } = script(settled(key));
      expect(await retryTransient(attempt, { sleep })).toBe(key);
      expect(attempt).toHaveBeenCalledTimes(1);
      expect(sleep).not.toHaveBeenCalled();
    }
  });

  it("retries transient failures with the backoff, then succeeds", async () => {
    const { attempt, sleep, sleeps } = script(transient, transient, settled(KEY));
    expect(await retryTransient(attempt, { sleep })).toBe(KEY);
    expect(attempt).toHaveBeenCalledTimes(3);
    expect(sleeps).toEqual(KEY_RETRY_DELAYS_MS.slice(0, 2));
  });

  it("gives up after the last retry and settles null", async () => {
    const { attempt, sleeps } = script();
    expect(await retryTransient(attempt, { sleep: async (ms) => { sleeps.push(ms); } })).toBeNull();
    expect(attempt).toHaveBeenCalledTimes(1 + KEY_RETRY_DELAYS_MS.length);
    expect(sleeps).toEqual([...KEY_RETRY_DELAYS_MS]);
  });

  it("the backoff is capped: three retries, growing, under a minute each", () => {
    expect(KEY_RETRY_DELAYS_MS.length).toBe(3);
    expect([...KEY_RETRY_DELAYS_MS].sort((a, b) => a - b)).toEqual([...KEY_RETRY_DELAYS_MS]);
    expect(Math.max(...KEY_RETRY_DELAYS_MS)).toBeLessThanOrEqual(60_000);
  });

  it("stops at once when the signal fires, before or between attempts", async () => {
    const ctl = new AbortController();
    const { attempt } = script(transient, settled(KEY));
    const sleep = vi.fn(async () => { ctl.abort(); });  // e.g. the share went terminal
    expect(await retryTransient(attempt, { sleep, signal: ctl.signal })).toBeNull();
    expect(attempt).toHaveBeenCalledTimes(1);
    const pre = script(settled(KEY));
    expect(await retryTransient(pre.attempt, { signal: ctl.signal })).toBeNull();
    expect(pre.attempt).not.toHaveBeenCalled();
  });
});

describe("fetchCartoKey (real timers faked)", () => {
  afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });

  it("recovers from a 503 on the first retry", async () => {
    vi.useFakeTimers();
    const spy = vi.fn()
      .mockResolvedValueOnce({ ok: false, status: 503, json: async () => ({}) })
      .mockResolvedValue({ ok: true, status: 200, json: async () => ({ carto_key: KEY }) });
    vi.stubGlobal("fetch", spy);
    const p = fetchCartoKey("/web/map-config");
    await vi.advanceTimersByTimeAsync(KEY_RETRY_DELAYS_MS[0]);
    expect(await p).toBe(KEY);
    expect(spy).toHaveBeenCalledTimes(2);
  });

  it("never retries a 404 or 410 (ended / expired share)", async () => {
    vi.useFakeTimers();
    for (const s of [404, 410]) {
      const spy = respond(s);
      vi.stubGlobal("fetch", spy);
      const p = fetchCartoKey("/share/tok/map-config");
      await vi.advanceTimersByTimeAsync(120_000);
      expect(await p).toBeNull();
      expect(spy).toHaveBeenCalledTimes(1);
    }
  });

  it("cancels a pending retry when the signal fires", async () => {
    vi.useFakeTimers();
    const spy = respond(503);
    vi.stubGlobal("fetch", spy);
    const ctl = new AbortController();
    const p = fetchCartoKey("/share/tok/map-config", ctl.signal);
    await vi.advanceTimersByTimeAsync(1_000);   // first attempt failed, retry pending
    ctl.abort();
    expect(await p).toBeNull();
    await vi.advanceTimersByTimeAsync(120_000);
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it("never throws", async () => {
    vi.stubGlobal("fetch", respond(401));
    await expect(fetchCartoKey("/web/map-config")).resolves.toBeNull();
  });
});

// ── one chain per page session ───────────────────────────────────────────────────

describe("onceForSession", () => {
  it("fetches once and shares the answer with concurrent and later callers", async () => {
    const fetchKey = vi.fn(async () => KEY);
    const loader = onceForSession(fetchKey);
    expect(loader.current()).toBeUndefined();  // pending: maps hold their tiles briefly
    const [a, b] = await Promise.all([loader.load(), loader.load()]);
    expect(await loader.load()).toBe(KEY);
    expect([a, b]).toEqual([KEY, KEY]);
    expect(loader.current()).toBe(KEY);
    expect(fetchKey).toHaveBeenCalledTimes(1);
  });

  it("settles to null on failure and does not start a second chain", async () => {
    const fetchKey = vi.fn(async (): Promise<string | null> => { throw new Error("boom"); });
    const loader = onceForSession(fetchKey);
    expect(await loader.load()).toBeNull();
    expect(await loader.load()).toBeNull();
    expect(loader.current()).toBeNull();
    expect(fetchKey).toHaveBeenCalledTimes(1);
  });
});
