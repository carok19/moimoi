"""Pruebas de entrada de canciones: nombres, links y descarga (con un servidor HTTP local)."""

from __future__ import annotations

import functools
import http.server
import threading

import numpy as np
import pytest
import soundfile as sf

from moimoi import ingest
from moimoi.separation.base import Cancelled


@pytest.mark.parametrize("title, expected", [
    ("Artista - Mi Canción (Video Oficial)", ("Mi Canción", "Artista")),
    ("Banda – Canción de prueba [Official Audio]", ("Canción de prueba", "Banda")),
    ("Canción sin artista (Letra)", ("Canción sin artista", "Canal")),
    ("Tema en vivo | En Vivo", ("Tema en vivo", "Canal")),
])
def test_guess_title_artist(title, expected):
    assert ingest.guess_title_artist({"title": title, "uploader": "Canal"}) == expected


def test_guess_title_uses_music_metadata():
    info = {"title": "lo que sea", "track": "Nombre Real", "artist": "Artista Real, Otro"}
    assert ingest.guess_title_artist(info) == ("Nombre Real", "Artista Real")


def test_title_from_filename():
    assert ingest.title_from_filename("01 - Artista - Tema_nuevo.mp3") == ("Tema nuevo", "Artista")
    assert ingest.title_from_filename("mi_cancion.wav") == ("mi cancion", None)


def test_upload_extension_check():
    assert ingest.check_upload_name("x.MP3") == ".mp3"
    with pytest.raises(ingest.IngestError):
        ingest.check_upload_name("documento.pdf")


def test_is_url():
    assert ingest.is_url("https://www.youtube.com/watch?v=abc")
    assert not ingest.is_url("youtube.com/watch?v=abc")
    assert not ingest.is_url("hola")


@pytest.fixture()
def audio_server(tmp_path, monkeypatch):
    for var in ("NO_PROXY", "no_proxy"):
        monkeypatch.setenv(var, "127.0.0.1,localhost")
    audio = (np.sin(np.linspace(0, 3000, 44100 * 4)) * 0.3).astype(np.float32)
    sf.write(tmp_path / "prueba_de_link.wav", np.stack([audio, audio], axis=1), 44100)
    handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(tmp_path))
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    yield f"http://127.0.0.1:{server.server_address[1]}/prueba_de_link.wav"
    server.shutdown()


def test_download_direct_link(audio_server, tmp_path):
    progress = []
    dest = tmp_path / "cancion"
    info = ingest.download(audio_server, dest, lambda f, m: progress.append(f), lambda: False, 1200)
    assert info["path"].name.startswith("original.")
    assert info["path"].stat().st_size > 1000
    assert "prueba" in info["title"]
    assert progress and progress[-1] == 1.0


def test_download_can_be_cancelled(audio_server, tmp_path):
    with pytest.raises(Cancelled):
        ingest.download(audio_server, tmp_path / "cancelada", lambda f, m: None, lambda: True, 1200)
