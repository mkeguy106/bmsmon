import { describe, expect, it } from "vitest";
import {
  API_KEY_CREATE_ERROR, NOTE_MAX_CHARS, NOTE_STATUS, SHARE_CREATE_ERROR, failureKind, noteSaveBlocked,
} from "./formErrors";

// Final review I3: the server refuses content it will never store with a 422 (a note over 4000
// characters, a NUL byte, a share or key name outside 1–80 characters). Retrying fails the same
// way, so a 422 must read as a validation message, never "retry" or "check the connection".
describe("failureKind", () => {
  it("reads api.ts's status errors", () => {
    expect(failureKind(new Error("422"))).toBe("refused");
    expect(failureKind(new Error("401"))).toBe("auth");
    expect(failureKind(new Error("403"))).toBe("auth");
    expect(failureKind(new Error("500"))).toBe("net");
    expect(failureKind(new TypeError("Failed to fetch"))).toBe("net");
    expect(failureKind("nope")).toBe("net");
  });
});

describe("validation copy", () => {
  it("a refused share or key name says what to change, not to check the connection", () => {
    for (const copy of [SHARE_CREATE_ERROR.refused, API_KEY_CREATE_ERROR.refused]) {
      expect(copy).toMatch(/1 to 80 characters/);
      expect(copy).not.toMatch(/connection|try again/i);
    }
    expect(SHARE_CREATE_ERROR.net).toMatch(/connection/);
    expect(API_KEY_CREATE_ERROR.net).toMatch(/connection/);
  });

  it("a refused note says it can't be stored, never retry", () => {
    expect(NOTE_STATUS.refused).toMatch(/too long|characters/);
    expect(NOTE_STATUS.refused).not.toMatch(/retry/i);
    expect(NOTE_STATUS.failed).toMatch(/retry/);
  });
});

describe("noteSaveBlocked", () => {
  it("blocks a note over the server's 4000-character cap, and only that", () => {
    expect(NOTE_MAX_CHARS).toBe(4000);
    expect(noteSaveBlocked("x".repeat(4000))).toBe(false);
    expect(noteSaveBlocked("x".repeat(4001))).toBe(true);
  });
});
