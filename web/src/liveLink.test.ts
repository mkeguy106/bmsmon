import { afterEach, describe, expect, it, vi } from "vitest";
import {
  RECONNECT_MAX_MS, probeSession, reconnectDelayMs, sessionFromClose, sessionFromProbe,
} from "./liveLink";

afterEach(() => { vi.unstubAllGlobals(); });

describe("reconnectDelayMs", () => {
  it("waits 0.75–1.5 s on the first step", () => {
    expect(reconnectDelayMs(0, 0)).toBe(750);
    expect(reconnectDelayMs(0, 1)).toBe(1_500);
  });

  it("doubles per failed attempt and caps at 60 s", () => {
    expect(reconnectDelayMs(1, 1)).toBe(3_000);
    expect(reconnectDelayMs(3, 1)).toBe(12_000);
    expect(reconnectDelayMs(10, 1)).toBe(RECONNECT_MAX_MS);
    expect(reconnectDelayMs(10, 0)).toBe(RECONNECT_MAX_MS / 2);
    expect(reconnectDelayMs(5_000, 1)).toBe(RECONNECT_MAX_MS);
  });

  it("treats a garbage attempt as the first and clamps the jitter", () => {
    expect(reconnectDelayMs(Number.NaN, 1)).toBe(1_500);
    expect(reconnectDelayMs(-4, 1)).toBe(1_500);
    expect(reconnectDelayMs(0, 7)).toBe(1_500);
    expect(reconnectDelayMs(0, -1)).toBe(750);
  });
});

describe("sessionFromClose", () => {
  it("maps the server's auth close codes, and nothing else", () => {
    expect(sessionFromClose(4401)).toBe("expired");
    expect(sessionFromClose(4403)).toBe("forbidden");
    expect(sessionFromClose(1006)).toBeNull();
    expect(sessionFromClose(1000)).toBeNull();
    expect(sessionFromClose(1011)).toBeNull(); // server error (database unavailable): transient
  });
});

describe("sessionFromProbe", () => {
  it("reads a sign-in redirect or a 401 as expired, and a 403 as forbidden", () => {
    expect(sessionFromProbe({ type: "opaqueredirect", status: 0 })).toBe("expired");
    expect(sessionFromProbe({ type: "basic", status: 401 })).toBe("expired");
    expect(sessionFromProbe({ type: "basic", status: 403 })).toBe("forbidden");
  });

  it("reads 2xx as ok, and any other answer or none as unreachable", () => {
    expect(sessionFromProbe({ type: "basic", status: 200 })).toBe("ok");
    expect(sessionFromProbe({ type: "basic", status: 404 })).toBe("unreachable");
    expect(sessionFromProbe({ type: "basic", status: 502 })).toBe("unreachable");
    expect(sessionFromProbe("network-error")).toBe("unreachable");
  });
});

describe("probeSession", () => {
  it("GETs /web/alert-config without following redirects", async () => {
    const f = vi.fn(async () => new Response(null, { status: 401 }));
    vi.stubGlobal("fetch", f);
    await expect(probeSession()).resolves.toEqual({ type: "default", status: 401 });
    expect(f).toHaveBeenCalledWith("/web/alert-config",
      expect.objectContaining({ redirect: "manual", cache: "no-store" }));
  });

  it("returns network-error when the fetch rejects", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => { throw new TypeError("Failed to fetch"); }));
    await expect(probeSession()).resolves.toBe("network-error");
  });
});
