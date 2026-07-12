import { useCallback } from "react";
import { readStored, useLocalStorage } from "../useLocalStorage";
import { DEFAULT_V2_SETTINGS, settingsCodec, type V2Settings } from "./settings";

/** localStorage-backed v2 settings. Seeds themeMode from the legacy bmsmon-theme on first run. */
export function useV2Settings() {
  const [settings, setSettings] = useLocalStorage<V2Settings>(
    "bmsmon-v2-settings",
    () => {
      if (readStored("bmsmon-v2-settings", settingsCodec.decode) != null) return DEFAULT_V2_SETTINGS;
      const legacy = (() => { try { return localStorage.getItem("bmsmon-theme"); } catch { return null; } })();
      return { ...DEFAULT_V2_SETTINGS, themeMode: legacy === "light" || legacy === "dark" ? legacy : "system" };
    },
    settingsCodec,
  );
  const patch = useCallback(
    (p: Partial<V2Settings>) => setSettings((prev) => ({ ...prev, ...p })),
    [setSettings],
  );
  return [settings, patch] as const;
}
