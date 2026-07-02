import { decodeSnapshot, decodeTempConfigs } from "./decode";
import type { DeviceRow, FleetItem } from "./types";
import type { TempConfig } from "./temp";

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
