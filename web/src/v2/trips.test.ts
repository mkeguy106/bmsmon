import { describe, expect, it } from "vitest";
import { classifyTrip, tripsCodec } from "./trips";
import type { PackRange } from "../range";

const r: PackRange = { milesLo: 20, milesHi: 32, activeHLo: 8, activeHHi: 12, wallHLo: 60, wallHHi: 96 };

describe("classifyTrip", () => {
  it("go when within the conservative low bound", () => expect(classifyTrip(15, r)).toBe("go"));
  it("tight between lo and hi", () => expect(classifyTrip(28, r)).toBe("tight"));
  it("no-go beyond the high bound", () => expect(classifyTrip(40, r)).toBe("no-go"));
});

describe("tripsCodec", () => {
  it("round-trips a trip list", () => {
    const trips = [{ id: "1", name: "Clinic", miles: 6.2 }];
    expect(tripsCodec.decode(tripsCodec.encode(trips))).toEqual(trips);
  });
  it("returns null for garbage", () => expect(tripsCodec.decode("{")).toBeNull());
});
