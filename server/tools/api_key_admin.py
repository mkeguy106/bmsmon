#!/usr/bin/env python3
"""Mint, list and revoke the read-only API keys the desktop widgets use.

Run inside the API container, which already has DATABASE_URL:

    docker exec -it bmsmon-api python -m tools.api_key_admin mint "desktop widgets"
    docker exec -it bmsmon-api python -m tools.api_key_admin list
    docker exec -it bmsmon-api python -m tools.api_key_admin revoke <id>

The plaintext key is printed ONCE at mint time and never stored — only its sha256
goes to the database, so a lost key is re-minted rather than recovered.
"""

import argparse
import asyncio
import secrets
import sys

import asyncpg

from app.auth.api_key import hash_key
from app.config import settings
from app.db import queries as q


async def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    sub = ap.add_subparsers(dest="cmd", required=True)
    m = sub.add_parser("mint", help="create a new key and print it once")
    m.add_argument("name", help="what this key is for, e.g. 'desktop widgets'")
    sub.add_parser("list", help="list keys (hashes are never shown)")
    r = sub.add_parser("revoke", help="revoke a key by id")
    r.add_argument("id")
    args = ap.parse_args()

    conn = await asyncpg.connect(settings.database_url)
    try:
        if args.cmd == "mint":
            # 256 bits, URL-safe so it survives being pasted into a config field.
            key = secrets.token_urlsafe(32)
            key_id = await q.create_api_key(conn, args.name, hash_key(key))
            print(f"id:  {key_id}")
            print(f"key: {key}")
            print("\nCopy it now — only its sha256 is stored, so it cannot be shown again.")
        elif args.cmd == "list":
            rows = await q.list_api_keys(conn)
            if not rows:
                print("no keys")
            for r_ in rows:
                state = "REVOKED" if r_["revoked_at"] else "active"
                used = r_["last_used_at"].isoformat() if r_["last_used_at"] else "never"
                print(f"{r_['id']}  {state:8}  last used {used}  {r_['name']}")
        elif args.cmd == "revoke":
            ok = await q.revoke_api_key(conn, args.id)
            print("revoked" if ok else "not found, or already revoked")
            return 0 if ok else 1
    finally:
        await conn.close()
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
