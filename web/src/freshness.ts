import type { FleetItem } from "./types";

/** A pack is stale (shown as last known, never as live) once its newest TELEMETRY is older
 *  than this. The phone polls background packs slowly (a pack can go about a minute between
 *  reports), so a shorter gap would flap cards to DISCONNECTED. Keep in step with STALE_MS in
 *  server/app/routers/api_widget.py. */
export const STALE_MS = 90_000;

/** Stale: no telemetry for STALE_MS, or the phone reported the BLE link down at or after its
 *  newest telemetry (store.ts keeps link_event only while it is not older than that telemetry). */
export function isPackStale(i: FleetItem, nowMs: number): boolean {
  return nowMs - i.ts_ms > STALE_MS || i.link_event === "Disconnected";
}

/** The addresses of every stale pack. */
export function staleAddresses(items: readonly FleetItem[], nowMs: number): Set<string> {
  return new Set(items.filter((i) => isPackStale(i, nowMs)).map((i) => i.address));
}

/** At least one pack has fresh telemetry: the phone is uploading. Drives v2's SYNCED pill,
 *  which used to mirror the socket and stayed green while every pack was stale (WEB-27). */
export function anyFresh(items: readonly FleetItem[], staleAddrs: ReadonlySet<string>): boolean {
  return items.some((i) => !staleAddrs.has(i.address));
}
