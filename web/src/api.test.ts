import { afterEach, describe, expect, it, vi } from "vitest";
import { getPhoneAlert, putPhoneAlert, restoreDevice } from "./api";

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

describe("phone alert (GET/PUT /web/phone-alert)", () => {
  const body = { low_pct: 50, updated_at_ms: 5, updated_by: "web" };
  it("GET decodes the threshold", async () => {
    vi.stubGlobal("fetch", respond(200, body));
    await expect(getPhoneAlert()).resolves.toEqual(body);
  });
  it("GET accepts OFF (null)", async () => {
    vi.stubGlobal("fetch", respond(200, { ...body, low_pct: null }));
    await expect(getPhoneAlert()).resolves.toMatchObject({ low_pct: null });
  });
  it("rejects a malformed body", async () => {
    vi.stubGlobal("fetch", respond(200, { low_pct: "x" }));
    await expect(getPhoneAlert()).rejects.toThrow(/malformed/);
  });
  it("PUT sends the JSON body and returns the decoded answer", async () => {
    const f = respond(200, body);
    vi.stubGlobal("fetch", f);
    await expect(putPhoneAlert(50)).resolves.toEqual(body);
    expect(f).toHaveBeenCalledWith("/web/phone-alert", { method: "PUT",
      headers: { "Content-Type": "application/json" }, body: JSON.stringify({ low_pct: 50 }) });
  });
  it("PUT rejects with the status, e.g. 403 for a viewer", async () => {
    vi.stubGlobal("fetch", respond(403, {}));
    await expect(putPhoneAlert(null)).rejects.toThrow("403");
  });
});
