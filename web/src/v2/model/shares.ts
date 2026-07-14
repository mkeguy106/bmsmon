import type { ShareRow } from "../../api";

export function shareStatus(s: ShareRow, nowMs: number): "active" | "expired" | "revoked" {
  if (s.revoked_at != null) return "revoked";
  return nowMs < s.expires_at ? "active" : "expired";
}

export function remainingShort(expiresAt: number, nowMs: number): string {
  const left = expiresAt - nowMs;
  if (left <= 0) return "expired";
  const d = Math.floor(left / 86_400_000);
  if (d >= 1) return `${d}d left`;
  const h = Math.floor(left / 3_600_000);
  if (h >= 1) return `${h}h left`;
  return `${Math.max(1, Math.floor(left / 60_000))}m left`;
}

export function lastOpened(s: ShareRow, nowMs: number): string {
  if (s.last_access_ms == null) return "never opened";
  const min = Math.floor((nowMs - s.last_access_ms) / 60_000);
  if (min < 1) return "just now";
  if (min < 60) return `${min}m ago`;
  const h = Math.floor(min / 60);
  if (h < 24) return `${h}h ago`;
  return `${Math.floor(h / 24)}d ago`;
}
