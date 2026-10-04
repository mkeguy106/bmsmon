// Confirmation copy for the admin actions that are costly or irreversible (WEB-21). Each
// prompt names its target and says what happens. Pure, so the wording is tested; the panels
// show it with window.confirm, which behaves the same on a phone and needs no dialog component.
import type { DeviceRow } from "./types";
import { relAgo } from "./util";

/** A device that uploaded this recently is taken to be the phone on the chair. */
export const RECENT_UPLOAD_MS = 10 * 60_000;
/** A non-revoked device unseen for longer than this is dormant: it does not count as another
 *  active device, so an old forgotten enrollment can't hide the "only active device" warning. */
export const ACTIVE_WINDOW_MS = 7 * 24 * 3_600_000;

export const deviceName = (d: DeviceRow): string => d.label?.trim() || d.install_uuid;

/** Epoch ms of a /web/devices row's last_seen_at (ISO 8601), or null. */
export function lastSeenMs(d: DeviceRow): number | null {
  if (!d.last_seen_at) return null;
  const ms = Date.parse(d.last_seen_at);
  return Number.isFinite(ms) ? ms : null;
}

/** `undoable`: v2 has Restore in Settings › Devices; v1 has none, so it omits the line. */
export function revokeDeviceConfirm(
  d: DeviceRow, all: readonly DeviceRow[], nowMs: number, undoable = true,
): string {
  const seen = lastSeenMs(d);
  const othersActive = all.filter((o) => {
    if (o.id === d.id || o.revoked) return false;
    const s = lastSeenMs(o);
    return s != null && nowMs - s <= ACTIVE_WINDOW_MS;
  }).length;
  const parts = [`Revoke "${deviceName(d)}"?`];
  if (seen != null && nowMs - seen <= RECENT_UPLOAD_MS) {
    parts.push(`It last uploaded ${relAgo(seen, nowMs)}, so this looks like the phone on the chair.`);
  }
  parts.push(othersActive === 0
    ? "It is the only active device: all uploads from the chair stop until it is restored, so the dashboard, shared location links and desktop widgets stop updating."
    : "It stops uploading telemetry at once.");
  if (undoable) parts.push("You can undo this with Restore in Settings › Devices.");
  return parts.join("\n\n");
}

export const restoreDeviceConfirm = (d: DeviceRow): string =>
  `Restore "${deviceName(d)}"?\n\nIt can upload telemetry again with its existing key. No re-enrollment is needed.`;

export const revokeShareConfirm = (name: string): string =>
  `Revoke the live-location link for "${name}"?\n\nThey stop seeing the chair within seconds. This can't be undone; create a new link if they still need one.`;

export const revokeApiKeyConfirm = (name: string): string =>
  `Revoke the API key "${name}"?\n\nAnything using it, such as the desktop widgets, stops updating. This can't be undone; mint a new key instead.`;

/** The share link is shown exactly once, so once it exists a stray tap on the backdrop must
 *  not discard it: only Done closes the dialog then. While the create request is in flight it must not close
 *  either: the share would be minted server-side with its link never shown. */
export const backdropDismisses = (url: string | null, busy = false): boolean => url == null && !busy;
