"""Cache-Control on the static web bundles: content-hashed assets are immutable,
HTML shells always revalidate (so deploys land). Uses a fake dist tree shaped like
the real one: one shared assets/ pool (v1+v2), dist/v2 with only its index.html,
and the share zone's own dist/share/assets."""

import pytest
from starlette.testclient import TestClient

from app.main import create_app

IMMUTABLE = "public, max-age=31536000, immutable"


@pytest.fixture
def static_client(tmp_path, monkeypatch):
    (tmp_path / "assets").mkdir()
    (tmp_path / "assets" / "main-C92ooUkb.js").write_text("//js")
    (tmp_path / "assets" / "main-g6OXSvOr.css").write_text("/*css*/")
    (tmp_path / "index.html").write_text("<html>v1</html>")
    (tmp_path / "v2").mkdir()
    (tmp_path / "v2" / "index.html").write_text("<html>v2</html>")
    (tmp_path / "share").mkdir()
    (tmp_path / "share" / "index.html").write_text("<html>guest</html>")
    (tmp_path / "share" / "assets").mkdir()
    (tmp_path / "share" / "assets" / "index-RJuVptPC.js").write_text("//guest js")
    monkeypatch.setenv("BMSMON_WEB_DIST", str(tmp_path))
    with TestClient(create_app()) as tc:
        yield tc


def test_hashed_assets_are_immutable(static_client):
    for path in ("/assets/main-C92ooUkb.js", "/assets/main-g6OXSvOr.css",
                 "/share/assets/index-RJuVptPC.js"):
        r = static_client.get(path)
        assert r.status_code == 200, path
        assert r.headers["cache-control"] == IMMUTABLE, path


def test_html_shells_always_revalidate(static_client):
    # explicit .html paths and the html=True directory-index forms
    for path in ("/index.html", "/v2/index.html", "/", "/v2/"):
        r = static_client.get(path)
        assert r.status_code == 200, path
        assert r.headers["cache-control"] == "no-cache", path


def test_conditional_304_keeps_cache_header(static_client):
    first = static_client.get("/assets/main-C92ooUkb.js")
    r = static_client.get("/assets/main-C92ooUkb.js",
                          headers={"If-None-Match": first.headers["etag"]})
    assert r.status_code == 304
    assert r.headers["cache-control"] == IMMUTABLE
