import { useEffect, useState } from "react";
import QRCode from "qrcode";
import { getDevices, mintCode, revokeDevice } from "../api";
import type { DeviceRow } from "../types";

export function AdminDevices() {
  const [devices, setDevices] = useState<DeviceRow[]>([]);
  const [code, setCode] = useState<string | null>(null);
  const [qr, setQr] = useState<string>("");
  const refresh = () => getDevices().then((d) => setDevices(d.devices)).catch(() => {});
  useEffect(() => { refresh(); }, []);

  // Encode { base, code } so the phone gets the server URL AND the one-time code in one scan.
  useEffect(() => {
    if (!code) { setQr(""); return; }
    const payload = JSON.stringify({ base: window.location.origin, code });
    QRCode.toDataURL(payload, { width: 260, margin: 1 }).then(setQr).catch(() => setQr(""));
  }, [code]);

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
        <div style={{ margin: "12px 0", padding: 14, background: "var(--input-bg)", borderRadius: 10,
          display: "flex", gap: 16, alignItems: "center", flexWrap: "wrap" }}>
          {qr && (
            <img src={qr} alt="enrollment QR" width={180} height={180}
              style={{ borderRadius: 8, background: "#fff", padding: 8 }} />
          )}
          <div style={{ minWidth: 200 }}>
            <div style={{ color: "var(--text2)", fontSize: 13, marginBottom: 10 }}>
              In the bmsmon app: <strong style={{ color: "var(--text)" }}>Cloud sync → Scan QR</strong>. Expires in 10 min.
            </div>
            <div style={{ color: "var(--text3)", fontSize: 11 }}>Code</div>
            <div className="mono" style={{ fontSize: 22, color: "var(--accent)", letterSpacing: 3 }}>{code}</div>
            <div style={{ color: "var(--text3)", fontSize: 11, marginTop: 8 }}>Server</div>
            <div className="mono" style={{ fontSize: 12, color: "var(--text2)" }}>{window.location.origin}</div>
          </div>
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
