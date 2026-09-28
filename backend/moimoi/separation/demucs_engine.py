"""Motor de separación con Demucs (Meta AI, Hybrid Transformer Demucs v4)."""

from __future__ import annotations

import logging
import threading
from pathlib import Path
from typing import Callable

import numpy as np

from .base import SAMPLE_RATE, CancelFn, Cancelled, Preset, ProgressFn, assemble_preset_stems, model_for

log = logging.getLogger("moimoi.demucs")

#: Tamaño aproximado de descarga de cada modelo (solo la primera vez).
MODEL_SIZES_MB = {"htdemucs": 80, "htdemucs_6s": 55, "htdemucs_ft": 320}


class ModelDownloadError(RuntimeError):
    pass


class DemucsSeparator:
    name = "demucs"

    def __init__(
        self,
        device: str = "auto",
        model_repo: Path | None = None,
        torch_threads: int | None = None,
        model_loader: Callable[[str], object] | None = None,
    ):
        self._device_pref = device
        self._repo = model_repo
        self._threads = torch_threads
        self._loader = model_loader
        self._models: dict[str, object] = {}
        self._lock = threading.Lock()

    # ---- estado -----------------------------------------------------------------------

    def status(self) -> dict:
        try:
            import demucs
            import torch
        except ImportError:
            return {"available": False, "device": None,
                    "detail": "Falta instalar Demucs y PyTorch (pip install demucs torch)."}
        device = self.device()
        detail = f"Demucs {getattr(demucs, '__version__', '?')} · PyTorch {torch.__version__.split('+')[0]}"
        gpu = None
        if device == "cuda":
            try:
                gpu = torch.cuda.get_device_name(0)
            except Exception:  # noqa: BLE001
                gpu = "GPU NVIDIA"
        elif device == "mps":
            gpu = "Apple Silicon (MPS)"
        return {"available": True, "device": device, "gpu": gpu, "detail": detail}

    def device(self) -> str:
        try:
            import torch
        except ImportError:
            return "cpu"
        pref = self._device_pref
        cuda_ok = torch.cuda.is_available()
        mps_ok = bool(getattr(torch.backends, "mps", None) and torch.backends.mps.is_available())
        if pref == "auto":
            return "cuda" if cuda_ok else "cpu"
        if pref == "cuda" and not cuda_ok:
            return "cpu"
        if pref == "mps" and not mps_ok:
            return "cpu"
        return pref if pref in {"cpu", "cuda", "mps"} else "cpu"

    # ---- modelos ----------------------------------------------------------------------

    def _load(self, name: str, progress: ProgressFn):
        with self._lock:
            if name in self._models:
                return self._models[name]
            # Un solo modelo en memoria a la vez.
            self._models.clear()
            if self._loader is not None:
                model = self._loader(name)
            else:
                model = self._load_pretrained(name, progress)
            model.eval()
            self._models[name] = model
            return model

    def _load_pretrained(self, name: str, progress: ProgressFn):
        from demucs.pretrained import get_model

        progress(0.0, f"Cargando el modelo de IA ({name})… la primera vez se descarga "
                      f"(~{MODEL_SIZES_MB.get(name, 80)} MB)")
        try:
            return get_model(name, repo=self._repo)
        except Exception as exc:  # noqa: BLE001 - errores de red, archivos corruptos, etc.
            log.exception("No se pudo cargar el modelo %s", name)
            raise ModelDownloadError(
                f"No se pudo cargar el modelo de IA '{name}'. La primera vez hace falta internet para "
                f"descargarlo (se guarda y después funciona sin conexión). Detalle: {exc}"
            ) from exc

    # ---- separación -------------------------------------------------------------------

    def separate(
        self,
        audio: np.ndarray,
        preset: Preset,
        quality: str,
        progress: ProgressFn,
        should_cancel: CancelFn,
    ) -> tuple[dict[str, np.ndarray], str]:
        import torch
        from demucs.apply import apply_model

        model_name, shifts = model_for(preset, quality)
        model = self._load(model_name, progress)
        if self._threads:
            torch.set_num_threads(self._threads)

        samplerate = getattr(model, "samplerate", SAMPLE_RATE)
        if samplerate != SAMPLE_RATE:
            raise RuntimeError(f"El modelo espera {samplerate} Hz")
        wav = torch.from_numpy(np.ascontiguousarray(audio, dtype=np.float32))
        channels = getattr(model, "audio_channels", 2)
        if wav.shape[0] != channels:
            wav = wav.mean(0, keepdim=True).expand(channels, -1).contiguous()

        # Normalización igual que la CLI de Demucs.
        ref = wav.mean(0)
        mean, std = ref.mean(), ref.std() + 1e-8
        length = wav.shape[1]

        sub_models = getattr(model, "models", None) or [model]
        segment = float(getattr(sub_models[0], "segment", 7.8) or 7.8)
        overlap = 0.25
        stride = max(1, int((1 - overlap) * segment * samplerate))
        passes = len(sub_models) * max(1, shifts)

        def callback(info: dict) -> None:
            if should_cancel():
                raise KeyboardInterrupt  # así lo pide Demucs para abortar
            if info.get("state") != "end":
                return
            within = min(1.0, (info.get("segment_offset", 0) + stride) / max(1, info.get("audio_length", length)))
            done = info.get("model_idx_in_bag", 0) * max(1, shifts) + info.get("shift_idx", 0)
            fraction = min(1.0, (done + within) / passes)
            progress(fraction, f"Separando pistas con IA… {fraction * 100:.0f}%")

        def run(device: str):
            with torch.no_grad():
                return apply_model(
                    model, ((wav - mean) / std)[None], shifts=shifts, split=True, overlap=overlap,
                    device=device, progress=False, num_workers=0,
                    callback=callback, callback_arg={"audio_length": length},
                )

        device = self.device()
        try:
            try:
                out = run(device)
            except RuntimeError as exc:
                # Sin memoria en la GPU: se reintenta en CPU (más lento pero funciona).
                if device != "cpu" and "out of memory" in str(exc).lower():
                    log.warning("Sin memoria en %s, reintentando en CPU", device)
                    if device == "cuda":
                        torch.cuda.empty_cache()
                    progress(0.0, "La GPU se quedó sin memoria; separando con el procesador…")
                    out = run("cpu")
                else:
                    raise
        except KeyboardInterrupt as exc:
            raise Cancelled() from exc
        finally:
            if device == "cuda":
                torch.cuda.empty_cache()

        out = (out * std + mean)[0].cpu().numpy().astype(np.float32)
        sources = {name: out[i] for i, name in enumerate(model.sources)}
        return assemble_preset_stems(preset, sources), model_name
