"""Armado de la aplicación FastAPI (API + interfaz web ya compilada)."""

from __future__ import annotations

import logging
import os
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, HTMLResponse

from . import __version__, api, exports, lyrics
from .config import Config, load_config
from .db import Database
from .worker import Worker

log = logging.getLogger("moimoi")

NOT_BUILT_PAGE = """<!doctype html><html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>MoiMoi</title>
<style>body{font-family:system-ui,sans-serif;background:#111318;color:#e8eaf0;display:grid;
place-items:center;min-height:100vh;margin:0;padding:16px}main{max-width:560px}
code{background:#23262f;padding:2px 6px;border-radius:4px}</style></head><body><main>
<h1>MoiMoi está funcionando</h1><p>Falta compilar la interfaz web. En una terminal:</p>
<p><code>cd frontend</code><br><code>npm install</code><br><code>npm run build</code></p>
<p>Después recarga esta página. La API está en <a href="/docs" style="color:#8ab4ff">/docs</a>.</p>
</main></body></html>"""


def create_app(
    cfg: Config | None = None,
    separator=None,
    analyzer=None,
    transcriber=None,
    exporter=None,
) -> FastAPI:
    cfg = cfg or load_config()
    cfg.ensure_dirs()
    # Los modelos de IA se guardan junto con los datos de MoiMoi.
    os.environ.setdefault("TORCH_HOME", str(cfg.data_dir / "modelos"))
    db = Database(cfg.db_path)
    if separator is None:
        from .separation.demucs_engine import DemucsSeparator

        separator = DemucsSeparator(cfg.device, cfg.model_repo, cfg.torch_threads)
    if analyzer is None:
        from .analysis import analyze_song

        analyzer = analyze_song
    if transcriber is None and lyrics.available():
        transcriber = lyrics.transcribe
    worker = Worker(cfg, db, separator, analyzer, transcriber, exporter or exports.run_export)

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        if cfg.start_worker:
            worker.start()
        try:
            yield
        finally:
            worker.stop()

    app = FastAPI(title="MoiMoi", version=__version__, lifespan=lifespan,
                  description="Separador de pistas con IA. API usada por la interfaz web y por otras apps.")
    app.state.cfg = cfg
    app.state.db = db
    app.state.worker = worker
    app.state.separator = separator
    app.state.transcriber = transcriber
    # Otras apps locales (p. ej. Multitrack Alabanza) pueden consultar la API desde el navegador.
    app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"],
                       expose_headers=["Content-Disposition"])
    app.include_router(api.router)
    _mount_frontend(app, cfg.frontend_dir)
    return app


def _mount_frontend(app: FastAPI, frontend_dir: Path | None) -> None:
    root = frontend_dir.resolve() if frontend_dir else None
    index = root / "index.html" if root else None

    @app.get("/{path:path}", include_in_schema=False)
    def frontend(path: str):
        if path.startswith("api/") or path == "api":
            raise HTTPException(404, "No encontrado")
        if index is None or not index.exists():
            return HTMLResponse(NOT_BUILT_PAGE)
        if path:
            target = (root / path).resolve()
            if target.is_file() and root in target.parents:
                cache = "public, max-age=31536000, immutable" if "/assets/" in f"/{path}" else "no-cache"
                return FileResponse(target, headers={"Cache-Control": cache})
        return FileResponse(index, headers={"Cache-Control": "no-cache"})
