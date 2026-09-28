"""Teoría musical básica: nombres de notas, acordes, tonalidades y perfiles."""

from __future__ import annotations

import numpy as np

SHARP_NAMES = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]
FLAT_NAMES = ["C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B"]
LATIN_SHARP = ["Do", "Do#", "Re", "Re#", "Mi", "Fa", "Fa#", "Sol", "Sol#", "La", "La#", "Si"]
LATIN_FLAT = ["Do", "Reb", "Re", "Mib", "Mi", "Fa", "Solb", "Sol", "Lab", "La", "Sib", "Si"]

#: Tonalidades mayores que se escriben con bemoles (por tónica): F, Bb, Eb, Ab, Db, Gb.
FLAT_MAJOR_TONICS = {5, 10, 3, 8, 1, 6}
#: Tonalidades menores con bemoles: Dm, Gm, Cm, Fm, Bbm, Ebm.
FLAT_MINOR_TONICS = {2, 7, 0, 5, 10, 3}

#: Calidades de acorde: intervalos (semitonos desde la fundamental), sufijo y "costo" a priori.
CHORD_QUALITIES: dict[str, dict] = {
    "maj": {"intervals": (0, 4, 7), "suffix": "", "prior": 0.0},
    "min": {"intervals": (0, 3, 7), "suffix": "m", "prior": 0.0},
    "7": {"intervals": (0, 4, 7, 10), "suffix": "7", "prior": -0.9},
    "maj7": {"intervals": (0, 4, 7, 11), "suffix": "maj7", "prior": -1.1},
    "min7": {"intervals": (0, 3, 7, 10), "suffix": "m7", "prior": -0.9},
    "sus4": {"intervals": (0, 5, 7), "suffix": "sus4", "prior": -0.9},
    "sus2": {"intervals": (0, 2, 7), "suffix": "sus2", "prior": -1.1},
    "dim": {"intervals": (0, 3, 6), "suffix": "dim", "prior": -1.6},
}
QUALITY_ORDER = list(CHORD_QUALITIES)

# Perfiles de Krumhansl-Kessler (tónica = índice 0).
KK_MAJOR = np.array([6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88])
KK_MINOR = np.array([6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17])

#: Acordes diatónicos (grado en semitonos, calidad) — tríadas y cuatríadas comunes.
DIATONIC_MAJOR = {
    (0, "maj"), (0, "maj7"), (0, "sus2"), (0, "sus4"),
    (2, "min"), (2, "min7"),
    (4, "min"), (4, "min7"),
    (5, "maj"), (5, "maj7"), (5, "sus2"),
    (7, "maj"), (7, "7"), (7, "sus4"),
    (9, "min"), (9, "min7"),
    (11, "dim"),
    (10, "maj"),  # bVII (muy común en pop/alabanza)
}
DIATONIC_MINOR = {
    (0, "min"), (0, "min7"), (0, "sus2"), (0, "sus4"),
    (2, "dim"),
    (3, "maj"), (3, "maj7"),
    (5, "min"), (5, "min7"),
    (7, "min"), (7, "maj"), (7, "7"),
    (8, "maj"), (8, "maj7"),
    (10, "maj"), (10, "7"),
}


def uses_flats(tonic: int, mode: str) -> bool:
    return tonic in (FLAT_MAJOR_TONICS if mode == "major" else FLAT_MINOR_TONICS)


def note_name(pc: int, flats: bool = False) -> str:
    return (FLAT_NAMES if flats else SHARP_NAMES)[pc % 12]


def latin_name(pc: int, flats: bool = False) -> str:
    return (LATIN_FLAT if flats else LATIN_SHARP)[pc % 12]


def chord_name(root: int, quality: str, bass: int | None = None, flats: bool = False) -> str:
    name = note_name(root, flats) + CHORD_QUALITIES[quality]["suffix"]
    if bass is not None and bass % 12 != root % 12:
        name += "/" + note_name(bass, flats)
    return name


def key_name(tonic: int, mode: str) -> str:
    flats = uses_flats(tonic, mode)
    return note_name(tonic, flats) + ("m" if mode == "minor" else "")


def key_label(tonic: int, mode: str) -> str:
    flats = uses_flats(tonic, mode)
    return f"{latin_name(tonic, flats)} {'menor' if mode == 'minor' else 'mayor'}"


def chord_templates(bass_weighted: bool = False) -> tuple[np.ndarray, list[tuple[int, str]]]:
    """Plantillas normalizadas (n_acordes x 12) y la lista (fundamental, calidad)."""
    templates, labels = [], []
    for quality in QUALITY_ORDER:
        intervals = CHORD_QUALITIES[quality]["intervals"]
        for root in range(12):
            vec = np.zeros(12)
            for idx, interval in enumerate(intervals):
                # La fundamental pesa más; la séptima/tensiones un poco menos.
                weight = 1.0 if idx == 0 else (0.85 if idx < 3 else 0.7)
                vec[(root + interval) % 12] = weight
            templates.append(vec / np.linalg.norm(vec))
            labels.append((root, quality))
    return np.array(templates), labels


def is_diatonic(root: int, quality: str, tonic: int, mode: str) -> bool:
    degree = (root - tonic) % 12
    table = DIATONIC_MAJOR if mode == "major" else DIATONIC_MINOR
    return (degree, quality) in table


def key_profiles() -> tuple[np.ndarray, list[tuple[int, str]]]:
    """24 perfiles normalizados (media 0, norma 1) para correlacionar con croma."""
    rows, labels = [], []
    for mode, base in (("major", KK_MAJOR), ("minor", KK_MINOR)):
        for tonic in range(12):
            prof = np.roll(base, tonic).astype(float)
            prof = prof - prof.mean()
            rows.append(prof / np.linalg.norm(prof))
            labels.append((tonic, mode))
    return np.array(rows), labels
