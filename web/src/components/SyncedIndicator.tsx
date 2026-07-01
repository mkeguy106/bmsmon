import type { TempConfig } from "../temp";
import { relAgo } from "../util";

/** Read-only indicator: thresholds come from the phone; the web dashboard only mirrors them. */
export function SyncedIndicator({ config, now }: { config: TempConfig | null; now: number }) {
  const synced = config != null;
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 9, padding: "9px 14px", borderRadius: 10,
      background: "var(--input-bg)", border: "1px solid var(--border)" }}>
      <span style={{ width: 7, height: 7, borderRadius: "50%", flex: "none",
        background: synced ? "var(--regen)" : "var(--text3)" }} />
      <span style={{ fontSize: 13, color: "var(--text2)" }}>
        {synced ? (
          <>Alert thresholds <strong style={{ color: "var(--text)", fontWeight: 600 }}>synced from Android</strong>
            {" · updated "}{relAgo(config!.updated_at_ms, now)}</>
        ) : (
          <>Waiting for the phone to sync alert thresholds — showing profile defaults.</>
        )}
      </span>
      <span className="mono" style={{ marginLeft: "auto", flex: "none", fontSize: 10, letterSpacing: 1,
        color: "var(--text3)", border: "1px solid var(--input-border)", borderRadius: 6, padding: "3px 8px" }}>
        READ-ONLY · EDIT ON PHONE
      </span>
    </div>
  );
}
