import type { Base, BasePack, BaseStatus } from "../fleet";
import { DAILY_DRIVER_BASE, isCharging } from "../fleet";
import type { FleetItem } from "../../types";
import { estimatePackRange, minRange, SEED_RANGE_PARAMS, type PackRange, type RangeParams } from "../../range";
import { Ring } from "./Ring";
import { StatTile, CellTiles, Chip } from "./Atoms";
import { sohColor } from "../colors";

const THERMAL_C = 44;

const STATUS_COLOR: Record<BaseStatus, string> = {
  "in-use": "var(--ok)", charging: "var(--warn)", backup: "var(--ok)",
  spares: "var(--text-4)", offline: "var(--text-4)",
};
const STATUS_TAG: Record<BaseStatus, string> = {
  "in-use": "IN USE", charging: "CHARGING", backup: "BACKUP",
  spares: "SPARES", offline: "OFFLINE",
};

function roleText(base: Base): string {
  const driver = base.id === DAILY_DRIVER_BASE ? "Daily driver" : "Reserve base";
  const st = { "in-use": "in use now", charging: "on charge", backup: "backup ready",
    spares: "spare", offline: "offline" }[base.status];
  return `${driver} · ${st}`;
}
export function fmtTemp(c: number | null | undefined, tempF: boolean): string {
  if (c == null) return "—";
  const v = tempF ? c * 9 / 5 + 32 : c;
  return `${v.toFixed(1)}°${tempF ? "F" : "C"}`;
}
function fmtEta(min: number | null | undefined): string {
  if (min == null || !Number.isFinite(min) || min <= 0) return "—";
  const m = Math.round(min);
  if (m < 60) return `${m} min`;
  return `${Math.floor(m / 60)}h ${String(m % 60).padStart(2, "0")}m`;
}
function num(v: number | null | undefined, digits = 0): string {
  return v == null || !Number.isFinite(v) ? "—" : v.toFixed(digits);
}

function PackCard({ item, letter, tempF }: { item: FleetItem; letter: string; tempF: boolean }) {
  const connected = true; // rendered only for connected packs (offline handled by ring opacity upstream)
  return (
    <div style={{ flex: 1, minWidth: 0, display: "flex", flexDirection: "column", gap: 12 }}>
      <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
        <span className="mono" style={{ fontSize: 12, fontWeight: 600 }}>{item.alias ?? `Pack ${letter}`}</span>
        <span style={{ width: 8, height: 8, borderRadius: "50%", background: sohColor(item.soh) }}
          title={item.soh != null ? `SOH ${Math.round(item.soh)}%` : "SOH —"} />
      </div>
      <div style={{ display: "flex", justifyContent: "center" }}>
        <Ring soc={item.soc} power={item.power_w} current={item.current_a} connected={connected} size={132} />
      </div>
      <div className="mono" style={{ fontSize: 12, textAlign: "center", color: "var(--text-3)" }}>
        {num(item.power_w)} W · {num(item.current_a, 1)} A
      </div>
      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 8 }}>
        <StatTile label="Capacity" value={`${num(item.remaining_ah, 1)}/${num(item.full_charge_ah, 0)} Ah`} />
        <StatTile label="Temp" value={fmtTemp(item.temp_c, tempF)} />
        <StatTile label="Health" value={`${num(item.soh)}%`} />
        <StatTile label="Cycles" value={num(item.cycles)} />
      </div>
      <CellTiles item={item} />
    </div>
  );
}

function FlowTile({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div className="card" style={{ padding: 12, flex: 1 }}>
      <div className="eyebrow">{label}</div>
      <div className="mono" style={{ fontSize: 20, marginTop: 4 }}>{value}</div>
      {sub && <div className="eyebrow" style={{ marginTop: 2, letterSpacing: ".06em" }}>{sub}</div>}
    </div>
  );
}

export function CommandStage({ base, rangeParams, tempF }: {
  base: Base; rangeParams: Map<string, RangeParams>; tempF: boolean;
}) {
  const live = base.packs.filter((p) => p.connected);
  const charging = base.status === "charging";
  const hot = live.some((p) => (p.item.temp_c ?? -Infinity) >= THERMAL_C);

  // Base-level flow: total watts across the connected pair.
  const totalW = live.reduce((s, p) => s + Math.abs(p.item.power_w ?? 0), 0);
  const flowLabel = base.status === "in-use" ? "DRAW NOW" : charging ? "CHARGE IN" : "FLOW";
  const flowValue = live.length === 0 ? "—" : `${Math.round(totalW)} W`;

  // Runtime band (weaker pack bounds) from range.ts, or time-to-full when charging.
  const ranges: PackRange[] = live
    .map((p) => estimatePackRange(isCharging(p.item), p.item.remaining_ah,
      rangeParams.get(p.item.address) ?? SEED_RANGE_PARAMS))
    .filter((r): r is PackRange => r != null);
  const etaFull = live.reduce<number | null>((mx, p) => {
    const e = p.item.eta_full_min;
    return e != null && Number.isFinite(e) ? Math.max(mx ?? 0, e) : mx;
  }, null);
  let runtimeLabel = "EST. RUNTIME";
  let runtimeValue = "—";
  if (charging) {
    runtimeLabel = "TIME TO FULL";
    runtimeValue = fmtEta(etaFull);
  } else if (ranges.length > 0) {
    const r = minRange(ranges);
    runtimeValue = `~${Math.round(r.activeHLo)}–${Math.round(r.activeHHi)}h`;
  }

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
      {hot && (
        <div className="card" style={{ padding: "10px 14px", borderColor: "var(--warn)",
          display: "flex", alignItems: "center", gap: 10 }}>
          <span style={{ width: 8, height: 8, borderRadius: "50%", background: "var(--warn)" }} />
          <span className="mono" style={{ fontSize: 12, color: "var(--warn)" }}>
            Thermal warning — a staged pack is at or above {fmtTemp(THERMAL_C, tempF)}
          </span>
        </div>
      )}

      <div className="card" style={{ display: "flex", flexDirection: "column", gap: 16 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <div className="eyebrow">Main stage</div>
          <span className="mono" style={{ fontSize: 15, fontWeight: 600 }}>Base {base.id}</span>
          <Chip tone={STATUS_COLOR[base.status]}>{STATUS_TAG[base.status]}</Chip>
          <span className="mono" style={{ fontSize: 11, color: "var(--text-4)", marginLeft: "auto" }}>
            {roleText(base)}
          </span>
        </div>

        <div style={{ display: "flex", gap: 20 }}>
          {live.length === 0 ? (
            <div className="mono" style={{ fontSize: 13, color: "var(--text-4)", padding: "24px 0" }}>
              Base {base.id} is disconnected.
            </div>
          ) : (
            live.map((p) => <PackCard key={p.item.address} item={p.item} letter={p.letter} tempF={tempF} />)
          )}
        </div>

        <div style={{ display: "flex", gap: 12 }}>
          <FlowTile label={flowLabel} value={flowValue} />
          <FlowTile label={runtimeLabel} value={runtimeValue} />
          <FlowTile label="DRIVEN TODAY" value="—" sub="Phase 4" />
        </div>
      </div>
    </div>
  );
}
