import { describe, expect, it } from "vitest";
import type { DeviceRow } from "./types";
import {
  backdropDismisses, lastSeenMs, restoreDeviceConfirm, revokeApiKeyConfirm, revokeDeviceConfirm,
  revokeShareConfirm,
} from "./adminConfirm";

const NOW = Date.parse("2026-10-03T12:00:00Z");
const dev = (o: Partial<DeviceRow> = {}): DeviceRow => ({
  id: "d1", install_uuid: "inst-1", label: "Pixel 6", last_seen_at: null, revoked: false, ...o,
});
const iso = (ms: number) => new Date(ms).toISOString();

describe("revokeDeviceConfirm (WEB-21)", () => {
  it("names the device by its label, else its install id", () => {
    expect(revokeDeviceConfirm(dev(), [dev()], NOW)).toMatch(/^Revoke "Pixel 6"\?/);
    expect(revokeDeviceConfirm(dev({ label: null }), [dev()], NOW)).toMatch(/^Revoke "inst-1"\?/);
    expect(revokeDeviceConfirm(dev({ label: "  " }), [dev()], NOW)).toMatch(/^Revoke "inst-1"\?/);
  });

  it("warns when the device uploaded recently: that is the phone on the chair", () => {
    const d = dev({ last_seen_at: iso(NOW - 2 * 60_000) });
    expect(revokeDeviceConfirm(d, [d], NOW)).toContain("last uploaded 2m ago, so this looks like the phone on the chair");
    const old = dev({ last_seen_at: iso(NOW - 3 * 3_600_000) });
    expect(revokeDeviceConfirm(old, [old], NOW)).not.toContain("phone on the chair");
  });

  it("says everything stops when it is the only active device, and just its uploads otherwise", () => {
    const d = dev();
    expect(revokeDeviceConfirm(d, [d, dev({ id: "d2", revoked: true })], NOW))
      .toContain("only active device: the dashboard, shared location links and desktop widgets stop updating");
    expect(revokeDeviceConfirm(d, [d, dev({ id: "d2" })], NOW)).toContain("It stops uploading telemetry at once.");
  });

  it("always says how to undo it", () => {
    expect(revokeDeviceConfirm(dev(), [dev()], NOW)).toContain("Restore in Settings › Devices");
  });
});

describe("restoreDeviceConfirm", () => {
  it("names the device and says it uploads again without re-enrolling", () => {
    const t = restoreDeviceConfirm(dev({ label: null }));
    expect(t).toMatch(/^Restore "inst-1"\?/);
    expect(t).toContain("No re-enrollment is needed.");
  });
});

describe("lastSeenMs", () => {
  it("parses ISO timestamps; missing or garbage reads as null", () => {
    expect(lastSeenMs(dev({ last_seen_at: "2026-10-03T11:58:00+00:00" }))).toBe(NOW - 2 * 60_000);
    expect(lastSeenMs(dev({ last_seen_at: null }))).toBeNull();
    expect(lastSeenMs(dev({ last_seen_at: "yesterday-ish" }))).toBeNull();
  });
});

describe("revokeShareConfirm / revokeApiKeyConfirm", () => {
  it("names the guest and says the link can't be brought back", () => {
    const t = revokeShareConfirm("Dave");
    expect(t).toMatch(/^Revoke the live-location link for "Dave"\?/);
    expect(t).toContain("can't be undone");
  });

  it("names the key and says what stops updating", () => {
    const t = revokeApiKeyConfirm("desktop widgets");
    expect(t).toMatch(/^Revoke the API key "desktop widgets"\?/);
    expect(t).toContain("desktop widgets, stops updating");
  });
});

describe("backdropDismisses", () => {
  it("lets the backdrop close the dialog only before a link exists", () => {
    expect(backdropDismisses(null)).toBe(true);
    expect(backdropDismisses("https://bmsmon.example/share/abc")).toBe(false);
  });
});
