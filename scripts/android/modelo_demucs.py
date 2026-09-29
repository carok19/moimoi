#!/usr/bin/env python
"""Convierte Demucs (htdemucs_6s) a ONNX para separar pistas dentro de la app de Android.

El modelo original calcula el espectrograma (STFT) y su inversa (iSTFT) dentro de la red, y esas
operaciones no se pueden exportar a ONNX. Como en demucs.onnx (https://github.com/sevagh/demucs.onnx,
MIT), acá se exporta solo el "núcleo" de la red: recibe el audio de un segmento y su espectrograma
(complejo como canales) y devuelve la rama de frecuencia (espectrogramas de cada instrumento) y la
rama de tiempo. La app hace la STFT, la iSTFT y la suma (ver DemucsEngine.java).

Uso:
    python modelo_demucs.py salida/                      # modelo real (descarga los pesos de Meta)
    python modelo_demucs.py salida/ --aleatorio          # misma arquitectura con pesos al azar (pruebas)

Deja en `salida/`: htdemucs_6s.onnx y htdemucs_6s.json (fuentes, frecuencia, largo del segmento…).
"""

from __future__ import annotations

import argparse
import json
import math
from fractions import Fraction
from pathlib import Path

import numpy as np
import torch
from torch import nn
from torch.nn import functional as F

from demucs.hdemucs import pad1d
from demucs.htdemucs import HTDemucs
from demucs.spec import spectro

SOURCES_6S = ["drums", "bass", "other", "vocals", "guitar", "piano"]


def random_model(seed: int = 0) -> HTDemucs:
    """HTDemucs con la arquitectura de htdemucs_6s y pesos al azar (para probar sin descargar nada)."""
    torch.manual_seed(seed)
    model = HTDemucs(sources=SOURCES_6S, segment=Fraction(39, 5))
    # Pesos "razonables": que las salidas no exploten ni queden en cero.
    with torch.no_grad():
        for p in model.parameters():
            if p.dim() > 1:
                p.mul_(0.5)
    return model.eval()


def pretrained_model(name: str) -> HTDemucs:
    from demucs.pretrained import get_model

    model = get_model(name)
    if not isinstance(model, HTDemucs):
        model = model.models[0]  # BagOfModels con un solo modelo
    return model.eval()


def spec(x: torch.Tensor, nfft: int = 4096) -> torch.Tensor:
    """Igual que HTDemucs._spec: STFT alineada para que el largo sea exacto (dividido por el salto)."""
    hl = nfft // 4
    le = int(math.ceil(x.shape[-1] / hl))
    pad = hl // 2 * 3
    x = pad1d(x, (pad, pad + le * hl - x.shape[-1]), mode="reflect")
    z = spectro(x, nfft, hl)[..., :-1, :]
    return z[..., 2: 2 + le]


def magnitude(z: torch.Tensor) -> torch.Tensor:
    """Complejo como canales: [B, C, F, T] complejo -> [B, 2C, F, T] (real, imag de cada canal)."""
    b, c, fr, t = z.shape
    return torch.view_as_real(z).permute(0, 1, 4, 2, 3).reshape(b, c * 2, fr, t)


class DemucsCore(nn.Module):
    """HTDemucs.forward sin STFT/iSTFT (copiado de demucs 4, htdemucs.py)."""

    def __init__(self, model: HTDemucs):
        super().__init__()
        self.model = model

    def forward(self, mix: torch.Tensor, x: torch.Tensor):
        m = self.model
        training_length = int(m.segment * m.samplerate)
        B, C, Fq, T = x.shape
        mean = x.mean(dim=(1, 2, 3), keepdim=True)
        std = x.std(dim=(1, 2, 3), keepdim=True)
        x = (x - mean) / (1e-5 + std)
        xt = mix
        meant = xt.mean(dim=(1, 2), keepdim=True)
        stdt = xt.std(dim=(1, 2), keepdim=True)
        xt = (xt - meant) / (1e-5 + stdt)

        saved, saved_t, lengths, lengths_t = [], [], [], []
        for idx, encode in enumerate(m.encoder):
            lengths.append(x.shape[-1])
            inject = None
            if idx < len(m.tencoder):
                lengths_t.append(xt.shape[-1])
                tenc = m.tencoder[idx]
                xt = tenc(xt)
                if not tenc.empty:
                    saved_t.append(xt)
                else:
                    inject = xt
            x = encode(x, inject)
            if idx == 0 and m.freq_emb is not None:
                frs = torch.arange(x.shape[-2], device=x.device)
                emb = m.freq_emb(frs).t()[None, :, :, None].expand_as(x)
                x = x + m.freq_emb_scale * emb
            saved.append(x)
        if m.crosstransformer:
            if m.bottom_channels:
                b, c, f, t = x.shape
                x = x.reshape(b, c, f * t)
                x = m.channel_upsampler(x)
                x = x.reshape(b, -1, f, t)
                xt = m.channel_upsampler_t(xt)
            x, xt = m.crosstransformer(x, xt)
            if m.bottom_channels:
                b, c, f, t = x.shape
                x = x.reshape(b, c, f * t)
                x = m.channel_downsampler(x)
                x = x.reshape(b, -1, f, t)
                xt = m.channel_downsampler_t(xt)

        for idx, decode in enumerate(m.decoder):
            skip = saved.pop(-1)
            x, pre = decode(x, skip, lengths.pop(-1))
            offset = m.depth - len(m.tdecoder)
            if idx >= offset:
                tdec = m.tdecoder[idx - offset]
                length_t = lengths_t.pop(-1)
                if tdec.empty:
                    pre = pre[:, :, 0]
                    xt, _ = tdec(pre, None, length_t)
                else:
                    skip = saved_t.pop(-1)
                    xt, _ = tdec(xt, skip, length_t)

        S = len(m.sources)
        x = x.view(B, S, -1, Fq, T)
        x = x * std[:, None] + mean[:, None]
        xt = xt.view(B, S, -1, training_length)
        xt = xt * stdt[:, None] + meant[:, None]
        return x, xt


def export(model: HTDemucs, out_dir: Path, name: str) -> dict:
    out_dir.mkdir(parents=True, exist_ok=True)
    core = DemucsCore(model).eval()
    training_length = int(model.segment * model.samplerate)
    torch.manual_seed(1)
    mix = torch.randn(1, model.audio_channels, training_length) * 0.1
    mag = magnitude(spec(mix, model.nfft))
    onnx_path = out_dir / f"{name}.onnx"
    # La atención "rápida" de PyTorch (_native_multi_head_attention) no existe en ONNX.
    if hasattr(torch.backends, "mha"):
        torch.backends.mha.set_fastpath_enabled(False)
    with torch.no_grad():
        ref_x, ref_xt = core(mix, mag)
        kwargs = dict(export_params=True, opset_version=17, do_constant_folding=True,
                      input_names=["mix", "spec"], output_names=["spec_out", "wave_out"])
        try:
            torch.onnx.export(core, (mix, mag), str(onnx_path), dynamo=False, **kwargs)
        except TypeError:  # versiones de PyTorch sin el parámetro "dynamo"
            torch.onnx.export(core, (mix, mag), str(onnx_path), **kwargs)

    import onnxruntime as ort

    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    got_x, got_xt = session.run(None, {"mix": mix.numpy(), "spec": mag.numpy()})
    err_x = float(np.max(np.abs(got_x - ref_x.numpy())) / (np.max(np.abs(ref_x.numpy())) + 1e-9))
    err_xt = float(np.max(np.abs(got_xt - ref_xt.numpy())) / (np.max(np.abs(ref_xt.numpy())) + 1e-9))
    meta = {
        "name": name,
        "sources": list(model.sources),
        "samplerate": int(model.samplerate),
        "audioChannels": int(model.audio_channels),
        "segmentSamples": training_length,
        "nfft": int(model.nfft),
        "hop": int(model.nfft // 4),
        "freqBins": int(mag.shape[2]),
        "frames": int(mag.shape[3]),
        "onnxCheck": {"specRelErr": err_x, "waveRelErr": err_xt},
    }
    (out_dir / f"{name}.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")
    return meta


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("salida", type=Path)
    parser.add_argument("--modelo", default="htdemucs_6s")
    parser.add_argument("--aleatorio", action="store_true", help="pesos al azar (pruebas sin internet)")
    parser.add_argument("--guardar-pesos", type=Path, help="guardar también el modelo de PyTorch (pruebas)")
    args = parser.parse_args()
    model = random_model() if args.aleatorio else pretrained_model(args.modelo)
    if args.guardar_pesos:
        torch.save(model, args.guardar_pesos)
    meta = export(model, args.salida, args.modelo)
    print(json.dumps(meta, indent=2))
    if max(meta["onnxCheck"].values()) > 1e-3:
        raise SystemExit("El modelo ONNX no coincide con PyTorch")


if __name__ == "__main__":
    main()
