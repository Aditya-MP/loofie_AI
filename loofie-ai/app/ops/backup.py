"""Database backups with pg_dump, run inside the Docker database container.

Backups hold real creator data: they go in backups/ (git-ignored) and must never be committed.
On a cloud server with a managed database, use the provider's daily backups as well; this is the
safety net for the local and single-server setup.
"""

import subprocess
from datetime import datetime
from pathlib import Path

from app.config import ROOT

BACKUP_DIR = ROOT / "backups"
DB = ["docker", "compose", "exec", "-T", "db"]


def backup(keep: int = 14) -> Path:
    BACKUP_DIR.mkdir(exist_ok=True)
    out = BACKUP_DIR / f"loofie_ai-{datetime.now():%Y%m%d-%H%M%S}.dump"
    with out.open("wb") as f:
        subprocess.run([*DB, "pg_dump", "-U", "loofie", "-d", "loofie_ai", "-Fc"], stdout=f, check=True, cwd=ROOT)
    if out.stat().st_size == 0:
        out.unlink()
        raise RuntimeError("pg_dump produced an empty file; is the database running?")
    for old in sorted(BACKUP_DIR.glob("loofie_ai-*.dump"))[:-keep]:
        old.unlink()
    return out


def restore(path: Path, database: str = "loofie_ai") -> None:
    """Replaces the current database contents with the backup."""
    with path.open("rb") as f:
        subprocess.run(
            [*DB, "pg_restore", "-U", "loofie", "-d", database, "--clean", "--if-exists", "--no-owner"],
            stdin=f,
            check=True,
            cwd=ROOT,
        )
