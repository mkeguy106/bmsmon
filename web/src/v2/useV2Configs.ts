import { useEffect, useState } from "react";
import { getTempConfig, getAlertConfig, DEFAULT_ALERT_CONFIG, type AlertConfig } from "../api";
import { selectActiveConfig, type TempConfig } from "../temp";

const REFRESH_MS = 60_000;

/** Polls the phone-synced temperature + capacity-seize config every 60 s (mirrors v1 App.tsx). */
export function useV2Configs(): { tempConfig: TempConfig | null; alertConfig: AlertConfig } {
  const [tempConfig, setTempConfig] = useState<TempConfig | null>(null);
  const [alertConfig, setAlertConfig] = useState<AlertConfig>(DEFAULT_ALERT_CONFIG);
  useEffect(() => {
    let alive = true;
    const load = () => {
      getTempConfig().then((r) => { if (alive) setTempConfig(selectActiveConfig(r.configs)); }).catch(() => { /* keep last */ });
      getAlertConfig().then((c) => { if (alive) setAlertConfig(c); }).catch(() => { /* keep last */ });
    };
    load();
    const t = setInterval(load, REFRESH_MS);
    return () => { alive = false; clearInterval(t); };
  }, []);
  return { tempConfig, alertConfig };
}
