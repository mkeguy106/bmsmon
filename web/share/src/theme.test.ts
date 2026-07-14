import { describe, expect, it } from "vitest";
import { loadShareTheme, saveShareTheme } from "./theme";

const fakeStorage = (init: Record<string, string> = {}) => {
  const m = new Map(Object.entries(init));
  return {
    getItem: (k: string) => m.get(k) ?? null,
    setItem: (k: string, v: string) => void m.set(k, v),
  };
};

describe("share theme persistence", () => {
  it("defaults to light with nothing stored or junk stored", () => {
    expect(loadShareTheme(fakeStorage())).toBe("light");
    expect(loadShareTheme(fakeStorage({ "bmsmon-share-theme": "purple" }))).toBe("light");
  });

  it("round-trips dark", () => {
    const s = fakeStorage();
    saveShareTheme(s, "dark");
    expect(loadShareTheme(s)).toBe("dark");
    saveShareTheme(s, "light");
    expect(loadShareTheme(s)).toBe("light");
  });

  it("survives a throwing storage (private mode)", () => {
    const throwing = {
      getItem: () => { throw new Error("nope"); },
      setItem: () => { throw new Error("nope"); },
    };
    expect(loadShareTheme(throwing)).toBe("light");
    expect(() => saveShareTheme(throwing, "dark")).not.toThrow();
  });
});
