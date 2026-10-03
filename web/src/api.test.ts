import { afterEach, describe, expect, it, vi } from "vitest";
import { restoreDevice } from "./api";

const respond = (status: number, body: unknown) =>
  vi.fn(async () => new Response(JSON.stringify(body), {
    status, headers: { "Content-Type": "application/json" },
  }));

afterEach(() => { vi.unstubAllGlobals(); });

describe("restoreDevice (POST /web/devices/{id}/restore)", () => {
  it("POSTs to the encoded restore path and resolves on {restored: id}", async () => {
    const f = respond(200, { restored: "a/b" });
    vi.stubGlobal("fetch", f);
    await expect(restoreDevice("a/b")).resolves.toBeUndefined();
    expect(f).toHaveBeenCalledWith("/web/devices/a%2Fb/restore", { method: "POST" });
  });

  it("rejects with the HTTP status, e.g. 404 for an unknown device", async () => {
    vi.stubGlobal("fetch", respond(404, { detail: "device not found" }));
    await expect(restoreDevice("x")).rejects.toThrow("404");
  });

  it("rejects a body that does not confirm this device", async () => {
    vi.stubGlobal("fetch", respond(200, { restored: "someone-else" }));
    await expect(restoreDevice("x")).rejects.toThrow(/malformed/);
  });
});
