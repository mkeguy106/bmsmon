import { relAgo } from "../../util";

/** The server's answer for the phone-battery alarm threshold (null = OFF). */
export interface PhoneAlert {
  low_pct: number | null;
  updated_at_ms: number;
  updated_by: "phone" | "web" | "default";
}

export const PHONE_ALERT_OPTIONS: { value: string; label: string }[] = [
  { value: "off", label: "Off" },
  ...Array.from({ length: 18 }, (_, i) => 10 + i * 5).map((p) => ({ value: String(p), label: `${p}%` })),
];

export const phoneAlertValue = (lowPct: number | null): string =>
  lowPct === null ? "off" : String(lowPct);

export const phoneAlertFromValue = (v: string): number | null =>
  v === "off" ? null : Number(v);

export function phoneAlertStatus(a: PhoneAlert, now: number): string {
  if (a.updated_by === "default") return "Default";
  const where = a.updated_by === "phone" ? "on the phone" : "in the WebUI";
  return `Set ${where} ${relAgo(a.updated_at_ms, now)}`;
}
