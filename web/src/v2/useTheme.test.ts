import { describe, expect, it } from "vitest";
import { resolveTheme } from "./useTheme";

describe("resolveTheme", () => {
  it("honors explicit light/dark", () => {
    expect(resolveTheme("light", true)).toBe("light");
    expect(resolveTheme("dark", false)).toBe("dark");
  });
  it("follows OS preference in system mode", () => {
    expect(resolveTheme("system", true)).toBe("dark");
    expect(resolveTheme("system", false)).toBe("light");
  });
});
