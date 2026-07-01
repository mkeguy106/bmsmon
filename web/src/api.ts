import type { DeviceRow, FleetItem } from "./types";
import type { TempConfig } from "./temp";

const j = async (r: Response) => { if (!r.ok) throw new Error(String(r.status)); return r.json(); };

export const getFleet = (): Promise<{ fleet: FleetItem[] }> => fetch("/web/fleet").then(j);
export const getTempConfig = (): Promise<{ configs: TempConfig[] }> => fetch("/web/temp-config").then(j);
export const getDevices = (): Promise<{ devices: DeviceRow[] }> => fetch("/web/devices").then(j);
export const mintCode = (): Promise<{ code: string; expires_at: string }> =>
  fetch("/web/enroll-codes", { method: "POST" }).then(j);
export const revokeDevice = (id: string): Promise<unknown> =>
  fetch(`/web/devices/${id}`, { method: "DELETE" }).then(j);
