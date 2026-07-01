/** Compact "time ago" for a past epoch-ms relative to [now]. */
export function relAgo(ms: number, now: number): string {
  const s = Math.max(0, Math.round((now - ms) / 1000));
  if (s < 45) return "just now";
  const m = Math.round(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h}h ago`;
  return `${Math.round(h / 24)}d ago`;
}

/** Compact "2h 14m" / "45m" for a minutes estimate (clamped at 0). */
export function fmtEta(minutes: number): string {
  const t = Math.max(0, Math.round(minutes));
  const h = Math.floor(t / 60);
  const m = t % 60;
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}
