"""Tonalidad (con cambios de tonalidad) y acordes.

Método: croma CQT de los instrumentos armónicos (sin batería) promediado por pulso,
comparado con plantillas de acordes; la secuencia final se elige con Viterbi
(los acordes tienden a durar y a cambiar al inicio del compás) y con un pequeño
favoritismo por los acordes de la tonalidad. El bajo separado ayuda a distinguir
acordes parecidos (C / Am) y a detectar inversiones (G/B).
"""

from __future__ import annotations

import numpy as np

from .features import ANALYSIS_SR, HOP, time_to_frames
from .music import (
    CHORD_QUALITIES, chord_name, chord_templates, is_diatonic, key_label, key_name, key_profiles,
    uses_flats,
)

KAPPA = 40.0          # peso de la similitud con la plantilla
PRIOR_WEIGHT = 4.0    # preferencia por las tríadas (con el mismo peso relativo frente a KAPPA)
CHROMA_WHITEN = 0.8   # parte del promedio del croma que se resta antes de comparar con las plantillas
KEY_BONUS = 0.6       # acorde diatónico a la tonalidad local
KEY_NEIGHBOUR_MARGIN = 0.1  # ventaja que necesitan los acordes para cambiar a una tonalidad a una quinta
BASS_WEIGHT = 2.0     # coincidencia con la nota del bajo
KEY_KAPPA = 8.0       # peso de la correlación en el seguimiento de tonalidad
KEY_STAY = 0.995      # probabilidad de mantener la tonalidad de un pulso al siguiente
CHORD_KEY_WEIGHT = 16.0  # peso de los acordes (diatónicos, tónica) en el seguimiento de tonalidad
CHORD_KEY_HALF = 48   # pulsos a cada lado que se miran para esa evidencia
MIN_KEY_REGION_BEATS = 24
MIN_KEY_REGION_SECONDS = 16.0  # una "tonalidad" más corta que esto no es un cambio de tonalidad
MAX_DOMINANT_PEDAL_SECONDS = 40.0  # tramo quieto en la dominante (p. ej. Si en Mi menor) antes de volver
KEY_TEMPO_SNAP_SECONDS = 12.0  # un cambio de tonalidad así de cerca de un cambio de tempo pasa a ese punto
KEY_SECTION_SNAP_SECONDS = 10.0  # ... y así de cerca del comienzo de una sección, si los acordes no se oponen


def _segments(rhythm: dict, duration: float, n_frames: int) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Tramos de análisis: uno por pulso (más el tramo previo al primer pulso).

    Devuelve (frames de borde, bordes en segundos, posición de cada tramo en el compás:
    -1 = antes del primer pulso, 0 = empieza en el "1").
    """
    beats = np.asarray(rhythm.get("beats") or [], dtype=float)
    if beats.size >= 2:
        meter = int(rhythm.get("beatsPerBar") or 4)
        phase = int(rhythm.get("downbeatPhase") or 0)
        times, index = beats, np.arange(beats.size)
        position_of = lambda j: (j - phase) % meter  # noqa: E731
    else:
        times = np.arange(0.5, max(duration, 0.5), 0.5)
        index = np.arange(times.size)
        position_of = lambda j: 0 if j % 8 == 0 else 1  # noqa: E731 - sin pulso: "compás" de 4 s
    frames = time_to_frames(times)
    keep = (frames > 0) & (frames < n_frames)
    frames, times, index = frames[keep], times[keep], index[keep]
    frames, unique_idx = np.unique(frames, return_index=True)
    times, index = times[unique_idx], index[unique_idx]
    bounds = np.concatenate([[0.0], times, [duration]])
    positions = np.array([-1] + [position_of(int(j)) for j in index], dtype=int)
    return frames, bounds, positions


def _sync(chroma: np.ndarray, frames: np.ndarray) -> np.ndarray:
    import librosa

    return librosa.util.sync(chroma, frames, aggregate=np.median, pad=True)


def _viterbi(emission: np.ndarray, log_stay: np.ndarray, log_switch: np.ndarray) -> np.ndarray:
    """Viterbi con transición "quedarse vs. cambiar a cualquier otro" que varía por paso.

    emission: (K, S). log_stay/log_switch: (S,) para la transición que entra al paso s.
    """
    n_states, n_steps = emission.shape
    score = emission[:, 0].copy()
    back = np.zeros((n_states, n_steps), dtype=np.int32)
    for s in range(1, n_steps):
        best_prev = int(np.argmax(score))
        stay = score + log_stay[s]
        switch = score[best_prev] + log_switch[s]
        use_switch = switch > stay
        back[:, s] = np.where(use_switch, best_prev, np.arange(n_states))
        # Si el mejor anterior es el mismo estado, "cambiar" no aplica.
        back[best_prev, s] = best_prev
        new = np.where(use_switch, switch, stay)
        new[best_prev] = stay[best_prev]
        score = new + emission[:, s]
    path = np.zeros(n_steps, dtype=np.int32)
    path[-1] = int(np.argmax(score))
    for s in range(n_steps - 1, 0, -1):
        path[s - 1] = back[path[s], s]
    return path


def _unit_columns(x: np.ndarray) -> np.ndarray:
    return x / np.maximum(np.linalg.norm(x, axis=0, keepdims=True), 1e-9)


def _chord_key_evidence(chords: list[dict], bounds: np.ndarray, labels: list[tuple[int, str]]) -> np.ndarray:
    """(tonalidades, tramos): 0.6 si el acorde que suena en el tramo es diatónico a la tonalidad y
    0.5 más si es su tónica (lo mismo que mide _key_fit, pero pulso a pulso)."""
    n = bounds.size - 1
    evidence = np.zeros((len(labels), n))
    cache: dict[tuple[int, str], np.ndarray] = {}
    middle = (bounds[:-1] + bounds[1:]) / 2
    j = 0
    for s in range(n):
        while j + 1 < len(chords) and chords[j]["end"] <= middle[s]:
            j += 1
        if not chords or chords[j]["quality"] == "N":
            continue
        chord = (chords[j]["root"], chords[j]["quality"])
        if chord not in cache:
            third = CHORD_QUALITIES[chord[1]]["intervals"][1]
            cache[chord] = np.array([
                0.6 * is_diatonic(chord[0], chord[1], tonic, mode)
                + 0.5 * (chord[0] == tonic and third == CHORD_QUALITIES["maj" if mode == "major" else "min"]["intervals"][1])
                for tonic, mode in labels
            ])
        evidence[:, s] = cache[chord]
    return evidence


def track_keys(harm: np.ndarray, durations: np.ndarray, chords: list[dict] | None = None,
               bounds: np.ndarray | None = None) -> list[tuple[int, str]]:
    """Tonalidad de cada tramo (con suavizado fuerte para detectar solo modulaciones reales).

    Con los acordes de una primera pasada, además del croma cuenta qué tonalidad los explica: en
    una mezcla densa el croma queda casi plano y confunde las tonalidades vecinas.
    """
    profiles, labels = key_profiles()
    n = harm.shape[1]
    weighted = harm * durations[None, :]
    cumsum = np.cumsum(np.pad(weighted, ((0, 0), (1, 0))), axis=1)
    half = 16
    emission = np.zeros((len(labels), n))
    for s in range(n):
        lo, hi = max(0, s - half), min(n, s + half + 1)
        window = cumsum[:, hi] - cumsum[:, lo]
        window = window - window.mean()
        norm = np.linalg.norm(window)
        if norm > 1e-9:
            emission[:, s] = KEY_KAPPA * (profiles @ (window / norm))
    if chords is not None and bounds is not None:
        evidence = _chord_key_evidence(chords, bounds, labels) * durations[None, :]
        ev_sum = np.cumsum(np.pad(evidence, ((0, 0), (1, 0))), axis=1)
        dur_sum = np.cumsum(np.pad(durations, (1, 0)))
        for s in range(n):
            lo, hi = max(0, s - CHORD_KEY_HALF), min(n, s + CHORD_KEY_HALF + 1)
            emission[:, s] += CHORD_KEY_WEIGHT * (ev_sum[:, hi] - ev_sum[:, lo]) / max(dur_sum[hi] - dur_sum[lo], 1e-9)
    log_stay = np.full(n, np.log(KEY_STAY))
    log_switch = np.full(n, np.log((1 - KEY_STAY) / (len(labels) - 1)))
    path = _viterbi(emission, log_stay, log_switch)
    return [labels[i] for i in path]


def _chord_times(chords: list[dict], tonic: int, mode: str) -> tuple[float, float]:
    """Fracción del tiempo con acordes diatónicos a la tonalidad y con el acorde de la tónica."""
    total = sum(c["end"] - c["start"] for c in chords if c["quality"] != "N") or 1.0
    diatonic = sum(c["end"] - c["start"] for c in chords
                   if c["quality"] != "N" and is_diatonic(c["root"], c["quality"], tonic, mode))
    tonic_quality = "maj" if mode == "major" else "min"
    tonic_time = sum(c["end"] - c["start"] for c in chords
                     if c["quality"] != "N" and c["root"] == tonic
                     and CHORD_QUALITIES[c["quality"]]["intervals"][1] == CHORD_QUALITIES[tonic_quality]["intervals"][1])
    return diatonic / total, tonic_time / total


def _chord_stats(chords: list[dict], tonic: int, mode: str) -> float:
    """Qué tan bien explican los acordes a una tonalidad (0-1 aprox.)."""
    diatonic, tonic_time = _chord_times(chords, tonic, mode)
    real = [c for c in chords if c["quality"] != "N"]
    ends_on_tonic = 1.0 if real and real[-1]["root"] == tonic else 0.0
    starts_on_tonic = 1.0 if real and real[0]["root"] == tonic else 0.0
    return 0.6 * diatonic + 0.5 * tonic_time + 0.15 * ends_on_tonic + 0.1 * starts_on_tonic


def _key_fit(chords: list[dict], tonic: int, mode: str) -> float:
    """Como _chord_stats pero sin el primer y el último acorde: en un tramo del medio de la canción
    (un popurrí) dependen de dónde se cortó el tramo, no de la tonalidad."""
    diatonic, tonic_time = _chord_times(chords, tonic, mode)
    return 0.6 * diatonic + 0.5 * tonic_time


def _relative(tonic: int, mode: str) -> tuple[int, str]:
    return ((tonic + 9) % 12, "minor") if mode == "major" else ((tonic + 3) % 12, "major")


def _fifth_neighbours(tonic: int, mode: str) -> list[tuple[int, str]]:
    """Las tonalidades a una quinta (arriba y abajo) y sus relativas."""
    keys = []
    for other in ((tonic + 5) % 12, (tonic + 7) % 12):
        keys += [(other, mode), _relative(other, mode)]
    return keys


def _is_dominant_of(key: tuple[int, str], other: tuple[int, str]) -> bool:
    return key[1] == "major" and key[0] == (other[0] + 7) % 12


def _join_same_keys(regions: list[dict]) -> list[dict]:
    joined: list[dict] = []
    for region in regions:
        if joined and joined[-1]["key"] == region["key"]:
            joined[-1]["end"] = region["end"]
        else:
            joined.append(region)
    return joined


def decode_chords(
    treble: np.ndarray,
    bass: np.ndarray,
    bounds: np.ndarray,
    keys: list[tuple[int, str]],
    positions: np.ndarray,
) -> list[dict]:
    templates, labels = chord_templates()
    n_chords = len(labels)
    n = treble.shape[1]
    energy = treble.sum(axis=0)
    median_energy = float(np.median(energy[energy > 0])) if np.any(energy > 0) else 1.0
    energy_rel = energy / max(median_energy, 1e-9)

    # En una mezcla densa (y con la compresión logarítmica) el croma queda casi plano: todos los
    # acordes se parecen y el Viterbi no cambiaba de acorde en minutos. Restar parte del promedio
    # deja solo las notas que sobresalen.
    whitened = np.maximum(treble - CHROMA_WHITEN * treble.mean(axis=0, keepdims=True), 0)
    sim = templates @ _unit_columns(whitened)  # (K, S)
    priors = PRIOR_WEIGHT * np.array([CHORD_QUALITIES[q]["prior"] for _, q in labels])

    bass_sum = bass.sum(axis=0)
    bass_dist = bass / np.maximum(bass_sum[None, :], 1e-9)
    # Resaltar la nota dominante del bajo (el croma del bajo tiene algo de ruido de fondo).
    bass_dist = np.maximum(bass_dist - 1.0 / 12, 0)
    bass_dist = bass_dist / np.maximum(bass_dist.sum(axis=0, keepdims=True), 1e-9)
    bass_median = float(np.median(bass_sum[bass_sum > 0])) if np.any(bass_sum > 0) else 1.0
    bass_conf = np.clip(bass_sum / max(bass_median, 1e-9), 0, 1)

    bass_fit = np.zeros((n_chords, n))
    for idx, (root, quality) in enumerate(labels):
        intervals = CHORD_QUALITIES[quality]["intervals"]
        fit = bass_dist[root % 12].copy()
        for interval in intervals[1:3]:
            fit += 0.45 * bass_dist[(root + interval) % 12]
        bass_fit[idx] = fit

    emission = KAPPA * sim + priors[:, None] + BASS_WEIGHT * bass_fit * bass_conf[None, :]
    for s, (tonic, mode) in enumerate(keys):
        for idx, (root, quality) in enumerate(labels):
            if is_diatonic(root, quality, tonic, mode):
                emission[idx, s] += KEY_BONUS
    # Estado "sin acorde" (silencio o solo percusión).
    n_level = 0.6 - 0.35 * np.clip(energy_rel / 0.3, 0, 1)
    emission = np.vstack([emission, KAPPA * n_level[None, :]])

    change = np.where(positions == 0, 0.5, np.where(positions < 0, 0.3, 0.1))
    change = np.where(positions == 2, 0.25, change)
    n_states = n_chords + 1
    log_stay = np.log(1 - change)
    log_switch = np.log(change / (n_states - 1))
    path = _viterbi(emission, log_stay, log_switch)

    chords: list[dict] = []
    for s, state in enumerate(path):
        start, end = float(bounds[s]), float(bounds[s + 1])
        if end <= start:
            continue
        if state == n_chords:
            root, quality = -1, "N"
        else:
            root, quality = labels[state]
        if chords and chords[-1]["root"] == root and chords[-1]["quality"] == quality:
            chords[-1]["end"] = end
            chords[-1]["_segs"].append(s)
        else:
            chords.append({"start": start, "end": end, "root": int(root), "quality": quality, "_segs": [s]})

    # Nota del bajo de cada acorde -> inversiones (G/B, C/E, D/F#...).
    for chord in chords:
        segs = chord.pop("_segs")
        chord["bass"] = None
        if chord["quality"] == "N":
            continue
        dist = bass_dist[:, segs].mean(axis=1)
        conf = float(bass_conf[segs].mean())
        top = int(np.argmax(dist))
        tones = [(chord["root"] + i) % 12 for i in CHORD_QUALITIES[chord["quality"]]["intervals"]]
        if conf > 0.4 and top != chord["root"] and top in tones[1:] and dist[top] > 0.45 \
                and dist[chord["root"]] < 0.5 * dist[top]:
            chord["bass"] = top
    return chords


def analyze_harmony(
    treble_chroma: np.ndarray,
    bass_chroma: np.ndarray,
    rhythm: dict,
    duration: float,
    tuning: float,
) -> dict:
    frames, bounds, positions = _segments(rhythm, duration, treble_chroma.shape[1])
    treble = _sync(treble_chroma, frames)
    bass = _sync(bass_chroma, frames)
    n = treble.shape[1]
    assert bounds.size == n + 1 and positions.size == n, (bounds.size, positions.size, n)
    durations = np.maximum(np.diff(bounds), 1e-3)

    harm = _unit_columns(treble) + 0.6 * _unit_columns(bass)
    harm = harm * np.clip(treble.sum(axis=0) / max(float(np.median(treble.sum(axis=0))), 1e-9), 0, 1)[None, :]
    keys = track_keys(harm, durations)

    # Primera pasada de acordes -> seguir la tonalidad también con los acordes y después
    # confirmar la de cada región (vecinas, mayor vs. relativa menor).
    chords = decode_chords(treble, bass, bounds, keys, positions)
    keys = track_keys(harm, durations, chords, bounds)

    # Duración de cada tonalidad y fusión de regiones cortas.
    regions: list[dict] = []
    for s, key in enumerate(keys):
        if regions and regions[-1]["key"] == key:
            regions[-1]["end"] = s + 1
        else:
            regions.append({"key": key, "start": s, "end": s + 1})

    def span(region: dict) -> float:
        return float(bounds[min(region["end"], n)] - bounds[region["start"]])

    def short(region: dict) -> bool:
        return region["end"] - region["start"] < MIN_KEY_REGION_BEATS or span(region) < MIN_KEY_REGION_SECONDS

    merged: list[dict] = []
    for region in regions:
        if merged and short(region):
            merged[-1]["end"] = region["end"]
        else:
            merged.append(dict(region))
    if len(merged) > 1 and short(merged[0]):
        merged[1]["start"] = merged[0]["start"]
        merged.pop(0)
    # Los acordes de cada región confirman la tonalidad o eligen una vecina: el croma confunde las
    # tonalidades a una quinta (Re menor / La menor, Mi menor / La menor), que comparten casi todas
    # las notas, y la relativa (Do / La menor), que comparte todas.
    for region in merged:
        t0, t1 = bounds[region["start"]], bounds[min(region["end"], n)]
        region_chords = [c for c in chords if c["end"] > t0 and c["start"] < t1]
        key = region["key"]
        current = _key_fit(region_chords, *key)
        best, best_gain = key, 0.0
        for candidate in _fifth_neighbours(*key):
            gain = _key_fit(region_chords, *candidate) - current - KEY_NEIGHBOUR_MARGIN
            if gain > best_gain:
                best, best_gain = candidate, gain
        key = best
        rel = _relative(*key)
        if _chord_stats(region_chords, *rel) > _chord_stats(region_chords, *key) + 0.05:
            key = rel
        region["key"] = key
    final_regions = _join_same_keys(merged)
    # Un tramo corto en la dominante (Si antes de Mi menor, Mi antes de La menor) no es un cambio de
    # tonalidad: es la dominante que prepara (o alarga) la tonalidad vecina.
    i = 0
    while i < len(final_regions):
        region = final_regions[i]
        after = final_regions[i + 1] if i + 1 < len(final_regions) else None
        before = final_regions[i - 1] if i > 0 else None
        if span(region) < MAX_DOMINANT_PEDAL_SECONDS and after and _is_dominant_of(region["key"], after["key"]):
            after["start"] = region["start"]
        elif span(region) < MAX_DOMINANT_PEDAL_SECONDS and before and _is_dominant_of(region["key"], before["key"]):
            before["end"] = region["end"]
        else:
            i += 1
            continue
        final_regions.pop(i)
        final_regions = _join_same_keys(final_regions)
        i = max(i - 1, 0)
    # En un popurrí la canción siguiente empieza donde cambia el tempo; el croma y los acordes de la
    # transición (a veces ambiguos) dejan el cambio de tonalidad unos segundos antes o después.
    tempo_starts = [float(p["start"]) for p in (rhythm.get("segments") or [])[1:]]
    for i in range(1, len(final_regions)):
        t = float(bounds[final_regions[i]["start"]])
        near = [x for x in tempo_starts if abs(x - t) <= KEY_TEMPO_SNAP_SECONDS]
        if not near:
            continue
        target = min(near, key=lambda x: abs(x - t))
        lo, hi = final_regions[i - 1]["start"] + 1, final_regions[i]["end"] - 1
        if lo > hi:
            continue
        s = lo + int(np.argmin(np.abs(bounds[lo:hi + 1] - target)))
        final_regions[i - 1]["end"] = s
        final_regions[i]["start"] = s

    keys = [None] * n
    for region in final_regions:
        for s in range(region["start"], min(region["end"], n)):
            keys[s] = region["key"]
    keys = [k or final_regions[-1]["key"] for k in keys]
    chords = decode_chords(treble, bass, bounds, keys, positions)

    # Tonalidad principal = la que más dura.
    totals: dict[tuple[int, str], float] = {}
    for region in final_regions:
        span = float(bounds[min(region["end"], n)] - bounds[region["start"]])
        totals[region["key"]] = totals.get(region["key"], 0.0) + span
    (tonic, mode) = max(totals, key=totals.get)
    confidence = float(np.clip(_chord_stats(chords, tonic, mode), 0, 1))

    flats = uses_flats(tonic, mode)
    for chord in chords:
        if chord["quality"] == "N":
            chord["name"] = "N"
        else:
            chord["name"] = chord_name(chord["root"], chord["quality"], chord["bass"], flats)
        chord["start"] = round(chord["start"], 3)
        chord["end"] = round(chord["end"], 3)

    key_changes = []
    for region in final_regions[1:]:
        k_tonic, k_mode = region["key"]
        key_changes.append({
            "time": round(float(bounds[region["start"]]), 3),
            "tonic": int(k_tonic), "mode": k_mode,
            "name": key_name(k_tonic, k_mode), "label": key_label(k_tonic, k_mode),
        })

    a4 = 440.0 * 2 ** (tuning / 12.0)
    return {
        "key": {"tonic": int(tonic), "mode": mode, "name": key_name(tonic, mode),
                "label": key_label(tonic, mode), "confidence": round(confidence, 2)},
        "keyStart": {"tonic": int(final_regions[0]["key"][0]), "mode": final_regions[0]["key"][1],
                     "name": key_name(*final_regions[0]["key"]), "label": key_label(*final_regions[0]["key"])},
        "keyChanges": key_changes,
        "tuning": {"a4": round(a4, 1), "cents": int(round(tuning * 100))},
        "chords": chords,
    }


def snap_key_changes(harmony: dict, section_starts: list[float]) -> None:
    """Un cambio de tonalidad casi siempre coincide con el comienzo de una sección: pasa al comienzo
    más cercano (a 10 s o menos) salvo que los acordes del tramo que cambia de tonalidad se expliquen
    mejor con la que tenían (en un popurrí las secciones a veces se cortan unos segundos antes)."""
    previous = (harmony["keyStart"]["tonic"], harmony["keyStart"]["mode"])
    for change in harmony["keyChanges"]:
        new = (change["tonic"], change["mode"])
        near = [t for t in section_starts if abs(t - change["time"]) <= KEY_SECTION_SNAP_SECONDS]
        if near:
            target = min(near, key=lambda t: abs(t - change["time"]))
            lo, hi = min(target, change["time"]), max(target, change["time"])
            span = [dict(c, start=max(c["start"], lo), end=min(c["end"], hi))
                    for c in harmony["chords"] if c["end"] > lo and c["start"] < hi]
            owner, other = (new, previous) if target < change["time"] else (previous, new)
            if _key_fit(span, *owner) >= _key_fit(span, *other):
                change["time"] = target
        previous = new


def compute_chromas(treble: np.ndarray, bass: np.ndarray, tuning: float) -> tuple[np.ndarray, np.ndarray]:
    from .features import chroma

    treble_chroma = chroma(treble, tuning, "C2", 5)
    bass_chroma = chroma(bass, tuning, "E1", 3)
    frames = min(treble_chroma.shape[1], bass_chroma.shape[1])
    return treble_chroma[:, :frames], bass_chroma[:, :frames]


__all__ = ["analyze_harmony", "compute_chromas", "snap_key_changes", "ANALYSIS_SR", "HOP"]
