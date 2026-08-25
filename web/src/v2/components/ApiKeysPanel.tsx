import { useEffect, useState, type CSSProperties } from "react";
import { createApiKey, getApiKeys, revokeApiKey, type ApiKeyRow } from "../../api";

const errKind = (e: unknown): "auth" | "net" =>
  e instanceof Error && (e.message === "401" || e.message === "403") ? "auth" : "net";

const AUTH_MSG =
  "Not authorized — your session may have expired (admin required). Reload to sign in again.";

const btn: CSSProperties = {
  background: "var(--nav-active)", border: "1px solid var(--border)", color: "var(--text)",
  fontSize: 12, padding: "6px 12px", borderRadius: 7, cursor: "pointer",
};

const input: CSSProperties = {
  background: "var(--input-bg, var(--nav-active))", border: "1px solid var(--border)",
  color: "var(--text)", fontSize: 12, padding: "6px 10px", borderRadius: 7, flex: 1, minWidth: 0,
};

const ago = (iso: string | null): string => {
  if (!iso) return "never";
  const ms = Date.now() - new Date(iso).getTime();
  if (ms < 60_000) return "just now";
  if (ms < 3_600_000) return `${Math.floor(ms / 60_000)}m ago`;
  if (ms < 86_400_000) return `${Math.floor(ms / 3_600_000)}h ago`;
  return `${Math.floor(ms / 86_400_000)}d ago`;
};

/** Manage the read-only API keys headless clients use (the desktop widgets).
 *  A key is shown exactly once at mint time — only its sha256 is stored, so a lost
 *  key is re-minted rather than recovered. Keys grant GET /api/v1/groups and
 *  nothing else: no writes, and no location. */
export function ApiKeysPanel() {
  const [keys, setKeys] = useState<ApiKeyRow[]>([]);
  const [name, setName] = useState("");
  const [minted, setMinted] = useState<{ name: string; key: string } | null>(null);
  const [copied, setCopied] = useState(false);
  const [loadErr, setLoadErr] = useState<"auth" | "net" | null>(null);
  const [actionErr, setActionErr] = useState<string | null>(null);

  const refresh = () => getApiKeys()
    .then((r) => { setKeys(r.keys); setLoadErr(null); })
    .catch((e) => setLoadErr(errKind(e)));
  useEffect(() => { refresh(); }, []);

  const mint = () => {
    const n = name.trim();
    if (!n) return;
    createApiKey(n)
      .then((r) => { setMinted({ name: r.name, key: r.key }); setCopied(false);
                     setName(""); setActionErr(null); refresh(); })
      .catch((e) => setActionErr(errKind(e) === "auth" ? AUTH_MSG
        : "Couldn't create the key — check the connection and try again."));
  };

  const revoke = (id: string) => revokeApiKey(id)
    .then(() => { setActionErr(null); refresh(); })
    .catch((e) => setActionErr(errKind(e) === "auth" ? AUTH_MSG
      : "Couldn't revoke the key — check the connection and try again."));

  const copy = (k: string) => {
    navigator.clipboard?.writeText(k).then(() => setCopied(true)).catch(() => setCopied(false));
  };

  if (loadErr === "auth") return <div style={{ fontSize: 12, color: "var(--text-3)" }}>{AUTH_MSG}</div>;

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
      <span style={{ fontSize: 13, color: "var(--text-2)" }}>
        Read-only keys for headless clients such as the desktop widgets. A key can read
        battery telemetry only — it cannot write, and it never sees location.
      </span>

      {actionErr && <div style={{ color: "var(--live)", fontSize: 12 }}>{actionErr}</div>}
      {loadErr === "net" && <div style={{ color: "var(--live)", fontSize: 12 }}>
        Couldn't load keys — check the connection.</div>}

      <div style={{ display: "flex", gap: 8 }}>
        <input style={input} value={name} placeholder="What is this key for?"
          maxLength={80} onChange={(e) => setName(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter") mint(); }} />
        <button style={btn} onClick={mint} disabled={!name.trim()}>Create</button>
      </div>

      {/* Shown once. There is no way back to this value after the panel is closed. */}
      {minted && (
        <div style={{ border: "1px solid var(--border)", borderRadius: 8, padding: 10,
                      display: "flex", flexDirection: "column", gap: 8 }}>
          <span style={{ fontSize: 12, color: "var(--warn)" }}>
            Copy this now — it is shown once and cannot be recovered.
          </span>
          <code className="mono" style={{ fontSize: 12, wordBreak: "break-all",
                                          color: "var(--text)" }}>{minted.key}</code>
          <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
            <button style={btn} onClick={() => copy(minted.key)}>
              {copied ? "Copied" : "Copy"}
            </button>
            <button style={btn} onClick={() => setMinted(null)}>Done</button>
          </div>
        </div>
      )}

      {keys.length === 0 && loadErr == null && (
        <span style={{ fontSize: 12, color: "var(--text-4)" }}>No API keys yet.</span>
      )}

      {keys.length > 0 && (
        <table style={{ width: "100%", borderCollapse: "collapse", fontSize: 12 }}>
          <thead><tr style={{ textAlign: "left" }}>
            <th className="eyebrow" style={{ paddingBottom: 6 }}>Name</th>
            <th className="eyebrow">Status</th>
            <th className="eyebrow">Last used</th>
            <th className="eyebrow" />
          </tr></thead>
          <tbody>
            {keys.map((k) => (
              <tr key={k.id} style={{ borderTop: "1px solid var(--border)",
                                      opacity: k.revoked_at ? 0.55 : 1 }}>
                <td style={{ padding: "8px 8px 8px 0" }}>{k.name}</td>
                <td className="mono">
                  {k.revoked_at
                    ? <span style={{ color: "var(--live)" }}>REVOKED</span>
                    : <span style={{ color: "var(--ok)" }}>ACTIVE</span>}
                </td>
                <td className="mono" style={{ color: "var(--text-3)" }}>{ago(k.last_used_at)}</td>
                <td style={{ textAlign: "right" }}>
                  {!k.revoked_at && (
                    <button style={btn} onClick={() => revoke(k.id)}>Revoke</button>
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
