def _flush(run, out, soc_full, cv_soc):
    if not run:
        return
    socs = [x["soc"] for x in run if x["soc"] is not None]
    if not socs or max(socs) < soc_full:
        return
    start_ms, end_ms = run[0]["bucket_ms"], run[-1]["bucket_ms"]
    from_soc = next((x["soc"] for x in run if x["soc"] is not None), None)
    temps = [x["temp_max"] for x in run if x["temp_max"] is not None]
    out.append({
        "start_ms": int(start_ms), "end_ms": int(end_ms),
        "from_soc": round(from_soc) if from_soc is not None else None,
        "duration_min": round((end_ms - start_ms) / 60_000),
        "cv_tail_min": sum(1 for x in run if x["soc"] is not None and x["soc"] >= cv_soc),
        "peak_temp_c": max(temps) if temps else None,
    })


def detect_charge_sessions(buckets, *, gap_ms=900_000, soc_full=99, cv_soc=98):
    """Group ascending 1-min charging buckets into sessions (a gap > gap_ms splits a run),
    keep runs whose peak SOC reaches soc_full, and summarize each. Newest-first."""
    out, run, prev = [], [], None
    for x in buckets:
        if prev is not None and x["bucket_ms"] - prev > gap_ms:
            _flush(run, out, soc_full, cv_soc)
            run = []
        run.append(x)
        prev = x["bucket_ms"]
    _flush(run, out, soc_full, cv_soc)
    out.sort(key=lambda s: s["start_ms"], reverse=True)
    return out
