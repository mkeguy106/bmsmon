import { useEffect, useState } from "react";
import QRCode from "qrcode";
import { getDevices, mintCode, revokeDevice } from "../api";
import type { DeviceRow } from "../types";

// WEB-10: distinguish "the SSO session is gone" (401/403 from the api helpers,
// which throw Error(String(status))) from a plain network/server failure.
const errKind = (e: unknown): "auth" | "net" =>
  e instanceof Error && (e.message === "401" || e.message === "403") ? "auth" : "net";

const AUTH_MSG = "Not authorized — your session may have expired. Reload to sign in again.";

export function AdminDevices() {
  const [devices, setDevices] = useState<DeviceRow[]>([]);
  const [loadErr, setLoadErr] = useState<"auth" | "net" | null>(null);
  // Inline mint/revoke failure message (replaces the old blanket alert()).
  const [actionErr, setActionErr] = useState<string | null>(null);
  const [code, setCode] = useState<string | null>(null);
  const [qr, setQr] = useState<string>("");
  const refresh = () => getDevices()
    .then((d) => { setDevices(d.devices); setLoadErr(null); })
    .catch((e) => setLoadErr(errKind(e)));
  useEffect(() => { refresh(); }, []);

  const mint = () => mintCode()
    .then((r) => { setCode(r.code); setActionErr(null); })
    .catch((e) => setActionErr(errKind(e) === "auth" ? AUTH_MSG
      : "Couldn't mint an enroll code — check the connection and try again."));
  const revoke = (id: string) => revokeDevice(id)
    .then(() => { setActionErr(null); refresh(); })
    .catch((e) => setActionErr(errKind(e) === "auth" ? AUTH_MSG
      : "Couldn't revoke the device — check the connection and try again."));

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
        <button style={{ marginLeft: "auto" }} onClick={mint}>
          Enroll device
        </button>
      </div>
      {actionErr && (
        <div style={{ marginTop: 10, color: "var(--critical)", fontSize: 13 }}>{actionErr}</div>
      )}
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
      {loadErr ? (
        <div style={{ marginTop: 12, color: "var(--text2)", fontSize: 13,
          display: "flex", alignItems: "center", gap: 12 }}>
          {loadErr === "auth"
            ? <span>{AUTH_MSG}</span>
            : <><span>Couldn't load devices.</span><button onClick={refresh}>Retry</button></>}
        </div>
      ) : (
      <table style={{ width: "100%", marginTop: 12, borderCollapse: "collapse", fontSize: 13 }}>
        <thead><tr style={{ color: "var(--text3)", textAlign: "left" }}>
          <th>Label</th><th>Last seen</th><th></th></tr></thead>
        <tbody>
          {devices.map((d) => (
            <tr key={d.id} style={{ borderTop: "1px solid var(--border)", opacity: d.revoked ? 0.4 : 1 }}>
              <td style={{ padding: "8px 0" }}>{d.label ?? d.install_uuid}</td>
              <td className="mono">{d.last_seen_at ?? "—"}</td>
              <td style={{ textAlign: "right" }}>
                {!d.revoked && <button onClick={() => revoke(d.id)}>Revoke</button>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      )}
    </div>
  );
}
