import io
import sys
from pathlib import Path

import numpy as np
import pytest
import soundfile as sf

# Permite `import synth` (generador de canciones de prueba) y `import moimoi`.
TESTS = Path(__file__).resolve().parent
sys.path.insert(0, str(TESTS))
sys.path.insert(0, str(TESTS.parent))

from fastapi.testclient import TestClient  # noqa: E402

from helpers import FakeSeparator  # noqa: E402
from moimoi.analysis import analyze_song  # noqa: E402
from moimoi.app import create_app  # noqa: E402
from moimoi.config import Config  # noqa: E402
from synth import worship_song  # noqa: E402


@pytest.fixture(scope="session")
def song_data():
    """Canción sintética (con sus pistas "reales") y su mezcla en WAV."""
    song = worship_song(bpm=100.0)
    mix = np.sum(list(song.stems.values()), axis=0)
    mix = mix / max(1.0, float(np.max(np.abs(mix))) / 0.9)
    buffer = io.BytesIO()
    sf.write(buffer, mix.T, 44100, format="WAV", subtype="PCM_16")
    return song, buffer.getvalue()


@pytest.fixture()
def client(tmp_path, song_data):
    song, _ = song_data
    cfg = Config(data_dir=tmp_path / "datos", frontend_dir=tmp_path / "no-hay-frontend", start_worker=True)
    app = create_app(cfg, separator=FakeSeparator(song.stems), analyzer=analyze_song)
    with TestClient(app) as test_client:
        yield test_client
