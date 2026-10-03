import { describe, expect, it } from "vitest";
import { connectionBanner } from "./connection";

describe("connectionBanner", () => {
  it("shows nothing while the session is ok", () => {
    expect(connectionBanner("ok")).toBeNull();
  });

  it("asks for a reload when the session expired or is not allowed, and says the data is last known", () => {
    for (const s of ["expired", "forbidden"] as const) {
      const b = connectionBanner(s)!;
      expect(b.tone).toBe("crit");
      expect(b.reload).toBe(true);
      expect(b.detail).toMatch(/last known/);
    }
    expect(connectionBanner("expired")!.title).toBe("Session expired");
  });

  it("warns without a reload while the server is unreachable", () => {
    const b = connectionBanner("unreachable")!;
    expect(b.tone).toBe("warn");
    expect(b.reload).toBe(false);
    expect(b.detail).toMatch(/last known/);
  });
});
