"""Pruebas de punta a punta de la API: subir, procesar, reproducir, exportar y borrar."""

from __future__ import annotations

import io
import json
import zipfile

import numpy as np
import pytest
import soundfile as sf
from fastapi.testclient import TestClient

from helpers import FakeSeparator, download_name, upload, wait_for, wait_job
from moimoi import audio_io
from moimoi.analysis import analyze_song
from moimoi.app import create_app
from moimoi.config import Config


def test_health_and_presets(client):
    health = client.get("/api/health").json()
    assert health["ok"] is True
    assert health["engine"]["available"] is True
    assert health["features"]["ffmpeg"] is True
    presets = client.get("/api/presets").json()
    assert [p["id"] for p in presets["presets"]] == ["2stems", "4stems", "6stems"]
    assert presets["default"] == "6stems"


def test_full_flow(client, song_data):
    created = upload(client, song_data)
    assert created["status"] == "queued"
    assert created["title"] == "Cancion de prueba demo"
    song = wait_for(client, created["id"])
    assert song["status"] == "ready", song
    assert [s["id"] for s in song["stems"]] == ["vocals", "drums", "bass", "guitar", "piano", "other"]
    assert song["summary"]["key"] == "G"
    assert song["summary"]["bpm"] == pytest.approx(100.0, abs=0.5)
    assert song["duration"] == pytest.approx(136.9, abs=0.1)

    # Reproducción: FLAC con soporte de rangos (para el navegador).
    stem_url = song["stems"][0]["url"]
    full = client.get(stem_url)
    assert full.status_code == 200 and full.headers["content-type"] == "audio/flac"
    data, sr = sf.read(io.BytesIO(full.content))
    assert sr == 44100 and data.shape[1] == 2
    partial = client.get(stem_url, headers={"Range": "bytes=0-99"})
    assert partial.status_code == 206 and len(partial.content) == 100

    peaks = client.get(f"/api/songs/{song['id']}/peaks").json()
    assert set(peaks["peaks"]) == {"vocals", "drums", "bass", "guitar", "piano", "other", "mix"}
    analysis = client.get(f"/api/songs/{song['id']}/analysis").json()
    assert [s["label"] for s in analysis["sections"]][:3] == ["Intro", "Verso 1", "Coro 1"]

    # Descarga de una pista suelta en MP3.
    mp3 = client.get(f"/api/songs/{song['id']}/download/bass.mp3")
    assert mp3.status_code == 200
    assert download_name(mp3) == "Cancion de prueba demo - Bajo.mp3"
    assert mp3.content[:3] == b"ID3" or mp3.content[0] == 0xFF

    # Guardar ajustes del mezclador y editar el título.
    updated = client.patch(f"/api/songs/{song['id']}", json={
        "title": "Mi canción", "settings": {"mixer": {"vocals": {"volume": 0.5, "mute": True}}},
    }).json()
    assert updated["title"] == "Mi canción"
    assert updated["settings"]["mixer"]["vocals"]["mute"] is True

    # Borrar.
    assert client.delete(f"/api/songs/{song['id']}").json() == {"ok": True}
    assert client.get(f"/api/songs/{song['id']}").status_code == 404


def test_multitrack_export_with_click(client, song_data):
    song = wait_for(client, upload(client, song_data)["id"])
    job = client.post(f"/api/songs/{song['id']}/exports", json={
        "type": "multitrack", "stems": ["drums", "bass", "piano"], "click": True,
    }).json()
    job = wait_job(client, job["id"])
    assert job["status"] == "done", job
    response = client.get(job["downloadUrl"])
    assert response.status_code == 200
    assert download_name(response) == "Cancion de prueba demo.zip"
    with zipfile.ZipFile(io.BytesIO(response.content)) as zf:
        names = sorted(zf.namelist())
        assert names == ["Bajo.wav", "Bateria.wav", "Click.wav", "Piano.wav", "moimoi.json"]
        manifest = json.loads(zf.read("moimoi.json"))
        click, sr = sf.read(io.BytesIO(zf.read("Click.wav")))
        drums, _ = sf.read(io.BytesIO(zf.read("Bateria.wav")))
        info = sf.info(io.BytesIO(zf.read("Bajo.wav")))
    assert info.subtype == "PCM_16" and info.samplerate == 44100 and info.channels == 2
    assert manifest["cancion"]["bpm"] == pytest.approx(100.0, abs=0.5)
    assert manifest["cancion"]["tonalidad"] == "G"
    assert [m["nombre"] for m in manifest["marcadores"]][:2] == ["Intro", "Verso 1"]
    assert manifest["marcadores"][1]["tiempoMs"] == pytest.approx(10100, abs=100)
    assert {p["archivo"] for p in manifest["pistas"]} == {"Bajo.wav", "Bateria.wav", "Piano.wav", "Click.wav"}
    # El click cae sobre los golpes de la batería (primer pulso en 0.5 s).
    first_click = np.argmax(np.abs(click[:, 0]) > 0.1) / sr
    assert first_click == pytest.approx(0.5, abs=0.01)
    assert drums.shape[0] == pytest.approx(click.shape[0], rel=0.01)


def test_mix_export_with_tempo_and_key_change(client, song_data):
    song = wait_for(client, upload(client, song_data, preset="4stems")["id"])
    assert [s["id"] for s in song["stems"]] == ["vocals", "drums", "bass", "other"]
    job = client.post(f"/api/songs/{song['id']}/exports", json={
        "type": "mix", "format": "wav", "tempo": 0.8, "semitones": -2,
        "mixer": {"vocals": {"mute": True}},
    }).json()
    job = wait_job(client, job["id"])
    assert job["status"] == "done", job
    assert job["result"]["name"] == "Cancion de prueba demo (en F, 80%) (mezcla).wav"
    response = client.get(job["downloadUrl"])
    audio, sr = sf.read(io.BytesIO(response.content))
    assert audio.shape[0] / sr == pytest.approx(song["duration"] / 0.8, rel=0.01)


def test_two_stem_and_stems_zip(client, song_data):
    song = wait_for(client, upload(client, song_data, preset="2stems")["id"])
    assert [s["id"] for s in song["stems"]] == ["vocals", "instrumental"]
    job = wait_job(client, client.post(f"/api/songs/{song['id']}/exports",
                                       json={"type": "stems", "format": "mp3"}).json()["id"])
    assert job["status"] == "done", job
    with zipfile.ZipFile(io.BytesIO(client.get(job["downloadUrl"]).content)) as zf:
        assert sorted(zf.namelist()) == ["Acompanamiento.mp3", "Voz.mp3", "moimoi.json"]


def test_validation_errors(client, song_data):
    bad = client.post("/api/songs/upload", files={"file": ("notas.txt", b"hola", "text/plain")})
    assert bad.status_code == 400
    assert client.post("/api/songs/url", json={"url": "no es un link"}).status_code == 400
    assert client.get("/api/songs/..%2F..%2Fetc").status_code == 404
    assert client.get("/api/songs/noexiste/analysis").status_code == 404
    created = upload(client, song_data)
    assert client.get(f"/api/songs/{created['id']}/audio/guitarra_falsa.flac").status_code == 404
    settings = client.put("/api/settings", json={"band": ["vocals", "guitar"], "notation": "latin"}).json()
    assert settings["band"] == ["vocals", "guitar"] and settings["notation"] == "latin"
    assert client.put("/api/settings", json={"band": ["banjo"]}).status_code == 400


def test_cancel_and_retry(client, song_data):
    created = upload(client, song_data)
    client.post(f"/api/songs/{created['id']}/cancel")
    song = wait_for(client, created["id"])
    assert song["status"] in ("cancelled", "ready")
    retried = client.post(f"/api/songs/{created['id']}/retry", json={"preset": "4stems"}).json()
    assert retried["preset"] == "4stems"
    song = wait_for(client, created["id"])
    assert song["status"] == "ready"
    assert len(song["stems"]) == 4


def test_frontend_placeholder(client):
    page = client.get("/")
    assert page.status_code == 200 and "npm run build" in page.text
    assert client.get("/api/nada").status_code == 404


def test_audio_helpers(tmp_path):
    audio = (np.sin(np.linspace(0, 2000, 44100)) * 0.5).astype(np.float32)[None, :].repeat(2, axis=0)
    for fmt in ("wav", "flac", "mp3"):
        path = tmp_path / f"x.{fmt}"
        audio_io.write_audio(path, audio, fmt)
        decoded = audio_io.decode_audio(path)
        assert decoded.shape[0] == 2 and abs(decoded.shape[1] - 44100) < 2400
    peaks = audio_io.compute_peaks(audio)
    assert isinstance(peaks, str) and len(peaks) > 10


def test_max_duration_is_enforced(tmp_path, song_data):
    song, wav = song_data
    cfg = Config(data_dir=tmp_path / "corta", frontend_dir=tmp_path / "x", max_duration_s=30)
    app = create_app(cfg, separator=FakeSeparator(song.stems), analyzer=analyze_song)
    with TestClient(app) as short_client:
        created = upload(short_client, song_data)
        result = wait_for(short_client, created["id"])
    assert result["status"] == "error"
    assert "máximo" in result["error"]
