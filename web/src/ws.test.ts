import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { FleetItem, Sample } from "./types";
import type { ProbeResult, Session } from "./liveLink";
import { connectLive } from "./ws";

const STALE_MS = 60_000;
/** reconnectDelayMs(0, 1): the first backoff step, with the jitter pinned at its top. */
const RECONNECT_MS = 1_500;
const SNAPSHOT = { type: "snapshot", fleet: [{ address: "A", ts_ms: 1, soc: 50 }] };

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
  onclose: ((e?: { code: number }) => void) | null = null;
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
  /** 1006 (abnormal closure) is what a browser reports for a dropped or refused socket. */
  fireClose(code = 1006) { this.readyState = MockWebSocket.CLOSED; this.onclose?.({ code }); }
  fireMessage(msg: unknown) { this.onmessage?.({ data: JSON.stringify(msg) }); }
  /** open + first snapshot: a healthy connection. */
  goLive() { this.fireOpen(); this.fireMessage(SNAPSHOT); }
}

let visibilityHandler: (() => void) | null = null;
let now = 0;

// Advance both fake performance.now() and the fake timer queue together.
const advance = (ms: number) => { now += ms; vi.advanceTimersByTime(ms); };
// Let resolved probe promises run their .then callbacks.
const flush = async () => { for (let i = 0; i < 5; i++) await Promise.resolve(); };

const sockets = () => MockWebSocket.instances;
const lastSocket = () => MockWebSocket.instances[MockWebSocket.instances.length - 1];

function setup(probe: () => Promise<ProbeResult> = () => Promise.resolve({ type: "basic", status: 200 })) {
  const snapshots: FleetItem[][] = [];
  const samples: Sample[] = [];
  const statuses: boolean[] = [];
  const sessions: Session[] = [];
  const probeSpy = vi.fn(probe);
  const stop = connectLive(
    (f) => snapshots.push(f),
    (s) => samples.push(s),
    (c) => statuses.push(c),
    { onSession: (s) => sessions.push(s), probe: probeSpy, random: () => 1 },
  );
  return { snapshots, samples, statuses, sessions, probe: probeSpy, stop };
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
  it("reports live on the first snapshot, not on open, and dispatches messages (WEB-26)", () => {
    const { snapshots, samples, statuses, stop } = setup();
    expect(sockets()).toHaveLength(1);
    expect(sockets()[0].url).toBe("ws://test.local/ws");

    sockets()[0].fireOpen();
    expect(statuses).toEqual([]); // accepted, but nothing delivered yet

    sockets()[0].fireMessage(SNAPSHOT);
    expect(statuses).toEqual([true]);
    expect(snapshots).toEqual([[{ address: "A", ts_ms: 1, soc: 50 }]]);
    sockets()[0].fireMessage(SNAPSHOT);
    expect(statuses).toEqual([true]); // once per connection

    sockets()[0].fireMessage({ type: "sample", address: "A", ts_ms: 2, soc: 51 });
    expect(samples).toEqual([{ address: "A", ts_ms: 2, soc: 51 }]); // "type" stripped

    sockets()[0].fireMessage({ type: "ping" }); // keepalive: no dispatch
    expect(snapshots).toHaveLength(2);
    expect(samples).toHaveLength(1);
    stop();
  });

  it("reconnects on the first backoff step after a healthy socket drops", () => {
    const { statuses, samples, stop } = setup();
    sockets()[0].goLive();
    sockets()[0].fireClose();
    expect(statuses).toEqual([true, false]);
    advance(RECONNECT_MS - 1);
    expect(sockets()).toHaveLength(1); // reconnect is scheduled, not immediate
    advance(1);
    expect(sockets()).toHaveLength(2);
    sockets()[1].goLive();
    expect(statuses).toEqual([true, false, true]);

    sockets()[1].fireMessage({ type: "sample", address: "A", ts_ms: 3, soc: 49 });
    expect(samples).toEqual([{ address: "A", ts_ms: 3, soc: 49 }]);
    stop();
  });

  it("backs off exponentially while sockets keep failing, caps at 60 s, and resets on a snapshot", () => {
    const { statuses, stop } = setup();
    // Each socket closes without delivering anything (random pinned to 1: the top of each step).
    for (const [i, wait] of [3_000, 6_000, 12_000, 24_000, 48_000, 60_000, 60_000].entries()) {
      lastSocket().fireClose();
      advance(wait - 1);
      expect(sockets()).toHaveLength(i + 1);
      advance(1);
      expect(sockets()).toHaveLength(i + 2);
    }
    expect(statuses).toEqual([]); // nothing was ever reported live
    lastSocket().goLive(); // a snapshot resets the backoff
    lastSocket().fireClose();
    advance(RECONNECT_MS);
    expect(sockets()).toHaveLength(9);
    stop();
  });

  // Review Focus: the server accepts, then closes 4401 at once (no identity). Not live, and
  // the verdict is immediate rather than waiting out the backoff for a probe.
  it("reads a 4401 close as an expired session at once, never live, with no probe", () => {
    const { statuses, sessions, probe, stop } = setup();
    sockets()[0].fireOpen();
    sockets()[0].fireClose(4401);
    expect(sessions).toEqual(["expired"]);
    expect(statuses).toEqual([]);
    expect(probe).not.toHaveBeenCalled();
    stop();
  });

  it("treats a 1011 close (server error, database unavailable) as transient: backs off, never expired", async () => {
    const { sessions, probe, statuses, stop } = setup(() => Promise.resolve({ type: "basic", status: 503 }));
    sockets()[0].fireOpen();
    sockets()[0].fireClose(1011);
    expect(sessions).toEqual([]);
    advance(3_000);
    expect(sockets()).toHaveLength(2); // normal backoff step
    lastSocket().fireClose(1011);
    advance(6_000);
    lastSocket().fireClose(1011);
    expect(probe).toHaveBeenCalledTimes(1);
    await flush();
    expect(sessions).toEqual(["unreachable"]); // a 503 probe is never "expired"
    expect(statuses).toEqual([]);
    stop();
  });

  it("reads a 4403 close as signed in but not allowed", () => {
    const { sessions, stop } = setup();
    sockets()[0].fireOpen();
    sockets()[0].fireClose(4403);
    expect(sessions).toEqual(["forbidden"]);
    stop();
  });

  it("probes the session after three sockets in a row deliver nothing", async () => {
    const { sessions, probe, stop } = setup(() => Promise.resolve({ type: "opaqueredirect", status: 0 }));
    sockets()[0].fireClose();
    advance(3_000);
    sockets()[1].fireClose();
    advance(6_000);
    expect(probe).not.toHaveBeenCalled();
    sockets()[2].fireClose();
    expect(probe).toHaveBeenCalledTimes(1);
    await flush();
    expect(sessions).toEqual(["expired"]);
    stop();
  });

  it("reports the session ok again once a socket delivers a snapshot", () => {
    const { sessions, stop } = setup();
    sockets()[0].fireClose(4401);
    advance(3_000);
    sockets()[1].goLive();
    expect(sessions).toEqual(["expired", "ok"]);
    stop();
  });

  it("keeps a single probe in flight", () => {
    const { probe, stop } = setup(() => new Promise<ProbeResult>(() => {}));
    for (const wait of [3_000, 6_000, 12_000, 24_000]) { lastSocket().fireClose(); advance(wait); }
    lastSocket().fireClose();
    expect(probe).toHaveBeenCalledTimes(1);
    stop();
  });

  it("visibility recovery does not leak an extra socket when the old onclose fires late (WEB-2)", () => {
    const { statuses, samples, stop } = setup();
    const old = sockets()[0];
    old.goLive();
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
    fresh.goLive();
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
    first.goLive();
    const firstMessage = first.onmessage; // in-flight callback reference

    // Normal drop → scheduled reconnect → replacement socket.
    first.fireClose();
    advance(RECONNECT_MS);
    expect(sockets()).toHaveLength(2);
    const second = sockets()[1];
    second.goLive();

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
    sock.goLive();
    stop();
    expect(sock.closeCalls).toBe(1);
    sock.fireClose(); // late close event after teardown
    expect(statuses).toEqual([true]); // no status flicker after stop
    advance(RECONNECT_MS * 2);
    expect(sockets()).toHaveLength(1); // nothing reconnected
  });

  // A slow probe must not paint a recovered link as broken: report("ok") fires only on a
  // socket's first snapshot, so a late verdict would otherwise stick while LIVE is lit.
  it("drops a probe verdict that lands after a socket delivered a snapshot", async () => {
    let resolve: (p: ProbeResult) => void = () => {};
    const { sessions, statuses, stop } = setup(() => new Promise<ProbeResult>((r) => { resolve = r; }));
    for (const wait of [3_000, 6_000]) { lastSocket().fireClose(); advance(wait); }
    lastSocket().fireClose(); // third failure: the probe is now in flight
    advance(12_000);
    lastSocket().goLive(); // the link recovers before the probe answers
    expect(statuses).toEqual([true]);
    resolve("network-error");
    await flush();
    expect(sessions).toEqual([]); // never "unreachable" while data flows
    stop();
  });

  it("drops it even when the socket that recovered has closed again since", async () => {
    let resolve: (p: ProbeResult) => void = () => {};
    const { sessions, stop } = setup(() => new Promise<ProbeResult>((r) => { resolve = r; }));
    for (const wait of [3_000, 6_000]) { lastSocket().fireClose(); advance(wait); }
    lastSocket().fireClose();
    advance(12_000);
    lastSocket().goLive();
    lastSocket().fireClose(); // healthy, then dropped: a snapshot still arrived after the probe began
    resolve({ type: "opaqueredirect", status: 0 });
    await flush();
    expect(sessions).toEqual([]);
    stop();
  });

  it("still reports a probe verdict when no snapshot arrived in the meantime", async () => {
    let resolve: (p: ProbeResult) => void = () => {};
    const { sessions, stop } = setup(() => new Promise<ProbeResult>((r) => { resolve = r; }));
    for (const wait of [3_000, 6_000]) { lastSocket().fireClose(); advance(wait); }
    lastSocket().fireClose();
    advance(12_000);
    lastSocket().fireClose(); // the next socket fails too
    resolve("network-error");
    await flush();
    expect(sessions).toEqual(["unreachable"]);
    stop();
  });

  it("stop() drops a session verdict that lands afterwards", async () => {
    let resolve: (p: ProbeResult) => void = () => {};
    const { sessions, stop } = setup(() => new Promise<ProbeResult>((r) => { resolve = r; }));
    for (const wait of [3_000, 6_000]) { lastSocket().fireClose(); advance(wait); }
    lastSocket().fireClose(); // third failure: the probe is now in flight
    stop();
    resolve("network-error");
    await flush();
    expect(sessions).toEqual([]);
  });
});
