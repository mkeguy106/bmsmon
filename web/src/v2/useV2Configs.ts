import { useEffect, useState } from "react";
import { DEFAULT_ALERT_CONFIG, getAlertConfig, getTempConfig, type AlertConfig } from "../api";
import { visibleInterval } from "../visiblePoll";
import { selectActiveConfig, type TempConfig } from "../temp";

const REFRESH_MS = 60_000;

/** Polls the phone-synced configs every 60 s (mirrors v1 App.tsx; skipped while the tab is
 *  hidden): the temperature zones, and the capacity seize config (`seize_soc`/`alerts_on`)
 *  that drives the v2 stage seize. The v2 capacity ALERT ladder is still a fixed design
 *  constant (see model/alerts.ts) — only the seize is synced. On a failed fetch both keep
 *  their last value; the seize starts from DEFAULT_ALERT_CONFIG (on, threshold 30). */
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
    const stop = visibleInterval(load, REFRESH_MS);
    return () => { alive = false; stop(); };
  }, []);
  return { tempConfig, alertConfig };
}
