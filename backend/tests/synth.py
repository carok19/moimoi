"""Canciones sintéticas con "verdad" conocida para probar el análisis.

Se generan pistas separadas (batería, bajo, piano, guitarra, pad y una voz
sintética) con tempo, compás, acordes, tonalidad y estructura exactos.
"""

from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np

SR = 44100
NOTE = {"C": 0, "C#": 1, "Db": 1, "D": 2, "D#": 3, "Eb": 3, "E": 4, "F": 5, "F#": 6, "Gb": 6,
        "G": 7, "G#": 8, "Ab": 8, "A": 9, "A#": 10, "Bb": 10, "B": 11}
QUALITY = {"": (0, 4, 7), "m": (0, 3, 7), "7": (0, 4, 7, 10)}


def parse_chord(name: str) -> tuple[int, str, tuple[int, ...]]:
    root = name[:2] if len(name) > 1 and name[1] in "#b" else name[:1]
    suffix = name[len(root):]
    return NOTE[root], suffix, QUALITY[suffix]


SHARPS = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]


def transpose_name(name: str, semitones: int) -> str:
    root, suffix, _ = parse_chord(name)
    return SHARPS[(root + semitones) % 12] + suffix


def midi_hz(m: float) -> float:
    return 440.0 * 2 ** ((m - 69) / 12)


@dataclass
class Section:
    label: str
    chords: list[str]  # un acorde por compás
    vocals: bool
    energy: float = 1.0
    #: Semitonos para esta sección (p. ej. el último coro un tono más arriba).
    transpose: int = 0


@dataclass
class SynthSong:
    stems: dict[str, np.ndarray]
    bpm: float
    beats_per_bar: int
    beats: np.ndarray
    downbeats: np.ndarray
    chords: list[tuple[float, float, str]]
    sections: list[tuple[float, float, str]]
    duration: float
    extra: dict = field(default_factory=dict)


def _env(n: int, attack: float, decay: float) -> np.ndarray:
    t = np.arange(n) / SR
    a = np.minimum(1.0, t / max(attack, 1e-4))
    return a * np.exp(-t / decay)


def _tone(freq: float, n: int, harmonics: int = 6, rolloff: float = 1.3, phase: float = 0.0) -> np.ndarray:
    t = np.arange(n) / SR
    out = np.zeros(n)
    for h in range(1, harmonics + 1):
        if freq * h > SR / 2.2:
            break
        out += np.sin(2 * np.pi * freq * h * t + phase * h) / (h ** rolloff)
    return out


def _add(track: np.ndarray, start: int, clip: np.ndarray) -> None:
    if start >= track.size:
        return
    end = min(track.size, start + clip.size)
    track[start:end] += clip[: end - start]


def make_song(
    sections: list[Section],
    bpm: float = 100.0,
    beats_per_bar: int = 4,
    lead_in: float = 0.5,
    drift: float = 0.0,
    seed: int = 1,
    transpose: int = 0,
) -> SynthSong:
    rng = np.random.default_rng(seed)
    beat_len = 60.0 / bpm
    total_bars = sum(len(s.chords) for s in sections)
    total_beats = total_bars * beats_per_bar
    # Tiempos de los pulsos (con deriva opcional para simular una banda sin click).
    beats = [lead_in]
    for i in range(1, total_beats + 1):
        factor = 1.0 + drift * np.sin(2 * np.pi * i / max(total_beats, 1))
        beats.append(beats[-1] + beat_len * factor)
    beats = np.array(beats)
    duration = float(beats[-1] + 2.0)
    n = int(duration * SR)
    tracks = {name: np.zeros(n) for name in ("vocals", "drums", "bass", "guitar", "piano", "other")}

    kick = _tone(55, int(0.25 * SR), harmonics=2) * _env(int(0.25 * SR), 0.002, 0.08)
    sweep_t = np.arange(int(0.25 * SR)) / SR
    kick += 0.6 * np.sin(2 * np.pi * (150 * np.exp(-sweep_t * 25) + 45) * sweep_t) * _env(sweep_t.size, 0.001, 0.07)
    snare_len = int(0.18 * SR)
    hat_len = int(0.05 * SR)

    chords_truth, sections_truth = [], []
    bar = 0
    for section in sections:
        sec_start = beats[bar * beats_per_bar]
        for chord in section.chords:
            b0 = bar * beats_per_bar
            t0, t1 = beats[b0], beats[b0 + beats_per_bar]
            root, _, intervals = parse_chord(chord)
            shift = transpose + section.transpose
            root = (root + shift) % 12
            chords_truth.append((float(t0), float(t1), transpose_name(chord, shift)))
            for k in range(beats_per_bar):
                bt = beats[b0 + k]
                start = int(bt * SR)
                next_bt = beats[b0 + k + 1]
                span = int((next_bt - bt) * SR)
                e = section.energy
                # Batería
                if k == 0 or (k == 2 and beats_per_bar == 4):
                    _add(tracks["drums"], start, 0.9 * e * kick)
                if (k % 2 == 1 and beats_per_bar == 4) or (beats_per_bar == 3 and k > 0):
                    noise = rng.standard_normal(snare_len) * _env(snare_len, 0.001, 0.05)
                    _add(tracks["drums"], start, 0.35 * e * noise + 0.2 * e * _tone(190, snare_len, 2) * _env(snare_len, 0.001, 0.04))
                for half in (0, 1):
                    hat = rng.standard_normal(hat_len) * _env(hat_len, 0.0005, 0.012)
                    hat = np.diff(np.concatenate([[0], hat]))  # "pasa-altos" barato
                    _add(tracks["drums"], start + half * span // 2, 0.12 * e * hat)
                # Bajo: fundamental en octava 2, corcheas.
                bass_hz = midi_hz(36 + root if root >= 4 else 48 + root)
                for half in (0, 1):
                    length = span // 2
                    note = _tone(bass_hz, length, harmonics=5, rolloff=1.6) * _env(length, 0.005, 0.25)
                    _add(tracks["bass"], start + half * length, 0.35 * note)
                # Piano: tríada en negras (octava 4).
                piano = np.zeros(span)
                for iv in intervals[:3]:
                    piano += _tone(midi_hz(60 + (root + iv) % 12), span, harmonics=7, rolloff=1.8)
                _add(tracks["piano"], start, 0.12 * piano * _env(span, 0.003, 0.35))
                # Guitarra: rasgueo en corcheas (más en el coro).
                if section.energy >= 1.0 or k % 2 == 0:
                    for half in (0, 1):
                        length = span // 2
                        strum = np.zeros(length)
                        for j, iv in enumerate((0, 7, 12, 16 if intervals[1] == 4 else 15, 19)):
                            delay = int(j * 0.006 * SR)
                            freq = midi_hz(43 + (root - 7) % 12 + iv)
                            tone = _tone(freq, length - delay, harmonics=9, rolloff=1.1)
                            strum[delay:] += tone * _env(length - delay, 0.002, 0.18)
                        _add(tracks["guitar"], start + half * length, 0.05 * section.energy * strum)
                # Voz: nota de la tríada por pulso con vibrato.
                if section.vocals:
                    tone_iv = intervals[[0, 1, 2, 1][k % 4] % len(intervals)]
                    pc = (root + tone_iv) % 12
                    midi = 72 + pc if pc < 5 else 60 + pc
                    t = np.arange(span) / SR
                    f = midi_hz(midi) * (1 + 0.006 * np.sin(2 * np.pi * 5.5 * t))
                    phase = 2 * np.pi * np.cumsum(f) / SR
                    voice = sum(np.sin(h * phase) * (1.0 / h) * (1.6 if 2 <= h <= 4 else 1.0) for h in range(1, 9))
                    _add(tracks["vocals"], start, 0.13 * voice * _env(span, 0.03, 1.5))
            # Pad (otros) sostenido en coros/puente.
            if section.energy >= 1.0:
                length = int((t1 - t0) * SR)
                pad = sum(_tone(midi_hz(48 + (root + iv) % 12 + 12), length, harmonics=4, rolloff=2.0)
                          for iv in intervals[:3])
                _add(tracks["other"], int(t0 * SR), 0.05 * pad * np.minimum(1, np.arange(length) / (0.3 * SR)))
            bar += 1
        sections_truth.append((float(sec_start), float(beats[bar * beats_per_bar]), section.label))

    stems = {}
    for name, mono in tracks.items():
        stereo = np.vstack([mono, mono * (0.9 if name in ("guitar", "piano") else 1.0)])
        stems[name] = stereo.astype(np.float32)
    return SynthSong(
        stems=stems,
        bpm=bpm,
        beats_per_bar=beats_per_bar,
        beats=beats[:-1],
        downbeats=beats[:-1][::beats_per_bar],
        chords=chords_truth,
        sections=sections_truth,
        duration=duration,
    )


def worship_song(**kwargs) -> SynthSong:
    """Estructura típica: intro, verso, coro, verso, coro, puente, coro, final (en Sol mayor)."""
    verse = ["G", "D", "Em", "C", "G", "D", "Em", "C"]
    chorus = ["C", "D", "G", "Em", "C", "D", "G", "G"]
    bridge = ["Em", "C", "G", "D", "Em", "C", "G", "D"]
    structure = [
        Section("Intro", ["G", "C", "G", "D"], vocals=False, energy=0.7),
        Section("Verso", verse, vocals=True, energy=0.8),
        Section("Coro", chorus, vocals=True, energy=1.2),
        Section("Verso", verse, vocals=True, energy=0.8),
        Section("Coro", chorus, vocals=True, energy=1.2),
        Section("Puente", bridge, vocals=True, energy=1.0),
        Section("Coro", chorus, vocals=True, energy=1.25),
        Section("Final", ["G", "C", "G", "G"], vocals=False, energy=0.7),
    ]
    return make_song(structure, **kwargs)
