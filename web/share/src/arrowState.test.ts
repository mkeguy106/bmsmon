import { describe, expect, it } from "vitest";
import { arrowMessage, arrowState } from "./arrowState";

const s = (o: Partial<Parameters<typeof arrowState>[0]>) =>
  arrowState({ on: true, denied: false, hasGuest: true, hasTarget: true, ...o });

describe("arrowState", () => {
  it("is idle until the guest taps Point me there", () => {
    expect(s({ on: false, hasGuest: false, hasTarget: false })).toBe("idle");
  });
  it("reports a denied permission before anything else", () => {
    expect(s({ denied: true, hasTarget: false })).toBe("denied");
  });
  // WEB-20: these two were both "Locating…".
  it("says the CHAIR has no fix, not that it is locating the guest", () => {
    expect(s({ hasTarget: false, hasGuest: true })).toBe("no-chair");
    expect(s({ hasTarget: false, hasGuest: false })).toBe("no-chair");
  });
  it("is locating only when the chair fix exists and the guest's doesn't yet", () => {
    expect(s({ hasGuest: false })).toBe("locating");
  });
  it("is ready with both fixes", () => {
    expect(s({})).toBe("ready");
  });
});

describe("arrowMessage", () => {
  it("names who is being waited on", () => {
    expect(arrowMessage("no-chair", false)).toBe("No recent location from the chair — nothing to point to yet.");
    expect(arrowMessage("locating", false)).toBe("Locating you…");
    expect(arrowMessage("locating", true)).toBe("Locating you… (waiting for GPS)");
  });
  it("is empty for the states that render their own UI", () => {
    expect(arrowMessage("idle", false)).toBe("");
    expect(arrowMessage("ready", false)).toBe("");
  });
});
