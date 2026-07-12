import { useEffect, useMemo, useState } from "react";
import type { CSSProperties } from "react";
import type { TempUnit } from "../../temp";
import { useLocalStorage } from "../../useLocalStorage";
import { groupBases, DAILY_DRIVER_BASE, type BasePack } from "../fleet";
import type { FleetData } from "../useFleetData";
import type { TrendSeries } from "../trends";
import { useTrends } from "../useTrends";
import { LineChart, type ChartSeries } from "../components/LineChart";
import { Segmented } from "../components/Segmented";
import { ChargeSessionTable } from "../components/ChargeSessionTable";
import { NotesCard } from "../components/NotesCard";
import { projectMonthsTo80 } from "../model/trends";

// ── Persisted control state ────────────────────────────────────────────────
type HistPack = "group" | "A" | "B";
type HistRange = "all" | "24h" | "7d" | "1m" | "1y" | "custom";
interface HistState { base: string; pack: HistPack; range: HistRange; from: string; to: string }

const DEFAULT_HIST: HistState = {
  base: DAILY_DRIVER_BASE, pack: "group", range: "all", from: "", to: "",
};
const RANGE_KEYS: HistRange[] = ["all", "24h", "7d", "1m", "1y", "custom"];

const histCodec = {
  decode: (raw: string): HistState | null => {
    try {
      const o = JSON.parse(raw) as Record<string, unknown>;
      if (!o || typeof o !== "object") return null;
      return {
        base: typeof o.base === "string" ? o.base : DEFAULT_HIST.base,
        pack: o.pack === "A" || o.pack === "B" || o.pack === "group" ? o.pack : DEFAULT_HIST.pack,
        range: RANGE_KEYS.includes(o.range as HistRange) ? (o.range as HistRange) : DEFAULT_HIST.range,
        from: typeof o.from === "string" ? o.from : "",
        to: typeof o.to === "string" ? o.to : "",
      };
    } catch { return null; }
  },
  encode: (v: HistState) => JSON.stringify(v),
};

const DAY_MS = 86_400_000;
const SPAN_MS: Record<Exclude<HistRange, "all" | "custom">, number> = {
  "24h": DAY_MS, "7d": 7 * DAY_MS, "1m": 30 * DAY_MS, "1y": 365 * DAY_MS,
};

type Metric = "soh" | "cell_spread_mv" | "temp_avg" | "temp_min" | "temp_max";

/** "YYYY-MM-DD" → epoch-ms (UTC midnight), or null when unparseable. */
function parseDay(s: string): number | null {
  const t = Date.parse(s);
  return Number.isFinite(t) ? t : null;
}

/**
 * Blend the base's packs into one averaged track: union of every bucket `t`,
 * mean of the non-null metric values at each `t` (null where no pack had one, so
 * gaps stay gaps and the line never bridges them). Packs share bucket boundaries
 * (same range → same bucket_ms), so the union aligns.
 */
function mergedAvg(list: TrendSeries[], metric: Metric): { t: number; v: number | null }[] {
  const sum = new Map<number, number>();
  const cnt = new Map<number, number>();
  const seen = new Set<number>();
  for (const s of list) {
    for (const p of s.points) {
      seen.add(p.t);
      const v = p[metric];
      if (v != null && Number.isFinite(v)) {
        sum.set(p.t, (sum.get(p.t) ?? 0) + v);
        cnt.set(p.t, (cnt.get(p.t) ?? 0) + 1);
      }
    }
  }
  return [...seen].sort((a, b) => a - b).map((t) => {
    const c = cnt.get(t) ?? 0;
    return { t, v: c > 0 ? sum.get(t)! / c : null };
  });
}

function btnStyle(active: boolean): CSSProperties {
  return {
    padding: "6px 12px", fontSize: 11, letterSpacing: ".06em", cursor: "pointer",
    borderRadius: 7, fontFamily: '"JetBrains Mono", monospace',
    border: active ? "1px solid var(--border-strong)" : "1px solid var(--border)",
    background: active ? "var(--nav-active)" : "transparent",
    color: active ? "var(--text)" : "var(--text-3)",
  };
}

function ChartCard({ title, caption, children }: {
  title: string; caption?: string; children: React.ReactNode;
}) {
  return (
    <div className="card">
      <div className="eyebrow" style={{ marginBottom: 10 }}>{title}</div>
      {children}
      {caption && (
        <div className="mono" style={{ fontSize: 11, color: "var(--text-3)", marginTop: 8 }}>
          {caption}
        </div>
      )}
    </div>
  );
}

export function HistoryView({ data, unit, mobile }: {
  data: FleetData; unit: TempUnit; mobile: boolean;
}) {
  const [hist, setHist] = useLocalStorage<HistState>("bmsmon-v2-hist", () => DEFAULT_HIST, histCodec);

  const bases = useMemo(
    () => groupBases(data.items, data.staleAddrs), [data.items, data.staleAddrs]);
  const base = bases.find((b) => b.id === hist.base) ?? bases[0];

  // Selected packs → addresses. group = all packs; A/B = the pack whose letter matches.
  const selectedPacks: BasePack[] = useMemo(() => {
    if (!base) return [];
    if (hist.pack === "group") return base.packs;
    const p = base.packs.find((bp) => bp.letter === hist.pack);
    return p ? [p] : [];
  }, [base, hist.pack]);
  const addresses = useMemo(() => selectedPacks.map((p) => p.item.address), [selectedPacks]);
  // address → A/B letter, for the charge-session table's PACK column in Group mode.
  const labels = useMemo(
    () => Object.fromEntries(selectedPacks.map((p) => [p.item.address, p.letter])),
    [selectedPacks]);

  // "all" resolves to the earliest first_ms once a series is loaded; until then a
  // now−1y default. The effect re-resolves it after the first fetch (first_ms is
  // window-independent, so it converges in one extra fetch).
  const [allFromMs, setAllFromMs] = useState<number | null>(null);

  // Quantize now to the minute so the [from,to] window (and thus the fetch key)
  // doesn't churn on the 1 s `data.now` tick — trends refetch at most once/min.
  const nowMs = Math.floor(data.now / 60_000) * 60_000;
  const [fromMs, toMs] = useMemo<[number, number]>(() => {
    if (hist.range === "custom") {
      const cFrom = parseDay(hist.from);
      const cTo = parseDay(hist.to);
      return [cFrom ?? nowMs - 7 * DAY_MS, cTo != null ? cTo + DAY_MS : nowMs];
    }
    if (hist.range === "all") return [allFromMs ?? nowMs - 365 * DAY_MS, nowMs];
    return [nowMs - SPAN_MS[hist.range], nowMs];
  }, [hist.range, hist.from, hist.to, nowMs, allFromMs]);

  const trends = useTrends(addresses, fromMs, toMs);

  // Track the earliest first_ms across loaded series → drives the "all" window.
  useEffect(() => {
    let m: number | null = null;
    for (const s of trends.values()) {
      if (s.first_ms != null && (m == null || s.first_ms < m)) m = s.first_ms;
    }
    if (m != null) setAllFromMs(m);
  }, [trends]);

  const groupMode = hist.pack === "group";
  const seriesList = useMemo(
    () => addresses.map((a) => trends.get(a)).filter((s): s is TrendSeries => !!s),
    [addresses, trends]);

  // Build the LineChart series for a metric (group = one averaged line; A/B = the
  // selected pack). `transform` converts unit (temperature °C→°F).
  const buildSeries = (metric: Metric, color: string, transform?: (v: number) => number): ChartSeries[] => {
    const tf = transform ?? ((v: number) => v);
    if (groupMode) {
      if (seriesList.length === 0) return [];
      return [{
        points: mergedAvg(seriesList, metric).map((p) => ({ t: p.t, v: p.v == null ? null : tf(p.v) })),
        color, label: base ? `Base ${base.id}` : undefined,
      }];
    }
    return selectedPacks
      .map((p) => {
        const s = trends.get(p.item.address);
        const pts = (s?.points ?? []).map((pt) => {
          const raw = pt[metric];
          return { t: pt.t, v: raw == null ? null : tf(raw) };
        });
        return { points: pts, color, label: p.letter };
      })
      .filter((cs) => cs.points.length > 0);
  };

  // ── Header: per-pack "since {year}" (differing years read as "replaced"). ──
  const headerLine = useMemo(() => {
    const parts = selectedPacks
      .map((p) => {
        const y = trends.get(p.item.address)?.first_ms;
        return y != null ? { letter: p.letter, year: new Date(y).getFullYear() } : null;
      })
      .filter((x): x is { letter: string; year: number } => x != null);
    if (parts.length === 0) return null;
    const years = parts.map((p) => p.year);
    const differ = years.length === 2 && years[0] !== years[1];
    const maxYear = Math.max(...years);
    return parts
      .map((p) => (differ && p.year === maxYear ? `${p.letter} replaced ${p.year}` : `${p.letter} since ${p.year}`))
      .join(" · ");
  }, [selectedPacks, trends]);

  // ── Capacity fade (SOH) ──
  const sohChart = buildSeries("soh", "var(--ok)");
  const projPoints = (sohChart[0]?.points ?? []).map((p) => ({ t: p.t, soh: p.v }));
  const months = projectMonthsTo80(projPoints);
  const capCaption = months == null ? "insufficient data" : `≈ ${Math.round(months)} mo to 80%`;
  const sohBands = [
    { from: 90, to: 100, color: "var(--ok)" },
    { from: 80, to: 90, color: "var(--warn)" },
    { from: 70, to: 80, color: "var(--live)" },
  ];

  // ── Cell imbalance ──
  const spreadChart = buildSeries("cell_spread_mv", "var(--warn)");

  // ── Temperature: convert °C→°F when the unit is F so the axis reads true. ──
  const toUnit = (c: number) => (unit === "F" ? c * 9 / 5 + 32 : c);
  const unitLabel = unit === "F" ? "°F" : "°C";
  const tempChart = buildSeries("temp_avg", "var(--text-3)", toUnit);
  const tempRibbon = useMemo(() => {
    const list = groupMode ? seriesList : selectedPacks.map((p) => trends.get(p.item.address)).filter((s): s is TrendSeries => !!s);
    if (list.length === 0) return undefined;
    const lo = mergedAvg(list, "temp_min");
    const hiMap = new Map(mergedAvg(list, "temp_max").map((p) => [p.t, p.v]));
    return {
      points: lo.map((p) => ({
        t: p.t,
        lo: p.v == null ? null : toUnit(p.v),
        hi: (() => { const h = hiMap.get(p.t); return h == null ? null : toUnit(h); })(),
      })),
      color: "var(--text-3)",
    };
    // toUnit depends only on `unit`; list on group/packs/trends.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [groupMode, seriesList, selectedPacks, trends, unit]);

  if (!base) {
    return (
      <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 10,
        padding: "96px 0", color: "var(--text-3)" }}>
        <span className="mono" style={{ fontSize: 12, letterSpacing: 2 }}>CONNECTING…</span>
        <span style={{ fontSize: 13 }}>Waiting for the first fleet snapshot.</span>
      </div>
    );
  }

  const set = (patch: Partial<HistState>) => setHist((s) => ({ ...s, ...patch }));

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
      {/* ── Controls ── */}
      <div style={{ display: "flex", flexWrap: "wrap", gap: 10, alignItems: "center" }}>
        <div style={{ display: "flex", flexWrap: "wrap", gap: 6 }}>
          {bases.map((b) => (
            <button key={b.id} className="mono" style={btnStyle(b.id === hist.base)}
              onClick={() => set({ base: b.id })}>
              Base {b.id}
            </button>
          ))}
        </div>
        <Segmented<HistPack>
          options={[{ value: "group", label: "GROUP" }, { value: "A", label: "A" }, { value: "B", label: "B" }]}
          value={hist.pack} onChange={(v) => set({ pack: v })} />
        <div style={{ display: "flex", flexWrap: "wrap", gap: 6 }}>
          {RANGE_KEYS.map((r) => (
            <button key={r} className="mono" style={btnStyle(r === hist.range)}
              onClick={() => set({ range: r })}>
              {r === "all" ? "ALL" : r === "custom" ? "CUSTOM" : r.toUpperCase()}
            </button>
          ))}
        </div>
        {hist.range === "custom" && (
          <div style={{ display: "flex", gap: 6, alignItems: "center" }}>
            <input type="date" value={hist.from} onChange={(e) => set({ from: e.target.value })}
              className="mono" style={dateInputStyle} />
            <span style={{ color: "var(--text-4)" }}>→</span>
            <input type="date" value={hist.to} onChange={(e) => set({ to: e.target.value })}
              className="mono" style={dateInputStyle} />
          </div>
        )}
      </div>

      {/* ── Header line ── */}
      {headerLine && (
        <div className="mono" style={{ fontSize: 11, color: "var(--text-4)" }}>{headerLine}</div>
      )}

      {/* ── Capacity + imbalance (side-by-side on desktop) ── */}
      <div style={{ display: "grid", gridTemplateColumns: mobile ? "1fr" : "1fr 1fr", gap: 16 }}>
        <ChartCard title="Capacity fade · SOH" caption={capCaption}>
          <LineChart series={sohChart} bands={sohBands} yMin={70} yMax={100} unitLabel="%" />
        </ChartCard>
        <ChartCard title="Cell imbalance · spread">
          <LineChart series={spreadChart}
            watchLine={{ v: 40, color: "var(--live)", label: "40 mV" }} unitLabel=" mV" />
        </ChartCard>
      </div>

      {/* ── Temperature (full width) ── */}
      <ChartCard title="Temperature · min / avg / max">
        <LineChart series={tempChart} ribbon={tempRibbon} unitLabel={unitLabel} height={200} />
      </ChartCard>

      {/* ── Charge-session log + per-base notes ── */}
      <ChargeSessionTable addresses={addresses} labels={labels} unit={unit} />
      <NotesCard baseId={base.id} />
    </div>
  );
}

const dateInputStyle: CSSProperties = {
  background: "var(--panel-2)", color: "var(--text)", border: "1px solid var(--border)",
  borderRadius: 6, padding: "5px 8px", fontSize: 11,
};
