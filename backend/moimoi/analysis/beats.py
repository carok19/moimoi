"""Tempo (BPM), pulsos, compás y primer tiempo de cada compás."""

from __future__ import annotations

import numpy as np

from .features import ANALYSIS_SR, HOP, SongSignals, frames_to_time, normalize, onset_envelope, rms_db


def _unit(v: np.ndarray) -> np.ndarray:
    v = np.asarray(v, dtype=float)
    top = np.percentile(v, 99) if v.size else 0.0
    return np.clip(v / top, 0, 2) if top > 1e-9 else v * 0.0


def _is_audible(y: np.ndarray | None, threshold_db: float = -45.0) -> bool:
    if y is None or y.size == 0:
        return False
    return float(np.percentile(rms_db(y, 2048, 2048), 90)) > threshold_db


def fit_steady_grid(beat_times: np.ndarray) -> dict | None:
    """Si los pulsos siguen un tempo constante (canción grabada con click), ajusta una grilla
    perfecta. Devuelve None si el tempo varía (p. ej. una grabación en vivo)."""
    if beat_times.size < 12:
        return None
    period = float(np.median(np.diff(beat_times)))
    if period <= 0:
        return None
    # Índice de cada pulso contado de a intervalos (robusto a un período estimado algo sesgado:
    # redondear (t - t0) / período acumularía el error a lo largo de la canción).
    k = np.concatenate([[0], np.cumsum(np.maximum(1, np.round(np.diff(beat_times) / period)))])
    mask = np.ones(beat_times.size, dtype=bool)
    slope, intercept = period, float(beat_times[0])
    for _ in range(4):
        if mask.sum() < 8:
            return None
        design = np.vstack([k[mask], np.ones(int(mask.sum()))]).T
        (slope, intercept), *_ = np.linalg.lstsq(design, beat_times[mask], rcond=None)
        resid = beat_times - (slope * k + intercept)
        limit = max(0.03, 3.0 * float(np.std(resid[mask])))
        mask = np.abs(resid) < limit
    resid = beat_times - (slope * k + intercept)
    rms = float(np.sqrt(np.mean(resid[mask] ** 2)))
    coverage = float(mask.mean())
    # También hay que descartar derivas lentas: comparamos el tempo de cada mitad.
    half = beat_times.size // 2
    d1, d2 = np.median(np.diff(beat_times[:half])), np.median(np.diff(beat_times[half:]))
    drift = abs(d1 - d2) / period
    steady = rms < 0.02 and coverage > 0.85 and drift < 0.02
    return {"period": float(slope), "offset": float(intercept), "k0": int(k[0]), "k1": int(k[-1]),
            "rms": rms, "coverage": coverage, "steady": steady}


def attack_offset(beat_times: np.ndarray, y: np.ndarray) -> float:
    """Corrección fina de fase: el detector de ataques por espectrograma marca el pulso unos
    milisegundos tarde. Buscamos el inicio real del golpe cerca de cada pulso (envolvente de
    alta resolución) y devolvemos el desplazamiento mediano, en segundos."""
    if beat_times.size < 8 or y is None or not np.any(np.abs(y) > 1e-4):
        return 0.0
    hop = 32
    frame = 64
    n = 1 + (y.size - frame) // hop
    if n < 10:
        return 0.0
    idx = np.arange(frame)[None, :] + hop * np.arange(n)[:, None]
    energy = np.log(np.mean(y[idx].astype(np.float64) ** 2, axis=1) + 1e-10)
    rise = np.maximum(np.diff(energy, prepend=energy[0]), 0)
    step = hop / ANALYSIS_SR
    offsets = []
    for t in beat_times:
        lo, hi = int((t - 0.08) / step), int((t + 0.03) / step)
        if lo < 1 or hi >= rise.size:
            continue
        window = rise[lo:hi]
        peak = int(np.argmax(window))
        if window[peak] > 1.0:  # subida de energía clara (~4 dB en 3 ms)
            offsets.append((lo + peak) * step + frame / 2 / ANALYSIS_SR - t)
    if len(offsets) < max(6, beat_times.size // 5):
        return 0.0
    offset = float(np.median(offsets))
    return offset if abs(offset) < 0.07 else 0.0


def estimate_meter_and_downbeats(
    beat_frames: np.ndarray,
    low_env: np.ndarray,
    treble_chroma: np.ndarray,
    bass_chroma: np.ndarray,
) -> tuple[int, int, float]:
    """Elige compás (3 o 4 tiempos) y en qué pulso cae el "1".

    Señales usadas en cada pulso: golpes graves (bombo/bajo) y cambios de armonía
    (los acordes suelen cambiar al inicio del compás).
    Devuelve (tiempos_por_compás, índice_del_primer_downbeat, confianza).
    """
    import librosa

    n = beat_frames.size
    if n < 8:
        return 4, 0, 0.0
    # Golpe grave alrededor de cada pulso.
    low = np.array([
        low_env[max(0, f - 2): f + 3].max() if f < low_env.size else 0.0 for f in beat_frames
    ])
    # Cambio de armonía entre el tramo que termina y el que empieza en cada pulso.
    sync_t = librosa.util.sync(treble_chroma, beat_frames, aggregate=np.median)
    sync_b = librosa.util.sync(bass_chroma, beat_frames, aggregate=np.median)

    def change(sync: np.ndarray) -> np.ndarray:
        norm = sync / np.maximum(np.linalg.norm(sync, axis=0, keepdims=True), 1e-9)
        # sync[:, i] es el tramo anterior al pulso i (el 0 es antes del primer pulso).
        before, after = norm[:, :n], norm[:, 1: n + 1]
        return 1.0 - np.sum(before * after, axis=0)

    feature = 0.8 * normalize(low) + 1.0 * normalize(change(sync_t)) + 0.7 * normalize(change(sync_b))
    scores = {}
    for meter in (4, 3):
        for phase in range(meter):
            on = np.zeros(n, dtype=bool)
            on[phase::meter] = True
            score = float(feature[on].mean() - feature[~on].mean())
            scores[(meter, phase)] = score + (0.12 if meter == 4 else 0.0)
    (meter, phase), best = max(scores.items(), key=lambda item: item[1])
    others = sorted(v for k, v in scores.items() if k != (meter, phase))
    confidence = float(np.clip((best - others[-1]) / 0.5, 0, 1)) if others else 0.0
    return meter, phase, confidence


def _at_beats(env: np.ndarray, beat_frames: np.ndarray) -> np.ndarray:
    return np.array([env[max(0, f - 2): f + 3].max() if f < env.size else 0.0 for f in beat_frames])


def half_time_phase(beat_frames: np.ndarray, kick_env: np.ndarray, snare_env: np.ndarray) -> int | None:
    """Detecta el error típico de marcar el doble del tempo (una balada a 72 leída como 144).

    En un ritmo de banda cada pulso real lleva bombo o redoblante (1 y 3 bombo, 2 y 4
    redoblante). Si esos golpes caen solo en pulsos alternos y en los otros suena apenas
    el hi-hat, el detector está contando corcheas: devolvemos la fase (0/1) de los pulsos
    fuertes. None si el tempo parece correcto.
    """
    if beat_frames.size < 16:
        return None
    accents = _unit(_at_beats(kick_env, beat_frames)) + _unit(_at_beats(snare_env, beat_frames))
    even, odd = float(accents[0::2].mean()), float(accents[1::2].mean())
    strong, weak = max(even, odd), min(even, odd)
    if strong > 0 and weak < 0.35 * strong:
        return 0 if even >= odd else 1
    return None


def analyze_rhythm(sig: SongSignals, treble_chroma: np.ndarray, bass_chroma: np.ndarray) -> dict:
    import librosa

    env = _unit(onset_envelope(sig.mix))
    drums_ok = _is_audible(sig.drums)
    if drums_ok:
        env = 0.5 * env + _unit(onset_envelope(sig.drums))
    if not np.any(env > 0):
        return {"bpm": None, "beats": [], "downbeats": [], "beatsPerBar": 4, "steady": False,
                "confidence": 0.0}

    tempo_fn = getattr(librosa.feature, "tempo", None) or librosa.beat.tempo
    tempo = float(np.atleast_1d(tempo_fn(onset_envelope=env, sr=ANALYSIS_SR, hop_length=HOP,
                                         start_bpm=120, max_tempo=240))[0])
    _, beat_frames = librosa.beat.beat_track(
        onset_envelope=env, sr=ANALYSIS_SR, hop_length=HOP, bpm=tempo, tightness=120,
        trim=False, units="frames",
    )
    beat_frames = np.asarray(beat_frames, dtype=int)

    # Quitar pulsos en silencio (antes de que empiece o después de que termine la música).
    loud = rms_db(sig.mix, 2048, HOP)
    audible_frames = np.nonzero(loud > max(-50.0, float(np.max(loud)) - 45.0))[0]
    if audible_frames.size:
        first, last = audible_frames[0] - 4, audible_frames[-1] + 4
        beat_frames = beat_frames[(beat_frames >= first) & (beat_frames <= last)]
    if beat_frames.size < 4:
        return {"bpm": round(tempo, 1) if tempo else None, "beats": [], "downbeats": [], "beatsPerBar": 4,
                "steady": False, "confidence": 0.0}

    if drums_ok and beat_frames.size >= 16:
        period_s = float(np.median(np.diff(beat_frames))) * HOP / ANALYSIS_SR
        if 60.0 / (2 * period_s) >= 50:  # no bajar de 50 BPM
            kick_env = onset_envelope(sig.drums, fmax=160.0)
            snare_env = onset_envelope(sig.drums, fmin=160.0, fmax=3000.0)
            phase = half_time_phase(beat_frames, kick_env, snare_env)
            if phase is not None:
                beat_frames = beat_frames[phase::2]

    # Confianza del pulso: cuánto más fuerte es el ataque en los pulsos que en el resto.
    on_beat = env[np.clip(beat_frames, 0, env.size - 1)].mean()
    pulse_confidence = float(np.clip((on_beat / max(env.mean(), 1e-9) - 1.0) / 1.5, 0, 1))

    beat_times = frames_to_time(beat_frames)
    beat_times = beat_times + attack_offset(beat_times, sig.drums if drums_ok else sig.mix)
    grid = fit_steady_grid(beat_times)
    steady = bool(grid and grid["steady"])
    if steady:
        k = np.arange(grid["k0"], grid["k1"] + 1)
        beat_times = grid["offset"] + grid["period"] * k
        beat_times = beat_times[(beat_times >= 0) & (beat_times < sig.duration)]
        beat_frames = np.round(beat_times * ANALYSIS_SR / HOP).astype(int)
        bpm = 60.0 / grid["period"]
    else:
        bpm = 60.0 / float(np.median(np.diff(beat_times)))

    low_source = sig.drums if drums_ok else sig.mix
    low_env = onset_envelope(low_source, fmax=160.0)
    if sig.bass is not None:
        low_env = _unit(low_env) + 0.6 * _unit(onset_envelope(sig.bass, fmax=320.0))
    meter, phase, meter_conf = estimate_meter_and_downbeats(beat_frames, low_env, treble_chroma, bass_chroma)
    downbeats = beat_times[phase::meter]

    return {
        "bpm": round(float(bpm), 1),
        "beats": [round(float(t), 3) for t in beat_times],
        "downbeats": [round(float(t), 3) for t in downbeats],
        "downbeatPhase": int(phase),
        "beatsPerBar": int(meter),
        "steady": steady,
        "confidence": round(pulse_confidence, 2),
        "meterConfidence": round(meter_conf, 2),
    }
