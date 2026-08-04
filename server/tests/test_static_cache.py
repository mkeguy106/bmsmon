"""Cache-Control on the static web bundles: content-hashed assets are immutable,
HTML shells always revalidate (so deploys land). Uses a fake dist tree shaped like
the real one: one shared assets/ pool (v2+v1), v2 as the default at dist/index.html,
dist/v1 with only its index.html, and the share zone's own dist/share/assets.

Also covers the /v2/ -> / redirect left behind when v2 was promoted to the root."""

import pytest
from starlette.testclient import TestClient

from app.main import create_app

IMMUTABLE = "public, max-age=31536000, immutable"


@pytest.fixture
def static_client(tmp_path, monkeypatch):
    (tmp_path / "assets").mkdir()
    (tmp_path / "assets" / "main-C92ooUkb.js").write_text("//js")
    (tmp_path / "assets" / "main-g6OXSvOr.css").write_text("/*css*/")
    (tmp_path / "index.html").write_text("<html>v2</html>")
    (tmp_path / "v1").mkdir()
    (tmp_path / "v1" / "index.html").write_text("<html>v1</html>")
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
    for path in ("/index.html", "/v1/index.html", "/", "/v1/"):
        r = static_client.get(path)
        assert r.status_code == 200, path
        assert r.headers["cache-control"] == "no-cache", path


def test_root_serves_v2_and_v1_is_nested(static_client):
    """The flip itself: v2 is what "/" hands out, v1 is only under /v1/."""
    assert static_client.get("/").text == "<html>v2</html>"
    assert static_client.get("/v1/").text == "<html>v1</html>"


def test_old_v2_path_redirects_to_root(static_client):
    """v2 bookmarks predate the promotion; 307 keeps the flip reversible."""
    for path in ("/v2", "/v2/"):
        r = static_client.get(path, follow_redirects=False)
        assert r.status_code == 307, path
        assert r.headers["location"] == "/", path


def test_v2_redirect_wins_over_the_static_mount(static_client, tmp_path):
    """A stale dist/v2/index.html (an old image layer, a dirty local build) must not
    shadow the redirect — explicit routes are registered before the "/" mount."""
    (tmp_path / "v2").mkdir()
    (tmp_path / "v2" / "index.html").write_text("<html>stale v2</html>")
    r = static_client.get("/v2/", follow_redirects=False)
    assert r.status_code == 307
    assert r.headers["location"] == "/"


def test_conditional_304_keeps_cache_header(static_client):
    first = static_client.get("/assets/main-C92ooUkb.js")
    r = static_client.get("/assets/main-C92ooUkb.js",
                          headers={"If-None-Match": first.headers["etag"]})
    assert r.status_code == 304
    assert r.headers["cache-control"] == IMMUTABLE
