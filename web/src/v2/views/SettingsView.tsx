import type { ReactNode } from "react";
import type { V2Settings } from "../settings";
import { Segmented } from "../components/Segmented";
import { DevicesPanel } from "../components/DevicesPanel";
import { SharesPanel } from "../components/SharesPanel";
import { ApiKeysPanel } from "../components/ApiKeysPanel";

function SettingRow<T extends string>({ label, options, value, onChange }: {
  label: string; options: { value: T; label: string }[]; value: T; onChange: (v: T) => void;
}) {
  return (
    <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 12 }}>
      <span style={{ fontSize: 13, color: "var(--text-2)" }}>{label}</span>
      <Segmented options={options} value={value} onChange={onChange} />
    </div>
  );
}

function SettingsCard({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="card" style={{ display: "flex", flexDirection: "column", gap: 14 }}>
      <div className="eyebrow">{title}</div>
      {children}
    </div>
  );
}

function AboutRow({ text }: { text: string }) {
  return <div style={{ fontSize: 12, color: "var(--text-3)" }}>{text}</div>;
}

/** Settings edits the App's single settings instance (passed in), so a change applies
 *  everywhere at once and nothing can write a stale copy back over it (WEB-16). */
export function SettingsView({ settings, patch }: {
  settings: V2Settings; patch: (p: Partial<V2Settings>) => void;
}) {
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 16, maxWidth: 480, margin: "0 auto" }}>
      <SettingsCard title="Units">
        <SettingRow label="Distance"
          options={[{ value: "mi", label: "MI" }, { value: "km", label: "KM" }]}
          value={settings.distUnit} onChange={(distUnit) => patch({ distUnit })} />
        <SettingRow label="Temperature"
          options={[{ value: "F", label: "°F" }, { value: "C", label: "°C" }]}
          value={settings.tempUnitPref} onChange={(tempUnitPref) => patch({ tempUnitPref })} />
      </SettingsCard>

      <SettingsCard title="Journey Map">
        <SettingRow label="Trail color"
          options={[{ value: "power", label: "DISCHARGE" }, { value: "soc", label: "SOC" }]}
          value={settings.mapMetricPref} onChange={(mapMetricPref) => patch({ mapMetricPref })} />
      </SettingsCard>

      <SettingsCard title="Appearance">
        <SettingRow label="Theme"
          options={[
            { value: "system", label: "SYSTEM" },
            { value: "light", label: "LIGHT" },
            { value: "dark", label: "DARK" },
          ]}
          value={settings.themeMode} onChange={(themeMode) => patch({ themeMode })} />
      </SettingsCard>

      <SettingsCard title="Devices">
        <DevicesPanel />
      </SettingsCard>

      <SettingsCard title="Location shares">
        <SharesPanel />
      </SettingsCard>

      <SettingsCard title="API keys">
        <ApiKeysPanel />
      </SettingsCard>

      <SettingsCard title="About">
        <AboutRow text="8 packs · 4 bases" />
        <AboutRow text="Redodo 12V 100Ah LiFePO4 · Beken BK-BLE-1.0" />
        <AboutRow text="bmsmon.covert.life" />
        <AboutRow text="WebUI v2" />
      </SettingsCard>
    </div>
  );
}
