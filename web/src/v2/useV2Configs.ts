import { useEffect, useState } from "react";
import { getTempConfig } from "../api";
import { visibleInterval } from "../visiblePoll";
import { selectActiveConfig, type TempConfig } from "../temp";

const REFRESH_MS = 60_000;

/** Polls the phone-synced temperature config every 60 s (mirrors v1 App.tsx; skipped while
 *  the tab is hidden). The v2 capacity ladder is a fixed design constant (see
 *  model/alerts.ts), so no alert-config fetch is needed. */
export function useV2Configs(): { tempConfig: TempConfig | null } {
  const [tempConfig, setTempConfig] = useState<TempConfig | null>(null);
  useEffect(() => {
    let alive = true;
    const load = () => {
      getTempConfig().then((r) => { if (alive) setTempConfig(selectActiveConfig(r.configs)); }).catch(() => { /* keep last */ });
    };
    load();
    const stop = visibleInterval(load, REFRESH_MS);
    return () => { alive = false; stop(); };
  }, []);
  return { tempConfig };
}
