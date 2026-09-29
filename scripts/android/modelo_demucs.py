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


class ChunkedAttention(nn.Module):
    """nn.MultiheadAttention (sin máscaras) calculada por tramos de consultas: da lo mismo, pero
    la matriz de atención completa (8 cabezas × 2688 × 2688 en la rama de frecuencia, ~230 MB) no
    existe nunca entera: en el celular la memoria alcanza de sobra."""

    def __init__(self, mha: nn.MultiheadAttention, chunk: int):
        super().__init__()
        if not mha._qkv_same_embed_dim or mha.in_proj_bias is None:
            raise ValueError("Atención no soportada")
        self.mha = mha
        self.chunk = chunk

    def forward(self, query, key, value, attn_mask=None, key_padding_mask=None, need_weights=False,
                is_causal=False, average_attn_weights=True):
        if attn_mask is not None or key_padding_mask is not None or is_causal:
            raise ValueError("Máscaras de atención no soportadas")
        m = self.mha
        if not m.batch_first:
            query, key, value = (t.transpose(0, 1) for t in (query, key, value))
        b, lq, e = query.shape
        h = m.num_heads
        d = e // h
        w, bias = m.in_proj_weight, m.in_proj_bias
        q = F.linear(query, w[:e], bias[:e]).view(b, lq, h, d).transpose(1, 2) * (d ** -0.5)
        k = F.linear(key, w[e:2 * e], bias[e:2 * e]).view(b, -1, h, d).transpose(1, 2)
        v = F.linear(value, w[2 * e:], bias[2 * e:]).view(b, -1, h, d).transpose(1, 2)
        kt = k.transpose(-1, -2)
        parts = []
        for start in range(0, lq, self.chunk):
            scores = torch.matmul(q[:, :, start:start + self.chunk], kt)
            parts.append(torch.matmul(scores.softmax(dim=-1), v))
        out = torch.cat(parts, dim=2).transpose(1, 2).reshape(b, lq, e)
        out = m.out_proj(out)
        if not m.batch_first:
            out = out.transpose(0, 1)
        return out, None


def chunk_attention(model: HTDemucs, chunk: int = 448) -> int:
    """Cambia cada atención del transformer por ChunkedAttention. Devuelve cuántas cambió."""
    count = 0
    if not model.crosstransformer:
        return count
    for module in list(model.crosstransformer.modules()):
        for name, child in list(module.named_children()):
            if isinstance(child, nn.MultiheadAttention):
                setattr(module, name, ChunkedAttention(child, chunk))
                count += 1
    return count


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


def store_fp16(onnx_path: Path, min_size: int = 1024) -> int:
    """Guarda los pesos en 16 bits (el archivo pesa la mitad) con un Cast a 32 bits delante de cada uno.
    ONNX Runtime resuelve esos Cast al cargar el modelo (plegado de constantes): en el celular calcula
    igual que con el modelo original. Devuelve cuántos pesos convirtió."""
    import onnx
    from onnx import TensorProto, helper, numpy_helper

    model = onnx.load(str(onnx_path))
    graph = model.graph
    kept, casts, count = [], [], 0
    for init in graph.initializer:
        if init.data_type == TensorProto.FLOAT and int(np.prod(init.dims)) >= min_size:
            half = numpy_helper.from_array(numpy_helper.to_array(init).astype(np.float16), f"{init.name}__f16")
            kept.append(half)
            casts.append(helper.make_node("Cast", [half.name], [init.name], to=TensorProto.FLOAT,
                                          name=f"{init.name}__a32"))
            count += 1
        else:
            kept.append(init)
    del graph.initializer[:]
    graph.initializer.extend(kept)
    nodes = casts + list(graph.node)
    del graph.node[:]
    graph.node.extend(nodes)
    onnx.save(model, str(onnx_path))
    return count


def _rel_err(got: np.ndarray, ref: np.ndarray) -> float:
    return float(np.max(np.abs(got - ref)) / (np.max(np.abs(ref)) + 1e-9))


def _snr_db(got: np.ndarray, ref: np.ndarray) -> float:
    noise = float(np.sum((got.astype(np.float64) - ref) ** 2))
    return float(10 * np.log10(float(np.sum(ref.astype(np.float64) ** 2)) / max(noise, 1e-30)))


def export(model: HTDemucs, out_dir: Path, name: str, chunk: int = 448, fp16: bool = False) -> dict:
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
        # Referencia: el modelo tal cual (la comparación del final verifica también la atención por tramos).
        ref_x, ref_xt = core(mix, mag)
        if chunk:
            chunk_attention(model, chunk)
        kwargs = dict(export_params=True, opset_version=17, do_constant_folding=True,
                      input_names=["mix", "spec"], output_names=["spec_out", "wave_out"])
        try:
            torch.onnx.export(core, (mix, mag), str(onnx_path), dynamo=False, **kwargs)
        except TypeError:  # versiones de PyTorch sin el parámetro "dynamo"
            torch.onnx.export(core, (mix, mag), str(onnx_path), **kwargs)

    import onnxruntime as ort

    def check() -> tuple[np.ndarray, np.ndarray]:
        session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
        return session.run(None, {"mix": mix.numpy(), "spec": mag.numpy()})

    got_x, got_xt = check()
    err_x, err_xt = _rel_err(got_x, ref_x.numpy()), _rel_err(got_xt, ref_xt.numpy())
    half = None
    if fp16:
        converted = store_fp16(onnx_path)
        half_x, half_xt = check()
        # Con pesos de 16 bits el resultado cambia muy poco (inaudible): se informa la relación señal/ruido.
        half = {"weights": converted, "specSnrDb": round(_snr_db(half_x, ref_x.numpy()), 1),
                "waveSnrDb": round(_snr_db(half_xt, ref_xt.numpy()), 1)}
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
        "attentionChunk": chunk,
        "onnxCheck": {"specRelErr": err_x, "waveRelErr": err_xt},
        "fp16": half,
    }
    (out_dir / f"{name}.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")
    return meta


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("salida", type=Path)
    parser.add_argument("--modelo", default="htdemucs_6s")
    parser.add_argument("--aleatorio", action="store_true", help="pesos al azar (pruebas sin internet)")
    parser.add_argument("--guardar-pesos", type=Path, help="guardar también el modelo de PyTorch (pruebas)")
    parser.add_argument("--tramo", type=int, default=448,
                        help="consultas por tramo en la atención (0 = sin tramos; usa mucha más memoria)")
    parser.add_argument("--fp16", action="store_true", help="guardar los pesos en 16 bits (archivo de la mitad)")
    args = parser.parse_args()
    model = random_model() if args.aleatorio else pretrained_model(args.modelo)
    if args.guardar_pesos:
        torch.save(model, args.guardar_pesos)
    meta = export(model, args.salida, args.modelo, chunk=args.tramo, fp16=args.fp16)
    print(json.dumps(meta, indent=2))
    if max(meta["onnxCheck"].values()) > 1e-3:
        raise SystemExit("El modelo ONNX no coincide con PyTorch")
    if meta["fp16"] and min(meta["fp16"]["specSnrDb"], meta["fp16"]["waveSnrDb"]) < 40:
        raise SystemExit("Con pesos de 16 bits el modelo cambia demasiado")


if __name__ == "__main__":
    main()
