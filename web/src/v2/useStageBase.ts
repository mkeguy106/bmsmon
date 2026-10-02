import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useLocalStorage, type Codec } from "../useLocalStorage";
import { groupBases, type Base } from "./fleet";
import type { FleetData } from "./useFleetData";
import {
  decodeStagePin, foldDischarge, sameSelection, selectStageBase,
  type StagePin, type StageReason,
} from "./model/stageBase";

const PIN_KEY = "bmsmon-v2-stage-pin";
const pinCodec: Codec<StagePin | null> = { decode: decodeStagePin, encode: (v) => JSON.stringify(v) };
/** Re-check cadence for the time-driven rungs (pin + hold expiry) when no data arrives. */
const STAGE_TICK_MS = 15_000;

export interface StageBase {
  /** Every base, daily driver first (groupBases order). */
  bases: Base[];
  /** The base on the main stage; null only before the first fleet snapshot. */
  staged: Base | null;
  /** Why it is there — "seize" drives the LOW chip, "pin" the PINNED chip + AUTO button. */
  reason: StageReason | null;
  /** Rail tap: show this base now (outranks the base in use for PIN_HOLD_MS). */
  pinBase: (id: string) => void;
  /** Back to automatic selection. */
  clearPin: () => void;
}

/**
 * The v2 stage selection, owned ONCE by the v2 App and passed to Command, Journey and the
 * Health hero so they always agree (one useLocalStorage instance for the pin — WEB-16's
 * per-instance fork can't happen). The ladder itself is the pure selectStageBase; this
 * hook only supplies its memory: the session's discharge observations (HOLD), the
 * previous answer (PARKED) and a clock tick for the time-driven rungs.
 */
export function useStageBase(data: FleetData, seizeThreshold: number | null): StageBase {
  const [pin, setPin] = useLocalStorage<StagePin | null>(PIN_KEY, () => null, pinCodec);
  const bases = useMemo(() => groupBases(data.items, data.staleAddrs), [data.items, data.staleAddrs]);

  const dischargeRef = useRef<ReadonlyMap<string, number>>(new Map());
  const lastDischargeMs = useMemo(() => {
    dischargeRef.current = foldDischarge(dischargeRef.current, bases);
    return dischargeRef.current;
  }, [bases]);

  const stickyRef = useRef<string | null>(null);
  // Bumped by the tick below only when the answer would change, so a quiet fleet (phone
  // offline, nothing re-rendering) still sees a pin or hold expire — without re-rendering
  // the whole app every tick.
  const [epoch, setEpoch] = useState(0);
  const selection = useMemo(
    () => selectStageBase({ bases, seizeThreshold, pin, lastDischargeMs, sticky: stickyRef.current, nowMs: Date.now() }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [bases, seizeThreshold, pin, lastDischargeMs, epoch]);

  const latest = useRef({ bases, seizeThreshold, pin, lastDischargeMs, selection });
  latest.current = { bases, seizeThreshold, pin, lastDischargeMs, selection };
  useEffect(() => {
    if (selection) stickyRef.current = selection.baseId;
  }, [selection]);
  useEffect(() => {
    const t = setInterval(() => {
      const { selection: shown, ...inputs } = latest.current;
      const next = selectStageBase({ ...inputs, sticky: stickyRef.current, nowMs: Date.now() });
      if (!sameSelection(next, shown)) setEpoch((e) => e + 1);
    }, STAGE_TICK_MS);
    return () => clearInterval(t);
  }, []);

  const pinBase = useCallback((id: string) => setPin({ baseId: id, atMs: Date.now() }), [setPin]);
  // AUTO must visibly mean automatic: drop the PARKED memory too, or an idle fleet would
  // just stay on the base that was pinned.
  const clearPin = useCallback(() => {
    stickyRef.current = null;
    setPin(null);
  }, [setPin]);
  const staged = selection ? bases.find((b) => b.id === selection.baseId) ?? null : null;
  const reason = selection?.reason ?? null;
  return useMemo(
    () => ({ bases, staged, reason, pinBase, clearPin }),
    [bases, staged, reason, pinBase, clearPin]);
}
