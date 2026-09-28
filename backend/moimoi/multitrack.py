"""Envío directo de canciones a Multitrack Alabanza (repo `Daw`), por la red local.

Multitrack Alabanza expone `GET /api/info` y `POST /api/importar` (cuerpo: el .zip). Lo
normal es que las dos apps corran en la misma computadora (http://127.0.0.1:4848), pero
también puede ser otra computadora de la red.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

DEFAULT_URL = "http://127.0.0.1:4848"

# Sin proxies: la conexión es siempre a la red local.
_opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


class MultitrackError(Exception):
    pass


def normalize_url(url: str | None) -> str:
    url = (url or DEFAULT_URL).strip().rstrip("/")
    if not url.startswith(("http://", "https://")):
        url = f"http://{url}"
    parsed = urllib.parse.urlparse(url)
    if not parsed.hostname:
        raise MultitrackError("Dirección de Multitrack Alabanza inválida")
    if parsed.port is None:
        url = f"{parsed.scheme}://{parsed.hostname}:4848"
    return url


def info(url: str | None, timeout: float = 2.5) -> dict:
    """Consulta si Multitrack Alabanza está abierto en esa dirección."""
    base = normalize_url(url)
    try:
        with _opener.open(f"{base}/api/info", timeout=timeout) as response:
            data = json.loads(response.read().decode("utf-8"))
    except (urllib.error.URLError, OSError, ValueError, json.JSONDecodeError) as exc:
        return {"ok": False, "url": base, "error": _describe(exc)}
    if data.get("app") != "multitrack-alabanza":
        return {"ok": False, "url": base, "error": "En esa dirección hay otra aplicación"}
    return {"ok": True, "url": base, "bloqueado": bool(data.get("bloqueado"))}


def send_zip(url: str | None, zip_path: Path, file_name: str, timeout: float = 300) -> dict:
    """Manda el .zip; Multitrack Alabanza lo abre en una pestaña nueva."""
    base = normalize_url(url)
    size = zip_path.stat().st_size
    with open(zip_path, "rb") as body:
        request = urllib.request.Request(
            f"{base}/api/importar",
            data=body,
            method="POST",
            headers={
                "Content-Type": "application/zip",
                "Content-Length": str(size),
                "X-Nombre-Archivo": urllib.parse.quote(file_name),
            },
        )
        try:
            with _opener.open(request, timeout=timeout) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            try:
                detail = json.loads(exc.read().decode("utf-8")).get("error")
            except (ValueError, AttributeError):
                detail = None
            raise MultitrackError(detail or f"Multitrack Alabanza respondió con error {exc.code}") from exc
        except (urllib.error.URLError, OSError) as exc:
            raise MultitrackError(_describe(exc)) from exc


def _describe(exc: Exception) -> str:
    reason = getattr(exc, "reason", exc)
    text = str(reason)
    if "refused" in text.lower() or "10061" in text:
        return "Multitrack Alabanza no está abierto (o usa otro puerto)"
    if "timed out" in text.lower():
        return "Multitrack Alabanza no respondió a tiempo"
    return f"No se pudo conectar con Multitrack Alabanza: {text}"
