// Shared SOC / SOH status-color banding for v2. Previously duplicated as identical
// local `socColor`/`sohColor` helpers across CommandStage/CommandFleetRail/CommandAside/
// HealthView — consolidated here so the bands live in one place.

/** SOC ring/bar color: muted when disconnected/unknown, else red <15 / amber <30 / green. */
export function socColor(soc: number | null | undefined, connected = true): string {
  if (!connected || soc == null) return "var(--text-4)";
  return soc < 15 ? "var(--live)" : soc < 30 ? "var(--warn)" : "var(--ok)";
}

/** SOH dot/bar color: muted when unknown, else green ≥90 / amber ≥80 / red below. */
export function sohColor(soh: number | null | undefined): string {
  if (soh == null) return "var(--text-4)";
  return soh >= 90 ? "var(--ok)" : soh >= 80 ? "var(--warn)" : "var(--live)";
}
