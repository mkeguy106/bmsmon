import { describe, expect, it } from "vitest";
import type { DeviceRow } from "./types";
import {
  ACTIVE_WINDOW_MS, backdropDismisses, lastSeenMs, restoreDeviceConfirm, revokeApiKeyConfirm, revokeDeviceConfirm,
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

  // Task 10 carry: say outright that the chair stops uploading, then what that stops.
  it("says everything stops when it is the only active device, and just its uploads otherwise", () => {
    const d = dev();
    const only = revokeDeviceConfirm(d, [d, dev({ id: "d2", revoked: true })], NOW);
    expect(only).toContain("only active device: all uploads from the chair stop until it is restored");
    expect(only).toContain("the dashboard, shared location links and desktop widgets stop updating");
    const seen = iso(NOW - 3_600_000);
    expect(revokeDeviceConfirm(d, [d, dev({ id: "d2", last_seen_at: seen })], NOW))
      .toContain("It stops uploading telemetry at once.");
  });

  // Final review minor 4: a dormant, never-revoked enrollment is not "another active device".
  it("does not count a dormant or never-seen device as another active one", () => {
    const d = dev();
    const dormant = dev({ id: "d2", last_seen_at: iso(NOW - ACTIVE_WINDOW_MS - 1) });
    const never = dev({ id: "d3", last_seen_at: null });
    const t = revokeDeviceConfirm(d, [d, dormant, never], NOW);
    expect(t).toContain("only active device");
    const edge = dev({ id: "d4", last_seen_at: iso(NOW - ACTIVE_WINDOW_MS) });
    expect(revokeDeviceConfirm(d, [d, dormant, edge], NOW)).not.toContain("only active device");
  });

  it("omits the Restore line where there is no Restore (v1)", () => {
    expect(revokeDeviceConfirm(dev(), [dev()], NOW, false)).not.toContain("Restore");
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

  // Final review minor 5: the link is shown once, so no dismissal while it is being created.
  it("does not close while the create request is in flight", () => {
    expect(backdropDismisses(null, true)).toBe(false);
    expect(backdropDismisses(null, false)).toBe(true);
  });
});
