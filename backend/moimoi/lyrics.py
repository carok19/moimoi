"""Transcripción de la letra (opcional) con faster-whisper sobre la pista de voz separada.

Se instala aparte: `pip install faster-whisper`. La primera vez descarga el modelo
(MOIMOI_WHISPER_MODEL, por defecto "small", ~460 MB).
"""

from __future__ import annotations

import os
import threading
from typing import Callable

import numpy as np
from scipy import signal

from .separation.base import Cancelled

_model = None
_model_name = None
_lock = threading.Lock()


def available() -> bool:
    try:
        import faster_whisper  # noqa: F401
    except ImportError:
        return False
    return True


def _load(name: str):
    global _model, _model_name
    with _lock:
        if _model is not None and _model_name == name:
            return _model
        from faster_whisper import WhisperModel

        device, compute = "cpu", "int8"
        try:
            import torch

            if torch.cuda.is_available():
                device, compute = "cuda", "float16"
        except ImportError:
            pass
        _model = WhisperModel(name, device=device, compute_type=compute)
        _model_name = name
        return _model


def _split_line(words: list[dict], max_words: int = 9) -> list[list[dict]]:
    """Parte segmentos largos en renglones cortos (mejor para leer mientras suena)."""
    lines, current = [], []
    for word in words:
        current.append(word)
        text = word["text"].strip()
        pause_after = False
        if len(current) >= max_words or text.endswith((".", ",", "?", "!", ";")):
            pause_after = True
        if pause_after and len(current) >= 3:
            lines.append(current)
            current = []
    if current:
        if lines and len(current) < 3:
            lines[-1].extend(current)
        else:
            lines.append(current)
    return lines


def transcribe(vocals: np.ndarray, sample_rate: int, progress: Callable[[float, str], None],
               language: str | None = None, should_cancel: Callable[[], bool] = lambda: False) -> dict:
    name = os.environ.get("MOIMOI_WHISPER_MODEL", "small")
    progress(0.02, f"Cargando el modelo de transcripción ({name})…")
    model = _load(name)
    mono = np.asarray(vocals, dtype=np.float32)
    if mono.ndim == 2:
        mono = mono.mean(axis=0)
    audio16 = signal.resample_poly(mono, 160, sample_rate // 100).astype(np.float32)
    duration = audio16.size / 16000
    segments, info = model.transcribe(
        audio16, language=language or None, word_timestamps=True, vad_filter=True, beam_size=5,
        condition_on_previous_text=False,
    )
    lines = []
    for seg in segments:
        if should_cancel():
            raise Cancelled()
        progress(min(0.98, 0.05 + 0.93 * seg.end / max(duration, 1)), "Transcribiendo la letra…")
        words = [{"start": round(w.start, 2), "end": round(w.end, 2), "text": w.word}
                 for w in (seg.words or []) if w.word.strip()]
        if not words:
            lines.append({"start": round(seg.start, 2), "end": round(seg.end, 2), "text": seg.text.strip(),
                          "words": []})
            continue
        for chunk in _split_line(words):
            lines.append({
                "start": chunk[0]["start"], "end": chunk[-1]["end"],
                "text": "".join(w["text"] for w in chunk).strip(), "words": chunk,
            })
    return {"language": info.language, "model": name, "lines": lines, "edited": False}
