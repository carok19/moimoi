"""Arranque: `python -m moimoi` (abre el navegador en http://127.0.0.1:4747)."""

from __future__ import annotations

import argparse
import logging
import socket
import threading
import webbrowser
from pathlib import Path

from . import __version__
from .config import load_config


def _lan_ip() -> str | None:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("10.255.255.255", 1))
            return s.getsockname()[0]
    except OSError:
        return None


def main() -> None:
    parser = argparse.ArgumentParser(prog="moimoi", description="MoiMoi: separador de pistas con IA")
    parser.add_argument("--host", help="Dirección (usa 0.0.0.0 para abrirlo desde otros equipos de tu red)")
    parser.add_argument("--port", type=int, help="Puerto (por defecto 4747)")
    parser.add_argument("--data-dir", type=Path, help="Carpeta de datos (por defecto ~/MoiMoi)")
    parser.add_argument("--device", choices=["auto", "cpu", "cuda", "mps"], help="Dónde correr la IA")
    parser.add_argument("--no-browser", action="store_true", help="No abrir el navegador")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    cfg = load_config()
    overrides = {}
    if args.host:
        overrides["host"] = args.host
    if args.port:
        overrides["port"] = args.port
    if args.data_dir:
        overrides["data_dir"] = args.data_dir.expanduser()
    if args.device:
        overrides["device"] = args.device
    if args.no_browser:
        overrides["open_browser"] = False
    cfg = cfg.with_overrides(**overrides)

    import uvicorn

    from .app import create_app

    app = create_app(cfg)
    url = f"http://{'127.0.0.1' if cfg.host in ('0.0.0.0', '::') else cfg.host}:{cfg.port}"
    print(f"\n  MoiMoi {__version__}  →  {url}")
    if cfg.host in ("0.0.0.0", "::"):
        ip = _lan_ip()
        if ip:
            print(f"  Desde otro equipo de tu red: http://{ip}:{cfg.port}")
    print(f"  Datos: {cfg.data_dir}\n  Para cerrar: Ctrl+C\n")
    if cfg.open_browser:
        threading.Timer(1.5, lambda: webbrowser.open(url)).start()
    uvicorn.run(app, host=cfg.host, port=cfg.port, log_level="warning")


if __name__ == "__main__":
    main()
