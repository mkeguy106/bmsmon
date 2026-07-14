import type { Codec } from "../useLocalStorage";
import type { ThemeMode } from "./useTheme";

export interface V2Settings {
  distUnit: "mi" | "km";
  tempUnitPref: "F" | "C";
  mapMetricPref: "power" | "soc";
  themeMode: ThemeMode;
  deviceMode: "mobile" | "desktop" | null;
}

export const DEFAULT_V2_SETTINGS: V2Settings = {
  distUnit: "mi", tempUnitPref: "F", mapMetricPref: "power", themeMode: "system", deviceMode: null,
};

export const MOBILE_MAX = 820;
export function resolveMobile(deviceMode: V2Settings["deviceMode"], winW: number): boolean {
  if (deviceMode === "mobile") return true;
  if (deviceMode === "desktop") return false;
  return winW < MOBILE_MAX;
}

export const settingsCodec: Codec<V2Settings> = {
  decode: (raw) => {
    try {
      const o = JSON.parse(raw);
      if (typeof o !== "object" || o === null) return null;
      return { ...DEFAULT_V2_SETTINGS, ...(o as Partial<V2Settings>) };
    } catch { return null; }
  },
  encode: (v) => JSON.stringify(v),
};
