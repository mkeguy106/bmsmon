#!/usr/bin/env python3
"""Structural guard for the image-publish pipeline (architecture review 2026-10-02, SEC-16).

A unit test for .github/workflows: it fails if an edit quietly re-opens "untested code reaches
:latest" or un-pins an action. CI runs it first in build-server's test-server job; locally:

    python3 .github/scripts/check_workflows.py      # any python3 with PyYAML

PyYAML ships in server/requirements.lock (via uvicorn[standard]), so CI needs nothing extra.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

import yaml

WORKFLOWS = Path(__file__).resolve().parent.parent / "workflows"
SHA_PINNED = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+@[0-9a-f]{40}$")
PATHS = ["server/**", "web/**", ".github/workflows/build-server.yml"]
PUBLISH_JOBS = ("build", "smoke", "promote")
MAIN_ONLY = ("github.event_name != 'pull_request'", "github.ref == 'refs/heads/main'")
SHA_TAG = "ghcr.io/mkeguy106/bmsmon-server:${{ github.sha }}"
# Compared for EXACT equality: a substring check on "refs/heads/main" also accepts the inverted
# expression (`github.ref == ...`), which would cancel main runs mid-publish and let everything else
# queue forever.
CANCEL_EXPR = "${{ github.ref != 'refs/heads/main' }}"


def as_list(value) -> list:
    if value is None:
        return []
    return [value] if isinstance(value, str) else list(value)


def pin_errors(docs: dict[str, dict]) -> list[str]:
    errors = []
    for fname, doc in docs.items():
        for jname, job in (doc.get("jobs") or {}).items():
            for step in job.get("steps") or []:
                uses = step.get("uses")
                if uses and not SHA_PINNED.match(uses):
                    errors.append(f"{fname}/{jname}: '{uses}' is not pinned to a full commit SHA")
    return errors


def build_server_errors(doc: dict) -> list[str]:
    errors: list[str] = []

    def need(ok: bool, msg: str) -> None:
        if not ok:
            errors.append(f"build-server.yml: {msg}")

    jobs = doc.get("jobs") or {}
    missing = {"test-server", "test-web", *PUBLISH_JOBS} - set(jobs)
    if missing:
        need(False, f"missing jobs {sorted(missing)} (expected test-server, test-web, build, smoke, promote)")
        return errors
    on = doc.get("on", doc.get(True))  # PyYAML (YAML 1.1) reads a bare `on:` key as True
    need(on.get("push", {}).get("branches") == ["**"], "tests must run on every branch push")
    need(on.get("push", {}).get("paths") == PATHS and on.get("pull_request", {}).get("paths") == PATHS,
         f"path filters must stay {PATHS}")
    need(doc.get("permissions") == {"contents": "read"}, "the workflow-level token must be read-only")
    for name in ("test-server", "test-web", "smoke"):
        need("permissions" not in jobs[name], f"{name} must keep the read-only default token")
    need(set(as_list(jobs["build"].get("needs"))) == {"test-server", "test-web"},
         "build must need both test jobs")
    need(as_list(jobs["smoke"].get("needs")) == ["build"], "smoke must need build")
    need(as_list(jobs["promote"].get("needs")) == ["smoke"], "promote must need smoke")
    for name in PUBLISH_JOBS:
        cond = str(jobs[name].get("if", ""))
        need(all(c in cond for c in MAIN_ONLY), f"{name} must run only for main and never for a pull_request")
    for name, job in jobs.items():
        if name != "promote":
            need(":latest" not in yaml.safe_dump(job), f"job {name} names :latest -- only promote may move it")
    need(":latest" in yaml.safe_dump(jobs["promote"]), "promote no longer moves :latest")
    pushes = [s for s in jobs["build"].get("steps") or []
              if "docker/build-push-action@" in str(s.get("uses", ""))]
    need(len(pushes) == 1, "build must have exactly one docker/build-push-action step")
    if pushes:
        raw = str((pushes[0].get("with") or {}).get("tags", ""))
        tags = [t.strip() for t in re.split(r"[\n,]", raw) if t.strip()]  # build-push-action: newline/comma list
        need(tags == [SHA_TAG], f"build must push only {SHA_TAG}, got {tags}")
    need(str((jobs["build"].get("env") or {}).get("DOCKER_BUILD_RECORD_UPLOAD")).lower() == "false",
         "DOCKER_BUILD_RECORD_UPLOAD must stay false")
    cancel = (doc.get("concurrency") or {}).get("cancel-in-progress")
    need(cancel == CANCEL_EXPR,
         f"concurrency cancel-in-progress must be exactly {CANCEL_EXPR!r} (never cancel a main run "
         f"mid-publish), got {cancel!r}")
    return errors


def main() -> int:
    docs = {p.name: yaml.safe_load(p.read_text()) for p in sorted(WORKFLOWS.glob("*.yml"))}
    errors = pin_errors(docs)
    if "build-server.yml" in docs:
        errors += build_server_errors(docs["build-server.yml"])
    else:
        errors.append("build-server.yml is missing")
    for e in errors:
        print(f"FAIL {e}")
    if not errors:
        print(f"ok   {len(docs)} workflow(s): actions SHA-pinned, publish pipeline gated")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
