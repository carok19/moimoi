"""Servidor de prueba para la interfaz: usa un motor de separación falso (devuelve las
pistas de una canción sintética), así se puede probar la app completa sin modelos de IA.

    python tests/e2e_server.py --port 4799 --data /tmp/moimoi-e2e
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))

import soundfile as sf  # noqa: E402
import uvicorn  # noqa: E402

from moimoi.config import REPO_DIR, Config  # noqa: E402
from moimoi.app import create_app  # noqa: E402
from synth import worship_song  # noqa: E402
from helpers import FakeSeparator, make_pack  # noqa: E402


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=4799)
    parser.add_argument("--data", type=Path, required=True)
    parser.add_argument("--wav", type=Path, help="Además, guardar la mezcla sintética en este WAV (para subirla)")
    parser.add_argument("--pack", type=Path, help="Además, guardar un paquete de voces guía de prueba (.zip)")
    args = parser.parse_args()
    if args.pack:
        args.pack.write_bytes(make_pack(damaged=True))
    song = worship_song(bpm=100.0)
    if args.wav:
        mix = sum(song.stems.values())
        mix = mix / max(1.0, float(abs(mix).max()) / 0.9)
        sf.write(args.wav, mix.T, 44100, subtype="PCM_16")
    cfg = Config(data_dir=args.data, frontend_dir=REPO_DIR / "frontend" / "dist", port=args.port)
    app = create_app(cfg, separator=FakeSeparator(song.stems))
    uvicorn.run(app, host="127.0.0.1", port=args.port, log_level="warning")


if __name__ == "__main__":
    main()
