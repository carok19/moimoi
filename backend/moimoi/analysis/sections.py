"""Estructura de la canción: intro, versos, coros, puente, final…

La música popular (y en especial la de alabanza) se arma con frases de 4 compases.
El método aprovecha eso:
1. Cada compás se describe con su armonía, timbre, volumen y si hay voz.
2. Se buscan los cortes fuertes: donde entra o sale la voz y donde cambia mucho
   el sonido (novedad en la matriz de auto-similitud).
3. Entre cortes, la canción se divide en frases de 4 compases. Cada frase se
   compara con las demás (misma secuencia de acordes + mismo timbre) y las
   frases parecidas reciben la misma letra (A, B, C…).
4. Frases seguidas del mismo tipo forman una sección (verso de 8 compases =
   dos frases A). Si un tipo siempre va seguido de otro (p. ej. C+D), se unen.
5. Los nombres salen de heurísticas: la parte con voz más repetida y más fuerte
   es el coro, la que aparece antes es el verso, una parte única cerca del final
   es el puente, lo instrumental del principio es la intro, etc.
Los nombres se pueden editar en la aplicación.
"""

from __future__ import annotations

import string

import numpy as np

from .features import ANALYSIS_SR, HOP, SongSignals, rms_db, time_to_frames

PHRASE_BARS = 4
SAME_PHRASE = 0.78  # similitud mínima para considerar dos frases "la misma parte"


def _bar_bounds(rhythm: dict, duration: float) -> np.ndarray:
    downbeats = [t for t in (rhythm.get("downbeats") or []) if 0 <= t < duration]
    if len(downbeats) >= 6:
        bounds = [0.0] + [float(t) for t in downbeats if t > 0.25] + [duration]
    else:
        bounds = list(np.arange(0.0, duration, 4.0)) + [duration]
    bounds = np.array(sorted(set(round(b, 3) for b in bounds)))
    if bounds.size > 2 and bounds[-1] - bounds[-2] < 1.0:
        bounds = np.delete(bounds, -2)
    return bounds


def _zscore(x: np.ndarray) -> np.ndarray:
    std = x.std(axis=0, keepdims=True)
    return (x - x.mean(axis=0, keepdims=True)) / np.where(std > 1e-9, std, 1.0)


def _checkerboard_novelty(feats: np.ndarray, half: int) -> np.ndarray:
    dist = np.linalg.norm(feats[:, None, :] - feats[None, :, :], axis=2)
    sigma = float(np.median(dist)) or 1.0
    ssm = np.exp(-(dist / sigma) ** 2)
    axis = np.arange(-half, half) + 0.5
    gauss = np.exp(-0.5 * (axis / (half * 0.7)) ** 2)
    kernel = np.outer(np.sign(axis), np.sign(axis)) * np.outer(gauss, gauss)
    n = ssm.shape[0]
    padded = np.pad(ssm, half, mode="edge")
    # novelty[i] = cambio entre el compás i-1 y el i
    return np.array([max(0.0, float(np.sum(padded[i: i + 2 * half, i: i + 2 * half] * kernel)))
                     for i in range(n)])


def _strong_cuts(novelty: np.ndarray, vocal: np.ndarray, n_bars: int) -> list[int]:
    cuts: set[int] = set()
    singing = vocal > 0.35
    for i in range(1, n_bars):
        before = singing[max(0, i - 2): i]
        after = singing[i: i + 2]
        if before.size and after.size and before.all() != after.all() and before.all() == (not after.any()) \
                and len(set(before)) == 1 and len(set(after)) == 1:
            cuts.add(i)
    if novelty.size > 2:
        threshold = float(np.mean(novelty) + 1.5 * np.std(novelty))
        for i in range(2, n_bars - 1):
            if novelty[i] > threshold and novelty[i] >= novelty[i - 1] and novelty[i] >= novelty[i + 1]:
                if all(abs(i - c) >= 2 for c in cuts):
                    cuts.add(i)
    return sorted(c for c in cuts if 0 < c < n_bars)


def _grid(a: int, b: int, phase: int) -> list[tuple[int, int]]:
    starts = [a] + [i for i in range(a + phase, b, PHRASE_BARS) if i > a]
    region: list[tuple[int, int]] = []
    for s, e in zip(starts, starts[1:] + [b]):
        if region and e - s < 2:
            region[-1] = (region[-1][0], e)  # resto de 1 compás: se une a la frase anterior
        else:
            region.append((s, e))
    if len(region) > 1 and region[0][1] - region[0][0] < 2:
        region[1] = (region[0][0], region[1][1])
        region.pop(0)
    return region


def _repetition_score(phrases: list[tuple[int, int]], chroma: np.ndarray) -> float:
    """Con la fase correcta, las frases se repiten casi idénticas (verso 1 = verso 2...)."""
    full = [p for p in phrases if p[1] - p[0] == PHRASE_BARS]
    if len(full) < 2:
        return 0.0
    best = []
    for i, p in enumerate(full):
        sims = [float(np.mean([np.dot(chroma[p[0] + k], chroma[q[0] + k]) for k in range(PHRASE_BARS)]))
                for j, q in enumerate(full) if j != i]
        best.append(max(sims))
    return float(np.mean(best))


def _phrases(n_bars: int, cuts: list[int], chroma: np.ndarray) -> list[tuple[int, int]]:
    """Frases de 4 compases dentro de cada región entre cortes fuertes."""
    edges = [0] + cuts + [n_bars]
    phrases: list[tuple[int, int]] = []
    for a, b in zip(edges[:-1], edges[1:]):
        if b - a <= PHRASE_BARS + 1:
            phrases.append((a, b))
            continue
        # Fase de la grilla: la que hace que las frases se repitan mejor
        # (con preferencia por arrancar justo en el corte).
        options = []
        for phase in range(PHRASE_BARS):
            grid = _grid(a, b, phase)
            options.append((_repetition_score(grid, chroma) + (0.02 if phase == 0 else 0.0), phase, grid))
        phrases.extend(max(options, key=lambda o: o[0])[2])
    return phrases


def _phrase_similarity(chroma: np.ndarray, local: np.ndarray, p: tuple[int, int], q: tuple[int, int],
                       sigma: float) -> float:
    lp, lq = p[1] - p[0], q[1] - q[0]
    k = min(lp, lq)
    a, b = chroma[p[0]: p[0] + k], chroma[q[0]: q[0] + k]
    # Misma secuencia de acordes; también transportada ±1–3 semitonos (el último coro un
    # tono más arriba sigue siendo el coro), con una pequeña penalización.
    seq = -1.0
    for shift in (0, 1, -1, 2, -2, 3, -3):
        rolled = np.roll(b, shift, axis=1)
        value = float(np.mean(np.maximum(0.0, np.sum(a * rolled, axis=1)))) - (0.06 if shift else 0.0)
        seq = max(seq, value)
    d = float(np.linalg.norm(local[p[0]: p[1]].mean(axis=0) - local[q[0]: q[1]].mean(axis=0)))
    timbre = float(np.exp(-(d / sigma) ** 2))
    length = 1.0 - abs(lp - lq) / max(lp, lq)
    return 0.6 * seq + 0.3 * timbre + 0.1 * length


def _timbre_sigma(phrases, local) -> float:
    n = len(phrases)
    means = np.array([local[a:b].mean(axis=0) for a, b in phrases])
    dists = [np.linalg.norm(means[i] - means[j]) for i in range(n) for j in range(i + 1, n)]
    return (float(np.median(dists)) if dists else 1.0) or 1.0


def _cluster(phrases, chroma, local) -> list[int]:
    n = len(phrases)
    sigma = _timbre_sigma(phrases, local)
    sim = np.eye(n)
    for i in range(n):
        for j in range(i + 1, n):
            sim[i, j] = sim[j, i] = _phrase_similarity(chroma, local, phrases[i], phrases[j], sigma)
    # Agrupamiento aglomerativo (enlace promedio) con umbral.
    clusters = [[i] for i in range(n)]
    while len(clusters) > 1:
        best, pair = -1.0, None
        for a in range(len(clusters)):
            for b in range(a + 1, len(clusters)):
                s = float(np.mean(sim[np.ix_(clusters[a], clusters[b])]))
                if s > best:
                    best, pair = s, (a, b)
        if best < SAME_PHRASE:
            break
        a, b = pair
        clusters[a] = clusters[a] + clusters[b]
        del clusters[b]
    labels = [0] * n
    for c, members in enumerate(sorted(clusters, key=min)):
        for m in members:
            labels[m] = c
    return labels


def _merge(phrases: list[tuple[int, int]], labels: list[int], energy: np.ndarray, vocal: np.ndarray,
           similarity) -> tuple[list[tuple[int, int]], list[int]]:
    # 1) frases seguidas iguales -> una sección
    segments, groups = [], []
    for (a, b), g in zip(phrases, labels):
        if groups and groups[-1] == g and (b - a) + (segments[-1][1] - segments[-1][0]) <= 16:
            segments[-1] = (segments[-1][0], b)
        else:
            segments.append((a, b))
            groups.append(g)
    # 2) un tipo que siempre va seguido del mismo otro tipo y suena igual (mismo volumen, misma
    #    voz, acordes parecidos) es la primera mitad de esa parte: p. ej. las dos frases de un
    #    coro. No une un verso con el coro que le sigue (suenan distinto).
    changed = True
    while changed:
        changed = False
        for x in sorted(set(groups)):
            positions = [i for i, g in enumerate(groups) if g == x]
            if any(i + 1 >= len(groups) for i in positions):
                continue
            followers = {groups[i + 1] for i in positions}
            if len(followers) != 1:
                continue
            y = followers.pop()
            if y == x or (len(positions) < 2 and groups.count(y) < 2):
                continue
            if any((segments[i + 1][1] - segments[i][0]) > 16 for i in positions):
                continue
            xs = np.concatenate([np.arange(*segments[i]) for i in positions])
            ys = np.concatenate([np.arange(*segments[i + 1]) for i in positions])
            if abs(float(energy[xs].mean() - energy[ys].mean())) > 1.5 \
                    or abs(float(vocal[xs].mean() - vocal[ys].mean())) > 0.25:
                continue
            if np.mean([similarity(segments[i], segments[i + 1]) for i in positions]) < 0.72:
                continue
            target = y if groups.count(y) >= groups.count(x) else x
            new_segments, new_groups, i = [], [], 0
            while i < len(groups):
                if groups[i] == x and i + 1 < len(groups) and groups[i + 1] == y:
                    new_segments.append((segments[i][0], segments[i + 1][1]))
                    new_groups.append(target)
                    i += 2
                else:
                    new_segments.append(segments[i])
                    new_groups.append(groups[i])
                    i += 1
            segments, groups, changed = new_segments, new_groups, True
            break
    # renumerar por orden de aparición
    order: dict[int, int] = {}
    for g in groups:
        order.setdefault(g, len(order))
    return segments, [order[g] for g in groups]


def _consolidate(segments: list[tuple[int, int]], groups: list[int], duration: float
                 ) -> tuple[list[tuple[int, int]], list[int]]:
    """Red de seguridad para canciones largas o muy variadas (un popurrí de 11 minutos): si
    quedaron demasiadas partes (más de una cada ~15 s), la más corta se junta con su vecina (la
    del mismo tipo si la hay, si no la más corta), hasta que queden las que corresponden."""
    segments, groups = list(segments), list(groups)
    limit = max(12, int(round(duration / 15.0)))
    while len(segments) > limit:
        lengths = [b - a for a, b in segments]
        k = int(np.argmin(lengths))
        left, right = k - 1, k + 1
        if left >= 0 and groups[left] == groups[k]:
            other = left
        elif right < len(segments) and groups[right] == groups[k]:
            other = right
        elif left < 0:
            other = right
        elif right >= len(segments):
            other = left
        else:
            other = left if lengths[left] <= lengths[right] else right
        a, b = min(segments[k][0], segments[other][0]), max(segments[k][1], segments[other][1])
        group = groups[other] if lengths[other] >= lengths[k] else groups[k]
        first = min(k, other)
        segments[first:first + 2] = [(a, b)]
        groups[first:first + 2] = [group]
    order: dict[int, int] = {}
    for g in groups:
        order.setdefault(g, len(order))
    return segments, [order[g] for g in groups]


def analyze_sections(sig: SongSignals, rhythm: dict, treble_chroma: np.ndarray) -> list[dict]:
    import librosa

    duration = sig.duration
    bounds = _bar_bounds(rhythm, duration)
    n_bars = bounds.size - 1
    if n_bars < 6:
        return [{"start": 0.0, "end": round(duration, 3), "label": "Canción", "group": "A",
                 "bars": int(n_bars), "vocals": sig.vocals is not None}]

    frames = np.clip(time_to_frames(bounds), 0, treble_chroma.shape[1])
    mfcc = librosa.feature.mfcc(y=sig.mix, sr=ANALYSIS_SR, n_mfcc=13, hop_length=HOP)[1:]
    mix_db = rms_db(sig.mix, 2048, HOP)
    voc_db = rms_db(sig.vocals, 2048, HOP) if sig.vocals is not None else np.full_like(mix_db, -100.0)

    chroma_bars, timbre, energy, vocal = [], [], [], []
    last = treble_chroma.shape[1] - 1
    for i in range(n_bars):
        f0 = int(min(frames[i], last))
        f1 = int(max(frames[i + 1], f0 + 1))
        c = treble_chroma[:, f0:f1].mean(axis=1)
        # Centrado: con croma "crudo" todos los acordes se parecen (correlación en vez de coseno).
        c = c - c.mean()
        chroma_bars.append(c / max(np.linalg.norm(c), 1e-9))
        m0, m1 = min(f0, mfcc.shape[1] - 1), max(min(f1, mfcc.shape[1]), min(f0, mfcc.shape[1] - 1) + 1)
        timbre.append(mfcc[:, m0:m1].mean(axis=1))
        e0, e1 = min(f0, mix_db.size - 1), max(min(f1, mix_db.size), min(f0, mix_db.size - 1) + 1)
        seg_mix, seg_voc = mix_db[e0:e1], voc_db[e0:e1]
        energy.append(float(np.mean(seg_mix)))
        vocal.append(float(np.mean((seg_voc > -45) & (seg_voc > seg_mix - 18))))
    chroma_bars = np.array(chroma_bars)
    energy = np.array(energy)
    vocal = np.array(vocal)

    local = np.hstack([
        0.9 * _zscore(np.array(timbre)),
        1.0 * _zscore(energy[:, None]) * np.sqrt(3),
        1.2 * _zscore(vocal[:, None]) * np.sqrt(3),
    ])
    novelty = _checkerboard_novelty(np.hstack([local, 1.5 * _zscore(chroma_bars)]),
                                    half=4 if n_bars >= 24 else 2)
    cuts = _strong_cuts(novelty, vocal, n_bars)
    # Donde cambia el tempo (en un popurrí, donde empieza otra canción) también se corta.
    for part in (rhythm.get("segments") or [])[1:]:
        bar = int(np.searchsorted(bounds, float(part["start"]) - 0.05))
        if 0 < bar < n_bars and bar not in cuts:
            cuts = sorted(cuts + [bar])
    phrases = _phrases(n_bars, cuts, chroma_bars)
    labels = _cluster(phrases, chroma_bars, local)
    sigma = _timbre_sigma(phrases, local)
    segments, groups = _merge(phrases, labels, energy, vocal,
                              lambda p, q: _phrase_similarity(chroma_bars, local, p, q, sigma))
    segments, groups = _consolidate(segments, groups, duration)

    seg_vocal = np.array([vocal[a:b].mean() for a, b in segments])
    seg_energy = np.array([energy[a:b].mean() for a, b in segments])
    names = _name_sections(segments, groups, seg_vocal, seg_energy)

    result = []
    for idx, ((a, b), g, label) in enumerate(zip(segments, groups, names)):
        result.append({
            "start": round(float(bounds[a]) if a > 0 else 0.0, 3),
            "end": round(float(bounds[b]) if b < n_bars else duration, 3),
            "label": label,
            "group": string.ascii_uppercase[g % 26],
            "bars": int(b - a),
            "vocals": bool(seg_vocal[idx] > 0.35),
        })
    return result


def _name_sections(segments, groups, seg_vocal, seg_energy) -> list[str]:
    n = len(segments)
    singing = seg_vocal > 0.35
    kinds: list[str | None] = [None] * n
    for i in range(n):
        if not singing[i]:
            kinds[i] = "Intro" if i == 0 else ("Final" if i == n - 1 else "Instrumental")

    vocal_groups: dict[int, list[int]] = {}
    for i, g in enumerate(groups):
        if singing[i]:
            vocal_groups.setdefault(g, []).append(i)
    repeated = {g: idx for g, idx in vocal_groups.items() if len(idx) >= 2}
    energy_z = (seg_energy - seg_energy.mean()) / (seg_energy.std() or 1.0)

    chorus = verse = pre = None
    if repeated:
        chorus = max(repeated, key=lambda g: float(np.mean(energy_z[repeated[g]])) + 0.35 * len(repeated[g])
                     + 0.2 * (repeated[g][-1] / n))
        others = {g: idx for g, idx in repeated.items() if g != chorus}
        if others:
            verse = min(others, key=lambda g: others[g][0])
            for g, idx in others.items():
                if g == verse:
                    continue
                follows = sum(1 for i in idx if i + 1 < n and groups[i + 1] == chorus)
                if follows >= max(1, len(idx) - 1):
                    pre = g
                    break
    if verse is None:
        candidates = [g for g in vocal_groups if g != chorus and vocal_groups[g][0] / n < 0.45]
        if candidates:
            verse = min(candidates, key=lambda g: vocal_groups[g][0])

    names: list[str] = []
    for i, g in enumerate(groups):
        if kinds[i] is not None:
            base = kinds[i]
        elif g == chorus:
            base = "Coro"
        elif g == verse:
            base = "Verso"
        elif g == pre:
            base = "Pre-coro"
        elif len(vocal_groups.get(g, [])) == 1 and i / n >= 0.45:
            base = "Puente"
        else:
            base = f"Parte {string.ascii_uppercase[g % 26]}"
        names.append(base)
    totals: dict[str, int] = {}
    for base in names:
        totals[base] = totals.get(base, 0) + 1
    counters: dict[str, int] = {}
    result = []
    for base in names:
        if base in {"Verso", "Coro", "Pre-coro", "Instrumental", "Puente"} and totals[base] > 1:
            counters[base] = counters.get(base, 0) + 1
            result.append(f"{base} {counters[base]}")
        else:
            result.append(base)
    return result
