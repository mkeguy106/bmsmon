import { describe, expect, it } from "vitest";
import { fmtEta, relAgo } from "./util";

describe("relAgo", () => {
  const now = 1_000_000_000;
  it("sub-45s reads as just now", () => {
    expect(relAgo(now, now)).toBe("just now");
    expect(relAgo(now - 44_000, now)).toBe("just now");
  });
  it("minutes, hours, days", () => {
    expect(relAgo(now - 45_000, now)).toBe("1m ago");
    expect(relAgo(now - 5 * 60_000, now)).toBe("5m ago");
    expect(relAgo(now - 2 * 3_600_000, now)).toBe("2h ago");
    expect(relAgo(now - 3 * 86_400_000, now)).toBe("3d ago");
  });
  it("a future timestamp clamps to just now", () => {
    expect(relAgo(now + 60_000, now)).toBe("just now");
  });
});

describe("fmtEta", () => {
  it("formats hours and minutes", () => {
    expect(fmtEta(134)).toBe("2h 14m");
    expect(fmtEta(45)).toBe("45m");
    expect(fmtEta(-3)).toBe("0m");
  });
});
