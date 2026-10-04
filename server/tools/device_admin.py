#!/usr/bin/env python3
"""List, restore and hard-delete enrolled devices.

Run inside the API container, which already has DATABASE_URL:

    docker exec -it bmsmon-api python -m tools.device_admin list
    docker exec -it bmsmon-api python -m tools.device_admin restore <id>
    docker exec -it bmsmon-api python -m tools.device_admin delete <id> [--yes]

`delete` removes only the device row; its telemetry in `samples` is kept. No key
material is ever printed.
"""

import argparse
import asyncio
import logging
import sys
from uuid import UUID

import asyncpg

from app.config import settings
from app.db import queries as q

# An explicit name: run with -m, this module's __name__ is "__main__".
logger = logging.getLogger("tools.device_admin")


def _uuid(value: str) -> UUID:
    try:
        return UUID(value)
    except ValueError:
        raise argparse.ArgumentTypeError(f"not a device id: {value!r}")


async def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("list", help="list devices (no key material)")
    r = sub.add_parser("restore", help="un-revoke a device")
    r.add_argument("id", type=_uuid)
    d = sub.add_parser("delete", help="hard-delete a device row (samples are kept)")
    d.add_argument("id", type=_uuid)
    d.add_argument("--yes", action="store_true", help="skip the confirmation prompt")
    args = ap.parse_args(argv)

    conn = await asyncpg.connect(settings.database_url)
    try:
        if args.cmd == "list":
            rows = await q.list_devices(conn)
            if not rows:
                print("no devices")
            for row in rows:
                state = "REVOKED" if row["revoked"] else "active"
                seen = row["last_seen_at"].isoformat() if row["last_seen_at"] else "never"
                print(f"{row['id']}  {state:8}  created {row['created_at'].isoformat()}"
                      f"  last seen {seen}  {row['label'] or '-'}")
        elif args.cmd == "restore":
            ok = await q.restore_device(conn, args.id)
            if ok:
                logger.info("device_admin: restored %s", args.id)
            print("restored" if ok else "device not found")
            return 0 if ok else 1
        elif args.cmd == "delete":
            if not args.yes:
                answer = input(f"Delete device {args.id}? Its samples are kept. [y/N] ")
                if answer.strip().lower() != "y":
                    print("aborted")
                    return 1
            ok = await q.delete_device_row(conn, args.id)
            if ok:
                logger.info("device_admin: deleted %s", args.id)
            print("deleted" if ok else "device not found")
            return 0 if ok else 1
    finally:
        await conn.close()
    return 0


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO,
                        format="%(asctime)s %(levelname)s %(name)s %(message)s")
    sys.exit(asyncio.run(main()))
