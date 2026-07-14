import { useState, type CSSProperties } from "react";
import { createShare } from "../../api";

const DURATIONS = [
  { value: "1h", label: "1 hour" },
  { value: "1d", label: "1 day" },
  { value: "1w", label: "1 week" },
] as const;
type Duration = (typeof DURATIONS)[number]["value"];

/** Mint a public location-share link: name the recipient, pick a duration, then hand
 *  the URL to the native share sheet (mobile) or the clipboard (desktop). The URL is
 *  shown once — it cannot be recovered later (only sha256 is stored server-side). */
export function ShareDialog({ onClose }: { onClose: () => void }) {
  const [name, setName] = useState("");
  const [duration, setDuration] = useState<Duration>("1d");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [url, setUrl] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  const copy = (full: string) =>
    navigator.clipboard.writeText(full).then(() => setCopied(true)).catch(() => {});

  const create = () => {
    setBusy(true);
    setErr(null);
    createShare(name.trim(), duration)
      .then((r) => {
        const full = window.location.origin + r.path;
        setUrl(full);
        if (navigator.share) {
          navigator.share({ title: "Live location", text: "Follow my live location", url: full })
            .catch(() => { /* sheet dismissed — the link stays on screen to copy */ });
        } else {
          copy(full);
        }
      })
      .catch((e) => setErr(
        e instanceof Error && (e.message === "401" || e.message === "403")
          ? "Not authorized — your session may have expired (admin required)."
          : "Couldn't create the share link — check the connection and try again."))
      .finally(() => setBusy(false));
  };

  return (
    <div style={backdrop} onClick={onClose}>
      <div style={card} onClick={(e) => e.stopPropagation()}>
        <div className="eyebrow" style={{ marginBottom: 12 }}>Share live location</div>
        {url == null ? (
          <>
            <label style={{ display: "block", fontSize: 12, color: "var(--text-2)", marginBottom: 6 }}>
              Who is this for?
            </label>
            <input value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. Dave"
              maxLength={80} autoFocus style={input} />
            <div style={{ display: "flex", gap: 8, margin: "14px 0" }}>
              {DURATIONS.map((d) => (
                <button key={d.value} onClick={() => setDuration(d.value)}
                  style={{ ...chip, ...(duration === d.value ? chipOn : null) }}>
                  {d.label}
                </button>
              ))}
            </div>
            {err && <div style={{ color: "var(--live)", fontSize: 12, marginBottom: 10 }}>{err}</div>}
            <div style={{ display: "flex", gap: 8, justifyContent: "flex-end" }}>
              <button style={btn} onClick={onClose}>Cancel</button>
              <button style={{ ...btn, background: "var(--ok)", color: "#fff", border: "none" }}
                disabled={busy || name.trim() === ""} onClick={create}>
                {busy ? "Creating…" : "Create link"}
              </button>
            </div>
          </>
        ) : (
          <>
            <div style={{ fontSize: 12, color: "var(--text-2)", marginBottom: 8 }}>
              Link for <strong style={{ color: "var(--text)" }}>{name.trim()}</strong> — shown once,
              save it now:
            </div>
            <div className="mono" style={{ fontSize: 11, wordBreak: "break-all",
              padding: 10, background: "var(--panel-2)", border: "1px solid var(--border)",
              borderRadius: 7, marginBottom: 12 }}>{url}</div>
            <div style={{ display: "flex", gap: 8, justifyContent: "flex-end", alignItems: "center" }}>
              {copied && <span style={{ fontSize: 11, color: "var(--ok)", marginRight: "auto" }}>Copied</span>}
              <button style={btn} onClick={() => copy(url)}>Copy</button>
              <button style={btn} onClick={onClose}>Done</button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

const backdrop: CSSProperties = {
  position: "fixed", inset: 0, zIndex: 2000, background: "rgba(0,0,0,.55)",
  display: "flex", alignItems: "center", justifyContent: "center", padding: 20,
};
const card: CSSProperties = {
  width: "100%", maxWidth: 360, background: "var(--panel)",
  border: "1px solid var(--border-strong)", borderRadius: 10, padding: 18,
};
const input: CSSProperties = {
  width: "100%", padding: "8px 10px", fontSize: 13, color: "var(--text)",
  background: "var(--panel-2)", border: "1px solid var(--border)", borderRadius: 7,
};
const btn: CSSProperties = {
  background: "var(--nav-active)", border: "1px solid var(--border)", color: "var(--text)",
  fontSize: 12, padding: "6px 12px", borderRadius: 7, cursor: "pointer",
};
const chip: CSSProperties = { ...btn, flex: 1 };
const chipOn: CSSProperties = { background: "var(--ok)", color: "#fff", border: "none" };
