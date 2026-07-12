import type { Base, BasePack, BaseStatus } from "../fleet";
import { Bar, Chip } from "./Atoms";
import { socColor, sohColor } from "../colors";

const STATUS_DOT: Record<BaseStatus, string> = {
  "in-use": "var(--ok)",
  charging: "var(--warn)",
  backup: "var(--ok)",
  spares: "var(--text-4)",
  offline: "var(--text-4)",
};
const STATUS_TAG: Record<BaseStatus, string> = {
  "in-use": "IN USE",
  charging: "CHARGING",
  backup: "BACKUP",
  spares: "SPARES",
  offline: "OFFLINE",
};

function PackRow({ pack, onStage }: { pack: BasePack; onStage: () => void }) {
  const { item, letter, connected } = pack;
  const soc = item.soc;
  return (
    <button
      type="button"
      onClick={onStage}
      style={{
        display: "flex", alignItems: "center", gap: 8, width: "100%",
        padding: "6px 4px", border: "none", borderRadius: 6, background: "transparent",
        color: "inherit", cursor: "pointer", font: "inherit", textAlign: "left",
        opacity: connected ? 1 : 0.55,
      }}
      onMouseEnter={(e) => { e.currentTarget.style.background = "var(--hover)"; }}
      onMouseLeave={(e) => { e.currentTarget.style.background = "transparent"; }}
    >
      <span className="mono" style={{ fontSize: 11, width: 12, color: "var(--text-3)" }}>{letter}</span>
      <span style={{ flex: 1 }}>
        <Bar frac={connected && soc != null ? soc / 100 : 0} color={socColor(soc, connected)} />
      </span>
      <span className="mono" style={{ fontSize: 11, width: 34, textAlign: "right", color: "var(--text-2)" }}>
        {connected && soc != null ? `${Math.round(soc)}%` : "—"}
      </span>
      <span style={{ width: 8, height: 8, borderRadius: "50%", background: sohColor(item.soh) }}
        title={item.soh != null ? `SOH ${Math.round(item.soh)}%` : "SOH —"} />
    </button>
  );
}

export function CommandFleetRail({ bases, stageBaseId, onStage }: {
  bases: Base[]; stageBaseId: string; onStage: (id: string) => void;
}) {
  return (
    <div style={{ width: 252, flex: "0 0 252px", display: "flex", flexDirection: "column",
      gap: 12, overflowY: "auto", padding: "2px 2px 12px" }}>
      <div className="eyebrow" style={{ padding: "0 4px" }}>Fleet</div>
      {bases.map((base) => {
        const staged = base.id === stageBaseId;
        return (
          <div key={base.id} className="card" style={{ padding: 12,
            border: staged ? "1px solid var(--border-strong)" : "1px solid var(--border)" }}>
            <button
              type="button"
              onClick={() => onStage(base.id)}
              style={{
                display: "flex", alignItems: "center", gap: 8, width: "100%",
                padding: 0, marginBottom: 8, border: "none", background: "transparent",
                color: "inherit", cursor: "pointer", font: "inherit", textAlign: "left",
              }}
            >
              <span style={{ width: 9, height: 9, borderRadius: "50%", background: STATUS_DOT[base.status] }} />
              <span className="mono" style={{ fontSize: 12, fontWeight: 600, flex: 1 }}>Base {base.id}</span>
              {staged && <Chip tone="var(--text)">Stage</Chip>}
              <Chip tone={STATUS_DOT[base.status]}>{STATUS_TAG[base.status]}</Chip>
            </button>
            <div style={{ display: "flex", flexDirection: "column", gap: 2 }}>
              {base.packs.map((p) => (
                <PackRow key={p.item.address} pack={p} onStage={() => onStage(base.id)} />
              ))}
            </div>
          </div>
        );
      })}
    </div>
  );
}
