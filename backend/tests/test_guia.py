"""Voz guía: reconocer paquetes de voces, ubicar cada voz en su compás y exportar la pista Guía."""

from __future__ import annotations

import io
import json
import zipfile

import numpy as np
import pytest
import soundfile as sf

from moimoi import exports, guia
from moimoi.separation.base import SAMPLE_RATE
from helpers import download_name, upload, wait_for, wait_job

# Nombres como los de los paquetes que se descargan de los sitios de secuencias.
PACK_NAMES = {
    "Spanish - Intro.wav": "intro",
    "Spanish - Verso (Verse).wav": "verso",
    "Spanish - Verso 1 (Verse 1).wav": "verso1",
    "Spanish - Verso 5 (Verse 5).wav": "verso5",
    "Spanish - Pre Coro (Pre Chorus).wav": "precoro",
    "Spanish - Pre Coro 3 (Pre Chorus 3).wav": "precoro3",
    "Spanish - Coro (Chorus).wav": "coro",
    "Spanish - Coro 2 (Chorus 2).wav": "coro2",
    "Spanish - Post Coro (Post Chorus).wav": "postcoro",
    "Spanish - Puente 4 (Bridge 4).wav": "puente4",
    "Spanish - Interludio (Interlude).wav": "interludio",
    "Spanish - Baja Intensidad (Breakdown).wav": "breakdown",
    "Spanish - Repetir (Tag).wav": "tag",
    "Spanish - Ending (Final).wav": "final",
    "Spanish - Outro.wav": "outro",
    "Spanish - Exhortation.wav": "exhortacion",
    "Spanish - 1.wav": "n1",
    "Spanish - 7.wav": "n7",
    "Spanish - Key Change Up (Sube Tono).wav": "sube",
    "Spanish - Key Change Down (Baja Tono).wav": "baja",
    "Spanish - Build (Sube Intensidad).wav": "build",
    "Spanish - Big Ending (Final Grande).wav": "bigending",
    "Spanish - Drums In (Entra Bateria).wav": "drumsin",
    "Spanish - Drums (Bateria).wav": "drums",
    "Spanish - Bass (Bajo).wav": "bass",
    "Spanish - All In (Toda La Banda).wav": "todos",
    "Spanish - Last Time (Ultima Vez).wav": "ultimavez",
    "Spanish - Break (Pausa).wav": "break",
    "Spanish - A Capella.wav": "acapella",
    "English Female - Slowly Build.wav": "slowbuild",
    "English Female - Worship Freely.wav": "libre",
    "English Female - Turnaround.wav": "turnaround",
    "French Guide -  Swell.wav": "swell",
    "Portugese - Channel.wav": "channel",
    "Portugese - Click.wav": "click",
    # Otros estilos de nombre
    "01 - Coro.mp3": "coro",
    "VG_PreCoro.wav": "precoro",
    "Verso2.wav": "verso2",
    "uno.wav": "n1",
    "04.wav": "n4",
    "1 2 3 4.wav": "cuenta",
    "Cuenta.m4a": "cuenta",
    "Mi voz rara.wav": None,
}


@pytest.mark.parametrize("name,cue", PACK_NAMES.items())
def test_classify_pack_names(name, cue):
    assert guia.classify(name) == cue


def test_detect_language_and_clicks():
    assert guia.detect_set("Click and Guide Samples/Spanish Guides/Song Sections/Spanish - 1.wav") == ("es", "Español")
    assert guia.detect_set("English Guides/Song Sections/English Female - Verse.wav") == ("en-f", "Inglés (mujer)")
    assert guia.detect_set("French Guides/Dynamic Cues/French Guide -  Break.wav") == ("fr", "Francés")
    assert guia.detect_set("Portugese - Verse.wav") == ("pt", "Portugués")
    assert guia.detect_set("Coro.wav") is None
    assert guia.classify_click("Click and Guide Samples/Click Tracks/New Click -  Classic-accents.wav") == (
        "classic", "Classic", "accent")
    assert guia.classify_click("Click Tracks/New Click -  Cowbell-quarter.wav") == ("cowbell", "Cowbell", "beat")
    assert guia.classify_click("Click Tracks/New Click -  Blip-sixteenth.wav")[2] == "sixteenth"
    # Una voz que dice "click" o un paquete cuya carpeta principal dice "Click" no son sonidos de click.
    assert guia.classify_click("Portugese Guides/Dynamic Cues/Portugese - Click.wav") is None
    assert guia.classify_click("Click and Guide Samples/Spanish Guides/Song Sections/Spanish - 1.wav") is None


def test_cue_for_section_labels():
    available = {"intro", "verso", "verso1", "verso2", "coro", "coro2", "precoro1", "puente", "outro"}
    assert guia.cue_for_label("Verso 2", available) == "verso2"
    assert guia.cue_for_label("Verso 3", available) == "verso"  # no hay "Verso 3": dice "Verso"
    assert guia.cue_for_label("Coro 2", available) == "coro"  # por defecto el coro no se numera
    assert guia.cue_for_label("Coro 2", available, "all") == "coro2"
    assert guia.cue_for_label("Verso 2", available, "none") == "verso"
    assert guia.cue_for_label("Pre-coro 1", available) == "precoro1"  # sin "Pre-coro" suelto, usa el 1
    assert guia.cue_for_label("Pre-coro 2", available) is None
    assert guia.cue_for_label("Final", available) == "outro"
    assert guia.cue_for_label("Parte A", available) is None
    assert guia.cue_for_label("Sube tono", {"sube"}) is None  # las indicaciones no son partes


def tone(freq: float, seconds: float = 0.25, sr: int = 48000, lead_silence: float = 0.0) -> np.ndarray:
    t = np.arange(int(seconds * sr)) / sr
    burst = 0.5 * np.sin(2 * np.pi * freq * t) * np.minimum(1, (seconds - t) / 0.02)
    audio = np.concatenate([np.zeros(int(lead_silence * sr)), burst, np.zeros(int(0.5 * sr))])
    return np.stack([audio, audio], axis=1)


def wav_bytes(audio: np.ndarray, sr: int = 48000, subtype: str = "PCM_24") -> bytes:
    buffer = io.BytesIO()
    sf.write(buffer, audio, sr, format="WAV", subtype=subtype)
    return buffer.getvalue()


# Cada voz de prueba es un tono con su propia frecuencia, para reconocerla en la pista Guía.
VOICE_FREQS = {
    "Intro": 300, "Verso 1 (Verse 1)": 400, "Verso 2 (Verse 2)": 450, "Verso (Verse)": 500,
    "Coro (Chorus)": 600, "Puente (Bridge)": 700, "Ending (Final)": 800, "Pre Coro (Pre Chorus)": 900,
    "1": 1000, "2": 1100, "3": 1200, "4": 1300, "Key Change Up (Sube Tono)": 1500,
}


def make_pack(damaged: bool = True) -> bytes:
    """Paquete con dos idiomas, sonidos de click, basura de Mac/Ableton y un archivo dañado."""
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as zf:
        for label, freq in VOICE_FREQS.items():
            zf.writestr(f"Guide Pack/Spanish Guides/Song Sections/Spanish - {label}.wav", wav_bytes(tone(freq)))
        zf.writestr("Guide Pack/English Guides/Song Sections/English Female - Chorus.wav", wav_bytes(tone(610)))
        zf.writestr("Guide Pack/English Guides/Song Sections/English Female - 1.wav", wav_bytes(tone(1010)))
        zf.writestr("Guide Pack/Click Tracks/New Click -  Classic-accents.wav",
                    wav_bytes(tone(2500, 0.03, 44100), 44100, "PCM_16"))
        zf.writestr("Guide Pack/Click Tracks/New Click -  Classic-quarter.wav",
                    wav_bytes(tone(2000, 0.03, 44100), 44100, "PCM_16"))
        zf.writestr("Guide Pack/Spanish Guides/Song Sections/Spanish - Solo.wav", wav_bytes(np.zeros((4800, 2))))
        zf.writestr("Guide Pack/Spanish Guides/Song Sections/Spanish - 1.wav.asd", b"ableton")
        zf.writestr("__MACOSX/Guide Pack/._Spanish - Intro.wav", b"mac")
        zf.writestr("Guide Pack/Project/Samples/Processed/Freeze/Blank Accent.wav", wav_bytes(np.zeros((4800, 2))))
        if damaged:
            zf.writestr("Guide Pack/Spanish Guides/Song Sections/Spanish - Vamp.wav", wav_bytes(tone(1700)))
    data = bytearray(buffer.getvalue())
    if damaged:
        # Como una descarga incompleta: un pedazo del archivo quedó en ceros.
        with zipfile.ZipFile(io.BytesIO(bytes(data))) as zf:
            info = zf.getinfo("Guide Pack/Spanish Guides/Song Sections/Spanish - Vamp.wav")
        start = info.header_offset + 30 + len(info.filename) + 40
        data[start:start + 2000] = bytes(2000)
    return bytes(data)


def test_import_pack_with_languages_clicks_and_damage(tmp_path):
    kit = guia.GuideKit(tmp_path / "voz-guia")
    pack = tmp_path / "Guide Pack.zip"
    pack.write_bytes(make_pack())
    summary = kit.import_files([("Guide Pack.zip", pack)])
    assert summary["added"] == len(VOICE_FREQS) + 2
    assert summary["recognized"] == summary["added"]
    assert summary["clicks"] == 1
    assert summary["sets"] == ["en-f", "es"]
    assert [e["original"] for e in summary["errors"]] == ["Spanish - Vamp.wav"]
    assert [s["original"] for s in summary["skipped"]] == ["Spanish - Solo.wav"]  # sin sonido

    info = kit.describe()
    assert info["active"] == "es"  # el español se elige solo
    assert {s["id"]: s["files"] for s in info["sets"]} == {"es": len(VOICE_FREQS), "en-f": 2}
    cues = {c["id"]: c["file"] for c in info["cues"]}
    assert cues["coro"] and cues["n4"] and cues["sube"] and not cues["puente1"]
    assert "Verso 3" in info["missing"] and "Coro" not in info["missing"]
    assert info["clicks"][0]["id"] == "classic" and set(info["clicks"][0]["sounds"]) == {"accent", "beat"}

    # Voces: mono, sin el silencio del principio y con el pico en -1 dBFS.
    coro = kit.assignments()["coro"]
    audio, sr = sf.read(coro)
    assert sr == SAMPLE_RATE and audio.ndim == 1
    assert np.max(np.abs(audio)) == pytest.approx(0.89, abs=0.01)
    assert np.argmax(np.abs(audio) > 0.1) < 0.02 * sr

    # Cargar otra vez el mismo paquete no duplica nada.
    kit.import_files([("Guide Pack.zip", pack)])
    assert {s["id"]: s["files"] for s in kit.describe()["sets"]} == {"es": len(VOICE_FREQS), "en-f": 2}

    # Otro idioma, reasignar, grabar una voz propia y borrar.
    kit.set_active("en-f")
    assert set(kit.assignments()) == {"coro", "n1"}
    english = kit.describe()
    chorus_id = next(f["id"] for f in english["files"] if f["cue"] == "coro")
    kit.assign(chorus_id, "coro2")
    assert set(kit.assignments()) == {"coro2", "n1"}
    recording = tmp_path / "grabacion.webm"
    recording.write_bytes(wav_bytes(tone(900)))
    kit.import_files([("grabacion.webm", recording)], forced_cue="puente", target_set="en-f")
    assert set(kit.assignments()) == {"coro2", "n1", "puente"}
    kit.remove_set("en-f")
    assert kit.describe()["active"] == "es"
    kit.remove_clicks("classic")
    assert kit.describe()["clicks"] == []
    kit.clear()
    assert kit.describe()["sets"] == []


def test_plan_guide_timing():
    period = 0.6  # 100 BPM
    beats = [0.5 + i * period for i in range(80)]
    sections = [
        {"start": 0.5, "label": "Intro"},
        {"start": 10.1, "label": "Verso 1"},
        {"start": 19.7, "label": "Coro 1"},
        {"start": 29.3, "label": "Verso 2", "guide": ""},  # sin voz (elegido a mano)
        {"start": 38.9, "label": "Coro 2", "guideExtra": "ultimavez"},
    ]
    timeline = exports.plan_timeline(exports.Grid(beats, [i % 4 == 0 for i in range(80)], 4, 100.0), 1.0, 1)
    assert timeline.pre_roll == pytest.approx(1.95)
    assert timeline.count_times == pytest.approx([0.05, 0.65, 1.25, 1.85])
    available = {"intro", "verso1", "coro", "n1", "n2", "n3", "n4", "sube", "ultimavez"}
    placements = guia.plan_guide(sections, beats, 4, available, timeline.out, timeline.count_times,
                                 extras={2: "sube"})
    got = [(p.cue, round(p.time, 2)) for p in placements]
    assert got == [
        ("n1", 0.05), ("n2", 0.65), ("n3", 1.25), ("n4", 1.85),  # cuenta; el Intro no se anuncia
        ("verso1", round(1.95 + 10.1 - 2.4, 2)),  # un compás antes
        ("sube", round(1.95 + 19.7 - 4.8, 2)), ("coro", round(1.95 + 19.7 - 2.4, 2)),
        ("ultimavez", round(1.95 + 38.9 - 4.8, 2)), ("coro", round(1.95 + 38.9 - 2.4, 2)),
    ]
    # Dos compases de cuenta: "1 … 2 …" y después "1 2 3 4".
    two_bars = exports.plan_timeline(exports.Grid(beats, [i % 4 == 0 for i in range(80)], 4, 100.0), 1.0, 2)
    count = [p.cue for p in guia.plan_guide([], beats, 4, available, two_bars.out, two_bars.count_times)]
    assert count == ["n1", "n2", "n1", "n2", "n3", "n4"]
    # Sin números cargados pero con la cuenta completa grabada: suena al principio del último compás.
    only_count = guia.plan_guide([], beats, 4, {"cuenta"}, two_bars.out, two_bars.count_times)
    assert [(p.cue, round(p.time, 2)) for p in only_count] == [("cuenta", round(two_bars.count_times[4], 2))]


def test_key_change_extras_and_render():
    analysis = {"key": {"tonic": 7, "mode": "major"}, "keyStart": {"tonic": 7, "mode": "major"},
                "keyChanges": [{"time": 60.0, "tonic": 9, "mode": "major"}, {"time": 90.2, "tonic": 7, "mode": "major"}]}
    sections = [{"start": 0.0}, {"start": 30.0}, {"start": 60.0}, {"start": 90.0}]
    assert exports.key_change_extras(analysis, sections) == {2: "sube", 3: "baja"}
    assert exports.key_change_extras({**analysis, "keyChanges": [{"time": 60, "tonic": 4, "mode": "minor"}]},
                                     sections) == {}  # relativa menor: no es un cambio de tono

    cues = {"a": np.ones(4410, dtype=np.float32), "b": np.full(4410, 0.5, dtype=np.float32)}
    placements = [guia.Placement("a", 0.0, ""), guia.Placement("b", 0.05, ""), guia.Placement("a", 0.5, ""),
                  guia.Placement("b", 0.5, "")]
    out = guia.render_guide(placements, cues, SAMPLE_RATE)
    assert out.shape == (2, SAMPLE_RATE)
    cut = int(0.05 * SAMPLE_RATE)
    assert out[0, cut - 1] < 0.1 and out[0, cut + 10] == pytest.approx(0.5)  # la primera se corta con fundido
    at = int(0.5 * SAMPLE_RATE)
    assert out[0, at + 10] == pytest.approx(0.5)  # dos voces juntas: queda la última


def dominant_freq(audio: np.ndarray, start: int, sr: int, length: float = 0.12) -> float:
    window = audio[start:start + int(length * sr)]
    spectrum = np.abs(np.fft.rfft(window * np.hanning(window.size)))
    return float(np.fft.rfftfreq(window.size, 1 / sr)[np.argmax(spectrum)])


def onsets(audio: np.ndarray, sr: int, threshold: float = 0.05, gap: float = 0.05) -> list[int]:
    """Comienzo de cada sonido (separados por al menos `gap` segundos de silencio)."""
    loud = np.nonzero(np.abs(audio) > threshold)[0]
    if loud.size == 0:
        return []
    return [int(loud[0])] + [int(loud[i + 1]) for i in np.nonzero(np.diff(loud) > gap * sr)[0]]


def test_multitrack_export_with_guide_and_count_in(client, song_data):
    response = client.post("/api/guia", files=[("files", ("Guide Pack.zip", make_pack(damaged=False), "application/zip"))])
    assert response.status_code == 200, response.text
    assert response.json()["active"] == "es"
    song = wait_for(client, upload(client, song_data)["id"])
    job = client.post(f"/api/songs/{song['id']}/exports", json={
        "type": "multitrack", "stems": ["drums", "bass"], "click": True, "clickSound": "classic",
        "guide": True, "preRollBars": 1,
    }).json()
    job = wait_job(client, job["id"])
    assert job["status"] == "done", job
    response = client.get(job["downloadUrl"])
    assert download_name(response) == "Cancion de prueba demo.zip"
    with zipfile.ZipFile(io.BytesIO(response.content)) as zf:
        manifest = json.loads(zf.read("moimoi.json"))
        tracks = {name: sf.read(io.BytesIO(zf.read(name))) for name in zf.namelist() if name.endswith(".wav")}
    assert [p["archivo"] for p in manifest["pistas"]] == ["Click.wav", "Guia.wav", "Bateria.wav", "Bajo.wav"]
    assert [p["nombre"] for p in manifest["pistas"]][:2] == ["Click", "Guía"]
    lengths = {name: audio.shape[0] for name, (audio, _) in tracks.items()}
    assert len(set(lengths.values())) == 1, lengths  # todas las pistas del mismo largo

    pre_roll = manifest["cancion"]["cuentaInicialMs"] / 1000
    assert pre_roll == pytest.approx(1.95, abs=0.03)  # 1 compás a 100 BPM, el primer "1" en 0.5 s
    assert lengths["Bateria.wav"] / SAMPLE_RATE == pytest.approx(song["duration"] + pre_roll, abs=0.01)
    markers = manifest["marcadores"]
    assert markers[0]["nombre"] == "Intro" and markers[0]["tiempoMs"] == 0
    assert markers[1]["nombre"] == "Verso 1"
    assert markers[1]["tiempoMs"] == pytest.approx((10.1 + pre_roll) * 1000, abs=100)
    assert manifest["cancion"]["duracionMs"] == pytest.approx(lengths["Bateria.wav"] / SAMPLE_RATE * 1000, abs=1)

    # La batería quedó corrida por la cuenta: su primer golpe está en 0.5 s + la cuenta.
    drums = tracks["Bateria.wav"][0][:, 0]
    assert np.argmax(np.abs(drums) > 0.1) / SAMPLE_RATE == pytest.approx(0.5 + pre_roll, abs=0.02)

    # Click: la cuenta (4 pulsos) y después la canción; con el sonido "Classic" del paquete.
    click = tracks["Click.wav"][0][:, 0]
    click_onsets = [s / SAMPLE_RATE for s in onsets(click, SAMPLE_RATE)]
    assert click_onsets[:5] == pytest.approx([0.05, 0.65, 1.25, 1.85, 0.5 + pre_roll], abs=0.01)
    freqs = [dominant_freq(click, int(t * SAMPLE_RATE), SAMPLE_RATE, 0.025) for t in click_onsets[:5]]
    assert freqs == pytest.approx([2500, 2000, 2000, 2000, 2500], abs=60)

    # Guía: cada voz suena donde dice el manifiesto, con su tono.
    guide = tracks["Guia.wav"][0][:, 0]
    by_cue = {"n1": 1000, "n2": 1100, "n3": 1200, "n4": 1300, "verso1": 400, "verso2": 450, "coro": 600,
              "puente": 700, "final": 800, "intro": 300, "precoro": 900, "verso": 500}
    planned = manifest["guia"]
    assert [p["voz"] for p in planned][:5] == ["n1", "n2", "n3", "n4", "verso1"]
    guide_onsets = onsets(guide, SAMPLE_RATE)
    assert len(guide_onsets) == len(planned)
    for entry, start in zip(planned, guide_onsets):
        assert start / SAMPLE_RATE == pytest.approx(entry["tiempoMs"] / 1000, abs=0.015), entry
        assert dominant_freq(guide, start, SAMPLE_RATE) == pytest.approx(by_cue[entry["voz"]], abs=15), entry
    verse = next(p for p in planned if p["voz"] == "verso1")
    assert verse["tiempoMs"] / 1000 == pytest.approx(pre_roll + 10.1 - 2.4, abs=0.03)


def test_guide_export_requires_voices(client, song_data):
    song = wait_for(client, upload(client, song_data)["id"])
    job = wait_job(client, client.post(f"/api/songs/{song['id']}/exports", json={
        "type": "multitrack", "guide": True}).json()["id"])
    assert job["status"] == "error"
    assert "voces guía" in job["error"]


def test_guide_api(client, tmp_path):
    empty = client.get("/api/guia").json()
    assert empty["sets"] == [] and empty["active"] is None and len(empty["cues"]) == len(guia.CUE_TYPES)
    response = client.post("/api/guia", files=[("files", ("Guide Pack.zip", make_pack(), "application/zip"))])
    data = response.json()
    assert data["summary"]["errors"][0]["original"] == "Spanish - Vamp.wav"
    file_id = next(f["id"] for f in data["files"] if f["cue"] == "coro")
    audio = client.get(f"/api/guia/audio/{file_id}.wav")
    assert audio.status_code == 200 and audio.headers["content-type"] == "audio/wav"
    assert client.get("/api/guia/audio/zzzz.wav").status_code == 404
    assert client.put(f"/api/guia/{file_id}", json={"cue": "no-existe"}).status_code == 400
    moved = client.put(f"/api/guia/{file_id}", json={"cue": "coro3"}).json()
    assert next(c for c in moved["cues"] if c["id"] == "coro3")["file"] == file_id
    # Grabación hecha en la app (el navegador la manda como webm/ogg): va al idioma elegido.
    rec = client.post("/api/guia", files=[("files", ("grabacion.webm", wav_bytes(tone(333)), "audio/webm"))],
                      data={"cue": "puente1", "set": "es"}).json()
    assert next(c for c in rec["cues"] if c["id"] == "puente1")["file"]
    assert client.put("/api/guia/activo", json={"set": "en-f"}).json()["active"] == "en-f"
    assert client.put("/api/guia/activo", json={"set": "xx"}).status_code == 404
    only_es = client.delete("/api/guia", params={"set": "en-f"}).json()
    assert [s["id"] for s in only_es["sets"]] == ["es"] and only_es["active"] == "es"
    assert client.delete("/api/guia/clicks/classic").json()["clicks"] == []
    assert client.delete("/api/guia").json()["sets"] == []
