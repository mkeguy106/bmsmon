import { useEffect, useMemo, useState } from "react";
import type { CSSProperties } from "react";
import type { TempUnit } from "../../temp";
import { useLocalStorage } from "../../useLocalStorage";
import { groupBases, DAILY_DRIVER_BASE } from "../fleet";
import type { FleetData } from "../useFleetData";
import { useTrack } from "../useTrack";
import {
  haversineMi, classifySegment, cumulativeMiles, detectHotspots, energySeries, tripSummary,
  type SegKind,
} from "../model/journey";
import { JourneyMap } from "../components/JourneyMap";
import { EnergyDistanceChart } from "../components/EnergyDistanceChart";
import { Ring } from "../components/Ring";
import { Segmented } from "../components/Segmented";

// ── Persisted control state ────────────────────────────────────────────────
type DateMode = "day" | "range";
interface JourneyState { dateMode: DateMode; day: string; from: string; to: string }

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
  dateMode: "day", day: todayStr(), from: todayStr(), to: todayStr(),
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
      };
    } catch { return null; }
  },
  encode: (v: JourneyState) => JSON.stringify(v),
};

const STATE_LABEL: Record<SegKind, string> = {
  active: "ACTIVE", transit: "IN TRANSIT", idle: "IDLE",
};

const PLAY_STEP_MS = 280;

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

/** One labelled readout cell in the playback bar. */
function Readout({ label, value }: { label: string; value: string }) {
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 2, minWidth: 62 }}>
      <span className="eyebrow" style={{ color: "var(--text-4)" }}>{label}</span>
      <span className="mono" style={{ fontSize: 14, color: "var(--text)", fontWeight: 600 }}>{value}</span>
    </div>
  );
}

export function JourneyView({ data, theme, unit: _unit, mobile }: {
  data: FleetData; theme: "dark" | "light"; unit: TempUnit; mobile: boolean;
}) {
  const [st, setSt] = useLocalStorage<JourneyState>("bmsmon-v2-journey", () => DEFAULT_JOURNEY, journeyCodec);
  const [cursorIndex, setCursorIndex] = useState(0);
  const [playing, setPlaying] = useState(false);

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

  const points = useTrack(addresses, fromMs, toMs);

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

  // Clamp the cursor into range whenever the track changes (0 when empty).
  useEffect(() => {
    setCursorIndex((i) => Math.max(0, Math.min(points.length - 1, i)));
  }, [points.length]);

  // Playback: advance the cursor mod length; never runs on an empty trip.
  useEffect(() => {
    if (!playing || points.length === 0) return;
    const t = setInterval(() => setCursorIndex((i) => (i + 1) % points.length), PLAY_STEP_MS);
    return () => clearInterval(t);
  }, [playing, points.length]);

  const ci = Math.max(0, Math.min(points.length - 1, cursorIndex));
  const cur = points[ci];
  const curKind: SegKind = segKinds[ci] ?? "idle";

  const mapHeight = mobile ? 380 : 480;

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
      {/* ── Date toolbar ── */}
      <div style={{ display: "flex", flexWrap: "wrap", gap: 10, alignItems: "center" }}>
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
      </div>

      {/* ── Map + dock ── */}
      <div style={{ display: "grid", gridTemplateColumns: mobile ? "1fr" : "1fr 264px", gap: 16 }}>
        <div style={{ height: mapHeight }}>
          <JourneyMap points={points} segKinds={segKinds} hotspots={hotspots}
            cursorIndex={ci} theme={theme} />
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

      {/* ── Playback bar + energy chart (only with a trip) ── */}
      {hasTrip ? (
        <>
          <div className="card" style={{ display: "flex", flexDirection: "column", gap: 12 }}>
            <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
              <button aria-label={playing ? "Pause" : "Play"} onClick={() => setPlaying((p) => !p)}
                style={{
                  width: 36, height: 36, borderRadius: 8, border: "1px solid var(--border)",
                  background: "var(--panel-2)", color: "var(--text)", cursor: "pointer",
                  fontSize: 14, display: "inline-flex", alignItems: "center", justifyContent: "center",
                }}>
                {playing ? "⏸" : "▶"}
              </button>
              <input type="range" min={0} max={Math.max(0, points.length - 1)} value={ci}
                onChange={(e) => { setPlaying(false); setCursorIndex(Number(e.target.value)); }}
                style={{ flex: 1, accentColor: "var(--warn)" }} />
            </div>
            <div style={{ display: "flex", flexWrap: "wrap", gap: 20 }}>
              <Readout label="SOC" value={cur?.soc != null ? `${Math.round(cur.soc)}%` : "—"} />
              <Readout label="DRAW" value={`${Math.round(Math.abs(cur?.power_w ?? 0))} W`} />
              <Readout label="DIST" value={`${(cumMi[ci] ?? 0).toFixed(2)} mi`} />
              <Readout label="STATE" value={STATE_LABEL[curKind]} />
            </div>
          </div>

          <div className="card">
            <div className="eyebrow" style={{ color: "var(--text-4)", marginBottom: 10 }}>
              ENERGY OVER DISTANCE
            </div>
            <EnergyDistanceChart energy={energy} cursorIndex={ci} distUnit="mi" />
          </div>
        </>
      ) : (
        <div className="mono" style={{ fontSize: 12, color: "var(--text-4)" }}>
          No GPS trip recorded for this day.
        </div>
      )}
    </div>
  );
}
