"""Definiciones comunes de separación: pistas (stems), presets y la interfaz de los motores."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Callable, Protocol

import numpy as np

SAMPLE_RATE = 44100

ProgressFn = Callable[[float, str], None]
CancelFn = Callable[[], bool]


class Cancelled(Exception):
    """El usuario canceló el trabajo."""


@dataclass(frozen=True)
class StemInfo:
    id: str
    name: str
    color: str
    #: Nombre de archivo para exportar a Multitrack (sin acentos: más compatible con zips).
    file_name: str
    #: Si aporta información armónica (acordes / tonalidad).
    harmonic: bool


STEMS: dict[str, StemInfo] = {
    "vocals": StemInfo("vocals", "Voz", "#ff5d8f", "Voz", True),
    "drums": StemInfo("drums", "Batería", "#ffa24c", "Bateria", False),
    "bass": StemInfo("bass", "Bajo", "#a77bff", "Bajo", True),
    "guitar": StemInfo("guitar", "Guitarra", "#ffd75e", "Guitarra", True),
    "piano": StemInfo("piano", "Piano", "#57a8ff", "Piano", True),
    "other": StemInfo("other", "Otros", "#3fd9b0", "Otros", True),
    "instrumental": StemInfo("instrumental", "Acompañamiento", "#9fb3c8", "Acompanamiento", True),
}

#: Orden de presentación en el mezclador.
STEM_ORDER = ["vocals", "drums", "bass", "guitar", "piano", "other", "instrumental"]


@dataclass(frozen=True)
class Preset:
    id: str
    name: str
    description: str
    stems: tuple[str, ...]
    #: Modelo de Demucs en calidad normal / alta, y cuántos "shifts" (pasadas) usar.
    model: str
    model_hq: str
    shifts: int
    shifts_hq: int


PRESETS: dict[str, Preset] = {
    "2stems": Preset(
        id="2stems",
        name="Voz y acompañamiento",
        description="2 pistas: la voz sola y todo lo demás (ideal para karaoke o para cantar encima).",
        stems=("vocals", "instrumental"),
        model="htdemucs",
        model_hq="htdemucs_ft",
        shifts=1,
        shifts_hq=1,
    ),
    "4stems": Preset(
        id="4stems",
        name="Voz, batería, bajo y otros",
        description="4 pistas: la separación clásica.",
        stems=("vocals", "drums", "bass", "other"),
        model="htdemucs",
        model_hq="htdemucs_ft",
        shifts=1,
        shifts_hq=1,
    ),
    "6stems": Preset(
        id="6stems",
        name="Voz, batería, bajo, guitarra, piano y otros",
        description="6 pistas: separa también guitarra y piano/teclados.",
        stems=("vocals", "drums", "bass", "guitar", "piano", "other"),
        model="htdemucs_6s",
        model_hq="htdemucs_6s",
        shifts=1,
        shifts_hq=2,
    ),
}

DEFAULT_PRESET = "6stems"
QUALITIES = ("normal", "alta")


def model_for(preset: Preset, quality: str) -> tuple[str, int]:
    if quality == "alta":
        return preset.model_hq, preset.shifts_hq
    return preset.model, preset.shifts


def ordered_stems(stems: list[str] | tuple[str, ...]) -> list[str]:
    return sorted(stems, key=lambda s: STEM_ORDER.index(s) if s in STEM_ORDER else len(STEM_ORDER))


def assemble_preset_stems(preset: Preset, sources: dict[str, np.ndarray]) -> dict[str, np.ndarray]:
    """Arma las pistas del preset a partir de las fuentes que devolvió el modelo.

    - "instrumental" = suma de todo lo que no es voz.
    - Si el preset pide una pista que el modelo no tiene (p. ej. guitarra con un
      modelo de 4 pistas), se suma a "other".
    """
    result: dict[str, np.ndarray] = {}
    wanted = set(preset.stems)
    leftovers: list[np.ndarray] = []
    for name, audio in sources.items():
        if name in wanted and name != "instrumental":
            result[name] = audio
        else:
            leftovers.append(audio)
    if "instrumental" in wanted:
        non_vocal = [a for n, a in sources.items() if n != "vocals"]
        result["instrumental"] = np.sum(non_vocal, axis=0).astype(np.float32)
    elif leftovers:
        extra = np.sum(leftovers, axis=0).astype(np.float32)
        if "other" in result:
            result["other"] = (result["other"] + extra).astype(np.float32)
        else:
            result["other"] = extra
    missing = wanted - set(result)
    for name in missing:
        # El modelo no produjo esa pista: queda en silencio para mantener la estructura.
        some = next(iter(sources.values()))
        result[name] = np.zeros_like(some)
    return {name: result[name] for name in preset.stems}


class Separator(Protocol):
    """Motor de separación de fuentes."""

    name: str

    def status(self) -> dict:
        """Estado del motor: {'available': bool, 'detail': str, 'device': str}."""
        ...

    def separate(
        self,
        audio: np.ndarray,
        preset: Preset,
        quality: str,
        progress: ProgressFn,
        should_cancel: CancelFn,
    ) -> tuple[dict[str, np.ndarray], str]:
        """Separa `audio` (float32, forma (2, n), 44.1 kHz).

        Devuelve (pistas, nombre_del_modelo). Las pistas tienen la misma forma que la entrada.
        """
        ...
