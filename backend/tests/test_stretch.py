"""Cambio de velocidad y tono al exportar (moimoi.stretch): tono exacto, largo exacto y los golpes
alineados de principio a fin (sin irse corriendo)."""

from __future__ import annotations

import numpy as np
import pytest

from moimoi.stretch import output_frames, stretch

SR = 44100


def _frequency(x: np.ndarray) -> float:
    spectrum = np.abs(np.fft.rfft(x * np.hanning(x.size), n=x.size * 8))
    k = int(np.argmax(spectrum))
    return k * SR / (x.size * 8)


@pytest.mark.parametrize("tempo,semitones", [(1.0, 2), (1.0, -3), (0.8, 0), (1.25, 0), (0.9, 5)])
def test_pitch_and_length(tempo, semitones):
    t = np.arange(4 * SR) / SR
    tone = (0.5 * np.sin(2 * np.pi * 440 * t)).astype(np.float32)
    audio = np.vstack([tone, tone])
    out = stretch(audio, tempo, semitones)
    assert out.shape == (2, output_frames(audio.shape[1], tempo))
    middle = out[0, out.shape[1] // 2 - 16384: out.shape[1] // 2 + 16384]
    assert _frequency(middle) == pytest.approx(440 * 2 ** (semitones / 12), abs=1.0)
    # Volumen estable y el mono sigue mono.
    body = out[0, SR // 2: -SR // 2]
    rms = np.sqrt(np.mean(body[: body.size // 2205 * 2205].reshape(-1, 2205) ** 2, axis=1))
    assert rms.std() / rms.mean() < 0.03
    assert np.allclose(out[0], out[1])


def _hits(seconds: float, every: float) -> tuple[np.ndarray, list[float]]:
    rng = np.random.default_rng(1)
    n = int(seconds * SR)
    x = np.zeros((2, n), dtype=np.float32)
    times = list(np.arange(0.25, seconds - 0.3, every))
    decay = np.exp(-np.arange(SR // 10) / (0.012 * SR))
    for t0 in times:
        s = int(t0 * SR)
        burst = (0.4 * decay * rng.standard_normal(decay.size)).astype(np.float32)
        x[0, s: s + burst.size] += burst[: n - s]
        x[1, s: s + burst.size] += 0.6 * burst[: n - s]
    t = np.arange(n) / SR
    chord = (0.1 * (np.sin(2 * np.pi * 261.6 * t) + np.sin(2 * np.pi * 329.6 * t))).astype(np.float32)
    return x + chord, times


def _hit_time(x: np.ndarray, expected: float) -> float:
    """Dónde sube más la energía (ventanas de 1 ms) a ±30 ms de donde debería estar el golpe."""
    win = 44
    env = np.sqrt(np.mean(x[: x.size // win * win].reshape(-1, win) ** 2, axis=1))
    lo, hi = int((expected - 0.03) * SR / win), int((expected + 0.03) * SR / win)
    rise = env[lo + 1: hi + 1] - env[lo - 1: hi - 1]
    return (lo + int(np.argmax(rise))) * win / SR


@pytest.mark.parametrize("tempo,semitones", [(0.9, -2), (1.15, 0), (1.0, 3)])
def test_hits_stay_on_time(tempo, semitones):
    audio, times = _hits(30.0, 0.5)
    out = stretch(audio, tempo, semitones)
    errors = [abs(_hit_time(out[0], t / tempo) - t / tempo) for t in times]
    # Cada golpe cae donde debe (Rubber Band en tiempo real se corría hasta decenas de ms).
    assert np.median(errors) < 0.003
    assert max(errors) < 0.010
    # Y al final de la canción también (sin deriva).
    assert np.median(errors[-10:]) < 0.003
