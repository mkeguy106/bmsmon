import { useEffect, useMemo, useRef, useState } from "react";
import { JourneyMap } from "../../src/v2/components/JourneyMap";
import { fetchCartoKey } from "../../src/v2/basemap";
import { appendTrack } from "../../src/v2/model/appendTrack";
import { cleanTrack } from "../../src/v2/model/cleanTrack";
import type { Hotspot } from "../../src/v2/model/journey";
import type { LivePos } from "../../src/v2/model/live";
import type { TrackPoint } from "../../src/v2/track";
import { useNow } from "../../src/useNow";
import { visibleInterval } from "../../src/visiblePoll";
import {
  FEED_POLL_MS, fetchFeed, remainingLabel, tokenFromPath, type Feed, type FeedPoint,
} from "./feed";
import { INITIAL_HEALTH, createFeedPoller, guestView, type BadgeTone, type PollHealth } from "./poll";
import { ArrowPanel } from "./Arrow";
import { Dock } from "./Dock";
import { loadShareTheme, saveShareTheme, type ShareTheme } from "./theme";
import { loadTrailMode, saveTrailMode, trailProps, type TrailMode } from "./trail";

type Status = "loading" | "ok" | "ended" | "expired" | "error";

/** Module-level so the map's trail effect (which lists hotspots as a dependency) does not
 *  rebuild every polyline on each 1 s clock tick, as a fresh `[]` per render would. */
const NO_HOTSPOTS: Hotspot[] = [];
const BADGE_DOT: Record<BadgeTone, string> = {
  ok: "var(--ok)", warn: "var(--warn)", lost: "var(--live)",
};
/** Shown only when the server's 48 h lookback holds no chair fix at all (C5). */
const NO_FIX_TEXT = "No recent location from the chair.";

export default function App() {
  const token = useMemo(() => tokenFromPath(window.location.pathname), []);
  const [feed, setFeed] = useState<Feed | null>(null);
  // The trail is ACCUMULATED across polls (the feed only re-sends new buckets), so it
  // lives outside `feed`. A ref mirrors it so the poll can read the seam bucket without
  // re-running its effect on every data change — same shape as v2's useTrack.
  const [track, setTrack] = useState<TrackPoint[]>([]);
  const trackRef = useRef<TrackPoint[]>(track);
  useEffect(() => { trackRef.current = track; }, [track]);
  const [status, setStatus] = useState<Status>("loading");
  const [health, setHealth] = useState<PollHealth>(INITIAL_HEALTH);
  // Ticking client clock: staleness and CONNECTION LOST must advance while polls are
  // failing, which is exactly when no new data arrives to trigger a render (WEB-13).
  const clientNow = useNow(1_000);
  const [guest, setGuest] = useState<LivePos | null>(null);
  const [theme, setTheme] = useState<ShareTheme>(() => loadShareTheme(localStorage));
  const [trailMode, setTrailMode] = useState<TrailMode>(() => loadTrailMode(localStorage));
  // CARTO basemap key: undefined while pending (the map holds its tiles briefly), then the
  // key or null. Purely cosmetic: any failure (an ended or expired link included) leaves it
  // null and the map on placeholder tiles. The feed poll alone decides the page's
  // terminal states; the key fetch only follows them (see the effect below).
  const [tileKey, setTileKey] = useState<string | null | undefined>(undefined);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    saveShareTheme(localStorage, theme);
  }, [theme]);
  useEffect(() => { saveTrailMode(localStorage, trailMode); }, [trailMode]);

  // One fetch chain per page (timeout + capped retry of transient failures; 404/410 are
  // final). Aborted on unmount and as soon as the poller reports the link ended or
  // expired, so no pending retry outlives the share.
  const linkOver = status === "ended" || status === "expired";
  useEffect(() => {
    if (!token || linkOver) return;
    const ctl = new AbortController();
    void fetchCartoKey(`/share/${token}/map-config`, ctl.signal)
      .then((k) => { if (!ctl.signal.aborted) setTileKey(k); });
    return () => ctl.abort();
  }, [token, linkOver]);

  useEffect(() => {
    if (!token) { setStatus("ended"); return; }
    let stopPolling: (() => void) | null = null;
    // Single-flight: a slow or hung poll holds back the next tick instead of stacking
    // requests (WEB-25); fetchFeed aborts at FETCH_TIMEOUT_MS so a hang becomes a failure.
    const poller = createFeedPoller({
      fetchFeed: (since) => fetchFeed(token, since),
      seam: () => {
        const t = trackRef.current;
        return t.length > 0 ? t[t.length - 1].t : null;
      },
      now: Date.now,
      onOk: ({ feed: f, replace, seam, health: h }) => {
        const incoming = f.points.map(toTrackPoint);
        // Midnight rolled over mid-session: the server's window moved, so replace rather
        // than splice today's buckets onto yesterday's trail.
        if (replace) setTrack(incoming);
        else setTrack((cur) => appendTrack(cur, incoming, seam ?? -Infinity));
        setFeed(f);
        setHealth(h);
        setStatus("ok");
      },
      onFail: (h) => {
        setHealth(h);
        // Before the first success there is nothing to show; after it the badge carries
        // the failure (CONNECTION LOST) instead of replacing the whole page.
        setStatus((s) => (s === "ok" ? "ok" : "error"));
      },
      // Terminally over: stop the timer so a later blip can't flip the sticky status.
      onTerminal: (kind) => { stopPolling?.(); setStatus(kind); },
    });
    poller.tick();
    // Visibility-gated: a backgrounded guest tab stops polling and catches up on refocus.
    stopPolling = visibleInterval(poller.tick, FEED_POLL_MS);
    return () => { poller.stop(); stopPolling?.(); };
  }, [token]);

  // cleanTrack + trailProps are O(points) passes; memoize so the guest's geolocation
  // watcher (which can re-render ~1/s via onGuest) doesn't rebuild the trail. Keyed on
  // `track`, whose identity appendTrack PRESERVES when a poll brings nothing new — which
  // is what makes the 4 s poll free: ~4 of 5 polls do no work here or in the map effect.
  // Hooks stay above the early returns; feed is null until the first poll lands.
  const cleaned = useMemo<TrackPoint[]>(() => cleanTrack(track), [track]);
  const trail = useMemo(() => trailProps(cleaned, trailMode), [cleaned, trailMode]);
  // Identity-stable marker position (WEB-24): a new object on every render would make the
  // map rebuild the marker icon — restarting its pulse — on every clock tick.
  const lastFix = feed?.last ?? null;
  const live = useMemo<LivePos | null>(
    () => (lastFix ? { lat: lastFix.lat, lon: lastFix.lon, tsMs: lastFix.t } : null),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [lastFix?.lat, lastFix?.lon, lastFix?.t]);

  if (!token || status === "ended") return <Message text="This share link isn't available." />;
  if (status === "expired") {
    return <Message text="This location share has expired. Ask for a new link." />;
  }
  if (status === "error") return <Message text="Can't reach the server — check your connection." />;
  if (status === "loading" || !feed) return <Message text="Loading…" />;

  const { points, segKinds } = trail;
  const view = guestView(feed.last, feed.now, health, clientNow);

  return (
    <div style={{ height: "100dvh", display: "flex", flexDirection: "column", overflow: "hidden" }}>
      <div style={{ display: "flex", alignItems: "center", gap: 10, padding: "10px 14px",
        flexShrink: 0 }}>
        <span style={{ fontSize: 15, fontWeight: 600 }}>Following {feed.owner}</span>
        <span className="mono" style={{ marginLeft: "auto", fontSize: 11, color: "var(--text-3)" }}>
          {remainingLabel(feed.expires_at, view.serverNow)}
        </span>
        <button aria-label={theme === "dark" ? "Switch to light mode" : "Switch to dark mode"}
          onClick={() => setTheme(theme === "dark" ? "light" : "dark")}
          style={{ background: "var(--nav-active)", border: "1px solid var(--border)",
            color: "var(--text)", fontSize: 14, lineHeight: 1, padding: "6px 9px",
            borderRadius: 7, cursor: "pointer" }}>
          {theme === "dark" ? "☀" : "☾"}
        </button>
      </div>
      <div style={{ flex: 1, minHeight: 0, position: "relative" }}>
        <JourneyMap points={points} segKinds={segKinds} hotspots={NO_HOTSPOTS}
          cursorIndex={Math.max(0, points.length - 1)} theme={theme}
          live={live} liveStale={view.markerStale} fitKey={token} metric="power"
          emptyText={NO_FIX_TEXT} fill guest={guest} tileKey={tileKey} />
        <span className="mono" style={{ position: "absolute", top: 12, right: 12,
          zIndex: 1000, display: "flex", alignItems: "center", gap: 6, padding: "6px 10px",
          borderRadius: 8, background: "rgba(9,9,11,.72)", color: "#e4e4e7", fontSize: 11,
          letterSpacing: 1 }}>
          <span aria-hidden style={{ width: 8, height: 8, borderRadius: "50%", background: BADGE_DOT[view.badge.tone],
            ...(view.badge.tone === "ok" && { boxShadow: "0 0 0 3px rgba(34,197,94,.2)" }) }} />
          {/* Only the state is a live region: the age beside it ticks every second through
              an outage, and a screen reader would otherwise re-read the badge each time.
              The age stays readable, just not announced. */}
          <span role="status">{view.badge.state}</span>
          {view.badge.detail && <span>· {view.badge.detail}</span>}
        </span>
        <div style={{ position: "absolute", bottom: 12, left: 12, zIndex: 1000,
          display: "flex", flexDirection: "column", gap: 6, alignItems: "flex-start" }}>
          {trailMode === "detail" && (
            <span className="mono" style={{ ...mapChip, gap: 8 }}>
              <span style={{ display: "inline-flex", gap: 3, alignItems: "center" }}>
                <Swatch color="var(--ok)" /><Swatch color="var(--warn)" /><Swatch color="var(--live)" />
              </span>
              EFFORT
              <span aria-hidden style={{ color: "#a1a1aa", letterSpacing: 2 }}>╌╌</span>
              VEHICLE
            </span>
          )}
          <button className="mono" aria-label="Toggle trail detail"
            onClick={() => setTrailMode(trailMode === "detail" ? "plain" : "detail")}
            style={{ ...mapChip, border: "1px solid var(--border-strong)", cursor: "pointer" }}>
            TRAIL · {trailMode === "detail" ? "DETAIL" : "PLAIN"}
          </button>
        </div>
      </div>
      <Dock status={feed.status} dim={view.lost} />
      <ArrowPanel target={live} targetNote={view.targetNote} onGuest={setGuest} />
    </div>
  );
}

/** Feed wire point -> the v2 TrackPoint the map/cleanTrack pipeline consumes. The guest
 *  feed deliberately carries no per-point SOC, and no accuracy radius. */
const toTrackPoint = (p: FeedPoint): TrackPoint => ({
  t: p.t, lat: p.lat, lon: p.lon,
  power_w: p.power_w, current_a: p.current_a, soc: null, acc: null,
});

const mapChip = {
  display: "flex", alignItems: "center", gap: 6, padding: "6px 10px", borderRadius: 8,
  background: "rgba(9,9,11,.72)", color: "#e4e4e7", fontSize: 11, letterSpacing: 1,
} as const;

function Swatch({ color }: { color: string }) {
  return <span style={{ width: 8, height: 8, borderRadius: "50%", background: color }} />;
}

function Message({ text }: { text: string }) {
  return (
    <div style={{ minHeight: "100dvh", display: "flex", alignItems: "center",
      justifyContent: "center", padding: 24, textAlign: "center", color: "var(--text-2)" }}>
      {text}
    </div>
  );
}
