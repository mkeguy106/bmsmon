import hashlib
import secrets

_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"


def generate_code() -> str:
    return "".join(secrets.choice(_ALPHABET) for _ in range(20))


def hash_code(code: str) -> str:
    return hashlib.sha256(code.strip().encode()).hexdigest()
