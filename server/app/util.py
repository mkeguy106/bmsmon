import uuid as _uuid


def jsonable(rows: list[dict]) -> list[dict]:
    """Make asyncpg row dicts JSON-serializable (datetimes -> ISO strings, UUIDs -> str).

    Shared by the /web/* REST responses and the /ws frames (SRV-13: previously a
    private helper in routers/ws.py imported cross-router).
    """
    out = []
    for r in rows:
        d = dict(r)
        for k, v in list(d.items()):
            if hasattr(v, "isoformat"):
                d[k] = v.isoformat()
            elif isinstance(v, _uuid.UUID):
                d[k] = str(v)
        out.append(d)
    return out
