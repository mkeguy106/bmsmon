import { describe, expect, it } from "vitest";
import { DEFAULT_V2_SETTINGS, resolveMobile, settingsCodec } from "./settings";

describe("resolveMobile", () => {
  it("auto-resolves on width when no override", () => {
    expect(resolveMobile(null, 700)).toBe(true);
    expect(resolveMobile(null, 1000)).toBe(false);
  });
  it("honors an explicit override", () => {
    expect(resolveMobile("desktop", 500)).toBe(false);
    expect(resolveMobile("mobile", 2000)).toBe(true);
  });
});

describe("settingsCodec", () => {
  it("round-trips and fills defaults for a partial blob", () => {
    const raw = JSON.stringify({ distUnit: "km" });
    const decoded = settingsCodec.decode(raw)!;
    expect(decoded.distUnit).toBe("km");
    expect(decoded.themeMode).toBe(DEFAULT_V2_SETTINGS.themeMode);
  });
  it("returns null for garbage", () => {
    expect(settingsCodec.decode("not json")).toBeNull();
  });
});
