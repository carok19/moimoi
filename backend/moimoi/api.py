"""API HTTP de MoiMoi (la usa la interfaz web y cualquier otra app, p. ej. Multitrack Alabanza)."""

from __future__ import annotations

import logging
import tempfile
from pathlib import Path
from typing import Literal

from fastapi import APIRouter, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel, Field

from . import __version__, audio_io, exports, guia, ingest, lyrics, multitrack, red
from .db import Database
from .separation.base import DEFAULT_PRESET, PRESETS, QUALITIES, STEMS, ordered_stems
from .storage import SongPaths, new_id, read_json, write_json
from .worker import Worker, enqueue

log = logging.getLogger("moimoi.api")
router = APIRouter(prefix="/api")

MAX_SETTINGS_BYTES = 512 * 1024
PROCESSING = {"queued", "downloading", "separating", "analyzing"}


# ---- utilidades --------------------------------------------------------------------------


def _state(request: Request):
    return request.app.state


def _db(request: Request) -> Database:
    return request.app.state.db


def _worker(request: Request) -> Worker | None:
    return request.app.state.worker


def _paths(request: Request, song_id: str) -> SongPaths:
    try:
        return SongPaths(request.app.state.cfg.songs_dir, song_id)
    except ValueError as exc:
        raise HTTPException(404, "Canción no encontrada") from exc


def _song_or_404(request: Request, song_id: str) -> dict:
    _paths(request, song_id)
    song = _db(request).get_song(song_id)
    if song is None:
        raise HTTPException(404, "Canción no encontrada")
    return song


def song_to_api(song: dict, paths: SongPaths) -> dict:
    preset = PRESETS.get(song["preset"])
    version = (song.get("updated_at") or "").replace(":", "").replace("-", "")
    stems = []
    for stem in ordered_stems(song.get("stems") or []):
        info = STEMS.get(stem)
        stems.append({
            "id": stem,
            "name": info.name if info else stem,
            "color": info.color if info else "#9fb3c8",
            "url": f"/api/songs/{song['id']}/audio/{stem}.flac?v={version}",
        })
    thumb = paths.thumbnail()
    meta = song.get("meta") or {}
    return {
        "id": song["id"],
        "title": song["title"],
        "artist": song.get("artist"),
        "sourceType": song["source_type"],
        "sourceUrl": song.get("source_url"),
        "originalFilename": song.get("original_filename"),
        "duration": song.get("duration"),
        "preset": song["preset"],
        "presetName": preset.name if preset else song["preset"],
        "quality": song["quality"],
        "model": song.get("model"),
        "status": song["status"],
        "progress": song.get("progress") or 0.0,
        "stage": song.get("stage"),
        "error": song.get("error"),
        "stems": stems,
        "summary": meta.get("summary"),
        "lyricsStatus": song.get("lyrics_status"),
        "settings": song.get("settings") or {},
        "thumbnailUrl": f"/api/songs/{song['id']}/thumbnail?v={version}" if thumb else None,
        "createdAt": song["created_at"],
        "updatedAt": song["updated_at"],
    }


def job_to_api(job: dict) -> dict:
    result = job.get("result") or None
    download = None
    if job["status"] == "done" and job["kind"] == "export" and result:
        download = f"/api/jobs/{job['id']}/download"
    return {
        "id": job["id"],
        "songId": job.get("song_id"),
        "kind": job["kind"],
        "status": job["status"],
        "progress": job.get("progress") or 0.0,
        "message": job.get("message"),
        "error": job.get("error"),
        "result": result,
        "downloadUrl": download,
        "createdAt": job["created_at"],
        "startedAt": job.get("started_at"),
        "finishedAt": job.get("finished_at"),
    }


def _check_preset(preset: str, quality: str) -> None:
    if preset not in PRESETS:
        raise HTTPException(400, f"Tipo de separación desconocido: {preset}")
    if quality not in QUALITIES:
        raise HTTPException(400, f"Calidad desconocida: {quality}")


# ---- estado general ------------------------------------------------------------------------


@router.get("/health")
def health(request: Request):
    state = _state(request)
    try:
        import pedalboard  # noqa: F401

        stretch_ok = True
    except ImportError:
        stretch_ok = False
    return {
        "ok": True,
        "app": "MoiMoi",
        "version": __version__,
        "engine": state.separator.status(),
        "features": {
            "youtube": ingest.ytdlp_available(),
            "lyrics": state.transcriber is not None,
            "stretchExport": stretch_ok,
            "ffmpeg": audio_io.find_ffmpeg() is not None,
        },
        "dataDir": str(state.cfg.data_dir),
    }


@router.get("/presets")
def presets():
    return {
        "default": DEFAULT_PRESET,
        "presets": [
            {"id": p.id, "name": p.name, "description": p.description, "stems": list(p.stems)}
            for p in PRESETS.values()
        ],
        "stems": [{"id": s.id, "name": s.name, "color": s.color} for s in STEMS.values()],
        "qualities": [
            {"id": "normal", "name": "Normal", "description": "Rápida y con muy buena calidad."},
            {"id": "alta", "name": "Alta", "description": "Mejor separación, tarda entre 2 y 4 veces más."},
        ],
    }


# ---- canciones -----------------------------------------------------------------------------


@router.get("/songs")
def list_songs(request: Request):
    cfg = request.app.state.cfg
    return [song_to_api(s, SongPaths(cfg.songs_dir, s["id"])) for s in _db(request).list_songs()]


@router.get("/songs/{song_id}")
def get_song(request: Request, song_id: str):
    song = _song_or_404(request, song_id)
    return song_to_api(song, _paths(request, song_id))


@router.post("/songs/upload")
async def upload_song(
    request: Request,
    file: UploadFile = File(...),
    preset: str = Form(DEFAULT_PRESET),
    quality: str = Form("normal"),
    title: str | None = Form(None),
    artist: str | None = Form(None),
):
    _check_preset(preset, quality)
    try:
        ext = ingest.check_upload_name(file.filename or "")
    except ingest.IngestError as exc:
        raise HTTPException(400, str(exc)) from exc
    song_id = new_id()
    paths = _paths(request, song_id)
    paths.create()
    dest = paths.root / f"original{ext}"
    size = 0
    try:
        with open(dest, "wb") as out:
            while chunk := await file.read(1024 * 1024):
                size += len(chunk)
                if size > ingest.MAX_UPLOAD_BYTES:
                    raise HTTPException(413, "El archivo es demasiado grande (máximo 1 GB)")
                out.write(chunk)
    except BaseException:
        paths.remove()
        raise
    if size == 0:
        paths.remove()
        raise HTTPException(400, "El archivo está vacío")
    tags = audio_io.read_tags(dest)
    guessed_title, guessed_artist = ingest.title_from_filename(file.filename or "cancion")
    song = {
        "id": song_id,
        "title": (title or tags.get("title") or guessed_title).strip(),
        "artist": (artist or tags.get("artist") or guessed_artist or None),
        "source_type": "upload",
        "original_filename": file.filename,
        "preset": preset,
        "quality": quality,
        "status": "queued",
        "stage": "En cola",
        "progress": 0.0,
        "meta": {"auto_title": False},
    }
    db = _db(request)
    db.insert_song(song)
    enqueue(db, _worker(request), "process", song_id)
    return song_to_api(db.get_song(song_id), paths)


class AddUrlRequest(BaseModel):
    url: str
    preset: str = DEFAULT_PRESET
    quality: str = "normal"
    title: str | None = None
    artist: str | None = None


@router.post("/songs/url")
def add_url(request: Request, body: AddUrlRequest):
    _check_preset(body.preset, body.quality)
    url = body.url.strip()
    if not ingest.is_url(url):
        raise HTTPException(400, "Pega un link válido (por ejemplo de YouTube).")
    if not ingest.ytdlp_available():
        raise HTTPException(501, "Falta yt-dlp para descargar links. Instálalo con: pip install yt-dlp")
    song_id = new_id()
    paths = _paths(request, song_id)
    paths.create()
    song = {
        "id": song_id,
        "title": (body.title or "Descargando…").strip(),
        "artist": body.artist,
        "source_type": "url",
        "source_url": url,
        "preset": body.preset,
        "quality": body.quality,
        "status": "queued",
        "stage": "En cola",
        "progress": 0.0,
        "meta": {"auto_title": not body.title},
    }
    db = _db(request)
    db.insert_song(song)
    enqueue(db, _worker(request), "process", song_id)
    return song_to_api(db.get_song(song_id), paths)


class UpdateSongRequest(BaseModel):
    title: str | None = Field(default=None, max_length=300)
    artist: str | None = Field(default=None, max_length=300)
    settings: dict | None = None


@router.patch("/songs/{song_id}")
def update_song(request: Request, song_id: str, body: UpdateSongRequest):
    song = _song_or_404(request, song_id)
    updates: dict = {}
    if body.title is not None and body.title.strip():
        updates["title"] = body.title.strip()
    if body.artist is not None:
        updates["artist"] = body.artist.strip() or None
    if body.settings is not None:
        merged = {**(song.get("settings") or {}), **body.settings}
        merged = {k: v for k, v in merged.items() if v is not None}
        import json

        if len(json.dumps(merged)) > MAX_SETTINGS_BYTES:
            raise HTTPException(413, "Ajustes demasiado grandes")
        updates["settings"] = merged
    if updates:
        _db(request).update_song(song_id, **updates)
    return song_to_api(_db(request).get_song(song_id), _paths(request, song_id))


def _cancel_active(request: Request, song_id: str) -> None:
    db, worker = _db(request), _worker(request)
    for job in db.list_jobs(song_id=song_id, active_only=True):
        if worker is not None:
            worker.cancel(job["id"])
        if job["status"] == "queued":
            db.update_job(job["id"], status="cancelled", message="Cancelado")


@router.delete("/songs/{song_id}")
def delete_song(request: Request, song_id: str):
    _song_or_404(request, song_id)
    _cancel_active(request, song_id)
    _db(request).delete_song(song_id)
    _paths(request, song_id).remove()
    return {"ok": True}


@router.post("/songs/{song_id}/cancel")
def cancel_song(request: Request, song_id: str):
    song = _song_or_404(request, song_id)
    _cancel_active(request, song_id)
    if song["status"] in PROCESSING:
        _db(request).update_song(song_id, status="cancelled", stage="Cancelado")
    return song_to_api(_db(request).get_song(song_id), _paths(request, song_id))


class ReprocessRequest(BaseModel):
    preset: str | None = None
    quality: str | None = None


@router.post("/songs/{song_id}/retry")
def retry_song(request: Request, song_id: str, body: ReprocessRequest | None = None):
    """Vuelve a procesar (después de un error, o para separar con otro tipo/calidad)."""
    song = _song_or_404(request, song_id)
    db = _db(request)
    if db.active_job_for_song(song_id, "process"):
        raise HTTPException(409, "La canción ya se está procesando")
    preset = (body.preset if body else None) or song["preset"]
    quality = (body.quality if body else None) or song["quality"]
    _check_preset(preset, quality)
    db.update_song(song_id, preset=preset, quality=quality, status="queued", stage="En cola",
                   progress=0.0, error=None)
    enqueue(db, _worker(request), "process", song_id)
    return song_to_api(db.get_song(song_id), _paths(request, song_id))


@router.post("/songs/{song_id}/reanalyze")
def reanalyze_song(request: Request, song_id: str):
    song = _song_or_404(request, song_id)
    if song["status"] != "ready":
        raise HTTPException(409, "La canción todavía no está lista")
    db = _db(request)
    job = enqueue(db, _worker(request), "reanalyze", song_id)
    db.update_song(song_id, status="analyzing", stage="En cola para analizar", progress=0.0)
    return job_to_api(job)


@router.get("/songs/{song_id}/analysis")
def get_analysis(request: Request, song_id: str):
    _song_or_404(request, song_id)
    data = read_json(_paths(request, song_id).analysis)
    if data is None:
        raise HTTPException(404, "Todavía no hay análisis")
    return data


@router.get("/songs/{song_id}/peaks")
def get_peaks(request: Request, song_id: str):
    _song_or_404(request, song_id)
    data = read_json(_paths(request, song_id).peaks)
    if data is None:
        raise HTTPException(404, "Todavía no hay formas de onda")
    return data


def _stem_or_404(song: dict, stem: str) -> str:
    if stem not in (song.get("stems") or []):
        raise HTTPException(404, "Esa pista no existe")
    return stem


@router.get("/songs/{song_id}/audio/{stem}.flac")
def stream_stem(request: Request, song_id: str, stem: str):
    song = _song_or_404(request, song_id)
    _stem_or_404(song, stem)
    path = _paths(request, song_id).stem(stem)
    if not path.exists():
        raise HTTPException(404, "Esa pista no existe")
    return FileResponse(path, media_type="audio/flac", headers={"Cache-Control": "no-cache"})


@router.get("/songs/{song_id}/download/{stem}.{fmt}")
def download_stem(request: Request, song_id: str, stem: str, fmt: Literal["wav", "mp3", "flac"]):
    song = _song_or_404(request, song_id)
    _stem_or_404(song, stem)
    try:
        path = exports.stem_file(_paths(request, song_id), stem, fmt)
    except exports.ExportError as exc:
        raise HTTPException(400, str(exc)) from exc
    info = STEMS.get(stem)
    name = f"{exports.safe_filename(exports.song_display_name(song))} - {info.name if info else stem}.{fmt}"
    return FileResponse(path, media_type=exports.MIME[fmt], filename=name)


@router.get("/songs/{song_id}/thumbnail")
def thumbnail(request: Request, song_id: str):
    _song_or_404(request, song_id)
    path = _paths(request, song_id).thumbnail()
    if path is None:
        raise HTTPException(404, "Sin portada")
    return FileResponse(path, headers={"Cache-Control": "max-age=86400"})


# ---- letra ---------------------------------------------------------------------------------


class LyricsRequest(BaseModel):
    language: str | None = Field(default=None, max_length=10)


@router.post("/songs/{song_id}/lyrics")
def request_lyrics(request: Request, song_id: str, body: LyricsRequest | None = None):
    song = _song_or_404(request, song_id)
    if _state(request).transcriber is None:
        raise HTTPException(501, "La transcripción de letra no está instalada. Ejecuta: pip install faster-whisper")
    if "vocals" not in (song.get("stems") or []):
        raise HTTPException(409, "Esta canción no tiene pista de voz")
    db = _db(request)
    existing = db.active_job_for_song(song_id, "lyrics")
    if existing:
        return job_to_api(existing)
    job = enqueue(db, _worker(request), "lyrics", song_id, {"language": body.language if body else None})
    db.update_song(song_id, lyrics_status="queued")
    return job_to_api(job)


@router.get("/songs/{song_id}/lyrics")
def get_lyrics(request: Request, song_id: str):
    _song_or_404(request, song_id)
    data = read_json(_paths(request, song_id).lyrics)
    if data is None:
        raise HTTPException(404, "Todavía no hay letra")
    return data


class LyricsLine(BaseModel):
    start: float
    end: float
    text: str = Field(max_length=500)
    words: list[dict] = []


class LyricsUpdate(BaseModel):
    lines: list[LyricsLine] = Field(max_length=2000)
    language: str | None = None


@router.put("/songs/{song_id}/lyrics")
def save_lyrics(request: Request, song_id: str, body: LyricsUpdate):
    _song_or_404(request, song_id)
    paths = _paths(request, song_id)
    previous = read_json(paths.lyrics, {}) or {}
    data = {**previous, "lines": [line.model_dump() for line in body.lines], "edited": True}
    if body.language:
        data["language"] = body.language
    write_json(paths.lyrics, data)
    _db(request).update_song(song_id, lyrics_status="ready")
    return data


# ---- exportaciones y trabajos --------------------------------------------------------------


class ExportRequest(BaseModel):
    type: Literal["multitrack", "stems", "mix"] = "multitrack"
    stems: list[str] | None = None
    format: Literal["wav", "mp3", "flac"] = "wav"
    click: bool = False
    clickVolume: float = Field(default=0.6, ge=0, le=2)
    tempo: float = Field(default=1.0, ge=0.25, le=4.0)
    semitones: int = Field(default=0, ge=-12, le=12)
    mixer: dict[str, dict] | None = None
    #: Pista "Guía" con las voces que anuncian cada parte (solo multitrack).
    guide: bool = False
    #: "verses" = "Verso 1, Verso 2" pero "Coro" en cada coro; "all" = "Coro 1, Coro 2"; "none".
    guideNumbering: Literal["verses", "all", "none"] = "verses"
    #: Anunciar "Sube tono" / "Baja tono" donde la canción cambia de tonalidad.
    guideKeyChanges: bool = True
    #: Sonido del click: "moimoi" o un estilo del paquete de voces ("classic", "cowbell"…).
    clickSound: str | None = Field(default=None, max_length=40)
    #: Compases de cuenta antes de que empiece la canción (solo multitrack).
    preRollBars: int = Field(default=0, ge=0, le=2)


@router.post("/songs/{song_id}/exports")
def create_export(request: Request, song_id: str, body: ExportRequest):
    song = _song_or_404(request, song_id)
    if song["status"] != "ready" and not song.get("stems"):
        raise HTTPException(409, "La canción todavía no está lista")
    unknown = set(body.stems or []) - set(song.get("stems") or [])
    if unknown:
        raise HTTPException(400, f"Pistas desconocidas: {', '.join(sorted(unknown))}")
    job = enqueue(_db(request), _worker(request), "export", song_id, body.model_dump(),
                  message="Preparando exportación…")
    return job_to_api(job)


@router.get("/jobs")
def list_jobs(request: Request, active: bool = False, song: str | None = None):
    return [job_to_api(j) for j in _db(request).list_jobs(song_id=song, active_only=active)]


@router.get("/jobs/{job_id}")
def get_job(request: Request, job_id: str):
    job = _db(request).get_job(job_id)
    if job is None:
        raise HTTPException(404, "Trabajo no encontrado")
    return job_to_api(job)


@router.post("/jobs/{job_id}/cancel")
def cancel_job(request: Request, job_id: str):
    db = _db(request)
    job = db.get_job(job_id)
    if job is None:
        raise HTTPException(404, "Trabajo no encontrado")
    if job["status"] in ("queued", "running"):
        worker = _worker(request)
        if worker is not None:
            worker.cancel(job_id)
        if job["status"] == "queued":
            db.update_job(job_id, status="cancelled", message="Cancelado")
    return job_to_api(db.get_job(job_id))


@router.get("/jobs/{job_id}/download")
def download_job(request: Request, job_id: str):
    job = _db(request).get_job(job_id)
    if job is None or job["status"] != "done" or not job.get("result"):
        raise HTTPException(404, "La exportación no está lista")
    result = job["result"]
    path = request.app.state.cfg.exports_dir / job_id / Path(result["file"]).name
    if not path.exists():
        raise HTTPException(410, "La exportación ya se borró; vuelve a exportar")
    return FileResponse(path, media_type=result.get("mime") or "application/octet-stream", filename=result["name"])


# ---- links y búsqueda ----------------------------------------------------------------------


@router.get("/url-info")
def url_info(url: str):
    if not ingest.is_url(url):
        raise HTTPException(400, "Link inválido")
    try:
        return ingest.url_info(url)
    except ingest.IngestError as exc:
        raise HTTPException(422, str(exc)) from exc


@router.get("/search")
def search(q: str, limit: int = 8):
    q = q.strip()
    if not q:
        return []
    try:
        return ingest.search(q, max(1, min(limit, 20)))
    except ingest.IngestError as exc:
        raise HTTPException(422, str(exc)) from exc


# ---- ajustes -------------------------------------------------------------------------------

DEFAULT_SETTINGS = {
    "defaultPreset": DEFAULT_PRESET,
    "defaultQuality": "normal",
    "band": [],
    "notation": "american",
    "countInBars": 1,
    "metronomeVolume": 0.7,
    "metronomeSound": "click",
    # Exportación para Multitrack Alabanza
    "multitrackUrl": multitrack.DEFAULT_URL,
    "exportClick": True,
    "exportGuide": True,
    "exportPreRollBars": 1,
    "exportClickSound": "moimoi",
    "guideNumbering": "verses",
    "guideKeyChanges": True,
    # Celulares y tablets de la misma red
    "lanAccess": True,
}


@router.get("/settings")
def get_settings(request: Request):
    return {**DEFAULT_SETTINGS, **_db(request).get_settings()}


@router.put("/settings")
def put_settings(request: Request, body: dict):
    allowed = {k: v for k, v in body.items() if k in DEFAULT_SETTINGS}
    if "defaultPreset" in allowed and allowed["defaultPreset"] not in PRESETS:
        raise HTTPException(400, "Tipo de separación desconocido")
    if "band" in allowed and (not isinstance(allowed["band"], list)
                              or not all(isinstance(s, str) and s in STEMS for s in allowed["band"])):
        raise HTTPException(400, "Lista de instrumentos inválida")
    if "multitrackUrl" in allowed:
        try:
            allowed["multitrackUrl"] = multitrack.normalize_url(str(allowed["multitrackUrl"]))
        except multitrack.MultitrackError as exc:
            raise HTTPException(400, str(exc)) from exc
    if "exportPreRollBars" in allowed and allowed["exportPreRollBars"] not in (0, 1, 2):
        raise HTTPException(400, "La cuenta inicial es de 0, 1 o 2 compases")
    if "guideNumbering" in allowed and allowed["guideNumbering"] not in ("verses", "all", "none"):
        raise HTTPException(400, "Opción de numeración inválida")
    if "exportClickSound" in allowed and not (isinstance(allowed["exportClickSound"], str)
                                              and 0 < len(allowed["exportClickSound"]) <= 40):
        raise HTTPException(400, "Sonido de click inválido")
    if "lanAccess" in allowed:
        allowed["lanAccess"] = bool(allowed["lanAccess"])
        if not red.is_loopback(request.client.host if request.client else None):
            raise HTTPException(403, "Solo se puede cambiar desde la computadora donde corre MoiMoi")
    _db(request).set_settings(allowed)
    return get_settings(request)


# ---- voz guía --------------------------------------------------------------------------------


def _kit(request: Request) -> guia.GuideKit:
    return request.app.state.guide_kit


@router.get("/guia")
def get_guide(request: Request, set: str | None = None):  # noqa: A002 - nombre del parámetro en la URL
    return _kit(request).describe(set)


@router.post("/guia")
async def upload_guide(request: Request, files: list[UploadFile] = File(...), cue: str | None = Form(None),
                       set: str | None = Form(None)):  # noqa: A002
    """Carga voces guía: archivos de audio sueltos, un .zip con el paquete completo (con varios
    idiomas y sonidos de click), o una grabación hecha en la app (`cue` = a qué parte corresponde)."""
    if cue is not None and cue not in guia.CUE_NAMES:
        raise HTTPException(400, "Tipo de voz desconocido")
    uploads: list[tuple[str, Path]] = []
    with tempfile.TemporaryDirectory() as tmp:
        total = 0
        for index, upload in enumerate(files[: guia.MAX_FILES]):
            name = Path(upload.filename or f"voz-{index}.wav").name
            path = Path(tmp) / f"{index:04d}{Path(name).suffix.lower() or '.bin'}"
            with open(path, "wb") as out:
                while chunk := await upload.read(1024 * 1024):
                    total += len(chunk)
                    if total > 500 * 1024 * 1024:
                        raise HTTPException(413, "Demasiado grande (máximo 500 MB en total)")
                    out.write(chunk)
            uploads.append((name, path))
        try:
            summary = _kit(request).import_files(uploads, forced_cue=cue, target_set=set)
        except ValueError as exc:
            raise HTTPException(400, str(exc)) from exc
    return {"summary": summary, **_kit(request).describe(set)}


class GuideActive(BaseModel):
    set: str


@router.put("/guia/activo")
def set_active_guide(request: Request, body: GuideActive):
    try:
        _kit(request).set_active(body.set)
    except KeyError as exc:
        raise HTTPException(404, "Ese idioma no existe") from exc
    return _kit(request).describe()


class GuideAssign(BaseModel):
    cue: str | None = None


@router.put("/guia/{file_id}")
def assign_guide(request: Request, file_id: str, body: GuideAssign):
    try:
        _kit(request).assign(file_id, body.cue)
    except KeyError as exc:
        raise HTTPException(404, "Esa voz no existe") from exc
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc
    return _kit(request).describe(request.query_params.get("set"))


@router.delete("/guia/clicks/{style}")
def delete_click_style(request: Request, style: str):
    _kit(request).remove_clicks(style)
    return _kit(request).describe()


@router.delete("/guia/{file_id}")
def delete_guide_file(request: Request, file_id: str):
    _kit(request).remove(file_id)
    return _kit(request).describe(request.query_params.get("set"))


@router.delete("/guia")
def clear_guide(request: Request, set: str | None = None):  # noqa: A002
    """Borra un idioma (`?set=es`) o todo el paquete de voces."""
    if set:
        _kit(request).remove_set(set)
    else:
        _kit(request).clear()
    return _kit(request).describe()


@router.get("/guia/audio/{file_id}.wav")
def guide_audio(request: Request, file_id: str):
    path = _kit(request).audio_path(file_id)
    if path is None:
        raise HTTPException(404, "Esa voz no existe")
    return FileResponse(path, media_type="audio/wav", headers={"Cache-Control": "max-age=31536000, immutable"})


# ---- Multitrack Alabanza y red local ------------------------------------------------------------


@router.get("/multitrack")
def multitrack_status(request: Request, url: str | None = None):
    target = url or get_settings(request)["multitrackUrl"]
    try:
        return multitrack.info(target)
    except multitrack.MultitrackError as exc:
        return {"ok": False, "url": target, "error": str(exc)}


class SendRequest(BaseModel):
    url: str | None = None


@router.post("/jobs/{job_id}/enviar")
def send_to_multitrack(request: Request, job_id: str, body: SendRequest | None = None):
    """Manda una exportación (zip) directo a Multitrack Alabanza."""
    job = _db(request).get_job(job_id)
    if job is None or job["status"] != "done" or not job.get("result"):
        raise HTTPException(404, "La exportación no está lista")
    result = job["result"]
    if not str(result.get("name", "")).lower().endswith(".zip"):
        raise HTTPException(400, "Solo se pueden enviar paquetes .zip")
    path = request.app.state.cfg.exports_dir / job_id / Path(result["file"]).name
    if not path.exists():
        raise HTTPException(410, "La exportación ya se borró; vuelve a exportar")
    target = (body.url if body else None) or get_settings(request)["multitrackUrl"]
    try:
        return multitrack.send_zip(target, path, result["name"])
    except multitrack.MultitrackError as exc:
        raise HTTPException(502, str(exc)) from exc


@router.get("/red")
def network_info(request: Request):
    """Direcciones para abrir MoiMoi desde celulares y tablets de la misma red WiFi."""
    cfg = request.app.state.cfg
    listening_lan = cfg.host in ("0.0.0.0", "::")
    ips = red.lan_addresses() if listening_lan else []
    https_on = bool(request.app.state.https_port)
    return {
        "lanAccess": bool(get_settings(request)["lanAccess"]),
        "listening": listening_lan,
        "port": cfg.port,
        "httpsPort": request.app.state.https_port,
        "http": [f"http://{ip}:{cfg.port}" for ip in ips],
        "https": [f"https://{ip}:{request.app.state.https_port}" for ip in ips] if https_on else [],
        "local": red.is_loopback(request.client.host if request.client else None),
    }


@router.get("/")
def api_root():
    return JSONResponse({"app": "MoiMoi", "version": __version__, "docs": "/docs"})
