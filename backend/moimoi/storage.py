"""Ubicación de los archivos de cada canción dentro de la carpeta de datos."""

from __future__ import annotations

import json
import os
import secrets
import shutil
import tempfile
from pathlib import Path
from typing import Any


def new_id() -> str:
    return secrets.token_hex(6)


def write_json(path: Path, data: Any) -> None:
    """Escritura atómica (archivo temporal + rename) para no dejar JSON a medias."""
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=path.name, suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            json.dump(data, fh, ensure_ascii=False, separators=(",", ":"))
        os.replace(tmp, path)
    except BaseException:
        Path(tmp).unlink(missing_ok=True)
        raise


def read_json(path: Path, default: Any = None) -> Any:
    try:
        with open(path, encoding="utf-8") as fh:
            return json.load(fh)
    except (FileNotFoundError, json.JSONDecodeError):
        return default


class SongPaths:
    def __init__(self, songs_dir: Path, song_id: str):
        if not song_id or any(c in song_id for c in "/\\.") or len(song_id) > 64:
            raise ValueError("Identificador de canción inválido")
        self.root = songs_dir / song_id

    @property
    def stems_dir(self) -> Path:
        return self.root / "pistas"

    def stem(self, stem_id: str) -> Path:
        return self.stems_dir / f"{stem_id}.flac"

    @property
    def cache_dir(self) -> Path:
        return self.root / "cache"

    @property
    def analysis(self) -> Path:
        return self.root / "analisis.json"

    @property
    def peaks(self) -> Path:
        return self.root / "picos.json"

    @property
    def lyrics(self) -> Path:
        return self.root / "letra.json"

    def source(self) -> Path | None:
        for path in sorted(self.root.glob("original.*")):
            if path.suffix not in {".part", ".ytdl", ".tmp"}:
                return path
        return None

    def thumbnail(self) -> Path | None:
        for ext in (".jpg", ".jpeg", ".png", ".webp"):
            path = self.root / f"portada{ext}"
            if path.exists():
                return path
        return None

    def create(self) -> None:
        self.stems_dir.mkdir(parents=True, exist_ok=True)

    def remove(self) -> None:
        shutil.rmtree(self.root, ignore_errors=True)

    def clear_cache(self) -> None:
        shutil.rmtree(self.cache_dir, ignore_errors=True)
