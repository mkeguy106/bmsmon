import { useEffect, useMemo, useRef, useState } from "react";
import { JourneyMap } from "../../src/v2/components/JourneyMap";
import { appendTrack } from "../../src/v2/model/appendTrack";
import { cleanTrack } from "../../src/v2/model/cleanTrack";
import type { LivePos } from "../../src/v2/model/live";
import type { TrackPoint } from "../../src/v2/track";
import { relAgo } from "../../src/util";
import { visibleInterval } from "../../src/visiblePoll";
import {
  FEED_POLL_MS, FULL_REFRESH_MS, fetchFeed, isStale, remainingLabel, tokenFromPath,
  type Feed, type FeedPoint,
} from "./feed";
import { ArrowPanel } from "./Arrow";
import { Dock } from "./Dock";
import { loadShareTheme, saveShareTheme, type ShareTheme } from "./theme";
import { loadTrailMode, saveTrailMode, trailProps, type TrailMode } from "./trail";

type Status = "loading" | "ok" | "ended" | "expired" | "error";

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
  const [nowMs, setNowMs] = useState(() => Date.now());
  const [guest, setGuest] = useState<LivePos | null>(null);
  const [theme, setTheme] = useState<ShareTheme>(() => loadShareTheme(localStorage));
  const [trailMode, setTrailMode] = useState<TrailMode>(() => loadTrailMode(localStorage));

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    saveShareTheme(localStorage, theme);
  }, [theme]);
  useEffect(() => { saveTrailMode(localStorage, trailMode); }, [trailMode]);

  useEffect(() => {
    if (!token) { setStatus("ended"); return; }
    let alive = true;
    let stopped = false;
    let stopPolling: (() => void) | null = null;
    let lastFullMs = 0;
    let dayStart: number | null = null;
    // Once a poll resolves "ended"/"expired" the share is terminally over: stop
    // polling so a later network blip can never flip the sticky terminal status
    // to "error" (or a stray "ok").
    const load = () => {
      // Incremental from the newest bucket's START (it is still filling server-side, so
      // it gets replaced). Fall back to a full fetch on the first load, whenever the
      // trail is empty, and every FULL_REFRESH_MS as a drift safety net.
      const prev = trackRef.current;
      const seamT = prev.length > 0 && Date.now() - lastFullMs < FULL_REFRESH_MS
        ? prev[prev.length - 1].t : null;
      if (seamT == null) lastFullMs = Date.now();
      return fetchFeed(token, seamT ?? undefined).then((r) => {
        if (!alive || stopped) return;
        setNowMs(Date.now());
        if (r.kind === "ok") {
          const incoming = r.feed.points.map(toTrackPoint);
          // Midnight rolled over mid-session: the server's window moved, so replace
          // rather than splice tomorrow's buckets onto yesterday's trail.
          const rolled = dayStart != null && r.feed.day_start !== dayStart;
          dayStart = r.feed.day_start;
          if (rolled) { lastFullMs = 0; setTrack(incoming); }
          else setTrack((cur) => appendTrack(cur, incoming, seamT ?? -Infinity));
          setFeed(r.feed);
          setStatus("ok");
        } else if (r.kind === "error") setStatus((s) => (s === "ok" ? "ok" : "error"));
        else {
          stopped = true;
          stopPolling?.();
          setStatus(r.kind);
        }
      });
    };
    load();
    // Visibility-gated: a backgrounded guest tab stops polling and catches up on refocus.
    stopPolling = visibleInterval(load, FEED_POLL_MS);
    return () => { alive = false; stopPolling?.(); };
  }, [token]);

  // cleanTrack + trailProps are O(points) passes; memoize so the guest's geolocation
  // watcher (which can re-render ~1/s via onGuest) doesn't rebuild the trail. Keyed on
  // `track`, whose identity appendTrack PRESERVES when a poll brings nothing new — which
  // is what makes the 4 s poll free: ~4 of 5 polls do no work here or in the map effect.
  // Hooks stay above the early returns; feed is null until the first poll lands.
  const cleaned = useMemo<TrackPoint[]>(() => cleanTrack(track), [track]);
  const trail = useMemo(() => trailProps(cleaned, trailMode), [cleaned, trailMode]);

  if (!token || status === "ended") return <Message text="This share link isn't available." />;
  if (status === "expired") {
    return <Message text="This location share has expired. Ask for a new link." />;
  }
  if (status === "error") return <Message text="Can't reach the server — check your connection." />;
  if (status === "loading" || !feed) return <Message text="Loading…" />;

  const { points, segKinds } = trail;
  const live: LivePos | null = feed.last
    ? { lat: feed.last.lat, lon: feed.last.lon, tsMs: feed.last.t } : null;
  const stale = isStale(feed.last, feed.now);

  return (
    <div style={{ height: "100dvh", display: "flex", flexDirection: "column", overflow: "hidden" }}>
      <div style={{ display: "flex", alignItems: "center", gap: 10, padding: "10px 14px",
        flexShrink: 0 }}>
        <span style={{ fontSize: 15, fontWeight: 600 }}>Following {feed.owner}</span>
        <span className="mono" style={{ marginLeft: "auto", fontSize: 11, color: "var(--text-3)" }}>
          {remainingLabel(feed.expires_at, nowMs)}
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
        <JourneyMap points={points} segKinds={segKinds} hotspots={[]}
          cursorIndex={Math.max(0, points.length - 1)} theme={theme}
          live={live} liveStale={stale} fitKey={token} metric="power"
          emptyText="Waiting for GPS…" fill guest={guest} />
        <span className="mono" style={{ position: "absolute", top: 12, right: 12, zIndex: 1000,
          display: "flex", alignItems: "center", gap: 6, padding: "6px 10px", borderRadius: 8,
          background: "rgba(9,9,11,.72)", color: "#e4e4e7", fontSize: 11, letterSpacing: 1 }}>
          {stale ? (<>
            <span style={{ width: 8, height: 8, borderRadius: "50%", background: "var(--warn)" }} />
            LAST KNOWN{live ? ` · ${relAgo(live.tsMs, feed.now)}` : ""}
          </>) : (<>
            <span style={{ width: 8, height: 8, borderRadius: "50%", background: "var(--ok)",
              boxShadow: "0 0 0 3px rgba(34,197,94,.2)" }} />
            LIVE
          </>)}
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
      <Dock status={feed.status} />
      <ArrowPanel target={live} onGuest={setGuest} />
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
