"""¿Qué instrumentos suenan en la canción y en qué partes?"""

from __future__ import annotations

import numpy as np

from .features import ANALYSIS_SR, rms_db, to_mono_22k

WINDOW_S = 0.5
HOP_S = 0.25


def _runs(active: np.ndarray, hop: float, min_len: float = 0.75, max_gap: float = 1.5) -> list[list[float]]:
    """Convierte una máscara de actividad en tramos [inicio, fin] (fusiona huecos cortos)."""
    runs: list[list[float]] = []
    start = None
    for i, on in enumerate(active):
        if on and start is None:
            start = i
        elif not on and start is not None:
            runs.append([start * hop, i * hop])
            start = None
    if start is not None:
        runs.append([start * hop, len(active) * hop])
    merged: list[list[float]] = []
    for run in runs:
        if merged and run[0] - merged[-1][1] <= max_gap:
            merged[-1][1] = run[1]
        else:
            merged.append(run)
    return [[round(a, 2), round(b + WINDOW_S - HOP_S, 2)] for a, b in merged if b - a >= min_len]


def analyze_presence(stems: dict[str, np.ndarray], sample_rate: int) -> dict:
    frame, hop = int(WINDOW_S * ANALYSIS_SR), int(HOP_S * ANALYSIS_SR)
    mono = {name: to_mono_22k(audio, sample_rate) for name, audio in stems.items()}
    length = max(len(v) for v in mono.values())
    mono = {n: np.pad(v, (0, length - len(v))) for n, v in mono.items()}
    mix = np.sum(list(mono.values()), axis=0)
    mix_db = rms_db(mix, frame, hop)
    music = mix_db > max(-55.0, float(np.max(mix_db)) - 50.0)
    music_windows = max(1, int(music.sum()))

    result: dict[str, dict] = {}
    for name, signal in mono.items():
        level = rms_db(signal, frame, hop)
        # Suena si supera un piso absoluto y no queda enterrado bajo la mezcla.
        active = (level > -48.0) & (level > mix_db - 24.0) & music
        ratio = float(active.sum()) / music_windows
        rel = float(np.median((level - mix_db)[active])) if active.any() else -60.0
        if ratio < 0.03 or rel < -30:
            label = "ausente"
        elif ratio > 0.45 and rel > -14:
            label = "alta"
        elif ratio > 0.2 and rel > -20:
            label = "media"
        else:
            label = "baja"
        result[name] = {
            "presence": round(ratio, 3),
            "relativeDb": round(rel, 1),
            "level": label,
            "active": _runs(active, HOP_S),
        }
    return result
