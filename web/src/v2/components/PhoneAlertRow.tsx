import { useEffect, useState } from "react";
import { getPhoneAlert, putPhoneAlert } from "../../api";
import { failureKind } from "../model/formErrors";
import {
  PHONE_ALERT_OPTIONS, phoneAlertEditable, phoneAlertFromValue, phoneAlertStatus, phoneAlertValue, type PhoneAlert,
} from "../model/phoneAlert";

/** The phone-battery alarm threshold. Viewers see it; only admins can change it (the server
 *  answers a viewer's PUT with 403, which flips the row read-only). The server is the source of
 *  truth and the phone edits it too, so it is re-read after every save. */
export function PhoneAlertRow() {
  const [alert, setAlert] = useState<PhoneAlert | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [readOnly, setReadOnly] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const refresh = () => getPhoneAlert()
    .then((a) => { setAlert(a); setLoadFailed(false); })
    .catch(() => setLoadFailed(true));
  useEffect(() => { refresh(); }, []);

  const save = (v: string) => {
    setBusy(true);
    putPhoneAlert(phoneAlertFromValue(v))
      .then((a) => { setAlert(a); setErr(null); return refresh(); })
      .catch((e) => {
        const k = failureKind(e);
        // 403 = signed in but not an admin: lock the row. 401 = the session expired: say so
        // and leave the row editable, so a reload and retry works.
        const forbidden = e instanceof Error && e.message === "403";
        if (forbidden) setReadOnly(true);
        setErr(forbidden ? "Only an admin can change this."
          : k === "auth" ? "Your sign-in expired — reload the page and try again."
          : k === "refused" ? "That value was refused."
          : "Couldn't save — check the connection and try again.");
      })
      .finally(() => setBusy(false));
  };

  const options = alert !== null
    && !PHONE_ALERT_OPTIONS.some((o) => o.value === phoneAlertValue(alert.low_pct))
    ? [...PHONE_ALERT_OPTIONS, { value: phoneAlertValue(alert.low_pct), label: `${alert.low_pct}%` }]
    : PHONE_ALERT_OPTIONS;

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 12 }}>
        <span style={{ fontSize: 13, color: "var(--text-2)" }}>Phone battery alert</span>
        <select aria-label="Phone battery alert threshold"
          value={alert ? phoneAlertValue(alert.low_pct) : ""}
          disabled={!phoneAlertEditable(alert) || readOnly || busy}
          onChange={(e) => save(e.target.value)}
          style={{ background: "var(--input-bg, var(--nav-active))", border: "1px solid var(--border)",
                   color: "var(--text)", fontSize: 12, padding: "6px 10px", borderRadius: 7 }}>
          {alert === null && <option value="">—</option>}
          {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
      </div>
      <span style={{ fontSize: 12, color: "var(--text-3)" }}>
        Pages your iPhone when the chair phone's battery drops below this.
        {alert && ` ${phoneAlertStatus(alert, Date.now())}.`}
      </span>
      {(readOnly || (alert !== null && !phoneAlertEditable(alert))) && <span style={{ fontSize: 12, color: "var(--text-3)" }}>
        Read-only: changing this needs the admin group.</span>}
      {loadFailed && <span style={{ fontSize: 12, color: "var(--live)" }}>
        Couldn't load the alert threshold — check the connection.</span>}
      {err && <span style={{ fontSize: 12, color: "var(--live)" }}>{err}</span>}
    </div>
  );
}
