"""SRV-21/C3: the `app` logger hierarchy logs at INFO with time, level and logger name.
Under uvicorn the root logger has no handlers and sits at WARNING, so app INFO lines
(rollup, GPS scrub, dropped samples) used to be discarded."""
import logging

from app.main import LOG_FORMAT, create_app


def test_app_loggers_log_info_with_time_level_and_name():
    create_app()
    create_app()  # idempotent: still exactly one handler, no duplicated lines
    log = logging.getLogger("app")
    assert log.level == logging.INFO
    ours = [h for h in log.handlers if h.get_name() == "bmsmon"]
    assert len(ours) == 1
    assert ours[0].formatter._fmt == LOG_FORMAT
    for field in ("%(asctime)s", "%(levelname)s", "%(name)s"):
        assert field in LOG_FORMAT
    assert logging.getLogger("app.main").isEnabledFor(logging.INFO)
    assert log.propagate  # pytest's caplog hooks the root logger and must still see records
