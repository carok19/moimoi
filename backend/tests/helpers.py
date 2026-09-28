"""Ayudas para las pruebas: separador falso y funciones para esperar trabajos."""

from __future__ import annotations

import time
from urllib.parse import unquote

import numpy as np
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
