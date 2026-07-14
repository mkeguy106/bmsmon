import { describe, expect, it } from "vitest";
import type { ShareRow } from "../../api";
import { lastOpened, remainingShort, shareStatus } from "./shares";

const row = (over: Partial<ShareRow>): ShareRow => ({
  id: 1, name: "Dave", created_at: 0, expires_at: 1_000_000,
  revoked_at: null, last_access_ms: null, access_count: 0, ...over,
});

describe("shares model", () => {
  it("status: revoked beats expired beats active", () => {
    expect(shareStatus(row({ revoked_at: 5 }), 10)).toBe("revoked");
    expect(shareStatus(row({}), 999_999)).toBe("active");
    expect(shareStatus(row({}), 1_000_000)).toBe("expired");
  });

  it("remainingShort: d / h / m granularity", () => {
    expect(remainingShort(0, 1)).toBe("expired");
    expect(remainingShort(3 * 86_400_000, 0)).toBe("3d left");
    expect(remainingShort(5 * 3_600_000, 0)).toBe("5h left");
    expect(remainingShort(90_000, 0)).toBe("1m left");
  });

  it("lastOpened: never / m / h / d", () => {
    expect(lastOpened(row({}), 0)).toBe("never opened");
    expect(lastOpened(row({ last_access_ms: 0 }), 30_000)).toBe("just now");
    expect(lastOpened(row({ last_access_ms: 0 }), 5 * 60_000)).toBe("5m ago");
    expect(lastOpened(row({ last_access_ms: 0 }), 3 * 3_600_000)).toBe("3h ago");
    expect(lastOpened(row({ last_access_ms: 0 }), 2 * 86_400_000)).toBe("2d ago");
  });
});
