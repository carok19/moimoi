"""Cambiar la velocidad y el tono sin afectar el otro.

Es el mismo método que usa la app del celular (com.moimoi.local.Stretch), así la computadora y el
celular exportan igual. Vocoder de fase de 4096 muestras con:

- bloqueo de fase alrededor de los picos del espectro (Laroche y Dolson), que evita el sonido
  "de lata" de un vocoder simple;
- los golpes (batería, ataques) se buscan antes en toda la pista y alrededor de cada uno no se
  estira (el estiramiento se reparte entre golpes): así caen justo en su lugar y no se
  "borronean"; en el golpe, las frecuencias que suben vuelven a la fase original, sin cortar las
  notas que siguen sonando;
- todos los canales con los mismos cuadros y golpes (un canal que es copia de otro queda igual).

El tono se cambia estirando el tiempo y después remuestreando. A diferencia de Rubber Band en
tiempo real (pedalboard.time_stretch), no se corre con el tiempo: la música queda alineada con el
click y la guía de principio a fin.
"""

from __future__ import annotations

from fractions import Fraction
from typing import Callable

import numpy as np
from scipy import signal

N = 4096
HS = 1024  # salto de síntesis
BINS = N // 2 + 1
OLA_GAIN = 1.5  # suma de las ventanas de Hann al cuadrado con salto N/4

ONSET_FFT = 2048
ONSET_HOP = 256


def needed(tempo: float, semitones: float) -> bool:
    return abs(tempo - 1.0) >= 1e-3 or abs(semitones) >= 1e-3


def output_frames(frames: int, tempo: float) -> int:
    return int(np.floor(frames / tempo + 0.5))


def _hann(n: int) -> np.ndarray:
    return (0.5 - 0.5 * np.cos(2 * np.pi * np.arange(n) / n)).astype(np.float32)


def _read(audio: np.ndarray, start: int, count: int) -> np.ndarray:
    """audio[:, start:start+count] con ceros fuera."""
    channels, n = audio.shape
    out = np.zeros((channels, count), dtype=np.float32)
    lo, hi = max(0, start), min(n, start + count)
    if hi > lo:
        out[:, lo - start: hi - start] = audio[:, lo:hi]
    return out


# ---- golpes ----------------------------------------------------------------------------------


def find_onsets(audio: np.ndarray) -> np.ndarray:
    """Dónde empiezan los golpes (en muestras), buscados en toda la pista antes de estirar."""
    mono = audio.mean(axis=0).astype(np.float32)
    frames = mono.size
    count = frames // ONSET_HOP + 1
    window = _hann(ONSET_FFT)
    padded = np.concatenate([np.zeros(ONSET_FFT // 2, np.float32), mono, np.zeros(ONSET_FFT, np.float32)])
    df = np.zeros(count)
    block = 1024
    prev_tail = None  # energía de los 4 cuadros anteriores al bloque
    for b0 in range(0, count, block):
        b1 = min(count, b0 + block)
        idx = (np.arange(b0, b1) * ONSET_HOP)[:, None] + np.arange(ONSET_FFT)[None, :]
        spec = np.fft.rfft(padded[idx] * window[None, :], axis=1)
        energy = (spec.real ** 2 + spec.imag ** 2).astype(np.float32)
        full = energy if prev_tail is None else np.vstack([prev_tail, energy])
        offset = 0 if prev_tail is None else prev_tail.shape[0]
        for j in range(b0, b1):
            if j < 4:
                continue
            e = full[offset + j - b0]
            before = full[offset + j - b0 - 4]
            floor = max(float(e.max()) * 1e-6, 1e-7)
            considered = e[4:] > floor
            n_considered = int(considered.sum())
            if n_considered > 20:
                df[j] = int((considered & (e[4:] > 2 * before[4:])).sum()) / n_considered
        prev_tail = full[-4:]
    onsets = []
    last = -(1 << 40)
    for j in range(1, count - 1):
        if df[j] > 0.35 and df[j] >= df[j - 1] and df[j] > df[j + 1]:
            at = _refine(mono, j * ONSET_HOP)
            if at - last >= 1024:
                onsets.append(at)
                last = at
    return np.array(onsets, dtype=np.int64)


def _refine(mono: np.ndarray, center: int) -> int:
    """El comienzo del golpe: donde la energía (ventanas de 64) supera de golpe al máximo de los
    ~15 ms anteriores (las ondulaciones de un acorde no lo hacen)."""
    lo, hi, win, step, memory = -1088, 1216, 64, 16, 40
    n = hi - lo + win
    x = _read(mono[None, :], center + lo, n)[0].astype(np.float64)
    count = (n - win) // step + 1
    energy = np.array([np.mean(x[q * step: q * step + win] ** 2) for q in range(count)]) + 1e-12
    best, at = 0.0, center
    for q in range(memory, count):
        score = energy[q] / energy[q - memory: q].max()
        if score > best:
            best, at = score, center + lo + q * step + win - step
    return int(at)


class TimeMap:
    """De la salida del vocoder a la entrada: alrededor de cada golpe 1 a 1 (sin estirar), con el
    golpe donde debe caer (entrada × estiramiento); entre golpes se estira lo que haga falta."""

    def __init__(self, onsets: np.ndarray, alpha: float, in_frames: int, pv_frames: int):
        anchors_u, anchors_x = [0.0], [0.0]
        regions: list[tuple[float, float]] = []
        prev_u = prev_x = 0.0
        h = N / 2
        for o in onsets:
            tau = float(o) * alpha
            xs, xe, us, ue = o - h, o + h, tau - h, tau + h
            if xs < prev_x + HS or us < prev_u + HS or xe > in_frames - HS or ue > pv_frames - HS:
                continue
            ratio = (us - prev_u) / (xs - prev_x)
            if ratio < alpha / 2 or ratio > alpha * 2:
                continue  # demasiado cerca del golpe anterior: se estira normal
            end_ratio = (pv_frames - ue) / max(1.0, in_frames - xe)
            if end_ratio < alpha / 4 or end_ratio > alpha * 4:
                continue
            anchors_u += [us, ue]
            anchors_x += [xs, xe]
            regions.append((us, ue))
            prev_u, prev_x = ue, xe
        anchors_u.append(max(float(pv_frames), prev_u + 1))
        anchors_x.append(max(float(in_frames), prev_x + 1))
        self.u = np.array(anchors_u)
        self.x = np.array(anchors_x)
        self.region_start = np.array([r[0] for r in regions])
        self.region_end = np.array([r[1] for r in regions])

    def input_at(self, out: float) -> float:
        u, x = self.u, self.x
        if out <= u[0]:
            return x[0] + (out - u[0]) * (x[1] - x[0]) / (u[1] - u[0])
        if out >= u[-1]:
            return x[-1] + (out - u[-1]) * (x[-1] - x[-2]) / (u[-1] - u[-2])
        i = int(np.searchsorted(u, out, side="right")) - 1
        return x[i] + (out - u[i]) * (x[i + 1] - x[i]) / (u[i + 1] - u[i])

    def region_at(self, out: float) -> int:
        i = int(np.searchsorted(self.region_start, out, side="right")) - 1
        if i >= 0 and out < self.region_end[i]:
            return i
        return -1


# ---- vocoder de fase ----------------------------------------------------------------------------


def _princarg(phase: np.ndarray) -> np.ndarray:
    return phase - 2 * np.pi * np.floor((phase + np.pi) / (2 * np.pi))


def _vocoder(audio: np.ndarray, alpha: float, pv_frames: int, time_map: TimeMap,
             progress: Callable[[float], None]) -> np.ndarray:
    channels = audio.shape[0]
    window = _hann(N)
    out = np.zeros((channels, pv_frames + 2 * N), dtype=np.float32)
    prev_x = None
    out_phasor = np.zeros((channels, BINS), dtype=np.complex128)
    prev_energy = None
    reference = None
    region = -1
    omega = 2 * np.pi * np.arange(BINS) / N
    prev_start = None
    total_frames = (pv_frames + N // 2) // HS + 1
    m = 0
    while True:
        base = m * HS - N // 2
        if base >= pv_frames:
            break
        u = m * float(HS)
        start = int(np.floor(time_map.input_at(u) + 0.5)) - N // 2
        frame = _read(audio, start, N) * window[None, :]
        spec = np.fft.rfft(frame, axis=1)  # (C, BINS)
        energy = np.sum(spec.real ** 2 + spec.imag ** 2, axis=0)
        first = prev_start is None
        dt = HS if first else max(1, start - prev_start)
        # Zona de golpe: las frecuencias que suben más de 3 dB respecto del cuadro anterior a la zona.
        reset = np.zeros(BINS, dtype=bool)
        r = time_map.region_at(u)
        if r < 0 or first:
            region = -1
        else:
            if r != region:
                region = r
                reference = prev_energy
            reset = energy > 2 * reference
        y = np.empty_like(spec)
        for c in range(channels):
            x = spec[c]
            if first:
                y[c] = x
            else:
                y[c] = _lock(x, prev_x[c], out_phasor[c], reset, omega, dt)
            mag = np.abs(y[c])
            ok = mag > 1e-12
            out_phasor[c, ok] = y[c, ok] / mag[ok]
        frames = np.fft.irfft(y, n=N, axis=1).astype(np.float32) * window[None, :]
        pos = base + N // 2  # el búfer de salida empieza N/2 antes de la muestra 0
        out[:, pos: pos + N] += frames
        prev_x = spec
        prev_energy = energy
        prev_start = start
        m += 1
        if m % 64 == 0:
            progress(min(1.0, m / total_frames))
    return out[:, N // 2: N // 2 + pv_frames] / OLA_GAIN


def _lock(x: np.ndarray, prev: np.ndarray, phasor: np.ndarray, reset: np.ndarray, omega: np.ndarray,
          dt: int) -> np.ndarray:
    """Un cuadro con bloqueo de fase: cada región alrededor de un pico gira como el pico."""
    e = x.real ** 2 + x.imag ** 2
    floor = float(e.max()) * 1e-10
    k = np.arange(1, BINS - 1)
    cond = (e[k] > floor) & (e[k] > e[k - 1]) & (e[k] >= e[k + 1])
    cond &= (k < 2) | (e[k] > e[np.maximum(k - 2, 0)])
    cond &= (k + 2 >= BINS) | (e[k] >= e[np.minimum(k + 2, BINS - 1)])
    peaks = k[cond]
    if peaks.size == 0:
        return x.copy()
    ends = np.empty(peaks.size, dtype=np.int64)
    ends[:-1] = (peaks[:-1] + peaks[1:]) // 2 + 1
    ends[-1] = BINS
    lengths = np.diff(np.concatenate([[0], ends]))
    xp, pp, op = x[peaks], prev[peaks], phasor[peaks]
    mag, prev_mag = np.abs(xp), np.abs(pp)
    valid = (~reset[peaks]) & (prev_mag > 1e-9) & (mag > 1e-12) & (op != 0)
    rot = np.ones(peaks.size, dtype=np.complex128)
    if valid.any():
        w = omega[peaks[valid]]
        dev = _princarg(np.angle(xp[valid] * np.conj(pp[valid])) - w * dt)
        advance = (w + dev / dt) * HS
        new = op[valid] * np.exp(1j * advance)
        rot[valid] = new * np.conj(xp[valid] / mag[valid])
    y = x * np.repeat(rot, lengths)
    y[reset] = x[reset]
    return y


# ---- todo junto ---------------------------------------------------------------------------------


def stretch(audio: np.ndarray, tempo: float, semitones: float,
            progress: Callable[[float], None] = lambda f: None) -> np.ndarray:
    """(canales, n) a velocidad `tempo` (0.8 -> 80 %) y `semitones` de tono: (canales, n / tempo)."""
    audio = np.asarray(audio, dtype=np.float32)
    if audio.ndim == 1:
        audio = audio[None, :]
    if not needed(tempo, semitones):
        return audio
    in_frames = audio.shape[1]
    total = output_frames(in_frames, tempo)
    pitch = 2.0 ** (semitones / 12.0)
    alpha = pitch / tempo
    pv_frames = int(np.floor(in_frames * alpha + 0.5))
    onsets = find_onsets(audio)
    progress(0.1)
    time_map = TimeMap(onsets, alpha, in_frames, pv_frames)
    pv = _vocoder(audio, alpha, pv_frames, time_map, lambda f: progress(0.1 + 0.8 * f))
    if abs(pitch - 1.0) > 1e-9:
        # Remuestreo: la salida n lee la entrada en n·pitch.
        ratio = Fraction(1.0 / pitch).limit_denominator(2000)
        pv = signal.resample_poly(pv, ratio.numerator, ratio.denominator, axis=1,
                                  window=("kaiser", 8.0)).astype(np.float32)
    progress(1.0)
    out = np.zeros((audio.shape[0], total), dtype=np.float32)
    n = min(total, pv.shape[1])
    out[:, :n] = pv[:, :n]
    return out
