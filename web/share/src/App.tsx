import { useEffect, useMemo, useState } from "react";
import { JourneyMap } from "../../src/v2/components/JourneyMap";
import { cleanTrack } from "../../src/v2/model/cleanTrack";
import type { LivePos } from "../../src/v2/model/live";
import type { TrackPoint } from "../../src/v2/track";
import { relAgo } from "../../src/util";
import {
  FEED_POLL_MS, fetchFeed, isStale, remainingLabel, tokenFromPath, type Feed,
} from "./feed";
import { ArrowPanel } from "./Arrow";
import { Dock } from "./Dock";
import { loadShareTheme, saveShareTheme, type ShareTheme } from "./theme";
import { loadTrailMode, saveTrailMode, trailProps, type TrailMode } from "./trail";

type Status = "loading" | "ok" | "ended" | "expired" | "error";

export default function App() {
  const token = useMemo(() => tokenFromPath(window.location.pathname), []);
  const [feed, setFeed] = useState<Feed | null>(null);
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
    let t: ReturnType<typeof setInterval>;
    // Once a poll resolves "ended"/"expired" the share is terminally over: stop
    // polling so a later network blip can never flip the sticky terminal status
    // to "error" (or a stray "ok").
    const load = () => fetchFeed(token).then((r) => {
      if (!alive || stopped) return;
      setNowMs(Date.now());
      if (r.kind === "ok") { setFeed(r.feed); setStatus("ok"); }
      else if (r.kind === "error") setStatus((s) => (s === "ok" ? "ok" : "error"));
      else {
        stopped = true;
        clearInterval(t);
        setStatus(r.kind);
      }
    });
    load();
    t = setInterval(load, FEED_POLL_MS);
    return () => { alive = false; clearInterval(t); };
  }, [token]);

  if (!token || status === "ended") return <Message text="This share link isn't available." />;
  if (status === "expired") {
    return <Message text="This location share has expired. Ask for a new link." />;
  }
  if (status === "error") return <Message text="Can't reach the server — check your connection." />;
  if (status === "loading" || !feed) return <Message text="Loading…" />;

  const cleaned: TrackPoint[] = cleanTrack(feed.points.map((p) => ({
    t: p.t, lat: p.lat, lon: p.lon, power_w: p.power_w, current_a: p.current_a, soc: null,
  })));
  const { points, segKinds } = trailProps(cleaned, trailMode);
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
