import { decodeSnapshot, decodeTempConfigs, decodeRangeConfigs, decodeHistory, decodeTrends, decodeChargeSessions, decodeNotes } from "./decode";
import type { DeviceRow, FleetItem } from "./types";
import type { TempConfig } from "./temp";
import type { RangeConfigRow } from "./range";
import type { HistSeries } from "./v2/history";
import type { TrendSeries, ChargeSession, NoteRow } from "./v2/trends";

const j = async (r: Response): Promise<unknown> => {
  if (!r.ok) throw new Error(String(r.status));
  return r.json();
};

const isObj = (x: unknown): x is Record<string, unknown> =>
  typeof x === "object" && x !== null;

// WEB-4: minimal runtime shape checks at the REST boundary. Failures throw so
// the existing catch-paths (keep last-known / show error) handle them.
export const getFleet = async (): Promise<{ fleet: FleetItem[] }> => {
  const r = await fetch("/web/fleet").then(j);
  const fleet = isObj(r) ? decodeSnapshot(r.fleet) : null;
  if (!fleet) throw new Error("malformed /web/fleet response");
  return { fleet };
};

export const getTempConfig = async (): Promise<{ configs: TempConfig[] }> => {
  const r = await fetch("/web/temp-config").then(j);
  const configs = isObj(r) ? decodeTempConfigs(r.configs) : null;
  if (!configs) throw new Error("malformed /web/temp-config response");
  return { configs };
};

export const getRangeConfig = async (): Promise<{ configs: RangeConfigRow[] }> => {
  const r = await fetch("/web/range-config").then(j);
  const configs = isObj(r) ? decodeRangeConfigs(r.configs) : null;
  if (!configs) throw new Error("malformed /web/range-config response");
  return { configs };
};

// GET /web/alert-config — the phone's synced low-SOC stage-seize threshold.
// When no phone has pushed config the server returns
// {seize_soc: null, alerts_on: true, updated_at_ms: 0}.
export interface AlertConfig {
  seize_soc: number | null;
  alerts_on: boolean;
  updated_at_ms: number;
}

export const DEFAULT_ALERT_CONFIG: AlertConfig = {
  seize_soc: null, alerts_on: true, updated_at_ms: 0,
};

export const getAlertConfig = async (): Promise<AlertConfig> => {
  const r = await fetch("/web/alert-config").then(j);
  if (!isObj(r) ||
      !(r.seize_soc === null || Number.isFinite(r.seize_soc)) ||
      typeof r.alerts_on !== "boolean" || !Number.isFinite(r.updated_at_ms)) {
    throw new Error("malformed /web/alert-config response");
  }
  return {
    seize_soc: r.seize_soc as number | null,
    alerts_on: r.alerts_on,
    updated_at_ms: r.updated_at_ms as number,
  };
};

export const getDevices = async (): Promise<{ devices: DeviceRow[] }> => {
  const r = await fetch("/web/devices").then(j);
  if (!isObj(r) || !Array.isArray(r.devices) ||
      !r.devices.every((d) => isObj(d) && typeof d.id === "string")) {
    throw new Error("malformed /web/devices response");
  }
  return { devices: r.devices as unknown as DeviceRow[] };
};

export const mintCode = async (): Promise<{ code: string; expires_at: string }> => {
  const r = await fetch("/web/enroll-codes", { method: "POST" }).then(j);
  if (!isObj(r) || typeof r.code !== "string") {
    throw new Error("malformed /web/enroll-codes response");
  }
  return r as { code: string; expires_at: string };
};

export const revokeDevice = (id: string): Promise<unknown> =>
  fetch(`/web/devices/${id}`, { method: "DELETE" }).then(j);

export const getHistory = async (hours = 24): Promise<{ series: HistSeries[] }> => {
  const r = await fetch(`/web/history?hours=${hours}`).then(j);
  const series = isObj(r) ? decodeHistory((r as { series?: unknown }).series) : null;
  if (!series) throw new Error("malformed /web/history response");
  return { series };
};

export const getTrends = async (address: string, fromMs: number, toMs: number): Promise<TrendSeries> => {
  const r = await fetch(`/web/trends?address=${encodeURIComponent(address)}&from_ms=${fromMs}&to_ms=${toMs}`).then(j);
  const s = decodeTrends(r);
  if (!s) throw new Error("malformed /web/trends response");
  return s;
};

export const getChargeSessions = async (address: string, days = 30): Promise<{ sessions: ChargeSession[] }> => {
  const r = await fetch(`/web/charge-sessions?address=${encodeURIComponent(address)}&days=${days}`).then(j);
  const sessions = isObj(r) ? decodeChargeSessions((r as { sessions?: unknown }).sessions) : null;
  if (!sessions) throw new Error("malformed /web/charge-sessions response");
  return { sessions };
};

export const getNotes = async (): Promise<{ notes: NoteRow[] }> => {
  const r = await fetch("/web/notes").then(j);
  const notes = isObj(r) ? decodeNotes((r as { notes?: unknown }).notes) : null;
  if (!notes) throw new Error("malformed /web/notes response");
  return { notes };
};

export const putNote = (baseId: string, body: string): Promise<unknown> =>
  fetch("/web/notes", { method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ base_id: baseId, body }) }).then(j);
