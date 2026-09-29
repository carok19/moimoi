"""Exportaciones: pistas sueltas, mezcla personalizada, click, voz guía y paquete para Multitrack.

El paquete "multitrack" es un .zip compatible con la app Multitrack Alabanza (repo `Daw`):
un archivo de audio por pista (el importador toma cada archivo como una pista y el nombre del
.zip como nombre de la canción). Incluye `moimoi.json` con el orden y nombre de las pistas,
tempo, tonalidad, acordes y las partes de la canción, que Multitrack Alabanza importa como
marcadores.
"""

from __future__ import annotations

import json
import math
import re
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

import numpy as np

from . import __version__, audio_io, guia
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
    """Cambia velocidad (tempo=0.8 -> 80 %) y tono (semitonos) sin afectar el otro.

    Mismo método que la app del celular (moimoi.stretch): queda alineado con el click y la guía
    de principio a fin (Rubber Band en tiempo real se iba corriendo hasta ~0,15 %).
    """
    from . import stretch as stretcher

    if not stretcher.needed(tempo, semitones):
        return audio
    return stretcher.stretch(audio, float(tempo), float(semitones))


# ---- click -----------------------------------------------------------------------------


def click_sound(accent: bool, sample_rate: int = SAMPLE_RATE) -> np.ndarray:
    length = int(0.045 * sample_rate)
    t = np.arange(length) / sample_rate
    freq = 1800.0 if accent else 1250.0
    tone = np.sin(2 * np.pi * freq * t) + 0.35 * np.sin(2 * np.pi * freq * 2.01 * t)
    envelope = np.exp(-t / (0.012 if accent else 0.009))
    attack = np.minimum(1.0, t / 0.0005)
    return (tone * envelope * attack * (0.9 if accent else 0.6)).astype(np.float32)


@dataclass
class Timeline:
    """Cómo se pasa del tiempo de la canción al de los archivos exportados.

    salida = cuenta_inicial + tiempo_canción / velocidad. `count_times` son los pulsos de la
    cuenta inicial (en segundos de salida), que ocurren dentro de los compases agregados al
    principio.
    """

    tempo: float = 1.0
    pre_roll: float = 0.0
    count_times: list[float] = field(default_factory=list)
    beats_per_bar: int = 4

    def out(self, t: float) -> float:
        return self.pre_roll + t / self.tempo


@dataclass
class Grid:
    beats: list[float]
    accents: list[bool]
    beats_per_bar: int
    bpm: float | None


def effective_grid(analysis: dict, settings: dict) -> Grid:
    """Pulsos y acentos con las correcciones que el usuario hizo en el reproductor
    (contar el doble / la mitad, mover el "1"): el mismo cálculo que la interfaz web."""
    tempo_info = analysis.get("tempo") or {}
    per_bar = int(tempo_info.get("beatsPerBar") or 4)
    beats = [float(b) for b in analysis.get("beats") or []]
    downs = {round(float(t), 3) for t in analysis.get("downbeats") or []}
    phase = next((i for i, b in enumerate(beats) if round(b, 3) in downs), 0)
    bpm = tempo_info.get("bpm")
    scale = settings.get("beatScale")
    if scale == "double" and len(beats) > 1:
        doubled: list[float] = []
        for i, b in enumerate(beats):
            doubled.append(b)
            if i + 1 < len(beats):
                doubled.append((b + beats[i + 1]) / 2)
        beats, phase, bpm = doubled, phase * 2, (bpm * 2 if bpm else bpm)
    elif scale == "half" and len(beats) > 1:
        offset = phase % 2
        beats = [b for i, b in enumerate(beats) if i % 2 == offset]
        phase, bpm = phase // 2, (bpm / 2 if bpm else bpm)
    first = (phase + int(settings.get("downbeatShift") or 0)) % per_bar
    accents = [(i - first) % per_bar == 0 for i in range(len(beats))]
    return Grid(beats, accents, per_bar, bpm)


def plan_timeline(grid: Grid, tempo: float, pre_roll_bars: int) -> Timeline:
    """Si se pide cuenta inicial, agrega silencio al principio para que la cuenta termine
    justo un pulso antes del primer "1" de la canción."""
    if pre_roll_bars <= 0 or len(grid.beats) < 2:
        return Timeline(tempo=tempo, beats_per_bar=grid.beats_per_bar)
    period = float(np.median(np.diff(grid.beats)))
    first = next((b for b, accent in zip(grid.beats, grid.accents) if accent), grid.beats[0])
    count = pre_roll_bars * grid.beats_per_bar
    step = period / tempo
    pre_roll = max(0.0, count * step + 0.05 - first / tempo)
    start = pre_roll + first / tempo - count * step
    return Timeline(tempo, pre_roll, [start + i * step for i in range(count)], grid.beats_per_bar)


def click_sounds(samples: dict[str, np.ndarray] | None = None) -> dict[bool, np.ndarray]:
    """{acento: sonido}. Con `samples` (los sonidos de click de un paquete) usa esos."""
    samples = {k: v for k, v in (samples or {}).items() if v is not None and v.size}
    if not samples:
        return {True: click_sound(True), False: click_sound(False)}
    beat = next((samples[r] for r in ("beat", "eighth", "sixteenth", "accent") if r in samples))
    return {True: samples.get("accent", beat), False: beat}


def click_track(grid: Grid, length_s: float, timeline: Timeline, sample_rate: int = SAMPLE_RATE,
                sounds: dict[bool, np.ndarray] | None = None) -> np.ndarray:
    """Pista de click (metrónomo) sobre los pulsos de la canción, con acento en el "1" y la
    cuenta inicial si la hay."""
    length = int(round(length_s * sample_rate))
    out = np.zeros(length, dtype=np.float32)
    sounds = sounds or {True: click_sound(True, sample_rate), False: click_sound(False, sample_rate)}
    events = [(t, i % timeline.beats_per_bar == 0) for i, t in enumerate(timeline.count_times)]
    events += [(timeline.out(b), accent) for b, accent in zip(grid.beats, grid.accents)]
    for time, accent in events:
        start = int(round(time * sample_rate))
        if start < 0 or start >= length:
            continue
        sound = sounds[accent]
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


def transposed_chords(analysis: dict, semitones: int, timeline: Timeline) -> list[dict]:
    key = transposed_key(analysis, semitones)
    flats = uses_flats(key["tonic"], key["mode"]) if key else False
    chords = []
    for c in analysis.get("chords") or []:
        if c.get("quality") == "N" or c.get("quality") not in CHORD_QUALITIES:
            continue
        root = (int(c["root"]) + semitones) % 12
        bass = None if c.get("bass") is None else (int(c["bass"]) + semitones) % 12
        chords.append({
            "inicio": round(timeline.out(c["start"]), 3),
            "fin": round(timeline.out(c["end"]), 3),
            "nombre": chord_name(root, c["quality"], bass, flats),
        })
    return chords


def song_sections(analysis: dict, song: dict) -> list[dict]:
    """Partes de la canción: las que editó el usuario, o las detectadas."""
    edited = (song.get("settings") or {}).get("sections")
    return edited if isinstance(edited, list) and edited else analysis.get("sections") or []


def key_change_extras(analysis: dict, sections: list[dict]) -> dict[int, str]:
    """{número de parte: "sube" | "baja"} donde la canción cambia de tonalidad (mismo modo)."""
    previous = analysis.get("keyStart") or analysis.get("key")
    starts = [float(s["start"]) for s in sections]
    extras: dict[int, str] = {}
    for change in analysis.get("keyChanges") or []:
        if previous and change.get("mode") == previous.get("mode") and starts:
            diff = (int(change["tonic"]) - int(previous["tonic"])) % 12
            index = min(range(len(starts)), key=lambda i: abs(starts[i] - float(change["time"])))
            if diff and index > 0 and abs(starts[index] - float(change["time"])) <= 1.0:
                extras[index] = "sube" if diff <= 6 else "baja"
        previous = change
    return extras


SECTION_PALETTE = {"Intro": "#8e9aaf", "Verso": "#4fa3ff", "Pre-coro": "#b388ff", "Coro": "#ff5d8f",
                   "Puente": "#ffb74d", "Instrumental": "#3fd9b0", "Final": "#8e9aaf"}


def section_markers(sections: list[dict], timeline: Timeline) -> list[dict]:
    """Marcadores con el formato de Multitrack Alabanza (nombre, tiempoMs, color)."""
    markers = []
    for i, s in enumerate(sections):
        label = str(s.get("label") or "Parte")
        base = label.rstrip(" 0123456789")
        # La primera parte arranca en 0 aunque haya cuenta inicial (el marcador lleva a la cuenta).
        time = 0.0 if i == 0 and float(s["start"]) <= 0.01 else timeline.out(float(s["start"]))
        markers.append({"nombre": label, "tiempoMs": int(round(time * 1000)),
                        "color": SECTION_PALETTE.get(base, "#9fb3c8")})
    return markers


# ---- trabajos de exportación -----------------------------------------------------------


def _fit(audio: np.ndarray, length: int, pre_roll: int) -> np.ndarray:
    """Agrega el silencio de la cuenta inicial y deja todas las pistas exactamente del mismo largo."""
    out = np.zeros((audio.shape[0], length), dtype=np.float32)
    end = min(length, pre_roll + audio.shape[1])
    if end > pre_roll:
        out[:, pre_roll:end] = audio[:, : end - pre_roll]
    return out


def run_export(song: dict, paths: SongPaths, params: dict, out_dir: Path,
               progress: Callable[[float, str], None], should_cancel: Callable[[], bool],
               guide_kit: guia.GuideKit | None = None) -> dict:
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
    pre_roll_bars = int(params.get("preRollBars") or 0)
    if not 0 <= pre_roll_bars <= 4:
        raise ExportError("Cuenta inicial fuera de rango (0 a 4 compases)")
    analysis = read_json(paths.analysis, {}) or {}
    grid = effective_grid(analysis, song.get("settings") or {})
    base_name = safe_filename(song_display_name(song))
    suffix = _variant_suffix(tempo, semitones, analysis)
    duration = float(song.get("duration") or analysis.get("duration") or 0)

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
        if params.get("click") and grid.beats:
            timeline = Timeline(tempo=tempo, beats_per_bar=grid.beats_per_bar)
            click = click_track(grid, mix.shape[1] / SAMPLE_RATE, timeline)
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
    multitrack = kind == "multitrack"
    if multitrack and fmt == "flac":
        fmt = "wav"  # Multitrack Alabanza lee WAV, MP3 y M4A
    want_click = bool(params.get("click")) and multitrack
    want_guide = bool(params.get("guide")) and multitrack
    if (want_click or pre_roll_bars) and not grid.beats:
        raise ExportError("No se detectó el pulso de esta canción: no se puede generar el click ni la cuenta")
    cues: dict[str, np.ndarray] = {}
    if want_guide:
        cues = guia.load_cues(guide_kit) if guide_kit is not None else {}
        if not cues:
            raise ExportError("Primero carga las voces guía en Ajustes → Voz guía")
    sounds = None
    style = params.get("clickSound")
    if want_click and style and style != "moimoi" and guide_kit is not None:
        samples = guide_kit.click_sounds(str(style))
        sounds = click_sounds(samples) if samples else None  # si se borró ese sonido, el de MoiMoi
    timeline = plan_timeline(grid, tempo, pre_roll_bars if multitrack else 0)
    sections = song_sections(analysis, song)
    length_s = timeline.pre_roll + duration / tempo
    length = int(round(length_s * SAMPLE_RATE))
    pre = int(round(timeline.pre_roll * SAMPLE_RATE))
    plain = not suffix and pre == 0  # las pistas se copian tal cual (conversión en caché)

    zip_name = f"{base_name}{suffix}.zip"
    target = out_dir / "paquete.zip"
    steps = len(wanted) + want_click + want_guide + 1
    step = 0
    tracks_meta: list[dict] = []
    placements: list[guia.Placement] = []
    compression = zipfile.ZIP_DEFLATED if fmt == "wav" else zipfile.ZIP_STORED

    with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=1) as zf:
        def add_audio(audio: np.ndarray, file_base: str, meta: dict) -> None:
            path = out_dir / f"tmp-{file_base}.{fmt}"
            audio_io.write_audio(path, audio, fmt)
            zf.write(path, f"{file_base}.{fmt}", compress_type=compression)
            path.unlink(missing_ok=True)
            tracks_meta.append({"archivo": f"{file_base}.{fmt}", "pan": 0, "mute": False, "solo": False, **meta})

        # Click y Guía primero: así quedan arriba en el multitrack.
        if want_click:
            check()
            progress(step / steps, "Generando click…")
            add_audio(click_track(grid, length_s, timeline, sounds=sounds), "Click",
                      {"nombre": "Click", "instrumento": "click", "volumen": 70})
            step += 1
        if want_guide:
            check()
            progress(step / steps, "Armando la voz guía…")
            extras = key_change_extras(analysis, sections) if params.get("guideKeyChanges", True) else {}
            placements = guia.plan_guide(sections, grid.beats, grid.beats_per_bar, set(cues), timeline.out,
                                         timeline.count_times, numbering=params.get("guideNumbering") or "verses",
                                         extras=extras)
            add_audio(guia.render_guide(placements, cues, length), "Guia",
                      {"nombre": "Guía", "instrumento": "guia", "volumen": 80})
            step += 1
        for name in wanted:
            check()
            info = STEMS.get(name)
            label = info.name if info else name
            progress(step / steps, f"Preparando {label}…")
            file_base = info.file_name if info else name
            meta = {"nombre": label, "instrumento": name, "volumen": 80}
            if plain:
                zf.write(stem_file(paths, name, fmt), f"{file_base}.{fmt}", compress_type=compression)
                tracks_meta.append({"archivo": f"{file_base}.{fmt}", "pan": 0, "mute": False, "solo": False, **meta})
            else:
                audio, _ = audio_io.read_audio(paths.stem(name))
                if suffix:
                    audio = soft_limit(stretch(audio, tempo, semitones), 0.99)
                add_audio(_fit(audio, length, pre), file_base, meta)
            step += 1
        manifest = build_manifest(song, analysis, grid, sections, tracks_meta, timeline, semitones, length_s,
                                  placements)
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


def build_manifest(song: dict, analysis: dict, grid: Grid, sections: list[dict], tracks: list[dict],
                   timeline: Timeline, semitones: int, length_s: float,
                   placements: list[guia.Placement] | None = None) -> dict:
    original_key = analysis.get("key")
    key = transposed_key(analysis, semitones)
    return {
        "formato": "moimoi-multitrack",
        "version": 1,
        "cancion": {
            "titulo": song.get("title"),
            "artista": song.get("artist"),
            "duracionMs": int(round(length_s * 1000)),
            "bpm": round(grid.bpm * timeline.tempo, 1) if grid.bpm else None,
            "compas": grid.beats_per_bar,
            "tonalidad": key["name"] if key else None,
            "tonalidadNombre": key["label"] if key else None,
            "tonalidadOriginal": original_key["name"] if original_key else None,
            "transposicion": semitones,
            "velocidad": timeline.tempo,
            "cuentaInicialMs": int(round(timeline.pre_roll * 1000)),
        },
        "pistas": tracks,
        "marcadores": section_markers(sections, timeline),
        "acordes": transposed_chords(analysis, semitones, timeline),
        "guia": [{"voz": p.cue, "parte": p.label, "tiempoMs": int(round(p.time * 1000))} for p in placements or []],
        "origen": {"app": "MoiMoi", "version": __version__, "cancionId": song.get("id"),
                   "url": song.get("source_url")},
    }
