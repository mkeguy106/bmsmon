import type { Banner } from "../model/connection";

/** Full-width strip under the TopBar while the web session or server link is broken. */
export function ConnectionBanner({ banner }: { banner: Banner }) {
  const color = banner.tone === "crit" ? "var(--live)" : "var(--warn)";
  return (
    <div role="status" style={{ display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap",
      padding: "8px 16px", background: "var(--panel)", borderBottom: `1px solid ${color}` }}>
      <span style={{ width: 8, height: 8, borderRadius: "50%", background: color, flexShrink: 0 }} />
      <span className="mono" style={{ fontSize: 12, fontWeight: 600, color }}>{banner.title}</span>
      <span style={{ fontSize: 12, color: "var(--text-2)", flex: 1, minWidth: 160 }}>{banner.detail}</span>
      {banner.reload && (
        <button type="button" onClick={() => window.location.reload()}
          style={{ fontSize: 12, padding: "4px 12px", borderRadius: 6, cursor: "pointer",
            border: `1px solid ${color}`, background: "transparent", color }}>
          Reload
        </button>
      )}
    </div>
  );
}
