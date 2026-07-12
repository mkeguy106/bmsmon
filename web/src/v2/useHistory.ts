import { useEffect, useState } from "react";
import { getHistory } from "../api";
import type { HistPoint } from "./history";

const REFRESH_MS = 180_000;

export function useHistory(): Map<string, HistPoint[]> {
  const [map, setMap] = useState<Map<string, HistPoint[]>>(new Map());
  useEffect(() => {
    let alive = true;
    const load = () => getHistory(24)
      .then((r) => { if (alive) setMap(new Map(r.series.map((s) => [s.address, s.points]))); })
      .catch(() => { /* keep last */ });
    load();
    const t = setInterval(load, REFRESH_MS);
    return () => { alive = false; clearInterval(t); };
  }, []);
  return map;
}
