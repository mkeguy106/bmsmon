import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { FleetItem, Sample } from "./types";
import { connectLive } from "./ws";

const STALE_MS = 60_000;
const RECONNECT_MS = 1_500;

// Minimal mock WebSocket. Instances are recorded so tests can assert exactly
// how many sockets were constructed (the WEB-2 leak symptom is an extra one).
class MockWebSocket {
  static CONNECTING = 0;
  static OPEN = 1;
  static CLOSING = 2;
  static CLOSED = 3;
  static instances: MockWebSocket[] = [];

  url: string;
  readyState = MockWebSocket.CONNECTING;
  onopen: (() => void) | null = null;
  onmessage: ((e: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;
  closeCalls = 0;

  constructor(url: string) {
    this.url = url;
    MockWebSocket.instances.push(this);
  }

  close() {
    this.closeCalls++;
    this.readyState = MockWebSocket.CLOSING;
    // Like the browser, close() does NOT fire onclose synchronously; tests
    // fire it explicitly via fireClose() to model the async close event.
  }

  // -- test helpers ---------------------------------------------------------
  fireOpen() { this.readyState = MockWebSocket.OPEN; this.onopen?.(); }
  fireClose() { this.readyState = MockWebSocket.CLOSED; this.onclose?.(); }
  fireMessage(msg: unknown) { this.onmessage?.({ data: JSON.stringify(msg) }); }
}

let visibilityHandler: (() => void) | null = null;
let now = 0;

// Advance both fake performance.now() and the fake timer queue together.
const advance = (ms: number) => { now += ms; vi.advanceTimersByTime(ms); };

const sockets = () => MockWebSocket.instances;
const lastSocket = () => MockWebSocket.instances[MockWebSocket.instances.length - 1];

function setup() {
  const snapshots: FleetItem[][] = [];
  const samples: Sample[] = [];
  const statuses: boolean[] = [];
  const stop = connectLive(
    (f) => snapshots.push(f),
    (s) => samples.push(s),
    (c) => statuses.push(c),
  );
  return { snapshots, samples, statuses, stop };
}

beforeEach(() => {
  vi.useFakeTimers();
  now = 0;
  MockWebSocket.instances = [];
  visibilityHandler = null;
  vi.stubGlobal("WebSocket", MockWebSocket);
  vi.stubGlobal("location", { protocol: "http:", host: "test.local" });
  vi.stubGlobal("performance", { now: () => now });
  vi.stubGlobal("document", {
    visibilityState: "visible",
    addEventListener: (type: string, fn: () => void) => {
      if (type === "visibilitychange") visibilityHandler = fn;
    },
    removeEventListener: (type: string) => {
      if (type === "visibilitychange") visibilityHandler = null;
    },
  });
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("connectLive", () => {
  it("connects, dispatches messages, and reports status", () => {
    const { snapshots, samples, statuses, stop } = setup();
    expect(sockets()).toHaveLength(1);
    expect(sockets()[0].url).toBe("ws://test.local/ws");

    sockets()[0].fireOpen();
    expect(statuses).toEqual([true]);

    sockets()[0].fireMessage({ type: "snapshot", fleet: [{ address: "A", ts_ms: 1, soc: 50 }] });
    expect(snapshots).toEqual([[{ address: "A", ts_ms: 1, soc: 50 }]]);

    sockets()[0].fireMessage({ type: "sample", address: "A", ts_ms: 2, soc: 51 });
    expect(samples).toEqual([{ address: "A", ts_ms: 2, soc: 51 }]); // "type" stripped

    sockets()[0].fireMessage({ type: "ping" }); // keepalive: no dispatch
    expect(snapshots).toHaveLength(1);
    expect(samples).toHaveLength(1);
    stop();
  });

  it("reconnects after the socket drops", () => {
    const { statuses, samples, stop } = setup();
    sockets()[0].fireOpen();
    sockets()[0].fireClose();
    expect(statuses).toEqual([true, false]);
    expect(sockets()).toHaveLength(1); // reconnect is scheduled, not immediate

    advance(RECONNECT_MS);
    expect(sockets()).toHaveLength(2);
    sockets()[1].fireOpen();
    expect(statuses).toEqual([true, false, true]);

    sockets()[1].fireMessage({ type: "sample", address: "A", ts_ms: 3, soc: 49 });
    expect(samples).toEqual([{ address: "A", ts_ms: 3, soc: 49 }]);
    stop();
  });

  it("visibility recovery does not leak an extra socket when the old onclose fires late (WEB-2)", () => {
    const { statuses, samples, stop } = setup();
    const old = sockets()[0];
    old.fireOpen();
    // Capture the handlers as they are when the close/message events are
    // already in flight (a real socket's queued events keep their callbacks).
    const oldClose = old.onclose;
    const oldMessage = old.onmessage;

    // Frozen tab: no messages (not even pings) for > STALE_MS, then refocus.
    advance(STALE_MS + 1);
    expect(visibilityHandler).not.toBeNull();
    visibilityHandler!();

    // Recovery closed the stale socket and opened exactly one replacement.
    expect(old.closeCalls).toBeGreaterThanOrEqual(1);
    expect(sockets()).toHaveLength(2);
    const fresh = lastSocket();
    fresh.fireOpen();
    const statusCount = statuses.length;

    // THE BUG: the OLD socket's onclose fires asynchronously, after the new
    // socket is already healthy. It must not flicker status or schedule a
    // reconnect that would spawn (and orphan) a third socket.
    old.readyState = MockWebSocket.CLOSED;
    oldClose?.();
    expect(statuses.length).toBe(statusCount); // no RECONNECTING flicker
    advance(RECONNECT_MS * 2);
    expect(sockets()).toHaveLength(2); // no extra socket constructed

    // The fresh socket is still the live one and keeps dispatching.
    fresh.fireMessage({ type: "sample", address: "A", ts_ms: 10, soc: 80 });
    expect(samples).toEqual([{ address: "A", ts_ms: 10, soc: 80 }]);

    // And the stale socket's in-flight onmessage must not dispatch.
    oldMessage?.({ data: JSON.stringify({ type: "sample", address: "A", ts_ms: 11, soc: 1 }) });
    expect(samples).toHaveLength(1);
    stop();
  });

  it("a replaced socket's onmessage does not dispatch (no duplicate stream)", () => {
    const { samples, stop } = setup();
    const first = sockets()[0];
    first.fireOpen();
    const firstMessage = first.onmessage; // in-flight callback reference

    // Normal drop → scheduled reconnect → replacement socket.
    first.fireClose();
    advance(RECONNECT_MS);
    expect(sockets()).toHaveLength(2);
    const second = sockets()[1];
    second.fireOpen();

    second.fireMessage({ type: "sample", address: "A", ts_ms: 20, soc: 70 });
    expect(samples).toEqual([{ address: "A", ts_ms: 20, soc: 70 }]);

    // A late message on the replaced socket must be ignored, not double-fed.
    firstMessage?.({ data: JSON.stringify({ type: "sample", address: "A", ts_ms: 21, soc: 2 }) });
    expect(samples).toHaveLength(1);
    stop();
  });

  it("malformed frames and garbage payloads are dropped without killing the socket (WEB-4)", () => {
    const { snapshots, samples, stop } = setup();
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    const sock = sockets()[0];
    sock.fireOpen();

    sock.onmessage?.({ data: "{not json" });                            // unparseable frame
    sock.fireMessage(42);                                               // non-object frame
    sock.fireMessage({ type: "sample", ts_ms: 5, soc: 10 });            // missing address
    sock.fireMessage({ type: "sample", address: "A", ts_ms: "x" });     // non-finite ts_ms
    sock.fireMessage({ type: "snapshot", fleet: { address: "A" } });    // fleet not an array
    expect(samples).toHaveLength(0);
    expect(snapshots).toHaveLength(0);

    // The socket survived and still dispatches good frames (unknown keys stripped).
    sock.fireMessage({ type: "sample", address: "A", ts_ms: 2, soc: 51, bogus: 1 });
    expect(samples).toEqual([{ address: "A", ts_ms: 2, soc: 51 }]);
    sock.fireMessage({ type: "snapshot", fleet: [{ address: "A", ts_ms: 3, soc: 52, alias: "2012 · A" }] });
    expect(snapshots).toEqual([[{ address: "A", ts_ms: 3, soc: 52, alias: "2012 · A" }]]);

    expect(warn).toHaveBeenCalled();
    warn.mockRestore();
    stop();
  });

  it("stop() closes the socket and prevents any further reconnects", () => {
    const { statuses, stop } = setup();
    const sock = sockets()[0];
    sock.fireOpen();
    stop();
    expect(sock.closeCalls).toBe(1);
    sock.fireClose(); // late close event after teardown
    expect(statuses).toEqual([true]); // no status flicker after stop
    advance(RECONNECT_MS * 2);
    expect(sockets()).toHaveLength(1); // nothing reconnected
  });
});
