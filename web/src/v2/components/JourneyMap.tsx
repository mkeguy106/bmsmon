import { useEffect, useRef, useState } from "react";
import L from "leaflet";
import "leaflet/dist/leaflet.css";
import type { TrackPoint } from "../track";
import { dischargeColor, type SegKind, type Hotspot } from "../model/journey";
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

export function JourneyMap({ points, segKinds, hotspots, cursorIndex, theme, live = null, fitKey = "" }: {
  points: TrackPoint[]; segKinds: SegKind[]; hotspots: Hotspot[]; cursorIndex: number;
  theme: "dark" | "light"; live?: LivePos | null; fitKey?: string;
}) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<L.Map | null>(null);
  const tileRef = useRef<L.TileLayer | null>(null);
  const trailRef = useRef<L.LayerGroup | null>(null);
  const cursorRef = useRef<L.CircleMarker | null>(null);
  const liveMarkerRef = useRef<L.Marker | null>(null);
  const programmaticMove = useRef(false);
  const [mapReady, setMapReady] = useState(false);
  const [following, setFollowing] = useState(false);

  const hasPoints = points.length > 0;
  const hasMapContent = hasPoints || live != null;

  // --- Map lifecycle: init when we have a trip (or a live fix), tear down on unmount / going empty.
  useEffect(() => {
    if (!hasMapContent) return;
    const el = containerRef.current;
    if (!el) return;
    const map = L.map(el, { zoomControl: true, attributionControl: true });
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
      setMapReady(false);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hasMapContent]);

  // User-initiated pan/zoom breaks follow. Programmatic moves are guarded so panTo/fitBounds
  // never count as the user grabbing the map.
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    const breakFollow = () => { if (!programmaticMove.current) setFollowing(false); };
    map.on("dragstart", breakFollow);
    map.on("zoomstart", breakFollow);
    return () => { map.off("dragstart", breakFollow); map.off("zoomstart", breakFollow); };
  }, [mapReady]);

  // Going live (or coming back to a live day) re-engages follow; leaving live disengages.
  useEffect(() => { setFollowing(live != null); }, [live != null, fitKey]);

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
    if (points.length === 0) return;

    const group = L.layerGroup();
    for (let i = 1; i < points.length; i++) {
      const kind = segKinds[i] ?? "idle";
      if (kind === "idle") continue;
      const prev = points[i - 1], cur = points[i];
      const line: [number, number][] = [[prev.lat, prev.lon], [cur.lat, cur.lon]];
      if (kind === "active") {
        L.polyline(line, { color: trailColor(cur.power_w), weight: 4, opacity: 0.95 }).addTo(group);
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

  }, [points, segKinds, hotspots, theme, mapReady]);

  // --- Fit the map to the trip once per selected window — NOT on theme flip, so a
  //     light/dark toggle re-tiles/re-colors without discarding the user's pan/zoom.
  useEffect(() => {
    const map = mapRef.current;
    if (!map || points.length === 0) return;
    map.invalidateSize();
    const bounds = L.latLngBounds(points.map((p) => [p.lat, p.lon] as [number, number]));
    if (bounds.isValid()) {
      programmaticMove.current = true;
      map.fitBounds(bounds, { padding: [24, 24], maxZoom: 17 });
      map.once("moveend", () => { programmaticMove.current = false; });
    }
    // Fit once per selected window (fitKey) — NEVER per live refresh of `points`, which
    // would yank the user's pan/zoom every 15 s.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fitKey, mapReady]);

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
    if (liveMarkerRef.current) {
      liveMarkerRef.current.setLatLng(latlng);
    } else {
      const icon = L.divIcon({
        className: "",                    // suppress Leaflet's default divIcon box styling
        html: '<div class="chair-marker">♿</div>',
        iconSize: [34, 34], iconAnchor: [17, 17],
      });
      liveMarkerRef.current = L.marker(latlng, { icon, interactive: false, zIndexOffset: 1000 }).addTo(map);
    }
    if (following) {
      programmaticMove.current = true;
      map.panTo(latlng);
      // Leaflet fires moveend after the pan settles; clearing on it re-arms the guard.
      map.once("moveend", () => { programmaticMove.current = false; });
    }
  }, [live, following, mapReady]);

  if (!hasMapContent) {
    return (
      <div style={{
        height: "100%", minHeight: 360, display: "flex", alignItems: "center",
        justifyContent: "center", background: "var(--panel-3)", border: "1px solid var(--border)",
        borderRadius: 8, color: "var(--text-4)", fontSize: 13,
      }}>
        No GPS trip recorded
      </div>
    );
  }

  return (
    <div style={{ position: "relative", height: "100%" }}>
      <div ref={containerRef} style={{
        height: "100%", minHeight: 360, borderRadius: 8, overflow: "hidden",
        border: "1px solid var(--border)", background: "var(--panel-3)",
      }} />
      {live != null && !following && (
        <button className="follow-btn mono" onClick={() => setFollowing(true)}>⌖ FOLLOW</button>
      )}
    </div>
  );
}
