"""Entrada de canciones: archivos subidos y links (YouTube y cualquier sitio que soporte yt-dlp)."""

from __future__ import annotations

import re
import shutil
from pathlib import Path
from typing import Callable
from urllib.parse import urlparse

from .audio_io import find_ffmpeg

AUDIO_EXTENSIONS = {
    ".mp3", ".wav", ".flac", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".webm", ".mp4", ".m4v",
    ".mov", ".mkv", ".aiff", ".aif", ".wma", ".alac", ".caf", ".3gp",
}
MAX_UPLOAD_BYTES = 1024 * 1024 * 1024  # 1 GB

_TITLE_NOISE = re.compile(
    r"\s*[\(\[\{][^\)\]\}]*(official|oficial|video|v[ií]deo|audio|lyric|letra|en vivo|live|"
    r"visualizer|hd|4k|remaster|karaoke|cover|ac[uú]stico|acoustic)[^\)\]\}]*[\)\]\}]",
    re.IGNORECASE,
)
_TRAILING_NOISE = re.compile(
    r"\s*[\|•·]\s*(official|oficial|video|audio|letra|lyrics?|en vivo|live)\b.*$", re.IGNORECASE
)
# Guion entre título y artista (con espacio al menos de un lado: "VIDEO OFICIAL -Miel San Marcos").
_DASH = re.compile(r"\s+[-–—]\s*|\s*[-–—]\s+")
# Una parte que es solo "VIDEO OFICIAL", "Official Audio", "Lyric Video"...
_NOISE_PART = re.compile(r"^(?:(?:official|oficial|music|lyrics?|letra|v[ií]deo|audio|visualizer|hd|4k)\s*)+$",
                         re.IGNORECASE)


class IngestError(Exception):
    """No se pudo obtener la canción."""


def is_url(text: str) -> bool:
    try:
        parsed = urlparse(text.strip())
    except ValueError:
        return False
    return parsed.scheme in {"http", "https"} and bool(parsed.netloc)


def clean_title(title: str) -> str:
    cleaned = _TITLE_NOISE.sub("", title)
    cleaned = _TRAILING_NOISE.sub("", cleaned)
    cleaned = re.sub(r"\s{2,}", " ", cleaned).strip(" -–—|")
    return cleaned or title.strip()


def split_title_artist(text: str) -> tuple[str, str | None]:
    """"Artista - Canción" -> (canción, artista). Si una parte "VIDEO OFICIAL" queda en el medio
    ("CANCIÓN - VIDEO OFICIAL - Artista ft. Otro"), lo de antes es el título y lo de después el artista."""
    parts = [p.strip() for p in _DASH.split(text.strip())]
    noise = [i for i, p in enumerate(parts) if p and _NOISE_PART.match(p)]
    if noise and 0 < noise[0] < len(parts) - 1:
        title = " - ".join(p for p in parts[:noise[0]] if p)
        artist = " - ".join(p for p in parts[noise[0] + 1:] if p and not _NOISE_PART.match(p))
        if title and artist:
            return title, artist
    parts = [p for p in parts if p and not _NOISE_PART.match(p)]
    if len(parts) >= 2:
        return " - ".join(parts[1:]), parts[0]
    return (parts[0] if parts else text.strip()), None


def guess_title_artist(info: dict) -> tuple[str, str | None]:
    """Título y artista a partir de los metadatos de yt-dlp."""
    track, artist = info.get("track"), info.get("artist") or info.get("creator")
    if track and artist:
        return clean_title(str(track)), str(artist).split(",")[0].strip()
    title = _TRAILING_NOISE.sub("", str(info.get("title") or "Canción sin título")).strip() or "Canción sin título"
    name, artist = split_title_artist(title)
    if artist:
        return clean_title(name), artist
    uploader = info.get("uploader") or info.get("channel")
    if uploader:
        uploader = re.sub(r"\s*-\s*Topic$", "", str(uploader)).strip()
    return clean_title(title), uploader or None


def title_from_filename(filename: str) -> tuple[str, str | None]:
    stem = Path(filename).stem.replace("_", " ").strip()
    stem = re.sub(r"^\d{1,3}[\s.\-]+", "", stem)  # "01 - Canción" -> "Canción"
    name, artist = split_title_artist(stem)
    if artist:
        return clean_title(name), artist
    return clean_title(stem) or "Canción sin título", None


def check_upload_name(filename: str) -> str:
    ext = Path(filename or "").suffix.lower()
    if ext not in AUDIO_EXTENSIONS:
        raise IngestError(
            "Formato no reconocido. Sube un archivo de audio o video (mp3, wav, flac, m4a, ogg, mp4...)."
        )
    return ext


# ---- yt-dlp -----------------------------------------------------------------------------


def ytdlp_available() -> bool:
    try:
        import yt_dlp  # noqa: F401
    except ImportError:
        return False
    return True


def _base_options() -> dict:
    options: dict = {
        "quiet": True,
        "no_warnings": True,
        "noprogress": True,
        "noplaylist": True,
        "socket_timeout": 30,
        "retries": 3,
        "fragment_retries": 3,
        "cachedir": False,
    }
    ffmpeg = find_ffmpeg()
    if ffmpeg:
        options["ffmpeg_location"] = ffmpeg
    # YouTube necesita un intérprete de JavaScript: yt-dlp usa Deno por defecto; si no está
    # pero sí Node.js (lo más común en una compu de desarrollo), se lo indicamos.
    if not shutil.which("deno") and shutil.which("node"):
        options["js_runtimes"] = {"node": {}}
    return options


def _ydl(options: dict):
    import yt_dlp

    try:
        return yt_dlp.YoutubeDL(options)
    except TypeError:
        options.pop("js_runtimes", None)
        return yt_dlp.YoutubeDL(options)


def _friendly_error(exc: Exception) -> IngestError:
    text = str(exc)
    lowered = text.lower()
    if "private" in lowered:
        return IngestError("El video es privado.")
    if "sign in" in lowered or "age" in lowered and "confirm" in lowered:
        return IngestError("YouTube pide iniciar sesión para este video (restricción de edad o similar).")
    if "unsupported url" in lowered:
        return IngestError("Ese link no es compatible.")
    if "unable to download" in lowered or "urlopen" in lowered or "timed out" in lowered:
        return IngestError("No se pudo conectar para descargar. Revisa tu conexión a internet.")
    text = re.sub(r"\x1b\[[0-9;]*m", "", text).replace("ERROR: ", "").strip()
    return IngestError(f"No se pudo descargar: {text[:300]}")


def url_info(url: str) -> dict:
    """Datos del link sin descargarlo (para mostrar una vista previa)."""
    if not ytdlp_available():
        raise IngestError("Falta yt-dlp. Instálalo con: pip install yt-dlp")
    options = {**_base_options(), "skip_download": True}
    try:
        with _ydl(options) as ydl:
            info = ydl.extract_info(url, download=False)
    except Exception as exc:  # noqa: BLE001 - yt-dlp lanza muchos tipos distintos
        raise _friendly_error(exc) from exc
    if info.get("_type") == "playlist":
        entries = [e for e in (info.get("entries") or []) if e]
        if not entries:
            raise IngestError("La lista de reproducción está vacía.")
        info = entries[0]
    title, artist = guess_title_artist(info)
    return {
        "url": info.get("webpage_url") or url,
        "title": title,
        "artist": artist,
        "duration": info.get("duration"),
        "thumbnail": info.get("thumbnail"),
    }


def search(query: str, limit: int = 8) -> list[dict]:
    """Busca en YouTube (sin descargar)."""
    if not ytdlp_available():
        raise IngestError("Falta yt-dlp. Instálalo con: pip install yt-dlp")
    options = {**_base_options(), "skip_download": True, "extract_flat": "in_playlist"}
    try:
        with _ydl(options) as ydl:
            info = ydl.extract_info(f"ytsearch{int(limit)}:{query}", download=False)
    except Exception as exc:  # noqa: BLE001
        raise _friendly_error(exc) from exc
    results = []
    for entry in info.get("entries") or []:
        if not entry or not entry.get("id"):
            continue
        video_id = entry["id"]
        thumbs = entry.get("thumbnails") or []
        title, artist = guess_title_artist(entry)
        results.append({
            "id": video_id,
            "url": entry.get("url") if str(entry.get("url", "")).startswith("http")
            else f"https://www.youtube.com/watch?v={video_id}",
            "title": title,
            "rawTitle": entry.get("title"),
            "artist": artist,
            "channel": entry.get("channel") or entry.get("uploader"),
            "duration": entry.get("duration"),
            "thumbnail": thumbs[-1]["url"] if thumbs else f"https://i.ytimg.com/vi/{video_id}/hqdefault.jpg",
        })
    return results


def download(
    url: str,
    dest_dir: Path,
    progress: Callable[[float, str], None],
    should_cancel: Callable[[], bool],
    max_duration_s: float,
) -> dict:
    """Descarga el audio del link en `dest_dir/original.<ext>` (+ portada si hay)."""
    if not ytdlp_available():
        raise IngestError("Falta yt-dlp. Instálalo con: pip install yt-dlp")
    from yt_dlp.utils import DownloadCancelled

    from .separation.base import Cancelled

    def hook(state: dict) -> None:
        if should_cancel():
            raise DownloadCancelled("Cancelado por el usuario")
        if state.get("status") == "downloading":
            total = state.get("total_bytes") or state.get("total_bytes_estimate") or 0
            done = state.get("downloaded_bytes") or 0
            fraction = min(1.0, done / total) if total else 0.0
            speed = state.get("speed")
            detail = f" · {speed / 1024 / 1024:.1f} MB/s" if speed else ""
            progress(fraction, f"Descargando audio… {fraction * 100:.0f}%{detail}")
        elif state.get("status") == "finished":
            progress(1.0, "Descarga completa")

    def check_duration(info: dict, *, incomplete: bool = False) -> str | None:
        duration = info.get("duration")
        if duration and duration > max_duration_s:
            return f"La canción dura {duration / 60:.0f} min; el máximo es {max_duration_s / 60:.0f} min."
        return None

    dest_dir.mkdir(parents=True, exist_ok=True)
    options = {
        **_base_options(),
        "format": "bestaudio/best",
        "outtmpl": {
            "default": str(dest_dir / "original.%(ext)s"),
            "thumbnail": str(dest_dir / "portada.%(ext)s"),
        },
        "writethumbnail": True,
        "progress_hooks": [hook],
        "match_filter": check_duration,
        "overwrites": True,
    }
    try:
        with _ydl(options) as ydl:
            info = ydl.extract_info(url, download=True)
    except DownloadCancelled as exc:
        raise Cancelled() from exc
    except Exception as exc:  # noqa: BLE001 - yt-dlp lanza muchos tipos distintos
        if should_cancel():
            raise Cancelled() from exc
        raise _friendly_error(exc) from exc
    if info is None:
        raise IngestError("No se pudo descargar ese link.")
    if info.get("_type") == "playlist":
        entries = [e for e in (info.get("entries") or []) if e]
        if not entries:
            raise IngestError("La lista de reproducción está vacía.")
        info = entries[0]
    reason = check_duration(info)
    if reason:
        raise IngestError(reason)
    files = [p for p in dest_dir.glob("original.*") if p.suffix not in {".part", ".ytdl"}]
    if not files:
        raise IngestError("La descarga no produjo ningún archivo de audio.")
    title, artist = guess_title_artist(info)
    return {
        "path": files[0],
        "title": title,
        "artist": artist,
        "duration": info.get("duration"),
        "url": info.get("webpage_url") or url,
    }
