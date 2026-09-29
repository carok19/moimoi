"""Armado de la aplicación FastAPI (API + interfaz web ya compilada)."""

from __future__ import annotations

import functools
import logging
import os
import time
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse

from . import __version__, api, exports, guia, lyrics, red
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
    guide_kit = guia.GuideKit(cfg.data_dir / "voz-guia")
    try:
        guide_kit.install_bundled(cfg.guide_bundle)  # voces guía y clicks incluidos (la primera vez)
    except Exception:  # noqa: BLE001 - sin voces incluidas MoiMoi sigue funcionando
        log.exception("No se pudieron instalar las voces guía incluidas")
    if exporter is None:
        exporter = functools.partial(exports.run_export, guide_kit=guide_kit)
    worker = Worker(cfg, db, separator, analyzer, transcriber, exporter)

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
    app.state.guide_kit = guide_kit
    app.state.https_port = None  # lo completa __main__ si arranca el servidor HTTPS

    # Celulares y tablets: solo si "Permitir celulares" está activado (se consulta la base
    # como mucho cada 2 segundos).
    lan_cache = {"value": True, "at": 0.0}

    def lan_allowed() -> bool:
        now = time.monotonic()
        if now - lan_cache["at"] > 2.0:
            lan_cache["value"] = bool(db.get_settings().get("lanAccess", True))
            lan_cache["at"] = now
        return lan_cache["value"]

    @app.middleware("http")
    async def lan_gate(request: Request, call_next):
        client = request.client.host if request.client else None
        if not red.is_loopback(client) and not lan_allowed():
            return JSONResponse(
                {"detail": "MoiMoi no acepta conexiones desde otros equipos. En la computadora, activa "
                           "Ajustes → Celulares → Permitir celulares."},
                status_code=403,
            )
        return await call_next(request)

    # Otras apps (Multitrack Alabanza, la app de Android) consultan la API desde otro origen.
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
