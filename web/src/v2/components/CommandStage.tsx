import type { ReactNode } from "react";
import type { Base, BaseStatus } from "../fleet";
import { DAILY_DRIVER_BASE } from "../fleet";
import { Ago } from "../../components/Ago";
import type { BaseView, PackView, RangeState, ThermalView } from "../model/baseView";
import type { TripSummary } from "../model/journey";
import type { StageReason } from "../model/stageBase";
import { Ring } from "./Ring";
import { StatTile, CellTiles, Chip, LastKnownNote } from "./Atoms";
import { sohColor } from "../colors";

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

/** One pack. A pack that is not live keeps its LAST-KNOWN reading, muted and timestamped
 *  (the rule the rail, the Health hero and v1 follow): hiding it hid the weaker pack (WEB-14). */
function PackCard({ pack, tempF }: { pack: PackView; tempF: boolean }) {
  const { item, letter, live } = pack;
  return (
    <div style={{ flex: 1, minWidth: 0, display: "flex", flexDirection: "column", gap: 12,
      opacity: live ? 1 : 0.55 }}>
      <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
        <span className="mono" style={{ fontSize: 12, fontWeight: 600 }}>{item.alias ?? `Pack ${letter}`}</span>
        <span style={{ width: 8, height: 8, borderRadius: "50%", background: sohColor(item.soh) }}
          title={item.soh != null ? `SOH ${Math.round(item.soh)}%` : "SOH —"} />
      </div>
      <div style={{ display: "flex", justifyContent: "center" }}>
        <Ring soc={item.soc} power={item.power_w} current={item.current_a} connected={live} size={132} />
      </div>
      <div className="mono" style={{ fontSize: 12, textAlign: "center",
        color: live ? "var(--text-3)" : "var(--text-4)" }}>
        {live
          ? `${num(item.power_w)} W · ${num(item.current_a, 1)} A`
          : <>DISCONNECTED · last seen <Ago tsMs={item.ts_ms} /></>}
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

function FlowTile({ label, value, sub }: { label: string; value: string; sub?: ReactNode }) {
  return (
    <div className="card" style={{ padding: 12, flex: 1 }}>
      <div className="eyebrow">{label}</div>
      <div className="mono" style={{ fontSize: 20, marginTop: 4 }}>{value}</div>
      {sub && <div className="eyebrow" style={{ marginTop: 2, letterSpacing: ".06em" }}>{sub}</div>}
    </div>
  );
}

/** The temperature banner, from the same synced zone ladder as the Alerts view (WEB-31). */
function ThermalBanner({ t, tempF }: { t: ThermalView; tempF: boolean }) {
  const color = t.severity === "critical" ? "var(--live)" : "var(--warn)";
  return (
    <div className="card" role="status" style={{ padding: "10px 14px", borderColor: color,
      display: "flex", flexDirection: "column", gap: 4 }}>
      <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
        <span style={{ width: 8, height: 8, borderRadius: "50%", background: color, flexShrink: 0 }} />
        <span className="mono" style={{ fontSize: 12, fontWeight: 600, color }}>
          {t.title} · pack {t.letter} at {fmtTemp(t.tempC, tempF)}
        </span>
      </div>
      <span style={{ fontSize: 12, color: "var(--text-2)" }}>{t.msg}</span>
    </div>
  );
}

/** Runtime band (the weaker pack bounds it) or time-to-full while charging. */
function runtimeTile(range: RangeState): { label: string; value: string; sub?: ReactNode } {
  if (range.kind === "charging") return { label: "TIME TO FULL", value: fmtEta(range.etaFullMin) };
  if (range.kind !== "estimate") return { label: "EST. RUNTIME", value: "—" };
  const r = range.range;
  return {
    label: "EST. RUNTIME", value: `~${Math.round(r.activeHLo)}–${Math.round(r.activeHHi)}h`,
    sub: range.lastKnown ? <LastKnownNote lk={range.lastKnown} /> : undefined,
  };
}

export function CommandStage({ base, view, reason = null, onClearPin, tempF, mobile, drivenToday }: {
  base: Base; view: BaseView; reason?: StageReason | null; onClearPin?: () => void;
  tempF: boolean; mobile: boolean; drivenToday: TripSummary;
}) {
  const flowLabel = base.status === "in-use" ? "DRAW NOW" : view.charging ? "CHARGE IN" : "FLOW";
  const flowValue = view.flowW == null ? "—" : `${Math.round(view.flowW)} W`;
  const runtime = runtimeTile(view.range);

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
      {view.thermal && <ThermalBanner t={view.thermal} tempF={tempF} />}

      <div className="card" style={{ display: "flex", flexDirection: "column", gap: 16 }}>
        {/* Wraps: with PINNED + AUTO (or LOW) the row is wider than a 390 px phone. */}
        <div style={{ display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap", rowGap: 6 }}>
          <div className="eyebrow">Main stage</div>
          <span className="mono" style={{ fontSize: 15, fontWeight: 600 }}>Base {base.id}</span>
          <Chip tone={STATUS_COLOR[base.status]}>{STATUS_TAG[base.status]}</Chip>
          {reason === "seize" && (
            <span title="A pack dropped to the low-SOC threshold and seized the stage">
              <Chip tone="var(--live)">LOW</Chip>
            </span>
          )}
          {reason === "pin" && (
            <>
              <Chip>PINNED</Chip>
              <button type="button" className="mono" onClick={onClearPin}
                title="Return to automatic selection (the base in use)"
                style={{ fontSize: 10, letterSpacing: ".08em", padding: "4px 10px", minHeight: 24,
                  borderRadius: 5, border: "1px solid var(--border-strong)", background: "transparent",
                  color: "var(--text-2)", cursor: "pointer" }}>
                AUTO
              </button>
            </>
          )}
          <span className="mono" style={{ fontSize: 11, color: "var(--text-4)", marginLeft: "auto" }}>
            {roleText(base)}
          </span>
        </div>

        {view.range.kind === "offline" && (
          <div className="mono" style={{ fontSize: 13, color: "var(--text-4)" }}>
            Base {base.id} is disconnected.
            {view.range.lastSeenMs != null && <> Last seen <Ago tsMs={view.range.lastSeenMs} />.</>}
          </div>
        )}

        {/* Real phone widths cannot fit two full pack columns side by side — stack them. */}
        <div style={{ display: "flex", gap: 20, flexDirection: mobile ? "column" : "row" }}>
          {view.packs.map((p) => <PackCard key={p.item.address} pack={p} tempF={tempF} />)}
        </div>

        <div style={{ display: "flex", gap: 12, flexWrap: "wrap" }}>
          <FlowTile label={flowLabel} value={flowValue} />
          <FlowTile label={runtime.label} value={runtime.value} sub={runtime.sub} />
          <FlowTile label="DRIVEN TODAY"
            value={drivenToday.miles > 0.05 ? `${drivenToday.activeMiles.toFixed(1)} mi` : "—"}
            sub={drivenToday.transitMiles > 0.05 ? `+${drivenToday.transitMiles.toFixed(1)} transit` : undefined} />
        </div>
      </div>
    </div>
  );
}
