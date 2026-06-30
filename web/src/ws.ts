import type { FleetItem, Sample } from "./types";

export function connectLive(
  onSnapshot: (f: FleetItem[]) => void,
  onSample: (s: Sample) => void,
  onStatus: (connected: boolean) => void,
) {
  let ws: WebSocket | null = null;
  let stop = false;
  const open = () => {
    const proto = location.protocol === "https:" ? "wss" : "ws";
    ws = new WebSocket(`${proto}://${location.host}/ws`);
    ws.onopen = () => onStatus(true);
    ws.onmessage = (e) => {
      const msg = JSON.parse(e.data);
      if (msg.type === "snapshot") onSnapshot(msg.fleet);
      else if (msg.type === "sample") onSample(msg);
    };
    ws.onclose = () => { onStatus(false); if (!stop) setTimeout(open, 1500); };
    ws.onerror = () => ws?.close();
  };
  open();
  return () => { stop = true; ws?.close(); };
}
