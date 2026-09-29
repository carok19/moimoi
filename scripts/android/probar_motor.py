#!/usr/bin/env python
"""Compara el motor de separación de la app (Java + ONNX Runtime) con Demucs en PyTorch.

    python probar_motor.py carpeta_modelo onnxruntime.jar [--segundos 25]

carpeta_modelo: la salida de modelo_demucs.py (htdemucs_6s.onnx) y, para los pesos al azar, el
modelo de PyTorch guardado con --guardar-pesos (aleatorio.pt). Sin aleatorio.pt se usa el modelo
real htdemucs_6s. Compila el motor (frontend/android/.../com/moimoi/engine) con javac, separa el
mismo audio con los dos y falla si no coinciden.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np
import torch

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
ENGINE = REPO / "frontend" / "android" / "app" / "src" / "main" / "java" / "com" / "moimoi" / "engine"


def test_audio(seconds: float, sr: int = 44100) -> np.ndarray:
    """Audio de prueba estéreo con acordes, bajo y golpes (el contenido da igual: se compara)."""
    rng = np.random.default_rng(0)
    t = np.arange(int(seconds * sr)) / sr
    left = 0.2 * np.sin(2 * np.pi * 220 * t) + 0.1 * np.sin(2 * np.pi * 330 * t)
    right = 0.2 * np.sin(2 * np.pi * 110 * t + 0.3) + 0.05 * rng.standard_normal(t.size)
    hits = (np.sin(2 * np.pi * 2 * t) > 0.95).astype(float) * rng.standard_normal(t.size) * 0.3
    return np.stack([left + hits, right + hits]).astype(np.float32)


def reference(model, wav: np.ndarray) -> np.ndarray:
    from demucs.apply import apply_model

    x = torch.from_numpy(wav)
    ref = x.mean(0)
    mean, std = ref.mean(), ref.std() + 1e-8
    with torch.no_grad():
        out = apply_model(model, ((x - mean) / std)[None], shifts=0, split=True, overlap=0.25)[0]
    return (out * std + mean).numpy()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("modelo", type=Path)
    parser.add_argument("jar", type=Path)
    parser.add_argument("--segundos", type=float, default=25.0)
    parser.add_argument("--hilos", type=int, default=4)
    args = parser.parse_args()

    meta = json.loads((args.modelo / "htdemucs_6s.json").read_text())
    pt = args.modelo / "aleatorio.pt"
    if pt.exists():
        model = torch.load(pt, weights_only=False).eval()
    else:
        sys.path.insert(0, str(HERE))
        from modelo_demucs import pretrained_model

        model = pretrained_model(meta["name"])
    wav = test_audio(args.segundos)
    print(f"Audio de prueba: {wav.shape[1] / 44100:.1f} s")
    expected = reference(model, wav)

    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        classes = tmp / "clases"
        classes.mkdir()
        sources = sorted(str(p) for p in ENGINE.glob("*.java")) + [str(HERE / "PruebaMotor.java")]
        subprocess.run(["javac", "-encoding", "UTF-8", "-cp", str(args.jar), "-d", str(classes), *sources], check=True)
        (tmp / "entrada.f32").write_bytes(wav.astype("<f4").tobytes())
        cp = f"{classes}:{args.jar}"
        subprocess.run(["java", "-cp", cp, "PruebaMotor", str(args.modelo / "htdemucs_6s.onnx"),
                        ",".join(meta["sources"]), str(tmp / "entrada.f32"), str(tmp / "fuente"),
                        str(args.hilos), str(meta["segmentSamples"])], check=True)
        worst = 0.0
        worst_snr = float("inf")
        for s, name in enumerate(meta["sources"]):
            got = np.frombuffer((tmp / f"fuente{s}.f32").read_bytes(), dtype="<f4").reshape(2, -1)
            ref = expected[s]
            err = got - ref
            snr = 10 * np.log10(np.sum(ref ** 2) / max(np.sum(err ** 2), 1e-20))
            print(f"  {name:7s} error máx {np.max(np.abs(err)):.2e}  SNR {snr:6.1f} dB")
            worst = max(worst, float(np.max(np.abs(err)) / (np.max(np.abs(ref)) + 1e-9)))
            worst_snr = min(worst_snr, float(snr))
    if meta.get("fp16"):
        # Pesos guardados en 16 bits: el resultado cambia un poco (muy por debajo de lo audible).
        if worst_snr < 40:
            raise SystemExit(f"El motor de la app no coincide con Demucs (SNR {worst_snr:.1f} dB)")
    elif worst > 1e-3:
        raise SystemExit(f"El motor de la app no coincide con Demucs (error relativo {worst:.2e})")
    print("El motor de la app coincide con Demucs.")


if __name__ == "__main__":
    main()
