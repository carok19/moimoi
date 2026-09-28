"""Cola de trabajos en segundo plano.

Hay dos hilos:
- "pesado": separación de pistas y transcripción de letra (usan mucha CPU/GPU, de a uno).
- "liviano": exportaciones (zip para Multitrack, mezclas, conversiones).

Los trabajos se guardan en SQLite: si se cierra el programa a mitad de una
separación, al volver a abrirlo se retoma sola.
"""

from __future__ import annotations

import logging
import shutil
import threading
import time
import traceback
from datetime import datetime, timedelta, timezone
from typing import Callable

import numpy as np

from . import audio_io, ingest
from .config import Config
from .db import Database, now_iso
from .separation.base import PRESETS, SAMPLE_RATE, Cancelled, Separator, ordered_stems
from .storage import SongPaths, new_id, write_json

log = logging.getLogger("moimoi.worker")

HEAVY_KINDS = ("process", "lyrics", "reanalyze")
LIGHT_KINDS = ("export",)


class Stage:
    """Reparte el progreso total (0-1) entre las etapas de un trabajo."""

    def __init__(self, report: Callable[[float, str], None], plan: list[tuple[str, float]]):
        total = sum(weight for _, weight in plan)
        self._starts: dict[str, float] = {}
        self._weights: dict[str, float] = {}
        acc = 0.0
        for name, weight in plan:
            self._starts[name] = acc / total
            self._weights[name] = weight / total
            acc += weight
        self._report = report

    def reporter(self, name: str) -> Callable[[float, str], None]:
        start, weight = self._starts[name], self._weights[name]

        def report(fraction: float, message: str) -> None:
            self._report(start + weight * min(1.0, max(0.0, fraction)), message)

        return report


class Worker:
    def __init__(
        self,
        cfg: Config,
        db: Database,
        separator: Separator,
        analyzer: Callable | None = None,
        transcriber: Callable | None = None,
        exporter: Callable | None = None,
    ):
        self.cfg = cfg
        self.db = db
        self.separator = separator
        self.analyzer = analyzer
        self.transcriber = transcriber
        self.exporter = exporter
        self._stop = threading.Event()
        self._wake = {"heavy": threading.Event(), "light": threading.Event()}
        self._cancelled: set[str] = set()
        self._lock = threading.Lock()
        self._threads: list[threading.Thread] = []
        self._last_progress_write: dict[str, float] = {}

    # ---- ciclo de vida ----------------------------------------------------------------

    def start(self) -> None:
        for job in self.db.requeue_interrupted_jobs():
            log.info("Retomando trabajo interrumpido %s (%s)", job["id"], job["kind"])
            if job["kind"] == "process" and job["song_id"]:
                self.db.update_song(job["song_id"], status="queued", stage="En cola (reanudando)")
        self._cleanup_old_exports()
        for lane, kinds in (("heavy", HEAVY_KINDS), ("light", LIGHT_KINDS)):
            thread = threading.Thread(target=self._loop, args=(lane, kinds), name=f"moimoi-{lane}", daemon=True)
            thread.start()
            self._threads.append(thread)

    def stop(self) -> None:
        self._stop.set()
        for event in self._wake.values():
            event.set()
        for thread in self._threads:
            thread.join(timeout=5)

    def notify(self, kind: str) -> None:
        self._wake["light" if kind in LIGHT_KINDS else "heavy"].set()

    def cancel(self, job_id: str) -> None:
        with self._lock:
            self._cancelled.add(job_id)

    def _is_cancelled(self, job_id: str) -> bool:
        with self._lock:
            return job_id in self._cancelled

    # ---- bucle ------------------------------------------------------------------------

    def _loop(self, lane: str, kinds: tuple[str, ...]) -> None:
        while not self._stop.is_set():
            job = self.db.claim_next_job(kinds)
            if job is None:
                self._wake[lane].wait(timeout=2.0)
                self._wake[lane].clear()
                continue
            self.run_job(job)

    def run_job(self, job: dict) -> None:
        """Ejecuta un trabajo ya marcado como 'running' (público para los tests)."""
        job_id = job["id"]
        try:
            if self._is_cancelled(job_id):
                raise Cancelled()
            handler = {
                "process": self._process_song,
                "reanalyze": self._reanalyze,
                "lyrics": self._lyrics,
                "export": self._export,
            }[job["kind"]]
            result = handler(job)
            self.db.update_job(job_id, status="done", progress=1.0, message="Listo",
                               finished_at=now_iso(), result=result)
        except Cancelled:
            self.db.update_job(job_id, status="cancelled", message="Cancelado", finished_at=now_iso())
            self._on_job_failed(job, None, cancelled=True)
        except Exception as exc:  # noqa: BLE001 - cualquier error queda registrado en el trabajo
            log.error("Falló el trabajo %s: %s\n%s", job_id, exc, traceback.format_exc())
            message = str(exc) or exc.__class__.__name__
            self.db.update_job(job_id, status="error", error=message, message="Error", finished_at=now_iso())
            self._on_job_failed(job, message, cancelled=False)
        finally:
            with self._lock:
                self._cancelled.discard(job_id)
            self._last_progress_write.pop(job_id, None)
            # Si borraron la canción mientras se procesaba, no dejar archivos huérfanos.
            song_id = job.get("song_id")
            if song_id and job["kind"] in HEAVY_KINDS and self.db.get_song(song_id) is None:
                SongPaths(self.cfg.songs_dir, song_id).remove()

    def _on_job_failed(self, job: dict, message: str | None, cancelled: bool) -> None:
        song_id = job.get("song_id")
        if not song_id or not self.db.get_song(song_id):
            return
        if job["kind"] == "process":
            self.db.update_song(song_id, status="cancelled" if cancelled else "error",
                                stage="Cancelado" if cancelled else "Error", error=message)
        elif job["kind"] == "lyrics":
            self.db.update_song(song_id, lyrics_status="cancelled" if cancelled else "error")
        elif job["kind"] == "reanalyze":
            self.db.update_song(song_id, status="ready", stage="Lista")

    def _progress(self, job: dict, song_status: str | None = None) -> Callable[[float, str], None]:
        job_id, song_id = job["id"], job.get("song_id")

        def report(fraction: float, message: str) -> None:
            if self._is_cancelled(job_id):
                raise Cancelled()
            now = time.monotonic()
            # No escribir en la base más de ~4 veces por segundo.
            if now - self._last_progress_write.get(job_id, 0.0) < 0.25 and fraction < 1.0:
                return
            self._last_progress_write[job_id] = now
            self.db.update_job(job_id, progress=round(fraction, 4), message=message)
            if song_status and song_id:
                self.db.update_song(song_id, progress=round(fraction, 4), stage=message, status=song_status)

        return report

    # ---- trabajos ---------------------------------------------------------------------

    def _process_song(self, job: dict) -> dict:
        song_id = job["song_id"]
        song = self.db.get_song(song_id)
        if song is None:
            raise RuntimeError("La canción ya no existe")
        paths = SongPaths(self.cfg.songs_dir, song_id)
        paths.create()
        preset = PRESETS[song["preset"]]
        should_cancel = lambda: self._is_cancelled(job["id"])  # noqa: E731

        plan = [("decode", 3), ("separate", 72), ("save", 6), ("analyze", 12)]
        if song["source_type"] == "url" and paths.source() is None:
            plan.insert(0, ("download", 7))
        self.db.update_song(song_id, status="queued", error=None, stage="Preparando…")

        def reporter(name: str, status: str):
            stage = Stage(self._progress(job, song_status=status), plan)
            return stage.reporter(name)

        # 1) Descarga (si es un link y todavía no se bajó).
        if song["source_type"] == "url" and paths.source() is None:
            report = reporter("download", "downloading")
            report(0.0, "Conectando…")
            info = ingest.download(song["source_url"], paths.root, report, should_cancel, self.cfg.max_duration_s)
            updates = {"source_url": info["url"]}
            if song.get("meta", {}).get("auto_title", True):
                updates["title"] = info["title"]
                if info.get("artist"):
                    updates["artist"] = info["artist"]
            self.db.update_song(song_id, **updates)

        source = paths.source()
        if source is None:
            raise RuntimeError("No se encontró el archivo de audio original")

        # 2) Decodificar.
        report = reporter("decode", "separating")
        report(0.0, "Leyendo el audio…")
        audio = audio_io.decode_audio(source, SAMPLE_RATE, 2)
        duration = audio.shape[1] / SAMPLE_RATE
        if duration > self.cfg.max_duration_s:
            raise RuntimeError(
                f"La canción dura {duration / 60:.1f} min; el máximo es {self.cfg.max_duration_s / 60:.0f} min."
            )
        if duration < 1.0:
            raise RuntimeError("El audio es demasiado corto (menos de 1 segundo).")
        self.db.update_song(song_id, duration=round(duration, 3), sample_rate=SAMPLE_RATE)
        report(1.0, "Audio listo")

        # 3) Separar.
        report = reporter("separate", "separating")
        report(0.0, "Separando pistas con IA…")
        stems, model_name = self.separator.separate(audio, preset, song["quality"], report, should_cancel)
        del audio
        stems = audio_io.peak_normalize_set(stems)

        # 4) Guardar pistas + formas de onda.
        report = reporter("save", "separating")
        paths.clear_cache()
        paths.stems_dir.mkdir(parents=True, exist_ok=True)
        for old in paths.stems_dir.glob("*.flac"):
            if old.stem not in stems:
                old.unlink()
        peaks: dict[str, str] = {}
        names = ordered_stems(list(stems))
        for index, name in enumerate(names):
            report(index / (len(names) + 1), f"Guardando pistas ({index + 1}/{len(names)})…")
            audio_io.write_flac(paths.stem(name), stems[name], SAMPLE_RATE)
            peaks[name] = audio_io.compute_peaks(stems[name], SAMPLE_RATE)
        mix = np.sum([stems[n] for n in names], axis=0)
        peaks["mix"] = audio_io.compute_peaks(mix, SAMPLE_RATE)
        write_json(paths.peaks, {
            "perSecond": audio_io.PEAKS_PER_SECOND,
            "duration": round(mix.shape[1] / SAMPLE_RATE, 3),
            "peaks": peaks,
        })
        del mix
        self.db.update_song(song_id, stems=names, model=model_name)

        # 5) Analizar (tempo, compases, tonalidad, acordes, secciones, instrumentos).
        report = reporter("analyze", "analyzing")
        self._run_analysis(song_id, paths, stems, report)
        self.db.update_song(song_id, status="ready", progress=1.0, stage="Lista", error=None)
        return {"stems": names, "model": model_name}

    def _run_analysis(self, song_id: str, paths: SongPaths, stems: dict[str, np.ndarray],
                      report: Callable[[float, str], None]) -> None:
        if self.analyzer is None:
            return
        report(0.0, "Analizando tempo, acordes y tonalidad…")
        analysis = self.analyzer(stems, SAMPLE_RATE, report)
        write_json(paths.analysis, analysis)
        song = self.db.get_song(song_id) or {}
        meta = dict(song.get("meta") or {})
        meta["summary"] = analysis.get("summary", {})
        self.db.update_song(song_id, meta=meta)
        report(1.0, "Análisis listo")

    def _load_stems(self, song: dict, paths: SongPaths) -> dict[str, np.ndarray]:
        stems = {}
        for name in song.get("stems") or []:
            audio, _ = audio_io.read_audio(paths.stem(name))
            stems[name] = audio
        if not stems:
            raise RuntimeError("La canción todavía no tiene pistas separadas")
        return stems

    def _reanalyze(self, job: dict) -> dict:
        song_id = job["song_id"]
        song = self.db.get_song(song_id)
        if song is None:
            raise RuntimeError("La canción ya no existe")
        paths = SongPaths(self.cfg.songs_dir, song_id)
        self.db.update_song(song_id, status="analyzing", stage="Analizando…")
        stems = self._load_stems(song, paths)
        self._run_analysis(song_id, paths, stems, self._progress(job, song_status="analyzing"))
        self.db.update_song(song_id, status="ready", progress=1.0, stage="Lista")
        return {}

    def _lyrics(self, job: dict) -> dict:
        if self.transcriber is None:
            raise RuntimeError("La transcripción de letra no está disponible (instala faster-whisper).")
        song_id = job["song_id"]
        song = self.db.get_song(song_id)
        if song is None:
            raise RuntimeError("La canción ya no existe")
        paths = SongPaths(self.cfg.songs_dir, song_id)
        stem_name = "vocals" if "vocals" in (song.get("stems") or []) else None
        if stem_name is None:
            raise RuntimeError("Esta canción no tiene pista de voz")
        self.db.update_song(song_id, lyrics_status="running")
        vocals, sr = audio_io.read_audio(paths.stem(stem_name))
        params = job.get("params") or {}
        lyrics = self.transcriber(vocals, sr, self._progress(job), language=params.get("language"),
                                  should_cancel=lambda: self._is_cancelled(job["id"]))
        write_json(paths.lyrics, lyrics)
        self.db.update_song(song_id, lyrics_status="ready")
        return {"lines": len(lyrics.get("lines", []))}

    def _export(self, job: dict) -> dict:
        if self.exporter is None:
            raise RuntimeError("Exportación no disponible")
        song_id = job["song_id"]
        song = self.db.get_song(song_id)
        if song is None:
            raise RuntimeError("La canción ya no existe")
        out_dir = self.cfg.exports_dir / job["id"]
        out_dir.mkdir(parents=True, exist_ok=True)
        paths = SongPaths(self.cfg.songs_dir, song_id)
        return self.exporter(song, paths, job.get("params") or {}, out_dir, self._progress(job),
                             lambda: self._is_cancelled(job["id"]))

    def _cleanup_old_exports(self, max_age_hours: float = 24) -> None:
        """Borra exportaciones viejas (son archivos temporales para descargar)."""
        if not self.cfg.exports_dir.exists():
            return
        limit = datetime.now(timezone.utc) - timedelta(hours=max_age_hours)
        for folder in self.cfg.exports_dir.iterdir():
            try:
                modified = datetime.fromtimestamp(folder.stat().st_mtime, timezone.utc)
            except OSError:
                continue
            if modified < limit:
                shutil.rmtree(folder, ignore_errors=True)


def enqueue(db: Database, worker: Worker | None, kind: str, song_id: str | None, params: dict | None = None,
            message: str = "En cola") -> dict:
    job = {"id": new_id(), "song_id": song_id, "kind": kind, "params": params or {}, "message": message}
    db.insert_job(job)
    if worker is not None:
        worker.notify(kind)
    return db.get_job(job["id"])
