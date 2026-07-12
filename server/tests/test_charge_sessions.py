from app.charge_sessions import detect_charge_sessions

M = 60_000  # one 1-min bucket


def b(i, soc, temp=25.0):
    return {"bucket_ms": i * M, "soc": soc, "temp_max": temp}


def test_empty():
    assert detect_charge_sessions([]) == []


def test_one_full_session():
    buckets = [b(0, 60), b(1, 80), b(2, 98, 30.0), b(3, 100, 31.0)]
    s = detect_charge_sessions(buckets)
    assert len(s) == 1
    assert s[0]["from_soc"] == 60
    assert s[0]["duration_min"] == 3
    assert s[0]["cv_tail_min"] == 2         # buckets with soc >= 98 (98 and 100)
    assert s[0]["peak_temp_c"] == 31.0


def test_incomplete_run_dropped():
    assert detect_charge_sessions([b(0, 60), b(1, 80), b(2, 90)]) == []  # never reaches 99


def test_gap_splits_two_sessions():
    a = [b(0, 60), b(1, 100)]
    later = [b(100, 55), b(101, 99)]        # 100-min gap (> 15 min) → separate
    s = detect_charge_sessions(a + later)
    assert len(s) == 2
    assert s[0]["start_ms"] == 100 * M      # newest first
