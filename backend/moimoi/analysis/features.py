"""Extracción de características de audio compartidas por los análisis."""

from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np
from scipy import signal

ANALYSIS_SR = 22050
HOP = 512


def to_mono_22k(audio: np.ndarray, sample_rate: int) -> np.ndarray:
    mono = np.asarray(audio, dtype=np.float32)
    if mono.ndim == 2:
        mono = mono.mean(axis=0)
    if sample_rate == ANALYSIS_SR:
        return mono.astype(np.float32)
    if sample_rate == 2 * ANALYSIS_SR:
        return signal.resample_poly(mono, 1, 2).astype(np.float32)
    from math import gcd

    g = gcd(int(sample_rate), ANALYSIS_SR)
    return signal.resample_poly(mono, ANALYSIS_SR // g, int(sample_rate) // g).astype(np.float32)


def rms_db(x: np.ndarray, frame: int, hop: int) -> np.ndarray:
    """RMS por ventana en dBFS (piso de -100 dB)."""
    if x.size < frame:
        x = np.pad(x, (0, frame - x.size))
    n = 1 + (x.size - frame) // hop
    idx = np.arange(frame)[None, :] + hop * np.arange(n)[:, None]
    windows = x[idx]
    rms = np.sqrt(np.mean(windows.astype(np.float64) ** 2, axis=1))
    return 20 * np.log10(np.maximum(rms, 1e-5))


def normalize(v: np.ndarray) -> np.ndarray:
    v = np.asarray(v, dtype=float)
    std = v.std()
    return (v - v.mean()) / std if std > 1e-9 else v * 0.0


@dataclass
class SongSignals:
    """Señales mono a 22.05 kHz derivadas de las pistas separadas."""

    duration: float
    mix: np.ndarray
    stems: dict[str, np.ndarray]
    #: Parte armónica sin batería (acompañamiento + un poco de voz): tonalidad.
    harmonic: np.ndarray
    #: Instrumentos armónicos sin bajo (+ un poco de voz): acordes.
    treble: np.ndarray
    #: Voz (para detectar dónde se canta).
    vocals: np.ndarray | None
    #: Bajo (para la nota grave del acorde).
    bass: np.ndarray | None
    #: Percusión (para el pulso).
    drums: np.ndarray | None
    extra: dict = field(default_factory=dict)

    @property
    def n_frames(self) -> int:
        return 1 + len(self.mix) // HOP


def build_signals(stems: dict[str, np.ndarray], sample_rate: int) -> SongSignals:
    import librosa

    mono = {name: to_mono_22k(audio, sample_rate) for name, audio in stems.items()}
    length = max(len(v) for v in mono.values())
    for name, value in mono.items():
        if len(value) < length:
            mono[name] = np.pad(value, (0, length - len(value)))
    mix = np.sum(list(mono.values()), axis=0).astype(np.float32)

    vocals = mono.get("vocals")
    drums = mono.get("drums")
    bass = mono.get("bass")
    treble_parts = [mono[n] for n in ("guitar", "piano", "other") if n in mono]
    if "instrumental" in mono:
        # 2 pistas: el acompañamiento incluye la batería -> nos quedamos con la parte armónica.
        inst_h, inst_p = librosa.effects.hpss(mono["instrumental"], margin=(1.0, 3.0))
        treble_parts.append(inst_h)
        if drums is None:
            drums = inst_p.astype(np.float32)
    if not treble_parts and bass is None:
        treble_parts = [librosa.effects.harmonic(mix, margin=3.0)]
    zeros = np.zeros(length, dtype=np.float32)
    treble = np.sum(treble_parts, axis=0).astype(np.float32) if treble_parts else zeros
    if bass is None:
        sos = signal.butter(4, 180, "lowpass", fs=ANALYSIS_SR, output="sos")
        bass = signal.sosfiltfilt(sos, treble).astype(np.float32)
        sos_hp = signal.butter(2, 150, "highpass", fs=ANALYSIS_SR, output="sos")
        treble = signal.sosfiltfilt(sos_hp, treble).astype(np.float32)
    if vocals is not None:
        # La melodía ayuda cuando el acompañamiento es escaso, pero con poco peso.
        treble = treble + 0.35 * vocals
    harmonic = (treble + bass).astype(np.float32)
    return SongSignals(
        duration=length / ANALYSIS_SR,
        mix=mix,
        stems=mono,
        harmonic=harmonic,
        treble=treble.astype(np.float32),
        vocals=vocals,
        bass=bass,
        drums=drums,
    )


def estimate_tuning(y: np.ndarray) -> float:
    """Desvío de afinación en fracciones de semitono (-0.5 a 0.5) respecto de La 440."""
    import librosa

    if not np.any(np.abs(y) > 1e-4):
        return 0.0
    # Con 1 minuto alcanza y es mucho más rápido.
    segment = y[: ANALYSIS_SR * 90]
    try:
        return float(librosa.estimate_tuning(y=segment, sr=ANALYSIS_SR, bins_per_octave=12))
    except Exception:  # noqa: BLE001 - señal muda o demasiado corta
        return 0.0


def chroma(y: np.ndarray, tuning: float, fmin_note: str, n_octaves: int) -> np.ndarray:
    """Cromagrama CQT (12 x frames) sin normalizar, con compresión logarítmica."""
    import librosa

    fmin = librosa.note_to_hz(fmin_note)
    max_octaves = int(np.floor(np.log2((ANALYSIS_SR / 2) / fmin))) - 0
    n_octaves = max(1, min(n_octaves, max_octaves))
    cqt = np.abs(librosa.cqt(
        y, sr=ANALYSIS_SR, hop_length=HOP, fmin=fmin, n_bins=12 * 3 * n_octaves,
        bins_per_octave=36, tuning=tuning,
    ))
    cqt = np.log1p(100.0 * cqt)
    return librosa.feature.chroma_cqt(
        C=cqt, sr=ANALYSIS_SR, hop_length=HOP, fmin=fmin, bins_per_octave=36, n_octaves=n_octaves,
        norm=None,
    )


def onset_envelope(y: np.ndarray, fmax: float | None = None) -> np.ndarray:
    import librosa

    kwargs = {"n_mels": 128}
    if fmax is not None:
        # Banda grave: pocas bandas mel (con 128 quedarían filtros vacíos).
        kwargs.update(fmax=fmax, n_mels=16, n_fft=4096)
    return librosa.onset.onset_strength(y=y, sr=ANALYSIS_SR, hop_length=HOP, aggregate=np.median, **kwargs)


def frames_to_time(frames) -> np.ndarray:
    return np.asarray(frames, dtype=float) * HOP / ANALYSIS_SR


def time_to_frames(times) -> np.ndarray:
    return np.round(np.asarray(times, dtype=float) * ANALYSIS_SR / HOP).astype(int)
