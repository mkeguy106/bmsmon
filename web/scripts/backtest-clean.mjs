// Compare drawn path length before/after the Kalman pass on a real day.
// Usage: node scripts/backtest-clean.mjs <track.json>
// Fetch input with:
//   ssh joely@ddnas02 'bash -lc "docker exec -i bmsmon-db psql -U bmsmon -d bmsmon -qAt -c \
//     \"SELECT json_agg(row_to_json(row)) FROM (SELECT (ts_ms/15000)*15000 AS t, avg(lat) AS lat, \
//      avg(lon) AS lon, avg(power_w) AS power_w, avg(current_a) AS current_a, avg(soc) AS soc, \
//      avg(gps_accuracy_m) AS acc FROM samples WHERE address=ADDR AND link_event IS NULL \
//      AND lat IS NOT NULL AND lon IS NOT NULL AND (gps_accuracy_m IS NULL OR gps_accuracy_m<=250) \
//      AND ts >= DAY AND ts < DAY+1 GROUP BY 1 ORDER BY 1) row\""' > track.json
import { readFileSync } from "node:fs";
import { rejectSpikes, collapseIdleExcursions, snapStays } from "../src/v2/model/cleanTrack.ts";
import { smoothKalman } from "../src/v2/model/kalmanTrack.ts";
import { haversineMi } from "../src/v2/model/journey.ts";

const pts = JSON.parse(readFileSync(process.argv[2], "utf8")).map((p) => ({
  t: Number(p.t), lat: Number(p.lat), lon: Number(p.lon),
  power_w: p.power_w == null ? null : Number(p.power_w),
  current_a: p.current_a == null ? null : Number(p.current_a),
  soc: p.soc == null ? null : Number(p.soc),
  acc: p.acc == null ? null : Number(p.acc),
}));
const miles = (a) => a.reduce((s, p, i) => (i ? s + haversineMi(a[i - 1], p) : 0), 0);
const preKalman = snapStays(collapseIdleExcursions(rejectSpikes(pts)));
const cleaned = smoothKalman(preKalman);
console.log(JSON.stringify({
  rawPoints: pts.length,
  rawMiles: +miles(pts).toFixed(3),
  afterSpikeAndStay: +miles(preKalman).toFixed(3),
  afterKalman: +miles(cleaned).toFixed(3),
  inferredSegments: cleaned.filter((p) => p.inferred).length,
}, null, 2));
