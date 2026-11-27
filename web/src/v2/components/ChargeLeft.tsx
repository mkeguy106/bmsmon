import { useNow } from "../../useNow";
import { clockTime, fmtLeft } from "../model/recharge";

/**
 * Time left on a charge, counting down to an anchored ready time (model/recharge.ts):
 * "≈ 3h 12m to full · ready by 6:40 PM", or "≈ 3h 12m left" when [short]. Owns its clock tick,
 * so only this text re-renders as time passes.
 */
export function ChargeLeft({ readyAtMs, short = false }: { readyAtMs: number; short?: boolean }) {
  const now = useNow(30_000);
  const left = fmtLeft(Math.max(0, (readyAtMs - now) / 60_000));
  return <>{short ? `≈ ${left} left` : `≈ ${left} to full · ready by ${clockTime(readyAtMs, now)}`}</>;
}
