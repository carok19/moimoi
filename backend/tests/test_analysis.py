"""Pruebas del análisis musical con canciones sintéticas de "verdad" conocida."""

from __future__ import annotations

import numpy as np
import pytest

from moimoi.analysis import analyze_song
from synth import Section, make_song, worship_song

FLAT_TO_SHARP = {"Db": "C#", "Eb": "D#", "Gb": "F#", "Ab": "G#", "Bb": "A#"}


def _sharp(name: str) -> str:
    name = name.split("/")[0]
    for flat, sharp in FLAT_TO_SHARP.items():
        if name.startswith(flat):
            return sharp + name[len(flat):]
    return name


def chord_accuracy(result: dict, song) -> float:
    grid = np.arange(song.beats[0] + 0.1, song.beats[-1] - 0.1, 0.1)

    def at(chords, t):
        for start, end, name in chords:
            if start <= t < end:
                return name
        return None

    predicted = [(c["start"], c["end"], _sharp(c["name"])) for c in result["chords"]]
    return float(np.mean([at(predicted, t) == at(song.chords, t) for t in grid]))


def downbeat_match(result: dict, song) -> float:
    found = np.array(result["downbeats"])
    if found.size == 0:
        return 0.0
    return float(np.mean([np.min(np.abs(song.downbeats - d)) < 0.07 for d in found]))


def section_labels(result: dict) -> list[str]:
    return [s["label"].rstrip(" 0123456789") for s in result["sections"]]


@pytest.fixture(scope="module")
def worship():
    song = worship_song(bpm=100.0)
    return song, analyze_song(song.stems, 44100)


def test_tempo_and_beats(worship):
    song, result = worship
    assert result["tempo"]["bpm"] == pytest.approx(100.0, abs=0.5)
    assert result["tempo"]["steady"] is True
    assert result["tempo"]["beatsPerBar"] == 4
    beats = np.array(result["beats"])
    # Cada pulso real tiene un pulso detectado a menos de 20 ms (el click tiene que coincidir).
    errors = [np.min(np.abs(beats - b)) for b in song.beats]
    assert np.median(errors) < 0.02
    assert downbeat_match(result, song) > 0.9


def test_key_and_chords(worship):
    song, result = worship
    assert result["key"]["name"] == "G"
    assert result["key"]["mode"] == "major"
    assert result["key"]["label"] == "Sol mayor"
    assert result["keyChanges"] == []
    assert chord_accuracy(result, song) > 0.9
    assert abs(result["tuning"]["a4"] - 440) < 3


def test_sections(worship):
    _, result = worship
    assert section_labels(result) == [
        "Intro", "Verso", "Coro", "Verso", "Coro", "Puente", "Coro", "Final",
    ]
    assert result["sections"][0]["start"] == 0.0
    assert result["sections"][-1]["end"] == pytest.approx(result["duration"], abs=0.01)
    assert result["sections"][1]["start"] == pytest.approx(10.1, abs=0.1)


def test_instruments(worship):
    _, result = worship
    instruments = result["instruments"]
    assert set(instruments) == {"vocals", "drums", "bass", "guitar", "piano", "other"}
    assert all(info["level"] != "ausente" for info in instruments.values())
    # El pad ("otros") solo suena en coros y puente: bastante menos que el bajo.
    assert instruments["other"]["presence"] < instruments["bass"]["presence"]
    assert instruments["vocals"]["active"][0][0] == pytest.approx(10.1, abs=0.8)
    assert result["summary"]["instruments"] == list(instruments)


def test_slow_ballad_is_not_read_at_double_tempo():
    song = worship_song(bpm=72.0, transpose=-5)  # en Re mayor
    result = analyze_song(song.stems, 44100)
    assert result["tempo"]["bpm"] == pytest.approx(72.0, abs=0.5)
    assert downbeat_match(result, song) > 0.9
    assert result["key"]["name"] == "D"


def test_waltz_in_minor_key():
    waltz = [
        Section("Intro", ["Am", "F", "C", "G"], vocals=False, energy=0.7),
        Section("Verso", ["Am", "F", "C", "G", "Am", "F", "E", "E"], vocals=True, energy=0.8),
        Section("Coro", ["F", "G", "C", "Am", "F", "G", "E", "Am"], vocals=True, energy=1.2),
        Section("Verso", ["Am", "F", "C", "G", "Am", "F", "E", "E"], vocals=True, energy=0.8),
        Section("Coro", ["F", "G", "C", "Am", "F", "G", "E", "Am"], vocals=True, energy=1.2),
        Section("Final", ["Am", "F", "Am", "Am"], vocals=False, energy=0.7),
    ]
    song = make_song(waltz, bpm=120.0, beats_per_bar=3)
    result = analyze_song(song.stems, 44100)
    assert result["tempo"]["beatsPerBar"] == 3
    assert result["key"]["name"] == "Am"
    assert result["key"]["label"] == "La menor"
    assert downbeat_match(result, song) > 0.9
    assert chord_accuracy(result, song) > 0.85


def test_live_band_without_click():
    song = worship_song(bpm=96.0, drift=0.03)
    result = analyze_song(song.stems, 44100)
    assert result["tempo"]["steady"] is False
    assert result["tempo"]["bpm"] == pytest.approx(96.0, abs=2.0)
    assert downbeat_match(result, song) > 0.9


def test_key_change_in_last_choruses():
    verse = ["G", "D", "Em", "C", "G", "D", "Em", "C"]
    chorus = ["C", "D", "G", "Em", "C", "D", "G", "G"]
    song = make_song([
        Section("Intro", ["G", "C", "G", "D"], vocals=False, energy=0.7),
        Section("Verso", verse, vocals=True, energy=0.8),
        Section("Coro", chorus, vocals=True, energy=1.2),
        Section("Verso", verse, vocals=True, energy=0.8),
        Section("Coro", chorus, vocals=True, energy=1.2),
        Section("Coro", chorus, vocals=True, energy=1.3, transpose=2),
        Section("Coro", chorus, vocals=True, energy=1.3, transpose=2),
        Section("Final", ["A", "D", "A", "A"], vocals=False, energy=0.7),
    ], bpm=110.0)
    result = analyze_song(song.stems, 44100)
    assert result["key"]["name"] == "G"
    assert [c["name"] for c in result["keyChanges"]] == ["A"]
    modulation_time = song.sections[5][0]
    assert result["keyChanges"][0]["time"] == pytest.approx(modulation_time, abs=0.2)
    assert section_labels(result) == ["Intro", "Verso", "Coro", "Verso", "Coro", "Coro", "Coro", "Final"]
    assert chord_accuracy(result, song) > 0.9


def test_two_stem_separation():
    song = worship_song(bpm=100.0)
    stems = {
        "vocals": song.stems["vocals"],
        "instrumental": np.sum([a for n, a in song.stems.items() if n != "vocals"], axis=0),
    }
    result = analyze_song(stems, 44100)
    assert result["tempo"]["bpm"] == pytest.approx(100.0, abs=0.5)
    assert result["key"]["name"] == "G"
    assert chord_accuracy(result, song) > 0.9
    assert section_labels(result)[:3] == ["Intro", "Verso", "Coro"]


def test_silence_and_very_short_audio():
    silence = {"vocals": np.zeros((2, 44100 * 5), dtype=np.float32),
               "instrumental": np.zeros((2, 44100 * 5), dtype=np.float32)}
    result = analyze_song(silence, 44100)
    assert result["beats"] == []
    assert result["instruments"]["vocals"]["level"] == "ausente"
    assert len(result["sections"]) >= 1


def _medley(*songs):
    """Canciones pegadas una detrás de otra (un popurrí): pistas y pulsos reales."""
    stems, beats, starts, offset = {}, [], [], 0.0
    for song in songs:
        starts.append(offset)
        for name, audio in song.stems.items():
            stems[name] = audio if name not in stems else np.concatenate([stems[name], audio], axis=1)
        beats.extend(song.beats + offset)
        offset += song.stems["drums"].shape[1] / 44100
    return stems, np.array(beats), starts


def test_medley_with_tempo_changes():
    first = worship_song(bpm=100.0)
    second = worship_song(bpm=140.0, transpose=2, seed=2)
    stems, true_beats, starts = _medley(first, second)
    result = analyze_song(stems, 44100)
    segments = result["tempo"]["segments"]
    assert [round(s["bpm"]) for s in segments] == [100, 140]
    assert abs(segments[1]["start"] - starts[1]) < 4.0
    assert segments[0]["start"] == 0.0 and segments[-1]["end"] == result["duration"]
    beats = np.array(result["beats"])
    # El click sigue los dos tempos: casi todos los pulsos reales tienen uno detectado muy cerca.
    errors = np.array([np.min(np.abs(beats - b)) for b in true_beats])
    assert np.mean(errors < 0.03) > 0.9
    assert np.all(np.diff(beats) > 0.3)


def test_single_tempo_song_has_one_segment(worship):
    _, result = worship
    segments = result["tempo"]["segments"]
    assert len(segments) == 1 and segments[0]["bpm"] == pytest.approx(100.0, abs=0.5)
    assert segments[0]["steady"] is True and segments[0]["beatsPerBar"] == 4


def test_export_grid_uses_each_part_downbeats_and_the_saved_grid():
    from moimoi import exports

    # Un popurrí: el segundo tramo empieza en 3.0 con su propio "1" (no sigue la cuenta del primero).
    analysis = {"tempo": {"beatsPerBar": 4, "bpm": 120.0}, "beats": [0.5 + 0.5 * i for i in range(12)],
                "downbeats": [0.5, 2.5, 3.0, 5.0]}
    grid = exports.effective_grid(analysis, {})
    assert [b for b, a in zip(grid.beats, grid.accents) if a] == [0.5, 2.5, 3.0, 5.0]
    # "Mover el 1" corre todos los acentos un pulso.
    moved = exports.effective_grid(analysis, {"downbeatShift": 1})
    assert [b for b, a in zip(moved.beats, moved.accents) if a] == [1.0, 3.0, 3.5, 5.5]
    doubled = exports.effective_grid(analysis, {"beatScale": "double"})
    assert len(doubled.beats) == 23 and doubled.bpm == 240.0
    assert [b for b, a in zip(doubled.beats, doubled.accents) if a] == [0.5, 2.5, 3.0, 5.0]
    # El tempo corregido en el reproductor manda (y ya trae aplicadas las otras correcciones).
    saved = {"grid": {"beats": [1.0, 1.6, 2.2, 2.8], "downbeats": [1.0], "bpm": 100}, "beatScale": "double"}
    custom = exports.effective_grid(analysis, saved)
    assert custom.beats == [1.0, 1.6, 2.2, 2.8] and custom.accents == [True, False, False, False] and custom.bpm == 100
