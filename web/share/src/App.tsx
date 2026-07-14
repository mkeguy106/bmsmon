import { useEffect, useMemo, useState } from "react";
import { JourneyMap } from "../../src/v2/components/JourneyMap";
import { cleanTrack } from "../../src/v2/model/cleanTrack";
import type { SegKind } from "../../src/v2/model/journey";
import type { LivePos } from "../../src/v2/model/live";
import type { TrackPoint } from "../../src/v2/track";
import { relAgo } from "../../src/util";
import {
  FEED_POLL_MS, fetchFeed, isStale, remainingLabel, tokenFromPath, type Feed,
} from "./feed";

type Status = "loading" | "ok" | "ended" | "expired" | "error";

export default function App() {
  const token = useMemo(() => tokenFromPath(window.location.pathname), []);
  const [feed, setFeed] = useState<Feed | null>(null);
  const [status, setStatus] = useState<Status>("loading");
  const [nowMs, setNowMs] = useState(() => Date.now());
  const [guest, setGuest] = useState<LivePos | null>(null);

  useEffect(() => {
    if (!token) { setStatus("ended"); return; }
    let alive = true;
    const load = () => fetchFeed(token).then((r) => {
      if (!alive) return;
      setNowMs(Date.now());
      if (r.kind === "ok") { setFeed(r.feed); setStatus("ok"); }
      else if (r.kind === "error") setStatus((s) => (s === "ok" ? "ok" : "error"));
      else setStatus(r.kind);
    });
    load();
    const t = setInterval(load, FEED_POLL_MS);
    return () => { alive = false; clearInterval(t); };
  }, [token]);

  if (!token || status === "ended") return <Message text="This share link isn't available." />;
  if (status === "expired") {
    return <Message text="This location share has expired. Ask for a new link." />;
  }
  if (status === "error") return <Message text="Can't reach the server — check your connection." />;
  if (status === "loading" || !feed) return <Message text="Loading…" />;

  const points: TrackPoint[] = cleanTrack(feed.points.map((p) => ({
    t: p.t, lat: p.lat, lon: p.lon, power_w: null, current_a: null, soc: null,
  })));
  const segKinds: SegKind[] = points.map(() => "active");
  const live: LivePos | null = feed.last
    ? { lat: feed.last.lat, lon: feed.last.lon, tsMs: feed.last.t } : null;
  const stale = isStale(feed.last, feed.now);
  const theme = document.documentElement.dataset.theme === "light" ? "light" : "dark";

  return (
    <div style={{ height: "100dvh", display: "flex", flexDirection: "column", overflow: "hidden" }}>
      <div style={{ display: "flex", alignItems: "center", gap: 10, padding: "10px 14px",
        flexShrink: 0 }}>
        <span style={{ fontSize: 15, fontWeight: 600 }}>Following {feed.owner}</span>
        <span className="mono" style={{ marginLeft: "auto", fontSize: 11, color: "var(--text-3)" }}>
          {remainingLabel(feed.expires_at, nowMs)}
        </span>
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
      </div>
      {/* Task 7 mounts <ArrowPanel target={live} onGuest={setGuest} /> here. */}
      {/* `false` (not `null`) — TS 5.9's TS2873 "always falsy" check rejects a literal
          `null` operand here; `false` renders nothing in JSX just the same. */}
      {false && guest}
    </div>
  );
}

function Message({ text }: { text: string }) {
  return (
    <div style={{ minHeight: "100dvh", display: "flex", alignItems: "center",
      justifyContent: "center", padding: 24, textAlign: "center", color: "var(--text-2)" }}>
      {text}
    </div>
  );
}
