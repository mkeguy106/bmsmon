import { useMemo } from "react";
import { useTrack } from "../useTrack";
import { cleanTrack } from "../model/cleanTrack";
import { cumulativeMiles, tripSummary } from "../model/journey";
import { useLocalStorage } from "../../useLocalStorage";
import { tripsCodec, type Trip } from "../trips";
import { CommandFleetRail } from "../components/CommandFleetRail";
import { CommandStage } from "../components/CommandStage";
import { CommandRange } from "../components/CommandRange";
import { CommandAside } from "../components/CommandAside";
import type { V2View } from "../nav";
import type { FleetData } from "../useFleetData";
import type { StageBase } from "../useStageBase";
import { useNow } from "../../useNow";
import type { TempConfig } from "../../temp";
import { baseView } from "../model/baseView";
import { fromDist, type DistUnit } from "../../units";

/**
 * The Command view: the 3-column mission-control grid (fleet rail · stage +
 * range · aside). Accepts the live fleet `data` as a PROP — the v2 App owns the
 * single live store and passes it in, so this view never opens a second store.
 */
export function CommandView({ data, stage, mobile, onOpen, tempF, tempConfig, distUnit }: {
  data: FleetData; stage: StageBase; mobile: boolean; onOpen: (v: V2View) => void; tempF: boolean;
  tempConfig: TempConfig | null; distUnit: DistUnit;
}) {
  // Which base occupies the stage is decided ONCE, in App (useStageBase): seize, pin,
  // in-use, hold, parked, daily driver. A rail tap pins.
  const { bases, staged, reason, pinBase, clearPin } = stage;
  const [trips, setTrips] = useLocalStorage<Trip[]>("bmsmon-v2-trips", () => [], tripsCodec);

  // Today's GPS track for the staged base (Phase-4 wiring: DRIVEN TODAY + route sketch).
  // Local midnight → now-ish window; a gentle 60 s refresh keeps Command cheaper than
  // Journey's 15 s live cadence. Same clean pipeline as the Journey map.
  // A minute-resolution local clock is plenty to catch the midnight rollover.
  const nowMin = useNow(60_000);
  const [dayFromMs, dayToMs] = useMemo<[number, number]>(() => {
    const start = new Date(); start.setHours(0, 0, 0, 0);
    return [start.getTime(), start.getTime() + 86_400_000];
    // The clock's day is what matters; recompute when the calendar day changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [new Date(nowMin).getDate()]);
  const addresses = useMemo(
    () => (staged ? staged.packs.map((p) => p.item.address) : []), [staged]);
  const rawTodayPoints = useTrack(addresses, dayFromMs, dayToMs, 60_000);
  const todayPoints = useMemo(() => cleanTrack(rawTodayPoints), [rawTodayPoints]);
  const todaySummary = useMemo(
    () => tripSummary(todayPoints, cumulativeMiles(todayPoints)), [todayPoints]);
  // What the stage and range card show about the staged base: one pure view-model (T2.1).
  const view = useMemo(
    () => (staged ? baseView(staged, { rangeParams: data.rangeParams, tempConfig }) : null),
    [staged, data.rangeParams, tempConfig]);

  // Minimal Phase-1 trip editor: a single prompt adds one trip; a blank entry
  // clears them all. Rich editing lands with the Journey view later. Trips are stored in
  // miles; the prompt takes the display unit.
  const onEditTrips = () => {
    const raw = window.prompt(
      `Add a trip as "name, ${distUnit === "km" ? "km" : "miles"}". Leave blank to clear all saved trips.`, "");
    if (raw == null) return;
    if (raw.trim() === "") { setTrips([]); return; }
    const cut = raw.lastIndexOf(",");
    if (cut < 0) return;
    const name = raw.slice(0, cut).trim();
    const dist = Number(raw.slice(cut + 1).trim());
    if (!name || !Number.isFinite(dist)) return;
    const miles = fromDist(dist, distUnit);
    const id = crypto.randomUUID?.() ?? `t-${Date.now()}`;
    setTrips((prev) => [...prev, { id, name, miles }]);
  };

  // Before the first snapshot the fleet is unknown — show CONNECTING rather than
  // an empty grid (mirrors v1 App.tsx).
  if (!staged || !view) {
    return (
      <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 10,
        padding: "96px 0", color: "var(--text-3)" }}>
        <span className="mono" style={{ fontSize: 12, letterSpacing: 2 }}>CONNECTING…</span>
        <span style={{ fontSize: 13 }}>Waiting for the first fleet snapshot.</span>
      </div>
    );
  }

  const container = mobile
    ? { display: "flex", flexDirection: "column" as const, gap: 16 }
    : { display: "grid", gridTemplateColumns: "252px minmax(0, 1fr) 340px", gap: 16, alignItems: "start" };

  return (
    <div style={container}>
      <div style={{ order: mobile ? 3 : 0, minWidth: 0 }}>
        <CommandFleetRail bases={bases} stageBaseId={staged.id} onStage={pinBase} />
      </div>
      <div style={{ order: mobile ? 1 : 0, minWidth: 0, display: "flex", flexDirection: "column", gap: 16 }}>
        <CommandStage base={staged} view={view} reason={reason} onClearPin={clearPin}
          tempF={tempF} distUnit={distUnit} mobile={mobile} drivenToday={todaySummary} />
        <CommandRange view={view} trips={trips} onEditTrips={onEditTrips} distUnit={distUnit} />
      </div>
      <div style={{ order: mobile ? 4 : 0, minWidth: 0 }}>
        <CommandAside bases={bases} onOpen={onOpen} todayPoints={todayPoints} />
      </div>
    </div>
  );
}
