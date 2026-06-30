import { useEffect, useState } from "react";
import { getDevices, mintCode, revokeDevice } from "../api";
import type { DeviceRow } from "../types";

export function AdminDevices() {
  const [devices, setDevices] = useState<DeviceRow[]>([]);
  const [code, setCode] = useState<string | null>(null);
  const refresh = () => getDevices().then((d) => setDevices(d.devices)).catch(() => {});
  useEffect(() => { refresh(); }, []);

  return (
    <div style={{ background: "var(--card)", border: "1px solid var(--border)", borderRadius: 14, padding: 16 }}>
      <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
        <strong>Devices</strong>
        <button style={{ marginLeft: "auto" }}
          onClick={() => mintCode().then((r) => setCode(r.code)).catch(() => alert("Not authorized"))}>
          Enroll device
        </button>
      </div>
      {code && (
        <div style={{ margin: "12px 0", padding: 12, background: "var(--input-bg)", borderRadius: 10 }}>
          <div style={{ color: "var(--text3)", fontSize: 12 }}>One-time code (expires in 10 min):</div>
          <div className="mono" style={{ fontSize: 24, color: "var(--accent)", letterSpacing: 3 }}>{code}</div>
        </div>
      )}
      <table style={{ width: "100%", marginTop: 12, borderCollapse: "collapse", fontSize: 13 }}>
        <thead><tr style={{ color: "var(--text3)", textAlign: "left" }}>
          <th>Label</th><th>Last seen</th><th></th></tr></thead>
        <tbody>
          {devices.map((d) => (
            <tr key={d.id} style={{ borderTop: "1px solid var(--border)", opacity: d.revoked ? 0.4 : 1 }}>
              <td style={{ padding: "8px 0" }}>{d.label ?? d.install_uuid}</td>
              <td className="mono">{d.last_seen_at ?? "—"}</td>
              <td style={{ textAlign: "right" }}>
                {!d.revoked && <button onClick={() => revokeDevice(d.id).then(refresh).catch(() => alert("Not authorized"))}>Revoke</button>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
