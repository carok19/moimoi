"""Arranque: `python -m moimoi` (abre el navegador en http://127.0.0.1:4747)."""

from __future__ import annotations

import argparse
import logging
import socket
import threading
import webbrowser
from pathlib import Path

from . import __version__, red
from .config import load_config

log = logging.getLogger("moimoi")


def start_https(app, cfg) -> int | None:
    """Servidor HTTPS en un hilo aparte, para abrir MoiMoi desde el navegador del celular
    (el motor de audio y "Compartir" solo funcionan en páginas seguras). Devuelve el puerto."""
    if not cfg.https_port or cfg.host not in ("0.0.0.0", "::"):
        return None
    pair = red.ensure_certificate(cfg.data_dir / "certificado")
    if pair is None:
        log.warning("Sin HTTPS para celulares: falta el paquete 'cryptography' (pip install cryptography)")
        return None
    family = socket.AF_INET6 if cfg.host == "::" else socket.AF_INET
    sock = socket.socket(family, socket.SOCK_STREAM)
    try:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind((cfg.host, cfg.https_port))
    except OSError as exc:
        sock.close()
        log.warning("Sin HTTPS para celulares: el puerto %s está ocupado (%s)", cfg.https_port, exc)
        return None
    import uvicorn

    cert, key = pair
    config = uvicorn.Config(app, ssl_certfile=str(cert), ssl_keyfile=str(key), lifespan="off",
                            log_level="warning")
    server = uvicorn.Server(config)
    threading.Thread(target=server.run, kwargs={"sockets": [sock]}, name="moimoi-https", daemon=True).start()
    return cfg.https_port


def download_models(cfg) -> None:
    """Descarga los modelos de Demucs que usa MoiMoi (quedan en la carpeta de datos)."""
    import os

    cfg.ensure_dirs()
    os.environ.setdefault("TORCH_HOME", str(cfg.data_dir / "modelos"))
    from demucs.pretrained import get_model

    from .separation.base import PRESETS

    names = sorted({p.model for p in PRESETS.values()} | {p.model_hq for p in PRESETS.values()})
    for name in names:
        print(f"Descargando {name}…", flush=True)
        get_model(name, repo=cfg.model_repo)
    print(f"Listo. Modelos guardados en {os.environ['TORCH_HOME']}")


def main() -> None:
    parser = argparse.ArgumentParser(prog="moimoi", description="MoiMoi: separador de pistas con IA")
    parser.add_argument("--host", help="Dirección (usa 0.0.0.0 para abrirlo desde otros equipos de tu red)")
    parser.add_argument("--port", type=int, help="Puerto (por defecto 4747)")
    parser.add_argument("--data-dir", type=Path, help="Carpeta de datos (por defecto ~/MoiMoi)")
    parser.add_argument("--device", choices=["auto", "cpu", "cuda", "mps"], help="Dónde correr la IA")
    parser.add_argument("--no-browser", action="store_true", help="No abrir el navegador")
    parser.add_argument("--descargar-modelos", action="store_true",
                        help="Descargar ahora los modelos de IA (para usar MoiMoi después sin internet) y salir")
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

    if args.descargar_modelos:
        download_models(cfg)
        return

    import uvicorn

    from .app import create_app

    app = create_app(cfg)
    app.state.https_port = start_https(app, cfg)
    url = f"http://{'127.0.0.1' if cfg.host in ('0.0.0.0', '::') else cfg.host}:{cfg.port}"
    print(f"\n  MoiMoi {__version__}  →  {url}")
    ips = red.lan_addresses() if cfg.host in ("0.0.0.0", "::") else []
    if ips:
        print("\n  Celulares y tablets conectados a la misma red WiFi:")
        print(f"    App MoiMoi (Android):    escribe {ips[0]}")
        if app.state.https_port:
            print(f"    Navegador del celular:   https://{ips[0]}:{app.state.https_port}"
                  "  (la primera vez acepta el aviso de seguridad)")
        print(f"    Otra computadora:        http://{ips[0]}:{cfg.port}")
    print(f"\n  Datos: {cfg.data_dir}\n  Para cerrar: Ctrl+C\n")
    if cfg.open_browser:
        threading.Timer(1.5, lambda: webbrowser.open(url)).start()
    uvicorn.run(app, host=cfg.host, port=cfg.port, log_level="warning")


if __name__ == "__main__":
    main()
