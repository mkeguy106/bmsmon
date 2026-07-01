import { describe, expect, it } from "vitest";
import { fmtEta } from "./util";

describe("fmtEta", () => {
  it("formats hours and minutes", () => {
    expect(fmtEta(134)).toBe("2h 14m");
    expect(fmtEta(45)).toBe("45m");
    expect(fmtEta(-3)).toBe("0m");
  });
});
