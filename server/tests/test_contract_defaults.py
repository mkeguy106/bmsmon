"""C4: env-var defaults are cross-plan contracts. Checked in a clean subprocess, because
Settings reads os.environ at import time."""
import json
import os
import pathlib
import subprocess
import sys

SERVER_DIR = pathlib.Path(__file__).resolve().parents[1]


def _defaults(expr: str, **env_overrides: str):
    env = {k: v for k, v in os.environ.items() if not k.startswith("BMSMON_")}
    env.update(env_overrides)
    out = subprocess.run(
        [sys.executable, "-c",
         f"import json; from app.config import settings as s; print(json.dumps({expr}))"],
        env=env, cwd=SERVER_DIR, capture_output=True, text=True, check=True)
    return json.loads(out.stdout)


def test_group_defaults():
    assert _defaults("[s.viewer_group, s.admin_group, s.dev_groups]") == [
        "Covert.Life - Full App Access - User Group",
        "Covert.Life - bmsmon - Admin Group",
        [],
    ]


def test_ws_allowed_origins_default():
    assert _defaults("s.ws_allowed_origins") == ["https://bmsmon.covert.life"]


def test_group_settings_ignore_surrounding_whitespace():
    # Membership is an exact match, so a stray space or newline in the stack .env would
    # otherwise lock every viewer (or the owner) out.
    assert _defaults("[s.viewer_group, s.admin_group]",
                     BMSMON_VIEWER_GROUP=" Covert.Life - Full App Access - User Group \n",
                     BMSMON_ADMIN_GROUP="\tCovert.Life - bmsmon - Admin Group ") == [
        "Covert.Life - Full App Access - User Group",
        "Covert.Life - bmsmon - Admin Group",
    ]


def test_blank_group_setting_stays_empty_and_matches_nobody():
    assert _defaults("[s.viewer_group, s.admin_group]",
                     BMSMON_VIEWER_GROUP="  ", BMSMON_ADMIN_GROUP="") == ["", ""]
