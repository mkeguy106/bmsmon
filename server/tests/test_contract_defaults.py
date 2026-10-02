"""C4: env-var defaults are cross-plan contracts. Checked in a clean subprocess, because
Settings reads os.environ at import time."""
import json
import os
import pathlib
import subprocess
import sys

SERVER_DIR = pathlib.Path(__file__).resolve().parents[1]


def _defaults(expr: str):
    env = {k: v for k, v in os.environ.items() if not k.startswith("BMSMON_")}
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
