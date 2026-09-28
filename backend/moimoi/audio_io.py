"""Lectura y escritura de audio.

- Decodificación de cualquier formato (mp3, m4a, webm, wav, flac, ogg...) con ffmpeg:
  el del sistema si está instalado, o el que trae el paquete `imageio-ffmpeg`.
- Escritura WAV/FLAC con soundfile y MP3 con lameenc (o ffmpeg si falta lameenc).
- Picos de forma de onda compactos para dibujar en el navegador.
"""

from __future__ import annotations

import base64
import math
import os
import shutil
import subprocess
from functools import lru_cache
from pathlib import Path

import numpy as np
import soundfile as sf

SAMPLE_RATE = 44100
PEAKS_PER_SECOND = 25

_POPEN_FLAGS = {"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {}


class AudioError(Exception):
    """No se pudo leer o escribir un archivo de audio."""


@lru_cache(maxsize=1)
def find_ffmpeg() -> str | None:
    """Ruta de ffmpeg: variable MOIMOI_FFMPEG, el del sistema o el de imageio-ffmpeg."""
    explicit = os.environ.get("MOIMOI_FFMPEG")
    if explicit and Path(explicit).exists():
        return explicit
    system = shutil.which("ffmpeg")
    if system:
        return system
    try:
        import imageio_ffmpeg

        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:  # noqa: BLE001 - paquete ausente o binario no disponible
        return None


def require_ffmpeg() -> str:
    path = find_ffmpeg()
    if not path:
        raise AudioError(
            "No se encontró ffmpeg. Instálalo (https://ffmpeg.org) o ejecuta "
            "'pip install imageio-ffmpeg'."
        )
    return path


def decode_audio(path: Path, sample_rate: int = SAMPLE_RATE, channels: int = 2) -> np.ndarray:
    """Decodifica a float32 con forma (channels, n)."""
    ffmpeg = require_ffmpeg()
    cmd = [
        ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error",
        "-i", str(path), "-vn", "-sn", "-dn",
        "-f", "f32le", "-acodec", "pcm_f32le", "-ac", str(channels), "-ar", str(sample_rate),
        "pipe:1",
    ]
    proc = subprocess.run(cmd, capture_output=True, **_POPEN_FLAGS)
    if proc.returncode != 0:
        detail = proc.stderr.decode("utf-8", errors="replace").strip().splitlines()
        raise AudioError("No se pudo leer el audio: " + (detail[-1] if detail else "formato no reconocido"))
    data = np.frombuffer(proc.stdout, dtype="<f4")
    frames = data.size // channels
    if frames == 0:
        raise AudioError("El archivo no tiene audio.")
    return np.ascontiguousarray(data[: frames * channels].reshape(frames, channels).T)


def _unescape_ffmetadata(value: str) -> str:
    out, escaped = [], False
    for ch in value:
        if escaped:
            out.append(ch)
            escaped = False
        elif ch == "\\":
            escaped = True
        else:
            out.append(ch)
    return "".join(out)


def read_tags(path: Path) -> dict[str, str]:
    """Etiquetas (título, artista, álbum) del archivo, si las tiene."""
    ffmpeg = find_ffmpeg()
    if not ffmpeg:
        return {}
    cmd = [ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-i", str(path), "-f", "ffmetadata", "-"]
    try:
        proc = subprocess.run(cmd, capture_output=True, timeout=30, **_POPEN_FLAGS)
    except (OSError, subprocess.TimeoutExpired):
        return {}
    tags: dict[str, str] = {}
    for line in proc.stdout.decode("utf-8", errors="replace").splitlines():
        if not line or line.startswith(";") or line.startswith("[") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        key = key.strip().lower()
        if key in {"title", "artist", "album", "album_artist"} and value.strip():
            tags[key] = _unescape_ffmetadata(value.strip())
    return tags


def read_audio(path: Path) -> tuple[np.ndarray, int]:
    """Lee WAV/FLAC propio (escrito por MoiMoi) como float32 (channels, n)."""
    try:
        data, sr = sf.read(str(path), dtype="float32", always_2d=True)
    except Exception as exc:  # noqa: BLE001
        raise AudioError(f"No se pudo leer {Path(path).name}: {exc}") from exc
    return np.ascontiguousarray(data.T), sr


def audio_info(path: Path) -> tuple[int, int, int]:
    """(frames, sample_rate, channels) sin leer todo el archivo."""
    info = sf.info(str(path))
    return info.frames, info.samplerate, info.channels


def _as_frames(audio: np.ndarray) -> np.ndarray:
    audio = np.asarray(audio, dtype=np.float32)
    if audio.ndim == 1:
        audio = audio[None, :]
    return np.clip(audio.T, -1.0, 1.0)


def write_wav(path: Path, audio: np.ndarray, sample_rate: int = SAMPLE_RATE, subtype: str = "PCM_16") -> None:
    sf.write(str(path), _as_frames(audio), sample_rate, subtype=subtype, format="WAV")


def write_flac(path: Path, audio: np.ndarray, sample_rate: int = SAMPLE_RATE) -> None:
    sf.write(str(path), _as_frames(audio), sample_rate, subtype="PCM_16", format="FLAC")


def to_int16_interleaved(audio: np.ndarray) -> bytes:
    frames = _as_frames(audio)
    return (frames * 32767.0).round().astype("<i2").tobytes()


def write_mp3(path: Path, audio: np.ndarray, sample_rate: int = SAMPLE_RATE, bitrate: int = 320) -> None:
    channels = 1 if np.asarray(audio).ndim == 1 else np.asarray(audio).shape[0]
    pcm = to_int16_interleaved(audio)
    try:
        import lameenc
    except ImportError:
        lameenc = None
    if lameenc is not None:
        encoder = lameenc.Encoder()
        encoder.set_bit_rate(bitrate)
        encoder.set_in_sample_rate(sample_rate)
        encoder.set_channels(channels)
        encoder.set_quality(2)
        data = encoder.encode(pcm) + encoder.flush()
        Path(path).write_bytes(bytes(data))
        return
    ffmpeg = require_ffmpeg()
    cmd = [
        ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
        "-f", "s16le", "-ar", str(sample_rate), "-ac", str(channels), "-i", "pipe:0",
        "-codec:a", "libmp3lame", "-b:a", f"{bitrate}k", str(path),
    ]
    proc = subprocess.run(cmd, input=pcm, capture_output=True, **_POPEN_FLAGS)
    if proc.returncode != 0:
        raise AudioError("No se pudo codificar MP3: " + proc.stderr.decode("utf-8", errors="replace")[-300:])


def write_audio(path: Path, audio: np.ndarray, fmt: str, sample_rate: int = SAMPLE_RATE) -> None:
    fmt = fmt.lower()
    if fmt == "wav":
        write_wav(path, audio, sample_rate)
    elif fmt == "flac":
        write_flac(path, audio, sample_rate)
    elif fmt == "mp3":
        write_mp3(path, audio, sample_rate)
    else:
        raise AudioError(f"Formato no soportado: {fmt}")


def compute_peaks(audio: np.ndarray, sample_rate: int = SAMPLE_RATE, per_second: int = PEAKS_PER_SECOND) -> str:
    """Pico absoluto por tramo (uint8 0-255), codificado en base64."""
    mono = np.max(np.abs(np.atleast_2d(audio)), axis=0)
    bucket = max(1, sample_rate // per_second)
    count = max(1, math.ceil(mono.size / bucket))
    padded = np.zeros(count * bucket, dtype=np.float32)
    padded[: mono.size] = mono
    peaks = padded.reshape(count, bucket).max(axis=1)
    encoded = np.round(np.clip(peaks, 0.0, 1.0) * 255.0).astype(np.uint8)
    return base64.b64encode(encoded.tobytes()).decode("ascii")


def db_to_gain(db: float) -> float:
    return float(10.0 ** (db / 20.0))


def peak_normalize_set(stems: dict[str, np.ndarray], ceiling: float = 0.98) -> dict[str, np.ndarray]:
    """Si alguna pista (o su suma) pasa del techo, baja TODAS por igual para no saturar
    y conservar el balance original entre instrumentos."""
    if not stems:
        return stems
    peak = max(float(np.max(np.abs(a))) for a in stems.values())
    total = np.sum(list(stems.values()), axis=0)
    peak = max(peak, float(np.max(np.abs(total))))
    if peak <= ceiling or peak == 0:
        return stems
    scale = ceiling / peak
    return {name: (audio * scale).astype(np.float32) for name, audio in stems.items()}
