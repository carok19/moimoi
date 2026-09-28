"""Red local: celulares (permiso y HTTPS) y envío directo a Multitrack Alabanza."""

from __future__ import annotations

import io
import json
import socket
import ssl
import threading
import time
import urllib.parse
import urllib.request
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest
from fastapi.testclient import TestClient

from moimoi import multitrack, red
from moimoi.__main__ import start_https
from moimoi.app import create_app
from moimoi.config import Config
from helpers import FakeSeparator, upload, wait_for, wait_job


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def test_loopback_detection():
    assert red.is_loopback("127.0.0.1") and red.is_loopback("::1") and red.is_loopback("localhost")
    assert red.is_loopback("testclient")
    assert not red.is_loopback("192.168.1.40") and not red.is_loopback(None)


def test_phones_need_permission(client):
    app = client.app
    phone = TestClient(app, client=("192.168.1.40", 50123))
    assert phone.get("/api/health").status_code == 200  # permitido por defecto
    # Solo la computadora puede cambiar el permiso.
    assert phone.put("/api/settings", json={"lanAccess": False}).status_code == 403
    assert client.put("/api/settings", json={"lanAccess": False}).json()["lanAccess"] is False
    time.sleep(2.1)  # el permiso se relee cada 2 segundos
    blocked = phone.get("/api/health")
    assert blocked.status_code == 403 and "Permitir celulares" in blocked.json()["detail"]
    assert client.get("/api/health").status_code == 200  # la computadora sigue entrando
    client.put("/api/settings", json={"lanAccess": True})
    time.sleep(2.1)
    assert phone.get("/api/songs").status_code == 200


def test_network_info(client):
    info = client.get("/api/red").json()
    assert info["local"] is True and info["port"] == 4747 and info["lanAccess"] is True
    assert info["httpsPort"] is None and info["https"] == []
    assert all(url.startswith("http://") and url.endswith(":4747") for url in info["http"])
    settings = client.put("/api/settings", json={"exportPreRollBars": 2, "guideNumbering": "all",
                                                  "exportClickSound": "cowbell"}).json()
    assert (settings["exportPreRollBars"], settings["guideNumbering"], settings["exportClickSound"]) == (2, "all", "cowbell")
    assert client.put("/api/settings", json={"exportPreRollBars": 3}).status_code == 400
    assert client.put("/api/settings", json={"guideNumbering": "x"}).status_code == 400
    assert client.put("/api/settings", json={"multitrackUrl": "192.168.1.10"}).json()["multitrackUrl"] == \
        "http://192.168.1.10:4848"


class FakeMultitrack(BaseHTTPRequestHandler):
    """Imita la API de Multitrack Alabanza (GET /api/info, POST /api/importar)."""

    received: list[dict] = []

    def log_message(self, *args):  # silencio en las pruebas
        pass

    def _json(self, status: int, data: dict) -> None:
        body = json.dumps(data).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):  # noqa: N802
        if self.path == "/api/info":
            self._json(200, {"app": "multitrack-alabanza", "importar": True, "bloqueado": False})
        else:
            self._json(404, {"error": "no"})

    def do_POST(self):  # noqa: N802
        length = int(self.headers["Content-Length"])
        body = self.rfile.read(length)
        name = urllib.parse.unquote(self.headers["X-Nombre-Archivo"])
        with zipfile.ZipFile(io.BytesIO(body)) as zf:
            files = sorted(zf.namelist())
        FakeMultitrack.received.append({"name": name, "type": self.headers["Content-Type"], "files": files})
        if "rechazar" in name:
            self._json(400, {"error": "El zip no tiene audios"})
        else:
            self._json(200, {"ok": True, "proyectoId": "p1", "nombre": name[:-4], "pistas": len(files) - 1,
                             "marcadores": 3, "activada": True})


@pytest.fixture()
def fake_multitrack():
    server = ThreadingHTTPServer(("127.0.0.1", 0), FakeMultitrack)
    FakeMultitrack.received = []
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    yield f"http://127.0.0.1:{server.server_address[1]}"
    server.shutdown()


def test_multitrack_status_and_send(client, song_data, fake_multitrack):
    assert multitrack.normalize_url("192.168.0.5") == "http://192.168.0.5:4848"
    status = client.get("/api/multitrack", params={"url": fake_multitrack}).json()
    assert status == {"ok": True, "url": fake_multitrack, "bloqueado": False}
    closed = client.get("/api/multitrack", params={"url": f"http://127.0.0.1:{free_port()}"}).json()
    assert closed["ok"] is False and "no está abierto" in closed["error"]

    song = wait_for(client, upload(client, song_data)["id"])
    job = wait_job(client, client.post(f"/api/songs/{song['id']}/exports", json={
        "type": "multitrack", "stems": ["drums", "bass"], "click": True}).json()["id"])
    client.put("/api/settings", json={"multitrackUrl": fake_multitrack})
    sent = client.post(f"/api/jobs/{job['id']}/enviar", json={})
    assert sent.status_code == 200, sent.text
    assert sent.json()["ok"] is True and sent.json()["pistas"] == 3
    assert FakeMultitrack.received[-1] == {
        "name": "Cancion de prueba demo.zip", "type": "application/zip",
        "files": ["Bajo.wav", "Bateria.wav", "Click.wav", "moimoi.json"],
    }
    # Multitrack Alabanza cerrado: error claro.
    down = client.post(f"/api/jobs/{job['id']}/enviar", json={"url": f"127.0.0.1:{free_port()}"})
    assert down.status_code == 502 and "no está abierto" in down.json()["detail"]
    # Solo paquetes .zip.
    mix = wait_job(client, client.post(f"/api/songs/{song['id']}/exports", json={"type": "mix"}).json()["id"])
    assert client.post(f"/api/jobs/{mix['id']}/enviar", json={}).status_code == 400
    assert client.post("/api/jobs/nada/enviar", json={}).status_code == 404


def test_https_for_phone_browsers(tmp_path, song_data):
    cert, key = red.ensure_certificate(tmp_path / "certificado")
    assert cert.exists() and key.exists()
    assert red.ensure_certificate(tmp_path / "certificado") == (cert, key)  # se reutiliza
    song, _ = song_data
    port = free_port()
    cfg = Config(data_dir=tmp_path / "datos", frontend_dir=tmp_path / "no", start_worker=False,
                 host="0.0.0.0", https_port=port)
    app = create_app(cfg, separator=FakeSeparator(song.stems))
    assert start_https(app, cfg) == port
    context = ssl.create_default_context()
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE  # certificado propio: el celular pide aceptarlo la primera vez
    deadline = time.time() + 10
    while True:
        try:
            with urllib.request.urlopen(f"https://127.0.0.1:{port}/api/health", context=context, timeout=2) as r:
                assert json.loads(r.read())["ok"] is True
            break
        except OSError:
            if time.time() > deadline:
                raise
            time.sleep(0.2)
    # El puerto ya está en uso: sigue funcionando sin HTTPS.
    assert start_https(app, cfg) is None
    assert start_https(app, Config(data_dir=tmp_path / "datos", host="127.0.0.1", https_port=port)) is None
