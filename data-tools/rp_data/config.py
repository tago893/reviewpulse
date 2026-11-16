"""Connection settings from environment variables, falling back to the repo's .env file."""

from __future__ import annotations

import os
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DATA_DIR = REPO_ROOT / "data"


def _read_dotenv(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.is_file():
        return values
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def setting(name: str, default: str) -> str:
    if name in os.environ:
        return os.environ[name]
    return _read_dotenv(REPO_ROOT / ".env").get(name, default)


def postgres_conninfo() -> str:
    """libpq keyword/value conninfo. The password is never printed by any command."""
    host = setting("POSTGRES_HOST", "localhost")
    port = setting("POSTGRES_PORT", "15432")
    db = setting("POSTGRES_DB", "reviewpulse")
    user = setting("POSTGRES_USER", "reviewpulse")
    password = setting("POSTGRES_PASSWORD", "reviewpulse-local-only")
    return f"host={host} port={port} dbname={db} user={user} password={password} connect_timeout=5"
