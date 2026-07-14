/** Great-circle math for the find-me arrow. Angles in degrees, distances in meters. */

const R = 6_371_000;
const rad = (d: number) => (d * Math.PI) / 180;

export function haversineMeters(aLat: number, aLon: number, bLat: number, bLon: number): number {
  const dLat = rad(bLat - aLat);
  const dLon = rad(bLon - aLon);
  const s = Math.sin(dLat / 2) ** 2 +
    Math.cos(rad(aLat)) * Math.cos(rad(bLat)) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(s));
}

/** Initial great-circle bearing from A to B, 0..360 clockwise from true north. */
export function initialBearingDeg(aLat: number, aLon: number, bLat: number, bLon: number): number {
  const y = Math.sin(rad(bLon - aLon)) * Math.cos(rad(bLat));
  const x = Math.cos(rad(aLat)) * Math.sin(rad(bLat)) -
    Math.sin(rad(aLat)) * Math.cos(rad(bLat)) * Math.cos(rad(bLon - aLon));
  return ((Math.atan2(y, x) * 180) / Math.PI + 360) % 360;
}

export function cardinal(bearing: number): string {
  const names = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"];
  return names[Math.round((((bearing % 360) + 360) % 360) / 45) % 8];
}

export function fmtDistance(meters: number): string {
  const feet = meters * 3.28084;
  if (feet < 1000) return `${Math.max(10, Math.round(feet / 10) * 10)} ft`;
  return `${(feet / 5280).toFixed(1)} mi`;
}

/** Screen rotation for the arrow glyph: bearing relative to where the phone points.
 *  headingDeg null (no compass) → null: caller falls back to cardinal text + map line. */
export function arrowRotation(bearingDeg: number, headingDeg: number | null): number | null {
  if (headingDeg == null) return null;
  return (((bearingDeg - headingDeg) % 360) + 360) % 360;
}
