import { useEffect, useState, type CSSProperties } from "react";
import { getShares, revokeShare, type ShareRow } from "../../api";
import { lastOpened, remainingShort, shareStatus } from "../model/shares";

const errKind = (e: unknown): "auth" | "net" =>
  e instanceof Error && (e.message === "401" || e.message === "403") ? "auth" : "net";

const AUTH_MSG =
  "Not authorized — your session may have expired (admin required). Reload to sign in again.";

const btn: CSSProperties = {
  background: "var(--nav-active)", border: "1px solid var(--border)", color: "var(--text)",
  fontSize: 12, padding: "6px 12px", borderRadius: 7, cursor: "pointer",
};

/** Manage public location shares: who has a live link, whether they opened it, revoke.
 *  Links themselves are unrecoverable here by design (only sha256 is stored). */
export function SharesPanel() {
  const [shares, setShares] = useState<ShareRow[]>([]);
  const [loadErr, setLoadErr] = useState<"auth" | "net" | null>(null);
  const [actionErr, setActionErr] = useState<string | null>(null);
  const nowMs = Date.now();

  const refresh = () => getShares()
    .then((r) => { setShares(r.shares); setLoadErr(null); })
    .catch((e) => setLoadErr(errKind(e)));
  useEffect(() => { refresh(); }, []);

  const revoke = (id: number) => revokeShare(id)
    .then(() => { setActionErr(null); refresh(); })
    .catch((e) => setActionErr(errKind(e) === "auth" ? AUTH_MSG
      : "Couldn't revoke the share — check the connection and try again."));

  const statusCell = (s: ShareRow) => {
    const st = shareStatus(s, nowMs);
    if (st === "active") return <span style={{ color: "var(--ok)" }}>{remainingShort(s.expires_at, nowMs)}</span>;
    if (st === "revoked") return <span style={{ color: "var(--live)" }}>REVOKED</span>;
    return <span style={{ color: "var(--text-4)" }}>EXPIRED</span>;
  };

  if (loadErr === "auth") return <div style={{ fontSize: 12, color: "var(--text-3)" }}>{AUTH_MSG}</div>;

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
      <span style={{ fontSize: 13, color: "var(--text-2)" }}>
        Live location links you've shared. New links are created from the Journey page.
      </span>
      {actionErr && <div style={{ color: "var(--live)", fontSize: 12 }}>{actionErr}</div>}
      {loadErr === "net" && <div style={{ color: "var(--live)", fontSize: 12 }}>
        Couldn't load shares — check the connection.</div>}
      {shares.length === 0 && loadErr == null && (
        <span style={{ fontSize: 12, color: "var(--text-4)" }}>No active or recent shares.</span>
      )}
      {shares.length > 0 && (
        <table style={{ width: "100%", borderCollapse: "collapse", fontSize: 12 }}>
          <thead><tr style={{ textAlign: "left" }}>
            <th className="eyebrow" style={{ paddingBottom: 6 }}>Name</th>
            <th className="eyebrow">Status</th>
            <th className="eyebrow">Opened</th>
            <th className="eyebrow" />
          </tr></thead>
          <tbody>
            {shares.map((s) => (
              <tr key={s.id} style={{ borderTop: "1px solid var(--border)",
                opacity: shareStatus(s, nowMs) === "active" ? 1 : 0.55 }}>
                <td style={{ padding: "8px 8px 8px 0" }}>{s.name}</td>
                <td className="mono">{statusCell(s)}</td>
                <td className="mono" style={{ color: "var(--text-3)" }}>
                  {lastOpened(s, nowMs)}{s.access_count > 0 ? ` · ×${s.access_count}` : ""}
                </td>
                <td style={{ textAlign: "right" }}>
                  {shareStatus(s, nowMs) === "active" && (
                    <button style={btn} onClick={() => revoke(s.id)}>Revoke</button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
