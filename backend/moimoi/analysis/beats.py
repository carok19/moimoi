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
    meters: tuple[int, ...] = (4, 3),
) -> tuple[int, int, float]:
    """Elige compás (3 o 4 tiempos, o solo los de `meters`) y en qué pulso cae el "1".

    Señales usadas en cada pulso: golpes graves (bombo/bajo) y cambios de armonía
    (los acordes suelen cambiar al inicio del compás).
    Devuelve (tiempos_por_compás, índice_del_primer_downbeat, confianza).
    """
    import librosa

    n = beat_frames.size
    if n < 8:
        return meters[0], 0, 0.0
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
    for meter in meters:
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


def counted_half(beat_frames: np.ndarray, kick_env: np.ndarray, snare_env: np.ndarray) -> bool:
    """El error contrario a half_time_phase: el detector contó la mitad del tempo (140 leído como
    70). Pasa cuando el bombo y el redoblante se turnan entre los pulsos y el medio de cada pulso
    (uno cae en los pulsos y el otro justo entre dos): son el 1-3 y el 2-4 de un pulso al doble."""
    if beat_frames.size < 16:
        return False
    starts = beat_frames[:-1]
    mids = ((starts + beat_frames[1:]) // 2).astype(int)
    n = mids.size
    snare = _unit(np.concatenate([_at_beats(snare_env, starts), _at_beats(snare_env, mids)]))
    kick = _unit(np.concatenate([_at_beats(kick_env, starts), _at_beats(kick_env, mids)]))
    snare_on, snare_mid = snare[:n].mean(), snare[n:].mean()
    kick_on, kick_mid = kick[:n].mean(), kick[n:].mean()
    # Uno de los dos cambia claramente de lugar y el otro lo acompaña (los platillos suenan en
    # los dos lugares y le bajan el contraste al redoblante).
    return bool((snare_mid > 1.5 * snare_on and kick_on > 1.2 * kick_mid)
                or (kick_on > 1.5 * kick_mid and snare_mid > 1.2 * snare_on)
                or (kick_mid > 1.5 * kick_on and snare_on > 1.2 * snare_mid)
                or (snare_on > 1.5 * snare_mid and kick_mid > 1.2 * kick_on))


#: Tempos posibles del mapa de tempo (BPM).
TEMPO_MIN, TEMPO_MAX = 55.0, 200.0
#: Un tramo de tempo dura por lo menos esto (segundos); si no, se junta con el vecino.
TEMPO_MIN_SEGMENT = 16.0


def tempo_columns(env: np.ndarray, step: int) -> tuple[np.ndarray, np.ndarray]:
    """Autocorrelación de ventanas de 8 s cada `step` cuadros, como el tempograma de librosa
    (ventana de Hann, relleno en rampa hasta 0 en los extremos y cada columna dividida por su
    máximo). Devuelve (cuadros centrales, [columna, retardo])."""
    fps = ANALYSIS_SR / HOP
    win = int(np.floor(8.0 * fps))
    half = win // 2
    n = env.size
    padded = np.zeros(n + 2 * half)
    ramp = np.arange(half) / half
    padded[:half] = env[0] * ramp
    padded[n + half:] = (env[-1] * ramp)[::-1]
    padded[half:half + n] = env
    window = 0.5 - 0.5 * np.cos(2 * np.pi * np.arange(win) / win)
    n_fft = 1 << int(np.ceil(np.log2(2 * win - 1)))
    centers = np.arange(0, n, step)
    out = np.zeros((centers.size, win))
    for j, t in enumerate(centers):
        spectrum = np.fft.rfft(padded[t:t + win] * window, n_fft)
        ac = np.fft.irfft(np.abs(spectrum) ** 2, n_fft)[:win]
        peak = float(np.max(np.abs(ac)))
        if peak > 0:
            out[j] = ac / peak
    return centers, out


def tempo_segments(env: np.ndarray, start_bpm: float = 120.0) -> list[tuple[int, int, float]]:
    """Mapa de tempo: tramos (cuadro inicial, cuadro final, período en cuadros) donde el tempo
    no cambia, para canciones con cambios de tempo (popurrís, partes más lentas).

    Cada segundo se mide la periodicidad de los ataques (autocorrelación, reforzada con la del
    doble del período: el bombo suele marcar cada dos pulsos) con la preferencia por tempos
    cercanos a 120 de librosa, y un Viterbi elige el camino más estable: cambiar de tempo cuesta,
    y cambiar al doble o a la mitad (un cambio de "feel", no de tempo) cuesta más todavía.
    """
    fps = ANALYSIS_SR / HOP
    n = env.size
    step = max(1, int(round(fps)))
    centers, ac = tempo_columns(env, step)
    win = ac.shape[1]
    combed = ac.copy()
    half = win // 2
    combed[:, 1:half] += 0.5 * ac[:, 2:2 * half:2]
    combed /= 1.5
    lags = np.arange(1, win)
    bpm = 60.0 * fps / lags
    keep = (bpm >= TEMPO_MIN) & (bpm <= TEMPO_MAX)
    lags, bpm = lags[keep], bpm[keep]
    prior = -0.5 * (np.log2(bpm) - np.log2(start_bpm)) ** 2
    score = np.log1p(1e6 * np.maximum(combed[:, lags], 0.0)) + prior
    m = lags.size
    ratio = lags[None, :] / lags[:, None]
    cost = np.full((m, m), 6.0)
    cost[np.abs(np.log2(ratio)) > np.log2(1.8)] = 18.0
    idx = np.arange(m)
    cost[idx, idx] = 0.0
    cost[idx[:-1], idx[1:]] = 0.7
    cost[idx[1:], idx[:-1]] = 0.7
    acc = score[0].copy()
    back = np.zeros((centers.size, m), dtype=int)
    for t in range(1, centers.size):
        candidates = acc[:, None] - cost
        back[t] = np.argmax(candidates, axis=0)
        acc = candidates[back[t], idx] + score[t]
    path = np.zeros(centers.size, dtype=int)
    path[-1] = int(np.argmax(acc))
    for t in range(centers.size - 1, 0, -1):
        path[t - 1] = back[t, path[t]]
    lag_path = lags[path].astype(float)

    # Tramos donde el período no se aleja más de un cuadro de la mediana del tramo.
    runs: list[list[int]] = []
    start = 0
    for i in range(1, lag_path.size + 1):
        if i == lag_path.size or abs(lag_path[i] - np.median(lag_path[start:i])) > 1:
            runs.append([start, i])
            start = i
    median = lambda r: float(np.median(lag_path[r[0]:r[1]]))  # noqa: E731
    min_columns = TEMPO_MIN_SEGMENT * fps / step
    while len(runs) > 1:
        lengths = [b - a for a, b in runs]
        k = int(np.argmin(lengths))
        if lengths[k] >= min_columns:
            break
        here = median(runs[k])
        left = abs(median(runs[k - 1]) - here) if k > 0 else np.inf
        right = abs(median(runs[k + 1]) - here) if k + 1 < len(runs) else np.inf
        if left <= right:
            runs[k - 1][1] = runs[k][1]
        else:
            runs[k + 1][0] = runs[k][0]
        del runs[k]

    segments: list[tuple[int, int, float]] = []
    for r, (a, b) in enumerate(runs):
        # Período fino: pico de la autocorrelación promedio del tramo (interpolación parabólica).
        mean = combed[a:b].mean(axis=0)
        guess = int(round(median((a, b))))
        lo, hi = max(2, guess - 1), min(win - 2, guess + 1)
        peak = lo + int(np.argmax(mean[lo:hi + 1]))
        y0, y1, y2 = mean[peak - 1], mean[peak], mean[peak + 1]
        den = y0 - 2 * y1 + y2
        lag = peak + (0.5 * (y0 - y2) / den if den < 0 else 0.0)
        f0 = 0 if r == 0 else int(round((centers[a - 1] + centers[a]) / 2))
        f1 = n if r == len(runs) - 1 else int(round((centers[b - 1] + centers[b]) / 2))
        segments.append((f0, f1, float(lag)))
    return segments


def onset_curve(env: np.ndarray) -> dict:
    """La curva de ataques que usó el detector de pulsos, en 8 bits (unos 40 KB para 11 minutos):
    con ella la aplicación vuelve a acomodar los pulsos a otro tempo que elija el usuario."""
    import base64

    top = float(np.max(env)) if env.size else 0.0
    data = np.round(255 * np.clip(env / top, 0, 1)).astype(np.uint8) if top > 0 else np.zeros(env.size, np.uint8)
    return {"fps": round(ANALYSIS_SR / HOP, 6), "data": base64.b64encode(data.tobytes()).decode("ascii")}


def _segment_of(frames: np.ndarray, bounds: list[int]) -> np.ndarray:
    """Número de tramo de tempo de cada cuadro (bounds = cuadros donde empieza cada tramo)."""
    return np.searchsorted(np.asarray(bounds[1:], dtype=int), frames, side="right")


def analyze_rhythm(sig: SongSignals, treble_chroma: np.ndarray, bass_chroma: np.ndarray) -> dict:
    import librosa

    env = _unit(onset_envelope(sig.mix))
    drums_ok = _is_audible(sig.drums)
    kick_env = snare_env = None
    if drums_ok:
        env = 0.5 * env + _unit(onset_envelope(sig.drums))
        kick_env = onset_envelope(sig.drums, fmax=160.0)
        snare_env = onset_envelope(sig.drums, fmin=160.0, fmax=3000.0)
    if not np.any(env > 0):
        return {"bpm": None, "beats": [], "downbeats": [], "beatsPerBar": 4, "steady": False,
                "confidence": 0.0, "segments": []}

    # El tempo se mide con el bombo y el redoblante (los platillos suelen marcar subdivisiones).
    tempo_env = _unit(kick_env) + _unit(snare_env) + 0.5 * env if drums_ok else env
    segments = tempo_segments(tempo_env)
    fps = ANALYSIS_SR / HOP
    bpm_curve = np.empty(env.size)
    for f0, f1, lag in segments:
        bpm_curve[f0:f1] = 60.0 * fps / lag
    tempo = float(60.0 * fps / segments[0][2])
    _, beat_frames = librosa.beat.beat_track(
        onset_envelope=env, sr=ANALYSIS_SR, hop_length=HOP, bpm=bpm_curve, tightness=120,
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
                "steady": False, "confidence": 0.0, "segments": []}

    bounds = [f0 for f0, _, _ in segments]
    seg_of = _segment_of(beat_frames, bounds)
    # ¿El detector marcó el doble o la mitad del tempo? Se mira en cada tramo (en un popurrí puede
    # pasar en uno solo).
    if drums_ok:
        kept = []
        for s in range(len(segments)):
            frames = beat_frames[seg_of == s]
            if frames.size >= 16:
                period_s = float(np.median(np.diff(frames))) * HOP / ANALYSIS_SR
                if 2 * 60.0 / period_s <= 180 and counted_half(frames, kick_env, snare_env):
                    # Contó la mitad: se agrega el pulso del medio.
                    frames = np.sort(np.concatenate([frames, (frames[:-1] + frames[1:]) // 2]))
                elif 60.0 / (2 * period_s) >= 50:  # no bajar de 50 BPM
                    phase = half_time_phase(frames, kick_env, snare_env)
                    if phase is not None:
                        frames = frames[phase::2]
            kept.append(frames)
        beat_frames = np.concatenate(kept)
        seg_of = _segment_of(beat_frames, bounds)

    # Confianza del pulso: cuánto más fuerte es el ataque en los pulsos que en el resto.
    on_beat = env[np.clip(beat_frames, 0, env.size - 1)].mean()
    pulse_confidence = float(np.clip((on_beat / max(env.mean(), 1e-9) - 1.0) / 1.5, 0, 1))

    beat_times = frames_to_time(beat_frames)
    beat_times = beat_times + attack_offset(beat_times, sig.drums if drums_ok else sig.mix)

    # Cada tramo con tempo constante (grabado con click) pasa a una grilla perfecta.
    parts: list[dict] = []
    for s in range(len(segments)):
        times = beat_times[seg_of == s]
        if times.size < 2:
            continue
        grid = fit_steady_grid(times)
        steady = bool(grid and grid["steady"])
        if steady:
            times = grid["offset"] + grid["period"] * np.arange(grid["k0"], grid["k1"] + 1)
            bpm = 60.0 / grid["period"]
        else:
            bpm = 60.0 / float(np.median(np.diff(times)))
        if parts and abs(bpm / parts[-1]["bpm"] - 1) < 0.03:
            # Tramo vecino con el mismo tempo: es el mismo.
            prev = parts[-1]
            weight = prev["times"].size / (prev["times"].size + times.size)
            prev.update(times=np.concatenate([prev["times"], times]), steady=prev["steady"] and steady,
                        bpm=weight * prev["bpm"] + (1 - weight) * bpm)
            continue
        parts.append({"times": times, "steady": steady, "bpm": bpm})
    # Sin pulsos repetidos donde se tocan dos tramos, ni fuera de la canción.
    clean: list[dict] = []
    for part in parts:
        times = part["times"]
        if clean:
            times = times[times >= clean[-1]["times"][-1] + 0.4 * 60.0 / part["bpm"]]
        times = times[(times >= 0) & (times < sig.duration)]
        if times.size >= 2:
            clean.append({**part, "times": times})
    parts = clean
    if sum(p["times"].size for p in parts) < 4:
        return {"bpm": round(tempo, 1) if tempo else None, "beats": [], "downbeats": [], "beatsPerBar": 4,
                "steady": False, "confidence": 0.0, "segments": []}
    beat_times = np.concatenate([p["times"] for p in parts])
    beat_frames = np.round(beat_times * ANALYSIS_SR / HOP).astype(int)

    # Compás y "1" de cada tramo (en un popurrí cada canción tiene los suyos).
    low_source = sig.drums if drums_ok else sig.mix
    low_env = onset_envelope(low_source, fmax=160.0)
    if sig.bass is not None:
        low_env = _unit(low_env) + 0.6 * _unit(onset_envelope(sig.bass, fmax=320.0))
    meter_all, phase_all, conf_all = estimate_meter_and_downbeats(beat_frames, low_env, treble_chroma, bass_chroma)
    downbeats: list[float] = []
    out_segments: list[dict] = []
    weights: dict[int, float] = {}
    confidence_sum = 0.0
    position = 0
    for part in parts:
        times = part["times"]
        count = times.size
        frames = beat_frames[position:position + count]
        position += count
        if len(parts) == 1:
            meter, phase, conf = meter_all, phase_all, conf_all
        elif count >= 16:
            meter, phase, conf = estimate_meter_and_downbeats(frames, low_env, treble_chroma, bass_chroma)
        else:
            meter, phase, conf = estimate_meter_and_downbeats(frames, low_env, treble_chroma, bass_chroma,
                                                              meters=(meter_all,))
        downbeats.extend(times[phase::meter].tolist())
        start = 0.0 if not out_segments else float(times[0])
        if out_segments:
            out_segments[-1]["end"] = round(start, 3)
        out_segments.append({"start": round(start, 3), "end": round(float(sig.duration), 3),
                             "bpm": round(float(part["bpm"]), 1), "beatsPerBar": int(meter),
                             "steady": bool(part["steady"])})
        span = float(times[-1] - times[0]) if count > 1 else 0.0
        weights[int(meter)] = weights.get(int(meter), 0.0) + span
        confidence_sum += conf * span
    total = sum(weights.values()) or 1.0
    meter = max(weights, key=weights.get) if weights else int(meter_all)
    main = max(out_segments, key=lambda s: s["end"] - s["start"]) if out_segments else None

    return {
        "bpm": main["bpm"] if main else round(tempo, 1),
        "beats": [round(float(t), 3) for t in beat_times],
        "downbeats": [round(float(t), 3) for t in downbeats],
        "downbeatPhase": int(phase_all),
        "beatsPerBar": int(meter),
        "steady": all(s["steady"] for s in out_segments) if out_segments else False,
        "confidence": round(pulse_confidence, 2),
        "meterConfidence": round(confidence_sum / total, 2),
        "segments": out_segments,
        "onset": onset_curve(env),
    }
