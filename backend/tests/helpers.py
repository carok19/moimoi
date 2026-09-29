"""Ayudas para las pruebas: separador falso y funciones para esperar trabajos."""

from __future__ import annotations

import io
import time
import zipfile
from urllib.parse import unquote

import numpy as np
import soundfile as sf
from fastapi.testclient import TestClient

from moimoi.separation.base import Cancelled, assemble_preset_stems, model_for


class FakeSeparator:
    """Devuelve las pistas "reales" de la canción sintética (sin IA), para probar el flujo."""

    name = "fake"

    def __init__(self, stems: dict[str, np.ndarray]):
        self.stems = stems
        self.calls = 0

    def status(self):
        return {"available": True, "device": "cpu", "gpu": None, "detail": "Motor de prueba"}

    def separate(self, audio, preset, quality, progress, should_cancel):
        self.calls += 1
        n = audio.shape[1]
        for step in range(5):
            if should_cancel():
                raise Cancelled()
            progress(step / 5, f"Separando… {step * 20}%")
        sources = {k: np.ascontiguousarray(v[:, :n]) for k, v in self.stems.items()}
        for k, v in sources.items():
            if v.shape[1] < n:
                sources[k] = np.pad(v, ((0, 0), (0, n - v.shape[1])))
        return assemble_preset_stems(preset, sources), model_for(preset, quality)[0]


def download_name(response) -> str:
    """Nombre de archivo del encabezado Content-Disposition (filename* o filename)."""
    header = response.headers["content-disposition"]
    if "filename*=utf-8''" in header:
        return unquote(header.split("filename*=utf-8''", 1)[1])
    return header.split("filename=", 1)[1].strip('"')


def wait_for(client: TestClient, song_id: str, timeout: float = 240) -> dict:
    start = time.time()
    while time.time() - start < timeout:
        song = client.get(f"/api/songs/{song_id}").json()
        if song["status"] in ("ready", "error", "cancelled"):
            return song
        time.sleep(0.3)
    raise AssertionError("La canción no terminó de procesarse a tiempo")


def wait_job(client: TestClient, job_id: str, timeout: float = 240) -> dict:
    start = time.time()
    while time.time() - start < timeout:
        job = client.get(f"/api/jobs/{job_id}").json()
        if job["status"] in ("done", "error", "cancelled"):
            return job
        time.sleep(0.3)
    raise AssertionError("El trabajo no terminó a tiempo")


def upload(client, song_data, preset="6stems", name="Cancion de prueba_demo.wav"):
    _, wav = song_data
    response = client.post(
        "/api/songs/upload",
        files={"file": (name, wav, "audio/wav")},
        data={"preset": preset, "quality": "normal"},
    )
    assert response.status_code == 200, response.text
    return response.json()


# ---- paquete de voces guía de prueba ----------------------------------------------------


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
