"""Base de datos SQLite: canciones, trabajos (cola de procesamiento) y ajustes."""

from __future__ import annotations

import json
import sqlite3
import threading
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

SCHEMA = """
CREATE TABLE IF NOT EXISTS songs (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    artist TEXT,
    source_type TEXT NOT NULL,
    source_url TEXT,
    original_filename TEXT,
    duration REAL,
    sample_rate INTEGER,
    preset TEXT NOT NULL,
    quality TEXT NOT NULL,
    model TEXT,
    stems TEXT,
    status TEXT NOT NULL,
    progress REAL NOT NULL DEFAULT 0,
    stage TEXT,
    error TEXT,
    thumbnail TEXT,
    lyrics_status TEXT,
    meta TEXT,
    settings TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS jobs (
    id TEXT PRIMARY KEY,
    song_id TEXT,
    kind TEXT NOT NULL,
    status TEXT NOT NULL,
    progress REAL NOT NULL DEFAULT 0,
    message TEXT,
    error TEXT,
    params TEXT,
    result TEXT,
    created_at TEXT NOT NULL,
    started_at TEXT,
    finished_at TEXT
);
CREATE INDEX IF NOT EXISTS jobs_status ON jobs(status, created_at);
CREATE TABLE IF NOT EXISTS settings (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
"""

JSON_FIELDS = {"stems", "meta", "settings", "params", "result"}

SONG_FIELDS = {
    "id", "title", "artist", "source_type", "source_url", "original_filename", "duration",
    "sample_rate", "preset", "quality", "model", "stems", "status", "progress", "stage", "error",
    "thumbnail", "lyrics_status", "meta", "settings", "created_at", "updated_at",
}
JOB_FIELDS = {
    "id", "song_id", "kind", "status", "progress", "message", "error", "params", "result",
    "created_at", "started_at", "finished_at",
}

ACTIVE_JOB_STATUSES = ("queued", "running")


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _encode(field: str, value: Any) -> Any:
    if field in JSON_FIELDS and value is not None:
        return json.dumps(value, ensure_ascii=False)
    return value


def _decode_row(row: sqlite3.Row | None) -> dict | None:
    if row is None:
        return None
    result = dict(row)
    for field in JSON_FIELDS & result.keys():
        if result[field] is not None:
            result[field] = json.loads(result[field])
    return result


class Database:
    def __init__(self, path: Path):
        self.path = Path(path)
        self._lock = threading.RLock()
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self._connect() as conn:
            conn.execute("PRAGMA journal_mode=WAL")
            conn.executescript(SCHEMA)

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.path, timeout=30)
        conn.row_factory = sqlite3.Row
        return conn

    def _execute(self, sql: str, params: Iterable[Any] = ()) -> None:
        with self._lock, self._connect() as conn:
            conn.execute(sql, tuple(params))

    def _query(self, sql: str, params: Iterable[Any] = ()) -> list[dict]:
        with self._connect() as conn:
            return [_decode_row(r) for r in conn.execute(sql, tuple(params)).fetchall()]

    def _insert(self, table: str, allowed: set[str], values: dict) -> None:
        unknown = set(values) - allowed
        if unknown:
            raise ValueError(f"Campos desconocidos para {table}: {sorted(unknown)}")
        columns = list(values)
        sql = f"INSERT INTO {table} ({', '.join(columns)}) VALUES ({', '.join('?' for _ in columns)})"
        self._execute(sql, (_encode(c, values[c]) for c in columns))

    def _update(self, table: str, allowed: set[str], row_id: str, values: dict) -> None:
        if not values:
            return
        unknown = set(values) - allowed
        if unknown:
            raise ValueError(f"Campos desconocidos para {table}: {sorted(unknown)}")
        assignments = ", ".join(f"{c} = ?" for c in values)
        params = [_encode(c, v) for c, v in values.items()] + [row_id]
        self._execute(f"UPDATE {table} SET {assignments} WHERE id = ?", params)

    # ---- canciones -------------------------------------------------------------------

    def insert_song(self, song: dict) -> None:
        stamp = now_iso()
        song = {"created_at": stamp, "updated_at": stamp, **song}
        self._insert("songs", SONG_FIELDS, song)

    def get_song(self, song_id: str) -> dict | None:
        rows = self._query("SELECT * FROM songs WHERE id = ?", (song_id,))
        return rows[0] if rows else None

    def list_songs(self) -> list[dict]:
        return self._query("SELECT * FROM songs ORDER BY created_at DESC, rowid DESC")

    def update_song(self, song_id: str, **values: Any) -> None:
        values["updated_at"] = now_iso()
        self._update("songs", SONG_FIELDS, song_id, values)

    def delete_song(self, song_id: str) -> None:
        with self._lock, self._connect() as conn:
            conn.execute("DELETE FROM jobs WHERE song_id = ?", (song_id,))
            conn.execute("DELETE FROM songs WHERE id = ?", (song_id,))

    # ---- trabajos --------------------------------------------------------------------

    def insert_job(self, job: dict) -> None:
        job = {"created_at": now_iso(), "status": "queued", "progress": 0.0, **job}
        self._insert("jobs", JOB_FIELDS, job)

    def get_job(self, job_id: str) -> dict | None:
        rows = self._query("SELECT * FROM jobs WHERE id = ?", (job_id,))
        return rows[0] if rows else None

    def update_job(self, job_id: str, **values: Any) -> None:
        self._update("jobs", JOB_FIELDS, job_id, values)

    def claim_next_job(self, kinds: Iterable[str] | None = None) -> dict | None:
        """Toma el trabajo en cola más antiguo (de esos tipos) y lo marca como 'running' (atómico)."""
        sql = "SELECT * FROM jobs WHERE status = 'queued'"
        params: list[str] = []
        if kinds is not None:
            kinds = list(kinds)
            sql += f" AND kind IN ({', '.join('?' for _ in kinds)})"
            params.extend(kinds)
        sql += " ORDER BY created_at, rowid LIMIT 1"
        with self._lock, self._connect() as conn:
            row = conn.execute(sql, params).fetchone()
            if row is None:
                return None
            conn.execute(
                "UPDATE jobs SET status = 'running', started_at = ? WHERE id = ?",
                (now_iso(), row["id"]),
            )
        job = _decode_row(row)
        job["status"] = "running"
        return job

    def list_jobs(self, song_id: str | None = None, active_only: bool = False) -> list[dict]:
        sql = "SELECT * FROM jobs"
        clauses, params = [], []
        if song_id is not None:
            clauses.append("song_id = ?")
            params.append(song_id)
        if active_only:
            clauses.append("status IN ('queued', 'running')")
        if clauses:
            sql += " WHERE " + " AND ".join(clauses)
        sql += " ORDER BY created_at, rowid"
        return self._query(sql, params)

    def active_job_for_song(self, song_id: str, kind: str) -> dict | None:
        rows = self._query(
            "SELECT * FROM jobs WHERE song_id = ? AND kind = ? AND status IN ('queued', 'running')"
            " ORDER BY created_at, rowid LIMIT 1",
            (song_id, kind),
        )
        return rows[0] if rows else None

    def requeue_interrupted_jobs(self) -> list[dict]:
        """Al arrancar: los trabajos que quedaron 'running' (se cerró el programa) vuelven a la cola."""
        rows = self._query("SELECT * FROM jobs WHERE status = 'running'")
        for job in rows:
            self.update_job(job["id"], status="queued", started_at=None, progress=0.0,
                            message="Reanudando después de reiniciar")
        return rows

    # ---- ajustes ---------------------------------------------------------------------

    def get_settings(self) -> dict:
        rows = self._query("SELECT key, value FROM settings")
        return {r["key"]: json.loads(r["value"]) for r in rows}

    def set_settings(self, values: dict) -> None:
        with self._lock, self._connect() as conn:
            for key, value in values.items():
                conn.execute(
                    "INSERT INTO settings (key, value) VALUES (?, ?)"
                    " ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                    (key, json.dumps(value, ensure_ascii=False)),
                )
