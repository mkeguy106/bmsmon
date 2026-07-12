import { useEffect, useRef, useState } from "react";
import L from "leaflet";
import "leaflet/dist/leaflet.css";
import type { TrackPoint } from "../track";
import { dischargeColor, type SegKind, type Hotspot } from "../model/journey";

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

export function JourneyMap({ points, segKinds, hotspots, cursorIndex, theme }: {
  points: TrackPoint[]; segKinds: SegKind[]; hotspots: Hotspot[]; cursorIndex: number; theme: "dark" | "light";
}) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<L.Map | null>(null);
  const tileRef = useRef<L.TileLayer | null>(null);
  const trailRef = useRef<L.LayerGroup | null>(null);
  const cursorRef = useRef<L.CircleMarker | null>(null);
  const [mapReady, setMapReady] = useState(false);

  const hasPoints = points.length > 0;

  // --- Map lifecycle: init when we have a trip, tear down on unmount / going empty.
  useEffect(() => {
    if (!hasPoints) return;
    const el = containerRef.current;
    if (!el) return;
    const map = L.map(el, { zoomControl: true, attributionControl: true });
    map.setView([0, 0], 2); // harmless default; fitBounds overrides once the trail draws
    mapRef.current = map;
    setMapReady(true);
    return () => {
      map.remove();
      mapRef.current = null;
      tileRef.current = null;
      trailRef.current = null;
      cursorRef.current = null;
      setMapReady(false);
    };
  }, [hasPoints]);

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

    map.invalidateSize();
    const bounds = L.latLngBounds(points.map((p) => [p.lat, p.lon] as [number, number]));
    if (bounds.isValid()) map.fitBounds(bounds, { padding: [24, 24], maxZoom: 17 });
  }, [points, segKinds, hotspots, theme, mapReady]);

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
    } else {
      cursorRef.current = L.circleMarker(latlng, {
        radius: 6, color: resolveColor("var(--app-bg)"), weight: 2,
        fillColor: resolveColor("var(--text)"), fillOpacity: 1,
      }).addTo(map);
    }
  }, [cursorIndex, points, mapReady, theme]);

  if (!hasPoints) {
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
    <div ref={containerRef} style={{
      height: "100%", minHeight: 360, borderRadius: 8, overflow: "hidden",
      border: "1px solid var(--border)", background: "var(--panel-3)",
    }} />
  );
}
