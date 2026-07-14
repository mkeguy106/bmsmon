import { useEffect, useRef, useState } from "react";
import L from "leaflet";
import "leaflet/dist/leaflet.css";
import type { TrackPoint } from "../track";
import { dischargeColor, socColor, type SegKind, type Hotspot } from "../model/journey";
import type { LivePos } from "../model/live";

/** Resolve a CSS custom property off :root to its concrete computed value. */
function cssVar(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

/** Turn a `var(--x)` spec (or a literal color) into a concrete color string.
 *  Leaflet writes stroke/fill straight into SVG attributes, where `var(--x)`
 *  does NOT resolve — so trail colors must be pre-resolved. */
function resolveColor(spec: string): string {
  const m = /^var\((--[\w-]+)\)$/.exec(spec.trim());
  return m ? cssVar(m[1]) : spec;
}

/** Concrete discharge color for an active segment (mirrors the model's thresholds). */
function trailColor(powerW: number | null): string {
  return resolveColor(dischargeColor(powerW ?? 0));
}

function tileUrl(theme: "dark" | "light"): string {
  const style = theme === "dark" ? "dark_all" : "light_all";
  return `https://{s}.basemaps.cartocdn.com/${style}/{z}/{x}/{y}{r}.png`;
}
const TILE_ATTRIB = "© OpenStreetMap contributors © CARTO";

export function JourneyMap({ points, segKinds, hotspots, cursorIndex, theme, live, liveStale, fitKey, metric, emptyText, fill, showTrail = true, guest = null }: {
  points: TrackPoint[]; segKinds: SegKind[]; hotspots: Hotspot[]; cursorIndex: number;
  theme: "dark" | "light"; live: LivePos | null; liveStale?: boolean; fitKey: string;
  metric: "power" | "soc"; emptyText?: string; fill?: boolean; showTrail?: boolean;
  guest?: LivePos | null;
}) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<L.Map | null>(null);
  const tileRef = useRef<L.TileLayer | null>(null);
  const trailRef = useRef<L.LayerGroup | null>(null);
  const cursorRef = useRef<L.CircleMarker | null>(null);
  const liveMarkerRef = useRef<L.Marker | null>(null);
  const guestRef = useRef<L.CircleMarker | null>(null);
  const guestLineRef = useRef<L.Polyline | null>(null);
  const programmaticMove = useRef(false);
  const lastFitKeyRef = useRef<string | null>(null);
  const lastFitPointsRef = useRef<TrackPoint[] | null>(null);
  const engagedKeyRef = useRef<string | null>(null);
  const [mapReady, setMapReady] = useState(false);
  const [following, setFollowing] = useState(false);

  const hasPoints = points.length > 0;
  const hasMapContent = hasPoints || live != null;

  // --- Map lifecycle: init when we have a trip (or a live fix), tear down on unmount / going empty.
  //     Mobile (fill) disables the zoom control — the TRAIL chip overlay sits where it would
  //     render, and pinch-zoom covers the gesture anyway.
  useEffect(() => {
    if (!hasMapContent) return;
    const el = containerRef.current;
    if (!el) return;
    const map = L.map(el, { zoomControl: !fill, attributionControl: true });
    // harmless default; fitBounds/follow overrides once the trail draws or a live fix arrives
    map.setView(live ? [live.lat, live.lon] : [0, 0], live ? 17 : 2);
    mapRef.current = map;
    setMapReady(true);
    return () => {
      map.remove();
      mapRef.current = null;
      tileRef.current = null;
      trailRef.current = null;
      cursorRef.current = null;
      liveMarkerRef.current = null;
      guestRef.current = null;
      guestLineRef.current = null;
      lastFitKeyRef.current = null;
      lastFitPointsRef.current = null;
      engagedKeyRef.current = null;
      setMapReady(false);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hasMapContent]);

  // User-initiated pan/zoom breaks follow. dragstart fires ONLY from user input — Leaflet's
  // programmatic panTo/fitBounds move the map via movestart, never dragstart — so it's always
  // safe to break follow on it unconditionally (no guard needed, and no race against an
  // in-flight animated follow-pan). zoomstart, by contrast, DOES fire for programmatic zooms
  // (e.g. fitBounds), so it stays guarded on the programmatic-move flag.
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    const onDragStart = () => setFollowing(false);
    const onZoomStart = () => { if (!programmaticMove.current) setFollowing(false); };
    map.on("dragstart", onDragStart);
    map.on("zoomstart", onZoomStart);
    return () => { map.off("dragstart", onDragStart); map.off("zoomstart", onZoomStart); };
  }, [mapReady]);

  // Follow engages once per window when live begins — NOT on every stale→fresh recovery,
  // which would re-seize a camera the user deliberately panned away mid-session.
  useEffect(() => {
    if (live == null) { setFollowing(false); return; }
    if (engagedKeyRef.current !== fitKey) {
      engagedKeyRef.current = fitKey;
      setFollowing(true);
    }
  }, [live == null, fitKey]);

  // --- Theme tiles: swap the CARTO layer when the app theme flips.
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    if (tileRef.current) { map.removeLayer(tileRef.current); tileRef.current = null; }
    tileRef.current = L.tileLayer(tileUrl(theme), { attribution: TILE_ATTRIB, maxZoom: 19 }).addTo(map);
  }, [theme, mapReady]);

  // --- Trail + hotspots. Rebuilt whenever the trip, its segmentation, or theme changes
  //     (theme re-resolves the grey transit color from --text-4).
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    if (trailRef.current) { map.removeLayer(trailRef.current); trailRef.current = null; }
    // Trail toggled off: the map keeps the live/last-known marker but draws no history.
    if (!showTrail || points.length === 0) return;

    const group = L.layerGroup();
    for (let i = 1; i < points.length; i++) {
      const kind = segKinds[i] ?? "idle";
      if (kind === "idle") continue;
      const prev = points[i - 1], cur = points[i];
      const line: [number, number][] = [[prev.lat, prev.lon], [cur.lat, cur.lon]];
      if (kind === "active") {
        const color = metric === "soc"
          ? resolveColor(socColor(cur.soc))
          : trailColor(cur.power_w);
        L.polyline(line, { color, weight: 4, opacity: 0.95 }).addTo(group);
      } else {
        L.polyline(line, {
          color: resolveColor("var(--text-4)"), weight: 3, opacity: 0.8, dashArray: "4 6",
        }).addTo(group);
      }
    }

    const halo = resolveColor("var(--live)");
    for (const h of hotspots) {
      const pt = points[h.index];
      if (!pt) continue;
      L.circleMarker([pt.lat, pt.lon], {
        radius: 12, color: halo, weight: 0, fillColor: halo, fillOpacity: 0.18, interactive: false,
      }).addTo(group);
      L.circleMarker([pt.lat, pt.lon], {
        radius: 5, color: "#fff", weight: 1.5, fillColor: halo, fillOpacity: 0.95,
      }).bindTooltip(`HOTSPOT ${Math.round(h.powerW)}W`, { direction: "top" }).addTo(group);
    }

    group.addTo(map);
    trailRef.current = group;

  }, [points, segKinds, hotspots, theme, metric, showTrail, mapReady]);

  // --- Fit the map to the trip once per selected window, when that window's OWN points
  //     arrive. Three renders must NOT fit: (1) a live refresh of an already-fitted window
  //     (lastFitKeyRef === fitKey); (2) the stale render right after a window change, where
  //     `points` is still the previous window's array — useTrack keeps the last-good track
  //     while the new fetch is in flight, so keying on fitKey alone would fit to the OLD
  //     day's bounds (same array identity we already fitted, caught via lastFitPointsRef);
  //     (3) empty points.
  useEffect(() => {
    const map = mapRef.current;
    if (!map || points.length === 0) return;
    if (lastFitKeyRef.current === fitKey) return;
    if (points === lastFitPointsRef.current) return;
    lastFitKeyRef.current = fitKey;
    lastFitPointsRef.current = points;
    map.invalidateSize();
    const bounds = L.latLngBounds(points.map((p) => [p.lat, p.lon] as [number, number]));
    if (bounds.isValid()) {
      programmaticMove.current = true;
      map.fitBounds(bounds, { padding: [24, 24], maxZoom: 17 });
      map.once("moveend", () => { programmaticMove.current = false; });
    }
  }, [points, fitKey, mapReady]);

  // --- Playback cursor: a single marker walked to points[cursorIndex].
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    const pt = points[cursorIndex];
    if (!pt) {
      if (cursorRef.current) { map.removeLayer(cursorRef.current); cursorRef.current = null; }
      return;
    }
    const latlng: [number, number] = [pt.lat, pt.lon];
    if (cursorRef.current) {
      cursorRef.current.setLatLng(latlng);
      // Re-resolve colors so a theme flip doesn't leave the cursor at the old theme's values.
      cursorRef.current.setStyle({ color: resolveColor("var(--app-bg)"), fillColor: resolveColor("var(--text)") });
    } else {
      cursorRef.current = L.circleMarker(latlng, {
        radius: 6, color: resolveColor("var(--app-bg)"), weight: 2,
        fillColor: resolveColor("var(--text)"), fillOpacity: 1,
      }).addTo(map);
    }
  }, [cursorIndex, points, mapReady, theme]);

  // --- Live marker: the wheelchair's current position, walked as fresh fixes arrive.
  //     Pans the map to follow while `following` is engaged (guarded so the pan itself
  //     doesn't get mistaken for a user drag and break follow).
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    if (!live) {
      if (liveMarkerRef.current) { map.removeLayer(liveMarkerRef.current); liveMarkerRef.current = null; }
      return;
    }
    const latlng: [number, number] = [live.lat, live.lon];
    // Stale ("last known") renders the chair grey and un-pulsed; fresh renders it live.
    const icon = L.divIcon({
      className: "",                      // suppress Leaflet's default divIcon box styling
      html: `<div class="chair-marker${liveStale ? " chair-marker-stale" : ""}">♿</div>`,
      iconSize: [34, 34], iconAnchor: [17, 17],
    });
    if (liveMarkerRef.current) {
      liveMarkerRef.current.setLatLng(latlng);
      liveMarkerRef.current.setIcon(icon);
    } else {
      liveMarkerRef.current = L.marker(latlng, { icon, interactive: false, zIndexOffset: 1000 }).addTo(map);
    }
    if (following) {
      programmaticMove.current = true;
      map.panTo(latlng);
      // Leaflet fires moveend after the pan settles; clearing on it re-arms the guard.
      map.once("moveend", () => { programmaticMove.current = false; });
    }
  }, [live, liveStale, following, mapReady]);

  // Guest marker + bearing line (share page only): where the viewer is, and a dashed
  // line from them to the chair so "which way do I walk" is visible on the map. Walked
  // in place like the cursor/live-marker effects above, not destroyed and recreated.
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    if (!guest) {
      if (guestRef.current) { map.removeLayer(guestRef.current); guestRef.current = null; }
      if (guestLineRef.current) { map.removeLayer(guestLineRef.current); guestLineRef.current = null; }
      return;
    }
    const latlng: [number, number] = [guest.lat, guest.lon];
    if (guestRef.current) {
      guestRef.current.setLatLng(latlng);
    } else {
      guestRef.current = L.circleMarker(latlng, {
        radius: 7, color: "#fff", weight: 2, fillColor: "#3b82f6", fillOpacity: 1,
      }).addTo(map);
    }
    if (live) {
      const line: [number, number][] = [[guest.lat, guest.lon], [live.lat, live.lon]];
      if (guestLineRef.current) {
        guestLineRef.current.setLatLngs(line);
      } else {
        guestLineRef.current = L.polyline(line, {
          color: "#3b82f6", weight: 2, dashArray: "6 6", opacity: 0.8,
        }).addTo(map);
      }
    } else if (guestLineRef.current) {
      map.removeLayer(guestLineRef.current);
      guestLineRef.current = null;
    }
  }, [guest, live, mapReady]);

  // Re-center: on the chair when live (re-engaging follow), else refit to the trail.
  const recenter = () => {
    const map = mapRef.current;
    if (!map) return;
    if (live) {
      setFollowing(true);
      programmaticMove.current = true;
      map.panTo([live.lat, live.lon]);
      map.once("moveend", () => { programmaticMove.current = false; });
    } else if (points.length > 0) {
      const bounds = L.latLngBounds(points.map((p) => [p.lat, p.lon] as [number, number]));
      if (bounds.isValid()) {
        programmaticMove.current = true;
        map.fitBounds(bounds, { padding: [24, 24], maxZoom: 17 });
        map.once("moveend", () => { programmaticMove.current = false; });
      }
    }
  };

  if (!hasMapContent) {
    return (
      <div style={{
        height: "100%", minHeight: fill ? 0 : 360, display: "flex", alignItems: "center",
        justifyContent: "center", background: "var(--panel-3)", border: "1px solid var(--border)",
        borderRadius: 8, color: "var(--text-4)", fontSize: 13,
      }}>
        {emptyText ?? "No GPS trip recorded"}
      </div>
    );
  }

  return (
    <div style={{ position: "relative", height: "100%" }}>
      <div ref={containerRef} style={{
        height: "100%", minHeight: fill ? 0 : 360, borderRadius: 8, overflow: "hidden",
        border: "1px solid var(--border)", background: "var(--panel-3)",
      }} />
      <button className="recenter-btn" aria-label="Re-center map" onClick={recenter}>
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <circle cx="12" cy="12" r="7" /><circle cx="12" cy="12" r="1.6" fill="currentColor" />
          <path d="M12 2v3M12 19v3M2 12h3M19 12h3" />
        </svg>
      </button>
    </div>
  );
}
