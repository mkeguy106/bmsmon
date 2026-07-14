import { useEffect, useState, type CSSProperties } from "react";
import { getDevices, mintCode, revokeDevice } from "../../api";
import type { DeviceRow } from "../../types";

// Distinguish an expired/insufficient SSO session (the api helpers throw Error(String(status))
// on 401/403 — these device endpoints are admin-gated) from a plain network/server failure.
const errKind = (e: unknown): "auth" | "net" =>
  e instanceof Error && (e.message === "401" || e.message === "403") ? "auth" : "net";

const AUTH_MSG =
  "Not authorized — your session may have expired (admin required). Reload to sign in again.";

const btn: CSSProperties = {
  background: "var(--nav-active)", border: "1px solid var(--border)", color: "var(--text)",
  fontSize: 12, padding: "6px 12px", borderRadius: 7, cursor: "pointer",
};

/** v2 device admin (enroll / list / revoke), ported from v1 AdminDevices. Rendered inside Settings. */
export function DevicesPanel() {
  const [devices, setDevices] = useState<DeviceRow[]>([]);
  const [loadErr, setLoadErr] = useState<"auth" | "net" | null>(null);
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
  // qrcode is imported LAZILY here (its ~168 kB source otherwise lands in the shared chunk
  // every visitor loads) — it's only needed once an admin actually mints an enroll code.
  useEffect(() => {
    if (!code) { setQr(""); return; }
    let alive = true;
    const payload = JSON.stringify({ base: window.location.origin, code });
    import("qrcode")
      .then((m) => m.default.toDataURL(payload, { width: 260, margin: 1 }))
      .then((url) => { if (alive) setQr(url); })
      .catch(() => { if (alive) setQr(""); });
    return () => { alive = false; };
  }, [code]);

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
      <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
        <span style={{ fontSize: 13, color: "var(--text-2)" }}>Enroll or manage phones that sync telemetry.</span>
        <button style={{ ...btn, marginLeft: "auto" }} onClick={mint}>Enroll device</button>
      </div>

      {actionErr && <div style={{ color: "var(--live)", fontSize: 12 }}>{actionErr}</div>}

      {code && (
        <div style={{ display: "flex", gap: 16, alignItems: "center", flexWrap: "wrap",
          padding: 14, background: "var(--panel-2)", border: "1px solid var(--border)", borderRadius: 8 }}>
          {qr && <img src={qr} alt="enrollment QR" width={168} height={168}
            style={{ borderRadius: 6, background: "#fff", padding: 8 }} />}
          <div style={{ minWidth: 180 }}>
            <div style={{ fontSize: 12, color: "var(--text-2)", marginBottom: 10 }}>
              In the bmsmon app: <strong style={{ color: "var(--text)" }}>Cloud sync → Scan QR</strong>. Expires in 10 min.
            </div>
            <div className="eyebrow">Code</div>
            <div className="mono" style={{ fontSize: 22, color: "var(--ok)", letterSpacing: 3 }}>{code}</div>
            <div className="eyebrow" style={{ marginTop: 8 }}>Server</div>
            <div className="mono" style={{ fontSize: 12, color: "var(--text-2)" }}>{window.location.origin}</div>
          </div>
        </div>
      )}

      {loadErr ? (
        <div style={{ fontSize: 12, color: "var(--text-2)", display: "flex", alignItems: "center", gap: 12 }}>
          {loadErr === "auth" ? <span>{AUTH_MSG}</span>
            : <><span>Couldn't load devices.</span><button style={btn} onClick={refresh}>Retry</button></>}
        </div>
      ) : devices.length === 0 ? (
        <div style={{ fontSize: 12, color: "var(--text-4)" }}>No devices enrolled yet.</div>
      ) : (
        <table style={{ width: "100%", borderCollapse: "collapse", fontSize: 12 }}>
          <thead>
            <tr style={{ textAlign: "left" }}>
              <th className="eyebrow" style={{ fontWeight: 400, paddingBottom: 6 }}>LABEL</th>
              <th className="eyebrow" style={{ fontWeight: 400 }}>LAST SEEN</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {devices.map((d) => (
              <tr key={d.id} style={{ borderTop: "1px solid var(--border)", opacity: d.revoked ? 0.4 : 1 }}>
                <td style={{ padding: "8px 0", color: "var(--text)" }}>{d.label ?? d.install_uuid}</td>
                <td className="mono" style={{ color: "var(--text-3)" }}>{d.last_seen_at ?? "—"}</td>
                <td style={{ textAlign: "right" }}>
                  {!d.revoked && <button style={btn} onClick={() => revoke(d.id)}>Revoke</button>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
