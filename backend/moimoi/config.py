"""Configuración de MoiMoi.

Todo se puede ajustar con variables de entorno; los valores por defecto están
pensados para usarlo en la propia computadora sin configurar nada.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, replace
from pathlib import Path

PACKAGE_DIR = Path(__file__).resolve().parent
REPO_DIR = PACKAGE_DIR.parent.parent


def _env_bool(name: str, default: bool) -> bool:
    value = os.environ.get(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "si", "sí", "on"}


def _env_path(name: str) -> Path | None:
    value = os.environ.get(name)
    return Path(value).expanduser() if value else None


@dataclass(frozen=True)
class Config:
    #: Carpeta donde se guardan la base de datos, las canciones y las exportaciones.
    data_dir: Path
    host: str = "127.0.0.1"
    port: int = 4747
    #: 'auto' (GPU NVIDIA si hay, si no CPU), 'cpu', 'cuda' o 'mps' (Apple Silicon).
    device: str = "auto"
    #: Carpeta local con modelos de Demucs ya descargados (uso sin internet).
    model_repo: Path | None = None
    #: Carpeta con el build del frontend (frontend/dist).
    frontend_dir: Path | None = None
    open_browser: bool = True
    #: Duración máxima aceptada por canción, en segundos.
    max_duration_s: float = 20 * 60
    #: Hilos de CPU para PyTorch (None = los que decida PyTorch).
    torch_threads: int | None = None
    #: Arrancar el procesador de trabajos en segundo plano (los tests lo apagan).
    start_worker: bool = True

    @property
    def songs_dir(self) -> Path:
        return self.data_dir / "canciones"

    @property
    def exports_dir(self) -> Path:
        return self.data_dir / "exportaciones"

    @property
    def db_path(self) -> Path:
        return self.data_dir / "moimoi.db"

    def with_overrides(self, **kwargs) -> "Config":
        return replace(self, **kwargs)

    def ensure_dirs(self) -> None:
        for path in (self.data_dir, self.songs_dir, self.exports_dir):
            path.mkdir(parents=True, exist_ok=True)


def load_config() -> Config:
    data_dir = _env_path("MOIMOI_DATA_DIR") or (Path.home() / "MoiMoi")
    frontend_dir = _env_path("MOIMOI_FRONTEND_DIR") or (REPO_DIR / "frontend" / "dist")
    threads = os.environ.get("MOIMOI_TORCH_THREADS")
    return Config(
        data_dir=data_dir,
        host=os.environ.get("MOIMOI_HOST", "127.0.0.1"),
        port=int(os.environ.get("MOIMOI_PORT", "4747")),
        device=os.environ.get("MOIMOI_DEVICE", "auto").strip().lower(),
        model_repo=_env_path("MOIMOI_MODEL_REPO"),
        frontend_dir=frontend_dir,
        open_browser=_env_bool("MOIMOI_OPEN_BROWSER", True),
        max_duration_s=float(os.environ.get("MOIMOI_MAX_DURATION_MIN", "20")) * 60,
        torch_threads=int(threads) if threads else None,
    )
