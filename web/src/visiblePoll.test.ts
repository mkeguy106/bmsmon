import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { visibleInterval, type VisibilityDoc } from "./visiblePoll";

class FakeDoc implements VisibilityDoc {
  hidden = false;
  private listeners: (() => void)[] = [];
  addEventListener(_type: "visibilitychange", fn: () => void) { this.listeners.push(fn); }
  removeEventListener(_type: "visibilitychange", fn: () => void) {
    this.listeners = this.listeners.filter((f) => f !== fn);
  }
  setHidden(h: boolean) { this.hidden = h; for (const f of [...this.listeners]) f(); }
  get listenerCount() { return this.listeners.length; }
}

describe("visibleInterval", () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); });

  it("fires on every interval tick while visible", () => {
    const doc = new FakeDoc();
    const fn = vi.fn();
    const stop = visibleInterval(fn, 1000, doc);
    vi.advanceTimersByTime(3000);
    expect(fn).toHaveBeenCalledTimes(3);
    stop();
  });

  it("skips ticks entirely while hidden", () => {
    const doc = new FakeDoc();
    const fn = vi.fn();
    const stop = visibleInterval(fn, 1000, doc);
    doc.setHidden(true);
    vi.advanceTimersByTime(5000);
    expect(fn).not.toHaveBeenCalled();
    stop();
  });

  it("fires immediately on hidden→visible when at least one interval elapsed", () => {
    const doc = new FakeDoc();
    const fn = vi.fn();
    const stop = visibleInterval(fn, 1000, doc);
    doc.setHidden(true);
    vi.advanceTimersByTime(2500);
    expect(fn).not.toHaveBeenCalled();
    doc.setHidden(false); // visibilitychange → visible
    expect(fn).toHaveBeenCalledTimes(1);
    stop();
  });

  it("does NOT double-fire on a quick tab flip (less than one interval hidden)", () => {
    const doc = new FakeDoc();
    const fn = vi.fn();
    const stop = visibleInterval(fn, 1000, doc);
    vi.advanceTimersByTime(1000); // 1 tick fired
    doc.setHidden(true);
    vi.advanceTimersByTime(300);
    doc.setHidden(false);
    expect(fn).toHaveBeenCalledTimes(1); // no immediate extra fire
    vi.advanceTimersByTime(700); // the regular tick still lands on schedule
    expect(fn).toHaveBeenCalledTimes(2);
    stop();
  });

  it("resumes the regular cadence after the catch-up fire", () => {
    const doc = new FakeDoc();
    const fn = vi.fn();
    const stop = visibleInterval(fn, 1000, doc);
    doc.setHidden(true);
    vi.advanceTimersByTime(3000);
    doc.setHidden(false);
    expect(fn).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(1000);
    expect(fn).toHaveBeenCalledTimes(2);
    stop();
  });

  it("cleanup stops the timer and removes the visibility listener", () => {
    const doc = new FakeDoc();
    const fn = vi.fn();
    const stop = visibleInterval(fn, 1000, doc);
    expect(doc.listenerCount).toBe(1);
    stop();
    expect(doc.listenerCount).toBe(0);
    vi.advanceTimersByTime(5000);
    doc.setHidden(true);
    doc.setHidden(false);
    expect(fn).not.toHaveBeenCalled();
  });
});
