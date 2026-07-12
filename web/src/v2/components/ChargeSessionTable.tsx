import { useEffect, useMemo, useState } from "react";
import type { CSSProperties } from "react";
import { getChargeSessions } from "../../api";
import { formatTemp, type TempUnit } from "../../temp";
import type { ChargeSession } from "../trends";

// A merged session carries the address it came from so Group mode can label A/B.
interface Row extends ChargeSession { addr: string }

/** minutes → "Hh Mm" (drops the hours part when under an hour). */
function fmtDuration(min: number): string {
  const m = Math.max(0, Math.round(min));
  const h = Math.floor(m / 60);
  return h > 0 ? `${h}h ${m % 60}m` : `${m}m`;
}

/** epoch-ms → short local date + time, e.g. "Jul 12, 3:45 PM". */
function fmtDate(ms: number): string {
  return new Date(ms).toLocaleString(undefined, {
    month: "short", day: "numeric", hour: "numeric", minute: "2-digit",
  });
}

/**
 * Fetch charge sessions for every selected address in parallel, keyed by addr.
 * `alive` guard drops superseded responses; the last good map is kept on error
 * so a transient failure doesn't blank the table.
 */
function useChargeSessions(addresses: string[], days: number): Map<string, ChargeSession[]> {
  const [byAddr, setByAddr] = useState<Map<string, ChargeSession[]>>(new Map());
  const key = addresses.join(",") + ":" + days;

  useEffect(() => {
    let alive = true;
    if (addresses.length === 0) {
      setByAddr(new Map());
      return;
    }
    Promise.all(
      addresses.map((a) =>
        getChargeSessions(a, days)
          .then((r) => [a, r.sessions] as const)
          .catch(() => null),
      ),
    ).then((results) => {
      if (!alive) return;
      const next = new Map<string, ChargeSession[]>();
      for (const r of results) if (r) next.set(r[0], r[1]);
      if (next.size > 0) setByAddr(next);
    });
    return () => { alive = false; };
    // key encodes addresses + days; the primitives it's built from are the deps.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  return byAddr;
}

export function ChargeSessionTable({ addresses, labels, days = 30, unit }: {
  addresses: string[]; labels: Record<string, string>; days?: number; unit: TempUnit;
}) {
  const byAddr = useChargeSessions(addresses, days);
  const showPack = addresses.length > 1;

  const rows = useMemo<Row[]>(() => {
    const merged: Row[] = [];
    for (const a of addresses) {
      for (const s of byAddr.get(a) ?? []) merged.push({ ...s, addr: a });
    }
    return merged.sort((x, y) => y.start_ms - x.start_ms);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [addresses.join(","), byAddr]);

  return (
    <div className="card">
      <div className="eyebrow" style={{ marginBottom: 10 }}>Charge sessions</div>
      {rows.length === 0 ? (
        <div className="mono" style={{ fontSize: 11, color: "var(--text-3)" }}>
          No completed charge sessions in the last {days} days.
        </div>
      ) : (
        <div style={{ overflowX: "auto" }}>
          <table className="mono" style={tableStyle}>
            <thead>
              <tr>
                <th style={thStyle}>DATE</th>
                {showPack && <th style={thStyle}>PACK</th>}
                <th style={thStyle}>FROM→100%</th>
                <th style={thStyle}>DURATION</th>
                <th style={thStyle}>CV TAIL</th>
                <th style={thStyle}>PEAK TEMP</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r, i) => (
                <tr key={`${r.addr}:${r.start_ms}:${i}`}>
                  <td style={tdStyle}>{fmtDate(r.start_ms)}</td>
                  {showPack && <td style={tdStyle}>{labels[r.addr] ?? "—"}</td>}
                  <td style={tdStyle}>{r.from_soc == null ? "—" : `${Math.round(r.from_soc)}%`}</td>
                  <td style={tdStyle}>{fmtDuration(r.duration_min)}</td>
                  <td style={tdStyle}>{Math.round(r.cv_tail_min)}m</td>
                  <td style={tdStyle}>{r.peak_temp_c == null ? "—" : formatTemp(r.peak_temp_c, unit)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

const tableStyle: CSSProperties = {
  width: "100%", borderCollapse: "collapse", fontSize: 11, whiteSpace: "nowrap",
};
const thStyle: CSSProperties = {
  textAlign: "left", padding: "6px 12px 8px 0", fontSize: 9.5, letterSpacing: ".08em",
  color: "var(--text-4)", fontWeight: 400, borderBottom: "1px solid var(--border)",
};
const tdStyle: CSSProperties = {
  padding: "7px 12px 7px 0", color: "var(--text-2)", borderBottom: "1px solid var(--border)",
};
