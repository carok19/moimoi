"""Exportaciones: pistas sueltas, mezcla personalizada, pista de click y paquete para Multitrack.

El paquete "multitrack" es un .zip compatible con la app Multitrack Alabanza (repo `Daw`):
un archivo WAV por pista (el importador toma cada archivo de audio como una pista y usa el
nombre del archivo como nombre de la pista, y el nombre del .zip como nombre de la canción).
Además incluye `moimoi.json` con tempo, tonalidad, acordes y secciones (marcadores), que el
importador actual ignora sin problema y que sirve para integraciones futuras.
"""

from __future__ import annotations

import json
import math
import re
import zipfile
from pathlib import Path
from typing import Callable

import numpy as np

from . import __version__, audio_io
from .analysis.music import CHORD_QUALITIES, chord_name, key_label, key_name, uses_flats
from .separation.base import SAMPLE_RATE, STEMS, Cancelled, ordered_stems
from .storage import SongPaths, read_json

FORMATS = ("wav", "mp3", "flac")
MIME = {"wav": "audio/wav", "mp3": "audio/mpeg", "flac": "audio/flac", "zip": "application/zip"}
MAX_GAIN = 2.0


class ExportError(Exception):
    pass


def safe_filename(text: str, fallback: str = "cancion") -> str:
    text = re.sub(r'[\\/:*?"<>|\x00-\x1f]+', " ", text or "").strip().strip(".")
    text = re.sub(r"\s+", " ", text)
    return text[:120] or fallback


def song_display_name(song: dict) -> str:
    title = song.get("title") or "Canción"
    artist = song.get("artist")
    return f"{artist} - {title}" if artist else title


# ---- pistas ----------------------------------------------------------------------------


def stem_file(paths: SongPaths, stem: str, fmt: str) -> Path:
    """Archivo de una pista en el formato pedido (se convierte una vez y queda en caché)."""
    if fmt not in FORMATS:
        raise ExportError(f"Formato no soportado: {fmt}")
    source = paths.stem(stem)
    if not source.exists():
        raise ExportError("Esa pista no existe")
    if fmt == "flac":
        return source
    paths.cache_dir.mkdir(parents=True, exist_ok=True)
    target = paths.cache_dir / f"{stem}.{fmt}"
    if not target.exists() or target.stat().st_mtime < source.stat().st_mtime:
        audio, sr = audio_io.read_audio(source)
        tmp = target.with_suffix(f".tmp.{fmt}")
        audio_io.write_audio(tmp, audio, fmt, sr)
        tmp.replace(target)
    return target


def load_stems(paths: SongPaths, names: list[str]) -> dict[str, np.ndarray]:
    stems = {}
    for name in names:
        audio, sr = audio_io.read_audio(paths.stem(name))
        if sr != SAMPLE_RATE:
            raise ExportError(f"Frecuencia de muestreo inesperada en {name}: {sr}")
        stems[name] = audio
    return stems


# ---- mezcla ----------------------------------------------------------------------------


def _pan_gains(pan: float) -> tuple[float, float]:
    """Paneo de potencia constante (-1 izquierda, 0 centro, 1 derecha)."""
    pan = max(-1.0, min(1.0, float(pan)))
    angle = (pan + 1) * math.pi / 4
    return math.cos(angle) * math.sqrt(2), math.sin(angle) * math.sqrt(2)


def active_channels(mixer: dict, stems: list[str]) -> dict[str, dict]:
    """Aplica mute/solo como en el mezclador: si hay alguna pista en solo, suenan solo esas."""
    settings = {s: {"volume": 1.0, "pan": 0.0, "mute": False, "solo": False, **(mixer.get(s) or {})} for s in stems}
    any_solo = any(bool(v.get("solo")) for v in settings.values())
    result = {}
    for name, value in settings.items():
        audible = bool(value.get("solo")) if any_solo else not bool(value.get("mute"))
        if audible and float(value.get("volume", 1.0)) > 0:
            result[name] = {"volume": min(MAX_GAIN, max(0.0, float(value.get("volume", 1.0)))),
                            "pan": float(value.get("pan", 0.0))}
    return result


def mix_stems(stems: dict[str, np.ndarray], channels: dict[str, dict]) -> np.ndarray:
    length = max(a.shape[1] for a in stems.values())
    out = np.zeros((2, length), dtype=np.float32)
    for name, params in channels.items():
        audio = stems[name]
        if audio.shape[0] == 1:
            audio = np.vstack([audio, audio])
        left, right = _pan_gains(params.get("pan", 0.0))
        gain = params["volume"]
        out[0, : audio.shape[1]] += audio[0] * gain * left
        out[1, : audio.shape[1]] += audio[1] * gain * right
    return out


def soft_limit(audio: np.ndarray, ceiling: float = 0.97) -> np.ndarray:
    """Evita que la mezcla sature: si pasa del techo, baja el volumen general."""
    peak = float(np.max(np.abs(audio))) if audio.size else 0.0
    if peak > ceiling:
        audio = audio * (ceiling / peak)
    return audio.astype(np.float32)


def stretch(audio: np.ndarray, tempo: float, semitones: float) -> np.ndarray:
    """Cambia velocidad (tempo=0.8 -> 80 %) y tono (semitonos) sin afectar el otro."""
    if abs(tempo - 1.0) < 1e-3 and abs(semitones) < 1e-3:
        return audio
    try:
        import pedalboard
    except ImportError as exc:
        raise ExportError("Para exportar con otra velocidad o tono hace falta 'pip install pedalboard'.") from exc
    result = pedalboard.time_stretch(
        np.ascontiguousarray(audio, dtype=np.float32), SAMPLE_RATE,
        stretch_factor=float(tempo), pitch_shift_in_semitones=float(semitones), high_quality=True,
    )
    return np.asarray(result, dtype=np.float32)


# ---- click -----------------------------------------------------------------------------


def click_sound(accent: bool, sample_rate: int = SAMPLE_RATE) -> np.ndarray:
    length = int(0.045 * sample_rate)
    t = np.arange(length) / sample_rate
    freq = 1800.0 if accent else 1250.0
    tone = np.sin(2 * np.pi * freq * t) + 0.35 * np.sin(2 * np.pi * freq * 2.01 * t)
    envelope = np.exp(-t / (0.012 if accent else 0.009))
    attack = np.minimum(1.0, t / 0.0005)
    return (tone * envelope * attack * (0.9 if accent else 0.6)).astype(np.float32)


def click_track(beats: list[float], downbeats: list[float], duration: float, tempo: float = 1.0,
                sample_rate: int = SAMPLE_RATE) -> np.ndarray:
    """Pista de click (metrónomo) sobre los pulsos detectados, con acento en el "1"."""
    length = int(math.ceil(duration / tempo * sample_rate))
    out = np.zeros(length, dtype=np.float32)
    accent_times = {round(t, 3) for t in downbeats}
    sounds = {True: click_sound(True, sample_rate), False: click_sound(False, sample_rate)}
    for beat in beats:
        start = int(round(beat / tempo * sample_rate))
        if start >= length:
            break
        sound = sounds[round(beat, 3) in accent_times]
        end = min(length, start + sound.size)
        out[start:end] += sound[: end - start]
    return np.vstack([out, out])


# ---- metadatos -------------------------------------------------------------------------


def transposed_key(analysis: dict, semitones: int) -> dict | None:
    key = analysis.get("key")
    if not key:
        return None
    tonic = (int(key["tonic"]) + semitones) % 12
    return {"tonic": tonic, "mode": key["mode"], "name": key_name(tonic, key["mode"]),
            "label": key_label(tonic, key["mode"])}


def transposed_chords(analysis: dict, semitones: int, tempo: float) -> list[dict]:
    key = transposed_key(analysis, semitones)
    flats = uses_flats(key["tonic"], key["mode"]) if key else False
    chords = []
    for c in analysis.get("chords") or []:
        if c.get("quality") == "N" or c.get("quality") not in CHORD_QUALITIES:
            continue
        root = (int(c["root"]) + semitones) % 12
        bass = None if c.get("bass") is None else (int(c["bass"]) + semitones) % 12
        chords.append({
            "inicio": round(c["start"] / tempo, 3),
            "fin": round(c["end"] / tempo, 3),
            "nombre": chord_name(root, c["quality"], bass, flats),
        })
    return chords


def section_markers(analysis: dict, song: dict, tempo: float) -> list[dict]:
    """Marcadores con el formato de Multitrack Alabanza (nombre, tiempoMs, color)."""
    edited = (song.get("settings") or {}).get("sections")
    sections = edited if isinstance(edited, list) and edited else analysis.get("sections") or []
    palette = {"Intro": "#8e9aaf", "Verso": "#4fa3ff", "Pre-coro": "#b388ff", "Coro": "#ff5d8f",
               "Puente": "#ffb74d", "Instrumental": "#3fd9b0", "Final": "#8e9aaf"}
    markers = []
    for s in sections:
        label = str(s.get("label") or "Parte")
        base = label.rstrip(" 0123456789")
        markers.append({"nombre": label, "tiempoMs": int(round(float(s["start"]) / tempo * 1000)),
                        "color": palette.get(base, "#9fb3c8")})
    return markers


# ---- trabajos de exportación -----------------------------------------------------------


def run_export(song: dict, paths: SongPaths, params: dict, out_dir: Path,
               progress: Callable[[float, str], None], should_cancel: Callable[[], bool]) -> dict:
    kind = params.get("type", "multitrack")
    available = list(song.get("stems") or [])
    wanted = [s for s in (params.get("stems") or available) if s in available]
    if not wanted:
        raise ExportError("Elige al menos una pista")
    wanted = ordered_stems(wanted)
    fmt = params.get("format", "wav")
    if fmt not in FORMATS:
        raise ExportError(f"Formato no soportado: {fmt}")
    tempo = float(params.get("tempo") or 1.0)
    if not 0.25 <= tempo <= 4.0:
        raise ExportError("Velocidad fuera de rango")
    semitones = int(round(float(params.get("semitones") or 0)))
    if not -12 <= semitones <= 12:
        raise ExportError("Transposición fuera de rango (-12 a 12)")
    analysis = read_json(paths.analysis, {}) or {}
    base_name = safe_filename(song_display_name(song))
    suffix = _variant_suffix(tempo, semitones, analysis)

    def check():
        if should_cancel():
            raise Cancelled()

    if kind == "mix":
        progress(0.05, "Leyendo pistas…")
        stems = load_stems(paths, wanted)
        check()
        channels = active_channels(params.get("mixer") or {}, wanted)
        if not channels:
            raise ExportError("Todas las pistas elegidas están en silencio")
        mix = mix_stems(stems, channels)
        del stems
        progress(0.3, "Aplicando velocidad y tono…" if suffix else "Mezclando…")
        mix = stretch(mix, tempo, semitones)
        if params.get("click") and analysis.get("beats"):
            click = click_track(analysis["beats"], analysis.get("downbeats") or [], mix.shape[1] / SAMPLE_RATE * tempo,
                                tempo)
            gain = float(params.get("clickVolume", 0.6))
            mix[:, : click.shape[1]] += gain * click[:, : mix.shape[1]]
        mix = soft_limit(mix)
        check()
        progress(0.85, "Guardando…")
        name = f"{base_name}{suffix} (mezcla).{fmt}"
        target = out_dir / f"mezcla.{fmt}"
        audio_io.write_audio(target, mix, fmt)
        return {"file": target.name, "name": name, "size": target.stat().st_size, "mime": MIME[fmt]}

    if kind not in {"multitrack", "stems"}:
        raise ExportError(f"Tipo de exportación desconocido: {kind}")
    if kind == "multitrack":
        fmt = "wav"  # el reproductor multitrack transmite WAV a los celulares
    zip_name = f"{base_name}{suffix}.zip"
    target = out_dir / "paquete.zip"
    steps = len(wanted) + (1 if params.get("click") else 0) + 1
    tracks_meta = []
    with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=1) as zf:
        for index, name in enumerate(wanted):
            check()
            info = STEMS.get(name)
            label = info.name if info else name
            progress(index / steps, f"Preparando {label}…")
            if not suffix:
                file_path = stem_file(paths, name, fmt)
            else:
                audio, _ = audio_io.read_audio(paths.stem(name))
                audio = soft_limit(stretch(audio, tempo, semitones), 0.99)
                file_path = out_dir / f"{name}.{fmt}"
                audio_io.write_audio(file_path, audio, fmt)
            file_name = f"{info.file_name if info else name}.{fmt}"
            zf.write(file_path, file_name, compress_type=zipfile.ZIP_STORED if fmt != "wav" else zipfile.ZIP_DEFLATED)
            if file_path.parent == out_dir:
                file_path.unlink(missing_ok=True)
            tracks_meta.append({"archivo": file_name, "nombre": label, "instrumento": name,
                                "volumen": 80, "pan": 0, "mute": False, "solo": False})
        if params.get("click"):
            check()
            if not analysis.get("beats"):
                raise ExportError("No se detectó el pulso de esta canción: no se puede generar el click")
            progress((steps - 1) / steps, "Generando click…")
            click = click_track(analysis["beats"], analysis.get("downbeats") or [],
                                float(song.get("duration") or analysis.get("duration") or 0), tempo)
            click_path = out_dir / f"click.{fmt}"
            audio_io.write_audio(click_path, click, fmt)
            zf.write(click_path, f"Click.{fmt}")
            click_path.unlink(missing_ok=True)
            tracks_meta.append({"archivo": f"Click.{fmt}", "nombre": "Click", "instrumento": "click",
                                "volumen": 70, "pan": 0, "mute": False, "solo": False})
        manifest = build_manifest(song, analysis, tracks_meta, tempo, semitones)
        zf.writestr("moimoi.json", json.dumps(manifest, ensure_ascii=False, indent=2))
    progress(1.0, "Listo")
    return {"file": target.name, "name": zip_name, "size": target.stat().st_size, "mime": MIME["zip"]}


def _variant_suffix(tempo: float, semitones: int, analysis: dict) -> str:
    parts = []
    if semitones:
        key = transposed_key(analysis, semitones)
        parts.append(f"en {key['name']}" if key else f"{semitones:+d} st")
    if abs(tempo - 1.0) >= 1e-3:
        parts.append(f"{tempo * 100:.0f}%")
    return f" ({', '.join(parts)})" if parts else ""


def build_manifest(song: dict, analysis: dict, tracks: list[dict], tempo: float, semitones: int) -> dict:
    bpm = (analysis.get("tempo") or {}).get("bpm")
    original_key = analysis.get("key")
    key = transposed_key(analysis, semitones)
    duration = float(song.get("duration") or analysis.get("duration") or 0)
    return {
        "formato": "moimoi-multitrack",
        "version": 1,
        "cancion": {
            "titulo": song.get("title"),
            "artista": song.get("artist"),
            "duracionMs": int(round(duration / tempo * 1000)),
            "bpm": round(bpm * tempo, 1) if bpm else None,
            "compas": (analysis.get("tempo") or {}).get("beatsPerBar", 4),
            "tonalidad": key["name"] if key else None,
            "tonalidadNombre": key["label"] if key else None,
            "tonalidadOriginal": original_key["name"] if original_key else None,
            "transposicion": semitones,
            "velocidad": tempo,
        },
        "pistas": tracks,
        "marcadores": section_markers(analysis, song, tempo),
        "acordes": transposed_chords(analysis, semitones, tempo),
        "origen": {"app": "MoiMoi", "version": __version__, "cancionId": song.get("id"),
                   "url": song.get("source_url")},
    }
