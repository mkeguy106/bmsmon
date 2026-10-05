

def counts(body: dict) -> dict:
    """An ingest reply minus the phone_alert block, for tests that pin accepted/dropped/last_seq."""
    return {k: v for k, v in body.items() if k != "phone_alert"}
