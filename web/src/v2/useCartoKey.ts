import { useEffect, useState } from "react";
import { fetchCartoKey, onceForSession } from "./basemap";

/** One /web/map-config request per page session, shared by every map mount. */
const webCartoKey = onceForSession(() => fetchCartoKey("/web/map-config"));

/** The CARTO basemap key for the v2 Journey map: null until it arrives, and for good if
 *  the server has none or the request fails (the map then shows placeholder tiles). */
export function useCartoKey(): string | null {
  const [key, setKey] = useState<string | null>(webCartoKey.current);
  useEffect(() => {
    let alive = true;
    void webCartoKey.load().then((k) => { if (alive) setKey(k); });
    return () => { alive = false; };
  }, []);
  return key;
}
