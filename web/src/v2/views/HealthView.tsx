import { useMemo } from "react";
import type { FleetItem } from "../../types";
import type { TempUnit } from "../../temp";
import { formatTemp } from "../../temp";
import type { HistPoint } from "../history";
import type { FleetData } from "../useFleetData";
import { useHistory } from "../useHistory";
import { healthSummary, healthBoardOrder, packStatus, type PackStatus } from "../model/health";
import { groupBases, DAILY_DRIVER_BASE, type BasePack } from "../fleet";
import { Bar, StatTile, Chip } from "../components/Atoms";
import { Sparkline } from "../components/Sparkline";
import { socColor, sohColor } from "../colors";

const STATUS_COLOR: Record<PackStatus, string> = {
  "in-use": "var(--ok)", charging: "var(--warn)", low: "var(--live)",
  idle: "var(--text-4)", offline: "var(--text-4)",
};
const STATUS_TAG: Record<PackStatus, string> = {
  "in-use": "IN USE", charging: "CHARGING", low: "LOW", idle: "IDLE", offline: "OFFLINE",
};


// PACK · CAPACITY · HEALTH · TEMP · CYCLES · trend sparkline · STATUS.
const BOARD_COLUMNS = "minmax(90px,1fr) minmax(120px,1.4fr) 64px 64px 56px 104px 96px";

function HeroBarRow({ label, frac, text, color }: {
  label: string; frac: number; text: string; color: string;
}) {
  return (
    <div style={{ marginBottom: 8 }}>
      <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 3 }}>
        <span className="eyebrow">{label}</span>
        <span className="mono" style={{ fontSize: 11, color: "var(--text-3)" }}>{text}</span>
      </div>
      <Bar frac={frac} color={color} />
    </div>
  );
}

function HeroPackCard({ pack }: { pack: BasePack }) {
  const { item, connected } = pack;
  const soc = connected ? item.soc : null;
  const capFrac = item.remaining_ah != null && item.full_charge_ah
    ? item.remaining_ah / item.full_charge_ah : null;
  const soh = item.soh;
  return (
    <div style={{ flex: "1 1 200px", minWidth: 180, opacity: connected ? 1 : 0.55 }}>
      <div className="mono" style={{ fontSize: 13, fontWeight: 600, marginBottom: 10 }}>
        {item.alias ?? item.address}
      </div>
      <HeroBarRow label="SOC" frac={soc != null ? soc / 100 : 0}
        text={soc != null ? `${Math.round(soc)}%` : "—"} color={socColor(soc, connected)} />
      <HeroBarRow label="CAPACITY" frac={capFrac ?? 0}
        text={capFrac != null ? `${Math.round(capFrac * 100)}%` : "—"} color="var(--text-3)" />
      <HeroBarRow label="HEALTH" frac={soh != null ? soh / 100 : 0}
        text={soh != null ? `${Math.round(soh)}%` : "—"} color={sohColor(soh)} />
    </div>
  );
}

function BoardHeader() {
  return (
    <div style={{ display: "grid", gridTemplateColumns: BOARD_COLUMNS, gap: 10,
      padding: "8px 14px", borderBottom: "1px solid var(--border)" }}>
      <span className="eyebrow">Pack</span>
      <span className="eyebrow">Capacity</span>
      <span className="eyebrow">Health</span>
      <span className="eyebrow">Temp</span>
      <span className="eyebrow">Cycles</span>
      <span className="eyebrow">Trend</span>
      <span className="eyebrow">Status</span>
    </div>
  );
}

function BoardRow({ item, connected, points, unit }: {
  item: FleetItem; connected: boolean; points: HistPoint[] | undefined; unit: TempUnit;
}) {
  const status = packStatus(item, connected);
  const soc = connected ? item.soc : null;
  const soh = item.soh;
  return (
    <div style={{ display: "grid", gridTemplateColumns: BOARD_COLUMNS, gap: 10,
      alignItems: "center", padding: "10px 14px", borderBottom: "1px solid var(--border)",
      opacity: connected ? 1 : 0.55 }}>
      <span className="mono" style={{ fontSize: 12, overflow: "hidden", textOverflow: "ellipsis",
        whiteSpace: "nowrap" }}>
        {item.alias ?? item.address}
      </span>
      <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
        <span style={{ flex: 1 }}>
          <Bar frac={soc != null ? soc / 100 : 0} color={socColor(soc, connected)} />
        </span>
        <span className="mono" style={{ fontSize: 11, width: 34, textAlign: "right",
          color: "var(--text-2)" }}>
          {soc != null ? `${Math.round(soc)}%` : "—"}
        </span>
      </div>
      <span className="mono" style={{ fontSize: 11, color: sohColor(soh) }}>
        {soh != null ? `${Math.round(soh)}%` : "—"}
      </span>
      <span className="mono" style={{ fontSize: 11, color: "var(--text-3)" }}>
        {item.temp_c != null ? formatTemp(item.temp_c, unit) : "—"}
      </span>
      <span className="mono" style={{ fontSize: 11, color: "var(--text-3)" }}>
        {item.cycles ?? "—"}
      </span>
      <Sparkline points={points} />
      <span>
        <Chip tone={STATUS_COLOR[status]}>{STATUS_TAG[status]}</Chip>
      </span>
    </div>
  );
}

export function HealthView({ data, unit, mobile }: {
  data: FleetData; unit: TempUnit; mobile: boolean;
}) {
  // 24 h sparkline history is consumed ONLY by this view, so the hook lives
  // here (not in App): sessions parked on Command never poll /web/history, and
  // the visibility-gated 180 s poll stops when the view unmounts on nav switch.
  // Remounting on re-entry re-fires the hook's immediate initial fetch.
  const history = useHistory();
  // Memoized on the actual inputs — items/staleAddrs are identity-stable in
  // useFleetData, so these only recompute when the fleet really changed.
  const summary = useMemo(
    () => healthSummary(data.items, data.staleAddrs), [data.items, data.staleAddrs]);
  const bases = useMemo(
    () => groupBases(data.items, data.staleAddrs), [data.items, data.staleAddrs]);
  const heroBase = bases.find((b) => b.id === DAILY_DRIVER_BASE) ?? bases[0];
  const board = useMemo(
    () => healthBoardOrder(data.items, data.staleAddrs), [data.items, data.staleAddrs]);

  const boardTable = (
    <div>
      <BoardHeader />
      {board.map((item) => (
        <BoardRow key={item.address} item={item} connected={!data.staleAddrs.has(item.address)}
          points={history.get(item.address)} unit={unit} />
      ))}
    </div>
  );

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
      <div style={{ display: "grid", gridTemplateColumns: mobile ? "1fr 1fr" : "repeat(4, 1fr)",
        gap: 12 }}>
        <StatTile label="PACKS READY" value={`${summary.ready}/8`} />
        <StatTile label="NEED RECHARGE" value={String(summary.needRecharge)} />
        <StatTile label="DEGRADED" value={String(summary.degraded)} />
        <StatTile label="FLEET CAPACITY" value={`${Math.round(summary.capacityPct)}%`} />
      </div>

      {heroBase && (
        <div className="card">
          <div className="eyebrow" style={{ marginBottom: 12 }}>In use now · Base {heroBase.id}</div>
          <div style={{ display: "flex", gap: 24, flexWrap: "wrap" }}>
            {heroBase.packs.map((p) => <HeroPackCard key={p.item.address} pack={p} />)}
          </div>
        </div>
      )}

      <div className="card" style={{ padding: 0 }}>
        {mobile ? (
          <div style={{ overflowX: "auto" }}>
            <div style={{ minWidth: 720 }}>{boardTable}</div>
          </div>
        ) : boardTable}
      </div>
    </div>
  );
}
