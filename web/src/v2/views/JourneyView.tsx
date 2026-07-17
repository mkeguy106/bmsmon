import { useMemo, useRef, useState } from "react";
import type { CSSProperties } from "react";
import type { TempUnit } from "../../temp";
import { useLocalStorage } from "../../useLocalStorage";
import { groupBases, DAILY_DRIVER_BASE, isCharging } from "../fleet";
import type { FleetData } from "../useFleetData";
import { useTrack } from "../useTrack";
import {
  haversineMi, classifySegment, cumulativeMiles, detectHotspots, energySeries, tripSummary,
  type SegKind,
} from "../model/journey";
import { cleanTrack } from "../model/cleanTrack";
import { LIVE_REFRESH_MS, isWindowLive, lastKnownPosition, livePosition, type LivePos } from "../model/live";
import { useNow } from "../../useNow";
import { Ago } from "../../components/Ago";
import { JourneyMap } from "../components/JourneyMap";
import { EnergyDistanceChart } from "../components/EnergyDistanceChart";
import { EfficiencyCard } from "../components/EfficiencyCard";
import { efficiencySummary } from "../model/efficiency";
import { SEED_RANGE_PARAMS } from "../../range";
import { Ring } from "../components/Ring";
import { Segmented } from "../components/Segmented";
import { JourneyDock } from "../components/JourneyDock";
import { ShareDialog } from "../components/ShareDialog";

// ── Persisted control state ────────────────────────────────────────────────
type DateMode = "day" | "range";
interface JourneyState { dateMode: DateMode; day: string; from: string; to: string; showTrail: boolean }

/** Local YYYY-MM-DD for a Date (native <input type="date"> format). */
function fmtDay(d: Date): string {
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}
function todayStr(): string { return fmtDay(new Date()); }

/** [start, next-day-start) local-midnight bounds for a YYYY-MM-DD string, or null if unparseable.
 *  Built with `new Date(y, m-1, d)` so it's LOCAL midnight and DST-correct at the day boundary. */
function dayBounds(s: string): { start: number; next: number } | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s);
  if (!m) return null;
  const y = +m[1], mo = +m[2], d = +m[3];
  const start = new Date(y, mo - 1, d).getTime();
  const next = new Date(y, mo - 1, d + 1).getTime(); // Date ctor normalizes month/DST rollover
  return Number.isFinite(start) && Number.isFinite(next) ? { start, next } : null;
}

/** Shift a YYYY-MM-DD string by whole days (calendar-correct via the Date ctor). */
function shiftDay(s: string, delta: number): string {
  const b = dayBounds(s);
  const base = b ? new Date(b.start) : new Date();
  base.setDate(base.getDate() + delta);
  return fmtDay(base);
}

const DEFAULT_JOURNEY: JourneyState = {
  dateMode: "day", day: todayStr(), from: todayStr(), to: todayStr(), showTrail: true,
};

const journeyCodec = {
  decode: (raw: string): JourneyState | null => {
    try {
      const o = JSON.parse(raw) as Record<string, unknown>;
      if (!o || typeof o !== "object") return null;
      return {
        dateMode: o.dateMode === "range" ? "range" : "day",
        day: typeof o.day === "string" && dayBounds(o.day) ? o.day : todayStr(),
        from: typeof o.from === "string" && dayBounds(o.from) ? o.from : todayStr(),
        to: typeof o.to === "string" && dayBounds(o.to) ? o.to : todayStr(),
        showTrail: typeof o.showTrail === "boolean" ? o.showTrail : true,
      };
    } catch { return null; }
  },
  encode: (v: JourneyState) => JSON.stringify(v),
};

const STATE_LABEL: Record<SegKind, string> = {
  active: "ACTIVE", transit: "IN TRANSIT", idle: "IDLE",
};

const dateInputStyle: CSSProperties = {
  background: "var(--panel-2)", color: "var(--text)", border: "1px solid var(--border)",
  borderRadius: 6, padding: "5px 8px", fontSize: 11,
};

function stepBtnStyle(): CSSProperties {
  return {
    width: 30, height: 30, display: "inline-flex", alignItems: "center", justifyContent: "center",
    borderRadius: 7, border: "1px solid var(--border)", background: "transparent",
    color: "var(--text-2)", cursor: "pointer", fontSize: 15, lineHeight: 1,
  };
}

/** One labelled readout cell in the hover-inspection row. */
function Readout({ label, value }: { label: string; value: string }) {
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 2, minWidth: 62 }}>
      <span className="eyebrow" style={{ color: "var(--text-4)" }}>{label}</span>
      <span className="mono" style={{ fontSize: 14, color: "var(--text)", fontWeight: 600 }}>{value}</span>
    </div>
  );
}

const overlayChrome: CSSProperties = {
  position: "absolute", background: "rgba(9,9,11,.72)", border: "1px solid var(--border-strong)",
  borderRadius: 6, padding: "6px 10px", fontSize: 11, letterSpacing: ".12em", zIndex: 1000,
};

export function JourneyView({ data, theme, unit: _unit, mobile, mapMetric }: {
  data: FleetData; theme: "dark" | "light"; unit: TempUnit; mobile: boolean; mapMetric: "power" | "soc";
}) {
  const [st, setSt] = useLocalStorage<JourneyState>("bmsmon-v2-journey", () => DEFAULT_JOURNEY, journeyCodec);
  const [hoverIndex, setHoverIndex] = useState<number | null>(null);
  const [shareOpen, setShareOpen] = useState(false);

  const set = (patch: Partial<JourneyState>) => setSt((s) => ({ ...s, ...patch }));

  // ── Resolve the [fromMs, toMs) window from the persisted date state. ──
  const [fromMs, toMs] = useMemo<[number, number]>(() => {
    if (st.dateMode === "range") {
      const f = dayBounds(st.from) ?? dayBounds(todayStr())!;
      const t = dayBounds(st.to) ?? dayBounds(todayStr())!;
      // Guard reversed ranges: use the earlier start and the later next.
      return [Math.min(f.start, t.start), Math.max(f.next, t.next)];
    }
    const b = dayBounds(st.day) ?? dayBounds(todayStr())!;
    return [b.start, b.next];
  }, [st.dateMode, st.day, st.from, st.to]);

  // ── Base = daily driver (fallback first). Its packs' addresses → the chair track. ──
  const bases = useMemo(() => groupBases(data.items, data.staleAddrs), [data.items, data.staleAddrs]);
  const base = bases.find((b) => b.id === DAILY_DRIVER_BASE) ?? bases[0];
  const addresses = useMemo(() => (base ? base.packs.map((p) => p.item.address) : []), [base]);

  // Coarse local clock: window-liveness (day boundaries) and the 120 s marker
  // staleness cutoff only need ~10 s resolution; the visible age text in the
  // badge is a self-ticking <Ago> leaf, so nothing here needs a 1 s clock.
  const now = useNow(10_000);
  const isLive = isWindowLive(fromMs, toMs, now);
  const rawPoints = useTrack(addresses, fromMs, toMs, isLive ? LIVE_REFRESH_MS : undefined);
  // Cleaned at render time (spike rejection / stay snapping / smoothing) — raw data stays
  // raw in the DB; the map, miles, energy chart, and playback all consume the cleaned track.
  const points = useMemo(() => cleanTrack(rawPoints), [rawPoints]);
  // Identity-stable live fix: the selectors allocate per call and `now` ticks periodically,
  // so return the PREVIOUS object while the values are unchanged — the map's live-marker
  // effect then only fires on real movement (or a fresh↔stale flip), not every tick.
  // Fresh fix wins; otherwise fall back to the LAST KNOWN position (any age), rendered
  // dimmed with its age in the badge — better than a marker that silently vanishes.
  const liveRef = useRef<LivePos | null>(null);
  const fresh = isLive ? livePosition(data.items, addresses, now) : null;
  const liveStale = isLive && fresh == null;
  const live = useMemo(() => {
    const next = isLive
      ? (fresh ?? lastKnownPosition(data.items, addresses))
      : null;
    const prev = liveRef.current;
    if (next && prev && next.lat === prev.lat && next.lon === prev.lon && next.tsMs === prev.tsMs) {
      return prev;
    }
    liveRef.current = next;
    return next;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isLive, fresh, data.items, addresses, now]);
  const fitKey = addresses.join(",") + ":" + fromMs + ":" + toMs;

  // ── Derived geometry/energy (memoized on the merged track). ──
  const cumMi = useMemo(() => cumulativeMiles(points), [points]);
  // DESTINATION-INDEXED: segKinds[i] describes the segment i-1 → i (index 0 is the
  // unused "idle" head). Length-n so JourneyMap/segKinds[cursorIndex] line up.
  const segKinds = useMemo<SegKind[]>(
    () => points.map((p, i) => classifySegment(p, i > 0 ? haversineMi(points[i - 1], p) : 0)),
    [points]);
  const hotspots = useMemo(() => detectHotspots(points, cumMi), [points, cumMi]);
  const energy = useMemo(() => energySeries(points, cumMi), [points, cumMi]);
  const summary = useMemo(() => tripSummary(points, cumMi), [points, cumMi]);

  const hasTrip = points.length > 0;
  const hasMapOverlayContent = points.length > 0 || live != null;

  // Hover inspection: the energy chart reports a point index; null when not hovering.
  // Drop a stale hover if the track shrank out from under it.
  const hi = hoverIndex != null && hoverIndex < points.length ? hoverIndex : null;
  const cur = hi != null ? points[hi] : undefined;
  const curKind: SegKind = hi != null ? segKinds[hi] ?? "idle" : "idle";

  // ── Efficiency: this outing's real cost/mile vs the learned band (+ live projection). ──
  const connected = base?.packs.filter((p) => p.connected) ?? [];
  const anyCharging = connected.some((p) => isCharging(p.item));
  const eff = useMemo(() => efficiencySummary({
    points, activeMiles: summary.activeMiles,
    packParams: connected.map((p) => data.rangeParams.get(p.item.address) ?? SEED_RANGE_PARAMS),
    remainingAh: connected
      .map((p) => p.item.remaining_ah)
      .filter((ah): ah is number => ah != null && Number.isFinite(ah) && ah > 0),
    charging: anyCharging, live: isLive,
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }), [points, summary.activeMiles, connected, data.rangeParams, anyCharging, isLive]);

  const mapHeight = 480;

  return (
    <div style={mobile
      ? { flex: 1, display: "flex", flexDirection: "column", minHeight: 0, overflow: "hidden" }
      : { display: "flex", flexDirection: "column", gap: 16 }}>
      {/* ── Date toolbar ── */}
      <div style={{ display: "flex", flexWrap: "wrap", gap: 10, alignItems: "center",
        ...(mobile && { padding: "12px 14px 8px", flexShrink: 0 }) }}>
        <div style={{ display: "flex", gap: 6, alignItems: "center" }}>
          <button aria-label="Previous day" style={stepBtnStyle()}
            onClick={() => (st.dateMode === "range"
              ? set({ from: shiftDay(st.from, -1), to: shiftDay(st.to, -1) })
              : set({ day: shiftDay(st.day, -1) }))}>‹</button>
          {st.dateMode === "day" ? (
            <input type="date" value={st.day} onChange={(e) => set({ day: e.target.value })}
              className="mono" style={dateInputStyle} />
          ) : (
            <div style={{ display: "flex", gap: 6, alignItems: "center" }}>
              <input type="date" value={st.from} onChange={(e) => set({ from: e.target.value })}
                className="mono" style={dateInputStyle} />
              <span style={{ color: "var(--text-4)" }}>→</span>
              <input type="date" value={st.to} onChange={(e) => set({ to: e.target.value })}
                className="mono" style={dateInputStyle} />
            </div>
          )}
          <button aria-label="Next day" style={stepBtnStyle()}
            onClick={() => (st.dateMode === "range"
              ? set({ from: shiftDay(st.from, 1), to: shiftDay(st.to, 1) })
              : set({ day: shiftDay(st.day, 1) }))}>›</button>
        </div>
        <Segmented<DateMode>
          options={[{ value: "day", label: "DAY" }, { value: "range", label: "RANGE" }]}
          value={st.dateMode} onChange={(v) => set({ dateMode: v })} />
        <button aria-label="Share live location" title="Share live location"
          style={stepBtnStyle()} onClick={() => setShareOpen(true)}>↗</button>
        {!mobile && isLive && (
          <span className="mono" style={{
            display: "inline-flex", alignItems: "center", gap: 6,
            color: "var(--live)", fontSize: 11, letterSpacing: 1,
          }}>
            <span style={{
              width: 8, height: 8, borderRadius: "50%", background: "var(--live)",
              animation: "chair-pulse 2s ease-out infinite",
            }} />
            LIVE
          </span>
        )}
      </div>

      {shareOpen && <ShareDialog onClose={() => setShareOpen(false)} />}

      {mobile ? (
        <>
          <div style={{ flex: 1, minHeight: 0, position: "relative", overflow: "hidden" }}>
            <JourneyMap points={points} segKinds={segKinds} hotspots={hotspots}
              cursorIndex={Math.max(0, points.length - 1)} theme={theme} live={live}
              liveStale={liveStale} fitKey={fitKey} metric={mapMetric} showTrail={st.showTrail}
              emptyText={isLive ? "Waiting for GPS…" : "No GPS trip recorded"} fill />
            {hasMapOverlayContent && (
              <>
                {/* Overlay chrome is intentionally dark in both themes, so its text is pinned light. */}
                <button className="mono" aria-pressed={st.showTrail}
                  onClick={() => set({ showTrail: !st.showTrail })}
                  style={{ ...overlayChrome, top: 12, left: 12, cursor: "pointer",
                    color: st.showTrail ? "#d4d4d8" : "#71717a", font: "inherit" }}>
                  TRAIL · {st.showTrail ? mapMetric.toUpperCase() : "OFF"}
                </button>
                {st.showTrail && <span className="mono" style={{ ...overlayChrome, bottom: 12, left: 12, color: "#a1a1aa",
                  display: "flex", gap: 9 }}>
                  <span style={{ display: "inline-flex", alignItems: "center", gap: 4 }}>
                    <span style={{ width: 14, height: 4, borderRadius: 2, background: "#71717a" }} />transit
                  </span>
                  {(mapMetric === "soc"
                    ? ([["ok", "var(--ok)"], ["low", "var(--warn)"], ["crit", "var(--live)"]] as const)
                    : ([["light", "var(--ok)"], ["mod", "var(--warn)"], ["heavy", "var(--live)"]] as const))
                    .map(([label, color]) => (
                      <span key={label} style={{ display: "inline-flex", alignItems: "center", gap: 4 }}>
                        <span style={{ width: 14, height: 4, borderRadius: 2, background: color }} />{label}
                      </span>
                    ))}
                </span>}
              </>
            )}
            {isLive && (
              <span className="mono" style={{ ...overlayChrome, top: 12, right: 12, color: "#e4e4e7",
                display: "flex", alignItems: "center", gap: 6 }}>
                {liveStale ? (
                  <>
                    <span style={{ width: 8, height: 8, borderRadius: "50%", background: "var(--warn)" }} />
                    LAST KNOWN{live && <> · <Ago tsMs={live.tsMs} /></>}
                  </>
                ) : (
                  <>
                    <span style={{ width: 8, height: 8, borderRadius: "50%", background: "var(--ok)",
                      boxShadow: "0 0 0 3px rgba(34,197,94,.2)" }} />
                    LIVE · GPS
                  </>
                )}
              </span>
            )}
          </div>
          <JourneyDock summary={summary} packs={base?.packs ?? []} />
        </>
      ) : (
        <>
          {/* ── Map + dock ── */}
          <div style={{ display: "grid", gridTemplateColumns: "1fr 264px", gap: 16 }}>
            <div style={{ height: mapHeight, position: "relative" }}>
              <JourneyMap points={points} segKinds={segKinds} hotspots={hotspots}
                cursorIndex={hi ?? -1} theme={theme} live={live} liveStale={liveStale} fitKey={fitKey}
                metric={mapMetric} showTrail={st.showTrail} />
              {(points.length > 0 || live != null) && (
                <button className="mono" aria-pressed={st.showTrail}
                  onClick={() => set({ showTrail: !st.showTrail })}
                  style={{ ...overlayChrome, top: 12, right: 12, cursor: "pointer",
                    color: st.showTrail ? "#d4d4d8" : "#71717a", font: "inherit" }}>
                  TRAIL · {st.showTrail ? mapMetric.toUpperCase() : "OFF"}
                </button>
              )}
            </div>

            <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
              <div className="card" style={{ display: "flex", flexDirection: "column", gap: 12 }}>
                <div className="eyebrow" style={{ color: "var(--text-4)" }}>
                  {base ? `BASE ${base.id}` : "FLEET"}
                </div>
                <div style={{ display: "flex", gap: 14, flexWrap: "wrap", justifyContent: "center" }}>
                  {base?.packs.map((p) => (
                    <div key={p.item.address} style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 4 }}>
                      <Ring soc={p.item.soc} power={p.item.power_w} current={p.item.current_a}
                        connected={p.connected} size={104} />
                      <span className="mono" style={{ fontSize: 11, color: "var(--text-3)" }}>{p.letter}</span>
                    </div>
                  ))}
                  {!base && (
                    <span className="mono" style={{ fontSize: 12, color: "var(--text-4)" }}>no fleet</span>
                  )}
                </div>
              </div>

              {/* Trip strip */}
              <div className="card" style={{ display: "flex", flexDirection: "column", gap: 10 }}>
                <div className="eyebrow" style={{ color: "var(--text-4)" }}>TRIP</div>
                <Readout label="DISTANCE" value={`${summary.miles.toFixed(1)} mi`} />
                <div style={{ display: "flex", gap: 16 }}>
                  <Readout label="ACTIVE" value={`${summary.activeMiles.toFixed(1)} mi`} />
                  <Readout label="TRANSIT" value={`${summary.transitMiles.toFixed(1)} mi`} />
                </div>
                <Readout label="PEAK" value={`${Math.round(summary.peakW)} W`} />
              </div>
            </div>
          </div>

          {/* ── Efficiency + energy chart (only with a trip) ── */}
          {hasTrip ? (
            <>
              {st.dateMode === "day" ? (
                <EfficiencyCard summary={eff} live={isLive} charging={anyCharging} />
              ) : (
                <div className="card mono" style={{ fontSize: 12, color: "var(--text-4)" }}>
                  Select a single day to see efficiency.
                </div>
              )}

              <div className="card">
                <div className="eyebrow" style={{ color: "var(--text-4)", marginBottom: 10 }}>
                  ENERGY OVER DISTANCE
                </div>
                <EnergyDistanceChart energy={energy} cursorIndex={hi} distUnit="mi" onHover={setHoverIndex} />
                {hi != null ? (
                  <div style={{ display: "flex", flexWrap: "wrap", gap: 20, marginTop: 12 }}>
                    <Readout label="SOC" value={cur?.soc != null ? `${Math.round(cur.soc)}%` : "—"} />
                    <Readout label="DRAW" value={`${Math.round(Math.abs(cur?.power_w ?? 0))} W`} />
                    <Readout label="DIST" value={`${(cumMi[hi] ?? 0).toFixed(2)} mi`} />
                    <Readout label="STATE" value={STATE_LABEL[curKind]} />
                  </div>
                ) : (
                  <div className="mono" style={{ fontSize: 11, color: "var(--text-4)", marginTop: 12 }}>
                    Hover the chart to inspect a point
                  </div>
                )}
              </div>
            </>
          ) : (
            <div className="mono" style={{ fontSize: 12, color: "var(--text-4)" }}>
              {isLive ? "Waiting for GPS…" : "No GPS trip recorded for this day."}
            </div>
          )}
        </>
      )}
    </div>
  );
}
