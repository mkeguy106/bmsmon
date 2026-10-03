"""SRV-21/C3: the `app` logger hierarchy logs at INFO with time, level and logger name.
Under uvicorn the root logger has no handlers and sits at WARNING, so app INFO lines
(rollup, GPS scrub, dropped samples) used to be discarded."""
import logging

import pytest

from app.main import LOG_FORMAT, QuietAccessLogFilter, create_app


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


def _access(path: str, status: int) -> logging.LogRecord:
    # The exact shape uvicorn's access logger emits: (client, method, path?query, http, status).
    return logging.LogRecord("uvicorn.access", logging.INFO, __file__, 1,
                             '%s - "%s %s HTTP/%s" %d',
                             ("10.0.0.1:5555", "GET", path, "1.1", status), None)


def test_access_filter_is_installed_once():
    create_app()
    create_app()
    mine = [f for f in logging.getLogger("uvicorn.access").filters
            if isinstance(f, QuietAccessLogFilter)]
    assert len(mine) == 1


@pytest.mark.parametrize("path,status,kept", [
    ("/api/v1/health", 200, False),             # docker healthcheck, every 30 s
    ("/api/v1/health/detail", 200, False),      # the uptime monitor
    ("/api/v1/groups", 200, False),             # desktop widgets
    ("/share/tok/feed?since=123", 200, False),  # a guest page, every 4 s
    ("/share/tok/feed", 304, False),
    ("/api/v1/health", 503, True),              # failures on those routes still log
    ("/api/v1/health/detail", 503, True),
    ("/api/v1/groups", 401, True),
    ("/share/tok/feed", 404, True),
    ("/share/tok", 200, True),                  # a page load, not a poll
    ("/share/tok/map-config", 200, True),
    ("/api/v1/ingest", 200, True),              # uploads stay: the deploy-gap diagnosis needs them
    ("/web/fleet", 200, True),
])
def test_access_filter_drops_only_successful_polls(path, status, kept):
    assert QuietAccessLogFilter().filter(_access(path, status)) is kept


def test_access_filter_passes_records_it_does_not_understand():
    odd = logging.LogRecord("uvicorn.access", logging.INFO, __file__, 1, "x", None, None)
    assert QuietAccessLogFilter().filter(odd) is True
