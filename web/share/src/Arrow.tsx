import { useEffect, useRef, useState, type CSSProperties } from "react";
import type { LivePos } from "../../src/v2/model/live";
import { arrowRotation, cardinal, fmtDistance, haversineMeters, initialBearingDeg } from "./geo";

/** Direction-to-target panel. Tier 1 (all browsers): watch the guest's geolocation and
 *  show distance + compass-point toward the chair (App draws the map line via onGuest).
 *  Tier 2 (progressive): rotate a live arrow by device heading. iOS gates the compass
 *  behind DeviceOrientationEvent.requestPermission() (must be called from the tap);
 *  webkitCompassHeading is already degrees-from-north, absolute alpha is CCW so
 *  heading = 360 - alpha. No compass → arrow hidden, cardinal text stays. */
export function ArrowPanel({ target, onGuest }: {
  target: LivePos | null;
  onGuest: (g: LivePos | null) => void;
}) {
  const [on, setOn] = useState(false);
  const [denied, setDenied] = useState(false);
  const [waiting, setWaiting] = useState(false);
  const [pos, setPos] = useState<{ lat: number; lon: number } | null>(null);
  const [heading, setHeading] = useState<number | null>(null);
  const watchId = useRef<number | null>(null);
  const orientHandler = useRef<((e: DeviceOrientationEvent) => void) | null>(null);

  const listen = () => {
    if (orientHandler.current) return; // already registered
    const onOrient = (e: DeviceOrientationEvent) => {
      const webkit = (e as DeviceOrientationEvent & { webkitCompassHeading?: number })
        .webkitCompassHeading;
      if (webkit != null) setHeading(webkit);
      else if (e.absolute && e.alpha != null) setHeading((360 - e.alpha) % 360);
    };
    orientHandler.current = onOrient;
    window.addEventListener("deviceorientationabsolute", onOrient as EventListener);
    window.addEventListener("deviceorientation", onOrient as EventListener);
  };

  const start = () => {
    setOn(true);
    if (!navigator.geolocation) {
      setDenied(true);
      return;
    }
    watchId.current = navigator.geolocation.watchPosition(
      (p) => {
        setWaiting(false);
        setPos({ lat: p.coords.latitude, lon: p.coords.longitude });
        onGuest({ lat: p.coords.latitude, lon: p.coords.longitude, tsMs: Date.now() });
      },
      (err) => {
        if (err.code === err.PERMISSION_DENIED) setDenied(true);
        else setWaiting(true);
      },
      { enableHighAccuracy: true, maximumAge: 5000 },
    );
    const dm = DeviceOrientationEvent as unknown as { requestPermission?: () => Promise<string> };
    (dm.requestPermission ? dm.requestPermission().catch(() => "denied")
      : Promise.resolve("granted"))
      .then((state) => { if (state === "granted") listen(); });
  };

  useEffect(() => () => {
    if (watchId.current != null) navigator.geolocation.clearWatch(watchId.current);
    if (orientHandler.current) {
      window.removeEventListener("deviceorientationabsolute", orientHandler.current as EventListener);
      window.removeEventListener("deviceorientation", orientHandler.current as EventListener);
      orientHandler.current = null;
    }
  }, []);

  if (!on) {
    return (
      <div style={panel}>
        <button style={btn} onClick={start}>Point me there</button>
        <span style={{ fontSize: 11, color: "var(--text-4)" }}>
          Uses your location to show distance and direction.
        </span>
      </div>
    );
  }
  if (denied) {
    return (
      <div style={panel}>
        <span style={{ color: "var(--text-3)", fontSize: 12 }}>
          Location permission denied — enable it in your browser to get directions.
        </span>
      </div>
    );
  }
  if (!pos || !target) {
    return (
      <div style={panel}>
        <span style={{ color: "var(--text-3)", fontSize: 12 }}>
          {waiting ? "Locating… (waiting for GPS)" : "Locating…"}
        </span>
      </div>
    );
  }

  const meters = haversineMeters(pos.lat, pos.lon, target.lat, target.lon);
  const bearing = initialBearingDeg(pos.lat, pos.lon, target.lat, target.lon);
  const rot = arrowRotation(bearing, heading);
  return (
    <div style={panel}>
      {rot != null && (
        <span aria-hidden style={{ display: "inline-block", fontSize: 26, lineHeight: 1,
          transform: `rotate(${rot}deg)`, transition: "transform .2s" }}>↑</span>
      )}
      <span className="mono" style={{ fontSize: 14 }}>{fmtDistance(meters)} {cardinal(bearing)}</span>
      <span style={{ fontSize: 11, color: "var(--text-4)" }}>
        {rot != null ? "the arrow points toward them" : "direction is from north"}
      </span>
    </div>
  );
}

const panel: CSSProperties = {
  display: "flex", alignItems: "center", gap: 12, padding: "10px 14px",
  borderTop: "1px solid var(--border)", flexShrink: 0,
};
const btn: CSSProperties = {
  background: "var(--nav-active)", border: "1px solid var(--border)", color: "var(--text)",
  fontSize: 12, padding: "8px 14px", borderRadius: 7, cursor: "pointer",
};
