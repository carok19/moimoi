"""Análisis musical a partir de las pistas separadas.

Produce: tempo (BPM), pulsos y compases, tonalidad (y modulaciones), afinación,
acordes con inversiones, secciones (intro/verso/coro/puente/final) y qué
instrumentos suenan en cada parte.
"""

from __future__ import annotations

from typing import Callable

import numpy as np

from .beats import analyze_rhythm
from .features import build_signals, estimate_tuning
from .harmony import analyze_harmony, compute_chromas
from .presence import analyze_presence
from .sections import analyze_sections

ANALYSIS_VERSION = 2


def analyze_song(
    stems: dict[str, np.ndarray],
    sample_rate: int,
    progress: Callable[[float, str], None] = lambda f, m: None,
) -> dict:
    progress(0.02, "Preparando el análisis…")
    sig = build_signals(stems, sample_rate)
    tuning = estimate_tuning(sig.harmonic)
    progress(0.12, "Calculando armonía…")
    treble_chroma, bass_chroma = compute_chromas(sig.treble, sig.bass, tuning)
    progress(0.4, "Detectando el pulso y el compás…")
    rhythm = analyze_rhythm(sig, treble_chroma, bass_chroma)
    progress(0.55, "Detectando acordes y tonalidad…")
    harmony = analyze_harmony(treble_chroma, bass_chroma, rhythm, sig.duration, tuning)
    progress(0.75, "Detectando las partes de la canción…")
    sections = analyze_sections(sig, rhythm, treble_chroma)
    # Un cambio de tonalidad casi siempre coincide con el comienzo de una sección.
    starts = [s["start"] for s in sections[1:]]
    for change in harmony["keyChanges"]:
        near = [t for t in starts if abs(t - change["time"]) <= 10.0]
        if near:
            change["time"] = min(near, key=lambda t: abs(t - change["time"]))
    progress(0.9, "Detectando instrumentos…")
    instruments = analyze_presence(stems, sample_rate)

    key = harmony["key"]
    summary = {
        "bpm": rhythm.get("bpm"),
        "beatsPerBar": rhythm.get("beatsPerBar", 4),
        "key": key["name"],
        "keyLabel": key["label"],
        "tonic": key["tonic"],
        "mode": key["mode"],
        "a4": harmony["tuning"]["a4"],
        "instruments": [name for name, info in instruments.items() if info["level"] != "ausente"],
    }
    return {
        "version": ANALYSIS_VERSION,
        "duration": round(sig.duration, 3),
        "tempo": {
            "bpm": rhythm.get("bpm"),
            "beatsPerBar": rhythm.get("beatsPerBar", 4),
            "steady": rhythm.get("steady", False),
            "confidence": rhythm.get("confidence", 0.0),
            "meterConfidence": rhythm.get("meterConfidence", 0.0),
            "segments": rhythm.get("segments", []),
        },
        "beats": rhythm.get("beats", []),
        "downbeats": rhythm.get("downbeats", []),
        "onset": rhythm.get("onset"),
        "key": key,
        "keyStart": harmony.get("keyStart"),
        "keyChanges": harmony["keyChanges"],
        "tuning": harmony["tuning"],
        "chords": harmony["chords"],
        "sections": sections,
        "instruments": instruments,
        "summary": summary,
    }


__all__ = ["analyze_song", "ANALYSIS_VERSION"]
