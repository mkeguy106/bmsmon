import { useState } from "react";
import type { FleetItem } from "../types";
import {
  OVERLAY_RANK, nextAckedKey, tempZone, worstOf, zoneColorVar, zoneLabel,
  type TempConfig, type TempEnvelope, type TempThresholds, type TempUnit, type Zone,
} from "../temp";
import { fmtEta } from "../util";
import { Ago } from "./Ago";
import { Ring } from "./Ring";
import { TempGauge } from "./TempGauge";
import { TempBanner, type PackTemp } from "./TempBanner";
import { TempOverlay } from "./TempOverlay";
import { SyncedIndicator } from "./SyncedIndicator";
import { ConditionsSimulator } from "./ConditionsSimulator";
import { PinButton } from "./PinButton";
import {
  SEED_RANGE_PARAMS, estimatePackRange, formatRangeLine, minRange, type RangeParams,
} from "../range";

// WEB-7: the drag-to-fake-temperature simulator can trigger the full CRITICAL
// overlay, so keep it out of the production dashboard. Dev builds keep it;
// production requires an explicit ?sim=1 (deliberate testing). Checked once.
const SIM_ENABLED: boolean =
  import.meta.env.DEV || new URLSearchParams(location.search).get("sim") === "1";

// WEB-8 note: the stage is deliberately NOT memoized — its featured pack gets a
// fresh sample every ~1.5 s, so it re-renders on every real update anyway; at
// ≤ a base of packs that is cheap. The live "updated ago" text is a
// self-ticking <Ago> leaf, so the stage no longer carries a 1 Hz `now` prop.
// Memoization is applied where it pays: the All-Batteries grid cards.
export function MainStage({ items, staleAddrs, thr, env, unit, config, pinned, onTogglePin, lowSeized, rangeParams }: {
  items: FleetItem[]; staleAddrs: Set<string>;
  thr: TempThresholds; env: TempEnvelope; unit: TempUnit; config: TempConfig | null;
  pinned: Set<string>; onTogglePin: (addr: string) => void; lowSeized?: boolean;
  rangeParams: Map<string, RangeParams>;
}) {
  const group = items[0]?.group_id;
  const featuredAddr = items[0]?.address;
  const pinnedMode = items.length > 0 && items.every((i) => pinned.has(i.address));
  const [simTemp, setSimTemp] = useState<number | null>(null);
  const [ackedKey, setAckedKey] = useState<string | null>(null);

  // Per-pack temp. The featured pack can be driven by the simulator (preview). A disconnected pack
  // keeps its last-known temp for display (dimmed), but does NOT drive live alerts.
  const rows = items.map((it) => {
    const connected = !staleAddrs.has(it.address);
    const sim = it.address === featuredAddr && simTemp != null;
    const displayTempC: number | null = sim ? simTemp : (it.temp_c ?? null);
    const zone: Zone | null = displayTempC != null ? tempZone(displayTempC, thr, env) : null;
    const liveAlert = sim || (connected && it.temp_c != null);
    return { it, connected, sim, displayTempC, zone, liveAlert };
  });

  const worst: (PackTemp & { key: string }) | null = (() => {
    const alertRows = rows.filter((r): r is typeof r & { displayTempC: number; zone: Zone } =>
      r.liveAlert && r.zone != null);
    const w = worstOf(alertRows.map((r) => ({
      name: r.it.alias ?? r.it.address, tempC: r.displayTempC, zone: r.zone, rank: r.zone.rank,
    })));
    return w ? { name: w.name, tempC: w.tempC, zone: w.zone, key: w.zone.key } : null;
  })();

  // WEB-7: recovery re-arms the ack (mirror of the Android fix) — once the
  // worst condition drops below overlay severity, a later recurrence of the
  // SAME zone must flash again. Render-phase adjustment of state (React's
  // documented "adjust state when props change" pattern; strictly conditional,
  // so it cannot loop).
  const effectiveAck = nextAckedKey(ackedKey, worst && { key: worst.key, rank: worst.zone.rank });
  if (effectiveAck !== ackedKey) setAckedKey(effectiveAck);

  const flashing = worst != null && worst.zone.rank >= OVERLAY_RANK && effectiveAck !== worst.key;
  const bannerColor = worst && worst.zone.rank > 0 ? zoneColorVar(worst.zone.key) : "var(--safe)";

  // Base-level discharge-remaining line — the twin of the Android stage line. Hidden when any
  // staged pack is disconnected (no fake numbers), charging (the recharge ETA owns the slot),
  // or missing remaining_ah. History band only — no live tilt on the web (documented divergence).
  const rangeLine = (() => {
    if (rows.length === 0 || rows.some((r) => !r.connected)) return null;
    const ranges = rows.map(({ it }) => estimatePackRange(
      (it.current_a ?? 0) > 0.1,
      it.remaining_ah,
      rangeParams.get(it.address) ?? SEED_RANGE_PARAMS,
    ));
    if (ranges.some((r) => r == null)) return null;
    return formatRangeLine(minRange(ranges as NonNullable<typeof ranges[number]>[]));
  })();

  return (
    <div style={{ display: "grid", gap: 16 }}>
      <SyncedIndicator config={config} />
      <TempBanner worst={worst} thr={thr} env={env} unit={unit} />

      <div style={{ background: "var(--card)", border: "1px solid var(--border)", borderRadius: 16, padding: 24 }}>
        <div style={{ display: "flex", alignItems: "baseline", gap: 10, marginBottom: 16 }}>
          <span className="mono" style={{ color: "var(--text3)", fontSize: 11, letterSpacing: 2 }}>MAIN STAGE</span>
          {group && <span style={{ color: "var(--text2)", fontSize: 13 }}>Base {group}</span>}
          {lowSeized && (
            <span className="mono" title="A pack dropped to the low-SOC threshold and seized the stage"
              style={{ fontSize: 10, letterSpacing: 1, color: "var(--critical)",
                border: "1px solid var(--critical)", borderRadius: 4, padding: "2px 6px" }}>LOW</span>
          )}
          {pinnedMode
            ? <span className="mono" style={{ marginLeft: "auto", fontSize: 10, letterSpacing: 1,
                color: "var(--accent)" }}>PINNED · AUTO OFF</span>
            : <span className="mono" style={{ marginLeft: "auto", fontSize: 10, letterSpacing: 1,
                color: "var(--text3)" }}>AUTO</span>}
        </div>
        <div style={{ display: "flex", gap: 40, justifyContent: "center", flexWrap: "wrap", minHeight: 280 }}>
          {rows.length === 0 && <div style={{ color: "var(--text3)", alignSelf: "center" }}>No active base</div>}
          {rows.map(({ it, connected, sim, displayTempC, zone }) => {
            const cur = it.current_a ?? 0;
            const flowColor = cur > 0.1 ? "var(--regen)" : cur < -0.1 ? "var(--power)" : "var(--text2)";
            const zColor = zone ? zoneColorVar(zone.key) : "var(--text3)";
            return (
              <div key={it.address} style={{ textAlign: "center", minWidth: 260, opacity: connected ? 1 : 0.82 }}>
                <div style={{ display: "flex", alignItems: "center", justifyContent: "center", gap: 8, marginBottom: 12 }}>
                  <span style={{ color: "var(--text)", fontWeight: 700, fontSize: 20 }}>{it.alias ?? it.address}</span>
                  {sim && (
                    <span className="mono" style={{ fontSize: 9, letterSpacing: 1, color: "var(--accent)",
                      border: "1px solid var(--accent)", borderRadius: 4, padding: "2px 5px" }}>SIM</span>
                  )}
                  <PinButton pinned={pinned.has(it.address)} onToggle={() => onTogglePin(it.address)} />
                </div>
                <div style={{ display: "flex", alignItems: "center", justifyContent: "center", gap: 22 }}>
                  <Ring soc={it.soc ?? null} current={it.current_a ?? null} power={it.power_w ?? null}
                    connected={connected} size={168} />
                  {zone && displayTempC != null && (
                    <div style={{ opacity: connected || sim ? 1 : 0.55 }}>
                      <TempGauge tempC={displayTempC} thr={thr} env={env} unit={unit} />
                    </div>
                  )}
                </div>
                <div style={{ minHeight: 40, marginTop: 12 }}>
                  {connected ? (
                    <>
                      <span className="mono" style={{ color: flowColor, fontSize: 18 }}>
                        {`${(it.power_w ?? 0).toFixed(0)} W · ${cur.toFixed(1)} A`}
                      </span>
                      {it.eta_full_min != null && (
                        <div className="mono" style={{ color: "var(--text2)", fontSize: 12, marginTop: 2 }}>
                          ~{fmtEta(it.eta_full_min)} to full
                        </div>
                      )}
                    </>
                  ) : (
                    <>
                      <div className="mono" style={{ color: "var(--text3)", fontSize: 17 }}>
                        {`${(it.power_w ?? 0).toFixed(0)} W · ${cur.toFixed(1)} A`}
                      </div>
                      <div className="mono" style={{ color: "var(--text3)", fontSize: 11, letterSpacing: 1, marginTop: 2 }}>
                        DISCONNECTED · updated <Ago tsMs={it.ts_ms} />
                      </div>
                    </>
                  )}
                </div>
                {zone && (
                  <div style={{ display: "inline-flex", alignItems: "center", gap: 7, marginTop: 8,
                    padding: "4px 10px", borderRadius: 7, background: "var(--input-bg)" }}>
                    <span style={{ width: 7, height: 7, borderRadius: "50%", background: zColor }} />
                    <span className="mono" style={{ fontSize: 11, letterSpacing: 1, color: zColor }}>{zoneLabel(zone.key)}</span>
                  </div>
                )}
              </div>
            );
          })}
        </div>

        {rangeLine && (
          <div className="mono" style={{ textAlign: "center", color: "var(--text2)",
            fontSize: 12, marginTop: 12 }}>
            {rangeLine}
          </div>
        )}

        {SIM_ENABLED && featuredAddr && (
          <ConditionsSimulator
            value={simTemp ?? items[0]?.temp_c ?? 24} active={simTemp != null}
            onChange={setSimTemp} onReset={() => setSimTemp(null)}
            unit={unit} featuredName={items[0]?.alias ?? "the featured pack"} readoutColor={bannerColor} />
        )}
      </div>

      {flashing && worst && (
        <TempOverlay worst={worst} env={env} unit={unit} onAck={() => setAckedKey(worst.key)} />
      )}
    </div>
  );
}
