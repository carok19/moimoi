"""Voz guía: las voces que anuncian las partes de la canción ("Verso 1", "Coro"…) y la cuenta.

El usuario carga su propio paquete de voces (por ejemplo "Click and Guide Samples", el que se
descarga de un sitio de secuencias, o grabaciones propias). Cada archivo se reconoce por su
nombre ("Spanish - Coro 2 (Chorus 2).wav", "01 - Verso.mp3", "VG_PreCoro.wav", "uno.wav"…) y
se puede reasignar a mano. Si el paquete trae varios idiomas, cada uno queda por separado
(Español, Inglés…) y se elige cuál usar. Los sonidos de click del paquete
("New Click - Classic-accents.wav"…) también se reconocen y se pueden usar en la pista Click.

Con eso se arma la pista **Guía** del multitrack: cada voz suena un compás antes de la parte que
anuncia, y la cuenta ("1, 2, 3, 4") suena en los compases que se agregan antes de la canción.
"""

from __future__ import annotations

import math
import re
import secrets
import shutil
import tempfile
import threading
import unicodedata
import zipfile
import zlib
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import soundfile as sf

from . import audio_io
from .separation.base import SAMPLE_RATE
from .storage import read_json, write_json

PARTES, CUENTA, INDICACIONES = "partes", "cuenta", "indicaciones"

#: Tipos de voz (clave, nombre para mostrar, grupo), en el orden en que se muestran.
CUE_TYPES: list[tuple[str, str, str]] = [
    ("intro", "Intro", PARTES),
    ("verso", "Verso", PARTES),
    *[(f"verso{i}", f"Verso {i}", PARTES) for i in range(1, 7)],
    ("precoro", "Pre-coro", PARTES),
    *[(f"precoro{i}", f"Pre-coro {i}", PARTES) for i in range(1, 5)],
    ("coro", "Coro", PARTES),
    *[(f"coro{i}", f"Coro {i}", PARTES) for i in range(1, 5)],
    ("postcoro", "Post-coro", PARTES),
    ("puente", "Puente", PARTES),
    *[(f"puente{i}", f"Puente {i}", PARTES) for i in range(1, 5)],
    ("instrumental", "Instrumental", PARTES),
    ("interludio", "Interludio", PARTES),
    ("solo", "Solo", PARTES),
    ("turnaround", "Vuelta (turnaround)", PARTES),
    ("breakdown", "Baja intensidad (breakdown)", PARTES),
    ("vamp", "Vamp", PARTES),
    ("refran", "Refrán", PARTES),
    ("tag", "Tag (repetir)", PARTES),
    ("rap", "Rap", PARTES),
    ("exhortacion", "Exhortación", PARTES),
    ("acapella", "A capella", PARTES),
    ("channel", "Channel", PARTES),
    ("final", "Final", PARTES),
    ("outro", "Outro", PARTES),
    ("cuenta", "Cuenta completa (1, 2, 3, 4)", CUENTA),
    *[(f"n{i}", f"{i} ({word})", CUENTA)
      for i, word in enumerate(["uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho"], 1)],
    ("ultimavez", "Última vez", INDICACIONES),
    ("sube", "Sube tono", INDICACIONES),
    ("baja", "Baja tono", INDICACIONES),
    ("todos", "Toda la banda", INDICACIONES),
    ("break", "Pausa (break)", INDICACIONES),
    ("hits", "Hits", INDICACIONES),
    ("hold", "Sostener", INDICACIONES),
    ("suave", "Suave", INDICACIONES),
    ("build", "Sube intensidad", INDICACIONES),
    ("slowbuild", "Sube de a poco", INDICACIONES),
    ("swell", "Swell", INDICACIONES),
    ("bigending", "Final grande", INDICACIONES),
    ("libre", "Adoración libre", INDICACIONES),
    ("adlib", "Ad lib", INDICACIONES),
    ("drums", "Batería", INDICACIONES),
    ("drumsin", "Entra batería", INDICACIONES),
    ("bass", "Bajo", INDICACIONES),
    ("guitar", "Guitarra", INDICACIONES),
    ("keys", "Teclado", INDICACIONES),
    ("pad", "Pad", INDICACIONES),
    ("click", "Click", INDICACIONES),
]
CUE_NAMES = {cue: name for cue, name, _ in CUE_TYPES}
CUE_GROUPS = {cue: group for cue, _, group in CUE_TYPES}
CUE_ORDER = {cue: i for i, (cue, _, _) in enumerate(CUE_TYPES)}
#: Las más importantes: si faltan, se avisa en Ajustes.
MAIN_CUES = ["intro", "verso", "verso1", "verso2", "verso3", "precoro", "coro", "puente", "instrumental",
             "final", "n1", "n2", "n3", "n4"]

CLICK_ROLES = [("accent", "Acento (el 1)"), ("beat", "Pulso"), ("eighth", "Corcheas"), ("sixteenth", "Semicorcheas")]

AUDIO_EXTS = {".wav", ".mp3", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".flac", ".aif", ".aiff", ".webm",
              ".wma", ".caf"}
MAX_FILES = 600
MAX_CUE_SECONDS = 20.0
MAX_CLICK_SECONDS = 2.0
DEFAULT_SET = ("mias", "Mis voces")

_NUMBERS = {
    "1": "n1", "uno": "n1", "one": "n1", "um": "n1", "un": "n1",
    "2": "n2", "dos": "n2", "two": "n2", "dois": "n2", "deux": "n2",
    "3": "n3", "tres": "n3", "three": "n3", "trois": "n3",
    "4": "n4", "cuatro": "n4", "four": "n4", "quatro": "n4", "quatre": "n4",
    "5": "n5", "cinco": "n5", "five": "n5", "cinq": "n5",
    "6": "n6", "seis": "n6", "six": "n6",
    "7": "n7", "siete": "n7", "seven": "n7", "sete": "n7", "sept": "n7",
    "8": "n8", "ocho": "n8", "eight": "n8", "oito": "n8", "huit": "n8",
}

# Reglas en orden: la primera que coincide gana (las compuestas antes que las simples:
# "big ending" antes que "ending", "pre coro" antes que "coro", "drums in" antes que "drums"…).
_RULES: list[tuple[re.Pattern, str]] = [(re.compile(p), cue) for p, cue in [
    (r"\b(?:big ending|final grande|gran final)\b", "bigending"),
    (r"\b(?:slowly build|sube de a poco|sube poco a poco|poco a poco)\b", "slowbuild"),
    (r"\b(?:build|sube intensidad|sube la intensidad|crece|crescendo)\b", "build"),
    (r"\b(?:breakdown|baja intensidad|baja la intensidad)\b", "breakdown"),
    (r"\b(?:key change up|sube (?:el |de |medio )?tono|subimos (?:el )?tono|modulacion|modula)\b", "sube"),
    (r"\b(?:key change down|baja (?:el |de |medio )?tono|bajamos (?:el )?tono)\b", "baja"),
    (r"\b(?:key change|cambio de tono|cambio de tonalidad)\b", "sube"),
    (r"\b(?:drums in|entra (?:la )?bateria)\b", "drumsin"),
    (r"\b(?:last time|ultima vez)\b", "ultimavez"),
    (r"\b(?:all in|toda la banda|todos|everybody|everyone)\b", "todos"),
    (r"\b(?:worship freely|adoracion libre|libre)\b", "libre"),
    (r"\bad ?lib\b", "adlib"),
    (r"\bpost ?(?:coro|chorus)\b|\bpostcoro\b|\bpostchorus\b", "postcoro"),
    (r"\b(?:pre ?coro|pre ?chorus|pre ?refrao)\s*(\d)\b", "precoro{0}"),
    (r"\b(?:pre ?coro|pre ?chorus|pre ?refrao)\b", "precoro"),
    (r"\b(?:verso|verse|estrofa|couplet)\s*(?:n|no|numero)?\s*(\d)\b", "verso{0}"),
    (r"\b(?:verso|verse|estrofa|couplet)\b", "verso"),
    (r"\b(?:coro|chorus|estribillo|refrao)\s*(\d)\b", "coro{0}"),
    (r"\b(?:coro|chorus|estribillo|refrao)\b", "coro"),
    (r"\b(?:puente|bridge|ponte|pont)\s*(\d)\b", "puente{0}"),
    (r"\b(?:puente|bridge|ponte|pont)\b", "puente"),
    (r"\bintro(?:duccion)?\b", "intro"),
    (r"\b(?:interludio|interlude)\b", "interludio"),
    (r"\binstrumental\b", "instrumental"),
    (r"\bsolo\b", "solo"),
    (r"\bturn ?around\b|\bvuelta\b", "turnaround"),
    (r"\bvamp\b", "vamp"),
    (r"\b(?:refran|refrain)\b", "refran"),
    (r"\b(?:tag|repetir|repite|repeat|otra vez)\b", "tag"),
    (r"\brap\b", "rap"),
    (r"\b(?:exhortacion|exhortation|exortacao)\b", "exhortacion"),
    (r"\ba ?capp?ella\b", "acapella"),
    (r"\bchannel\b", "channel"),
    (r"\boutro\b", "outro"),
    (r"\b(?:final|ending|fin|cierre|coda)\b", "final"),
    (r"\bhits?\b", "hits"),
    (r"\b(?:hold|sostener|sosten|sostenido)\b", "hold"),
    (r"\b(?:softly|soft|suave|suavemente)\b", "suave"),
    (r"\bswell\b", "swell"),
    (r"\b(?:break|pausa|corte|parada|stop)\b", "break"),
    (r"\b(?:drums|bateria)\b", "drums"),
    (r"\b(?:bass|bajo)\b", "bass"),
    (r"\b(?:guitar|guitarra|guitara|guitars)\b", "guitar"),
    (r"\b(?:keys|teclado|teclados|piano)\b", "keys"),
    (r"\bpads?\b", "pad"),
    (r"\bclick\b", "click"),
    (r"\b(?:cuenta|conteo|count ?in|count ?off|count)\b", "cuenta"),
]]

# Palabras de relleno comunes en los nombres de archivo de los paquetes.
_NOISE = re.compile(
    r"\b(?:voz guia|voces guia|guias?|guides?|cues?|vg|hombre|mujer|masculin[oa]|femenin[oa]|male|female|"
    r"man|woman|esp(?:anol)?|spanish|castellano|english|ingles|french|frances|francais|portugese|portuguese|"
    r"portugues|italian|italiano|german|aleman|deutsch|latam|voz|voces|voice|voices|v\d|song sections?|"
    r"dynamic)\b"
)

_LANGUAGES = [
    ("es", "Español", {"spanish", "espanol", "castellano", "latino", "latam"}),
    ("en", "Inglés", {"english", "ingles"}),
    ("pt", "Portugués", {"portugese", "portuguese", "portugues", "brasil", "brazil", "brasileiro"}),
    ("fr", "Francés", {"french", "frances", "francais"}),
    ("it", "Italiano", {"italian", "italiano"}),
    ("de", "Alemán", {"german", "aleman", "deutsch"}),
]
_GENDERS = [
    ("f", "mujer", {"female", "mujer", "femenina", "femenino", "woman", "femme", "feminino"}),
    ("m", "hombre", {"male", "hombre", "masculina", "masculino", "man", "homme"}),
]

_CLICK_WORD = re.compile(r"\b(?:click|clicks|metronomo|metronome|claqueta)\b")
_CLICK_ROLE_RULES = [
    ("accent", re.compile(r"\b(?:accents?|acentos?|acentuado|downbeat)\b")),
    ("beat", re.compile(r"\b(?:quarters?|negras?|beats?|pulso|normal)\b")),
    ("eighth", re.compile(r"\b(?:eighths?|corcheas?|8ths?)\b")),
    ("sixteenth", re.compile(r"\b(?:sixteenths?|semicorcheas?|16ths?)\b")),
]
# Carpetas de otros programas que vienen dentro de algunos paquetes (proyectos de Ableton…).
_SKIP_DIRS = {"__macosx", "freeze", "ableton project info", "backup"}


def normalize(text: str) -> str:
    text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode("ascii").lower()
    text = re.sub(r"[_\-.,()\[\]{}+/\\]+", " ", text)
    return re.sub(r"\s+", " ", text).strip()


def _stem(name: str) -> str:
    base = Path(name.replace("\\", "/")).name
    return Path(base).stem if Path(base).suffix.lower() in AUDIO_EXTS else base


def classify(name: str) -> str | None:
    """Tipo de voz a partir del nombre de un archivo (o de una parte de la canción)."""
    base = normalize(_stem(name))
    # Numeración al principio ("01 - Coro"), pero no si el nombre ES un número ("1").
    stripped = re.sub(r"^\d{1,3}\s+(?=\D)", "", base)
    cleaned = re.sub(r"\s+", " ", _NOISE.sub(" ", stripped)).strip()
    for candidate in (cleaned, stripped):
        for pattern, cue in _RULES:
            match = pattern.search(candidate)
            if match:
                if "{0}" in cue:
                    numbered = cue.format(match.group(1))
                    return numbered if numbered in CUE_NAMES else cue.replace("{0}", "")
                return cue
    for candidate in (cleaned, stripped, base):
        compact = candidate.replace(" ", "")
        if compact in {"1234", "123", "12345678", "unodostrescuatro", "onetwothreefour"}:
            return "cuenta"
        if candidate in _NUMBERS:
            return _NUMBERS[candidate]
        if candidate.isdigit() and 1 <= int(candidate) <= 8:
            return f"n{int(candidate)}"
    return None


def detect_set(path: str) -> tuple[str, str] | None:
    """Idioma (y voz) de un archivo según su nombre o carpeta: ("es", "Español"), ("en-f", "Inglés (mujer)")."""
    tokens = set(normalize(path).split())
    for lang, label, words in _LANGUAGES:
        if tokens & words:
            for suffix, gender, gender_words in _GENDERS:
                if tokens & gender_words:
                    return f"{lang}-{suffix}", f"{label} ({gender})"
            return lang, label
    return None


def classify_click(path: str) -> tuple[str, str, str] | None:
    """Sonido de click de un paquete: (estilo, nombre del estilo, rol) o None si no lo es."""
    base = normalize(_stem(path))
    parent = normalize(Path(path.replace("\\", "/")).parent.name)
    if not _CLICK_WORD.search(f"{parent} {base}"):
        return None
    for role, pattern in _CLICK_ROLE_RULES:
        if pattern.search(base):
            style = pattern.sub(" ", base)
            style = re.sub(r"\b(?:new|nuevo|click|clicks|metronomo|metronome|claqueta|sound|sonido|sample)\b", " ",
                           style)
            style = re.sub(r"\s+", " ", style).strip() or "click"
            style_id = re.sub(r"[^a-z0-9]+", "-", style).strip("-")[:40] or "click"
            return style_id, style.title(), role
    return None


def cue_for_label(label: str, available: set[str], numbering: str = "verses") -> str | None:
    """Voz a usar para una parte de la canción.

    numbering: "verses" = número solo en los versos ("Verso 2", pero "Coro" en cada coro),
    "all" = número en todas las partes que lo tengan ("Coro 2"), "none" = nunca.
    """
    cue = classify(label)
    if cue is None or CUE_GROUPS.get(cue) != PARTES:
        return None
    base = re.sub(r"\d+$", "", cue)
    candidates = [cue]
    if base != cue:
        keep = numbering == "all" or (numbering == "verses" and base == "verso")
        candidates = [cue, base] if keep else [base, cue]
    candidates += _FALLBACKS.get(base, [])
    return next((c for c in candidates if c in available), None)


_FALLBACKS = {
    "interludio": ["instrumental"],
    "instrumental": ["interludio"],
    "turnaround": ["interludio", "instrumental"],
    "solo": ["instrumental"],
    "final": ["outro"],
    "outro": ["final"],
}


# ---- el paquete de voces (kit) ------------------------------------------------------------


class GuideKit:
    """Voces y clicks guardados en `<datos>/voz-guia/` (audio normalizado + kit.json)."""

    def __init__(self, root: Path):
        self.root = root
        self.audio_dir = root / "audio"
        self.index = root / "kit.json"
        self._lock = threading.RLock()

    # -- datos --

    def _load(self) -> dict:
        data = read_json(self.index, None) or {}
        data.setdefault("files", {})
        data.setdefault("sets", {})
        data.setdefault("active", None)
        for info in data["files"].values():
            info.setdefault("kind", "voz")
            if info["kind"] == "voz":
                info.setdefault("set", DEFAULT_SET[0])
        return data

    def _save(self, data: dict) -> None:
        self.root.mkdir(parents=True, exist_ok=True)
        write_json(self.index, data)

    @staticmethod
    def _voices(data: dict, set_id: str | None) -> dict[str, dict]:
        return {fid: info for fid, info in data["files"].items()
                if info["kind"] == "voz" and info.get("set") == set_id}

    @staticmethod
    def _pick_active(data: dict) -> str | None:
        counts: dict[str, int] = {}
        for info in data["files"].values():
            if info["kind"] == "voz" and info.get("cue"):
                counts[info["set"]] = counts.get(info["set"], 0) + 1
        if not counts:
            return None
        spanish = [s for s in counts if s == "es" or s.startswith("es-")]
        if spanish:
            return max(spanish, key=counts.get)
        return max(counts, key=counts.get)

    # -- consulta --

    def describe(self, set_id: str | None = None) -> dict:
        with self._lock:
            data = self._load()
        active = data["active"]
        shown = set_id if set_id in data["sets"] else active
        sets = []
        for sid, info in data["sets"].items():
            voices = self._voices(data, sid)
            sets.append({"id": sid, "name": info.get("name") or sid, "files": len(voices),
                         "assigned": sum(1 for v in voices.values() if v.get("cue")), "active": sid == active})
        sets.sort(key=lambda s: (not s["active"], s["name"]))
        files = [{"id": fid, "original": info.get("original"), "cue": info.get("cue"),
                  "duration": info.get("duration"), "url": f"/api/guia/audio/{fid}.wav"}
                 for fid, info in self._voices(data, shown).items()]
        files.sort(key=lambda f: (CUE_ORDER.get(f["cue"], len(CUE_ORDER)), f["original"] or ""))
        assigned = {f["cue"]: f["id"] for f in files if f["cue"]}
        return {
            "active": active,
            "set": shown,
            "sets": sets,
            "cues": [{"id": cue, "name": name, "group": group, "file": assigned.get(cue)}
                     for cue, name, group in CUE_TYPES],
            "files": files,
            "count": len(assigned),
            "missing": [CUE_NAMES[c] for c in MAIN_CUES if c not in assigned] if shown else [],
            "clicks": self._describe_clicks(data),
        }

    @staticmethod
    def _describe_clicks(data: dict) -> list[dict]:
        styles: dict[str, dict] = {}
        for fid, info in data["files"].items():
            if info["kind"] != "click":
                continue
            style = styles.setdefault(info["style"], {"id": info["style"], "name": info.get("styleName") or info["style"],
                                                      "sounds": {}})
            style["sounds"][info["role"]] = f"/api/guia/audio/{fid}.wav"
        return sorted(styles.values(), key=lambda s: s["name"])

    def assignments(self, set_id: str | None = None) -> dict[str, Path]:
        """{tipo de voz: archivo de audio} del idioma elegido (o del indicado)."""
        with self._lock:
            data = self._load()
        target = set_id or data["active"]
        result = {}
        for file_id, info in self._voices(data, target).items():
            path = self.audio_dir / f"{file_id}.wav"
            if info.get("cue") and path.exists():
                result[info["cue"]] = path
        return result

    def click_sounds(self, style: str) -> dict[str, np.ndarray]:
        """Sonidos de un estilo de click: {"accent": audio, "beat": audio, …} (mono)."""
        with self._lock:
            data = self._load()
        sounds = {}
        for file_id, info in data["files"].items():
            if info["kind"] == "click" and info.get("style") == style:
                path = self.audio_dir / f"{file_id}.wav"
                if path.exists():
                    audio, _ = audio_io.read_audio(path)
                    sounds[info["role"]] = audio[0]
        return sounds

    def audio_path(self, file_id: str) -> Path | None:
        if not re.fullmatch(r"[0-9a-f]{12}", file_id or ""):
            return None
        path = self.audio_dir / f"{file_id}.wav"
        return path if path.exists() else None

    # -- importar --

    def _store_voice(self, data: dict, source: Path, original: str, cue: str | None, set_id: str) -> dict:
        audio = trim_silence(_decode_mono(source))
        duration = audio.size / SAMPLE_RATE
        if duration > MAX_CUE_SECONDS:
            return {"original": original, "skipped": f"demasiado largo ({duration:.0f} s)"}
        if duration < 0.05:
            return {"original": original, "skipped": "no tiene sonido"}
        peak = float(np.max(np.abs(audio))) or 1.0
        audio = (audio * (0.89 / peak)).astype(np.float32)  # pico en -1 dBFS
        # El mismo archivo cargado otra vez reemplaza al anterior.
        for fid, info in list(self._voices(data, set_id).items()):
            if info.get("original") == original:
                self._drop(data, fid)
        if cue:
            # Una sola voz por tipo en cada idioma: la nueva reemplaza a la anterior.
            for info in self._voices(data, set_id).values():
                if info.get("cue") == cue:
                    info["cue"] = None
        file_id = self._write(audio)
        data["files"][file_id] = {"kind": "voz", "set": set_id, "original": original, "cue": cue,
                                  "duration": round(duration, 2)}
        return {"original": original, "id": file_id, "cue": cue, "set": set_id}

    def _store_click(self, data: dict, source: Path, original: str, style: str, style_name: str, role: str) -> dict:
        audio = _decode_mono(source)
        audio = trim_silence(audio, -60.0, keep_start=True)
        if audio.size < int(0.003 * SAMPLE_RATE):
            return {"original": original, "skipped": "no tiene sonido"}
        audio = audio[: int(MAX_CLICK_SECONDS * SAMPLE_RATE)]
        for fid, info in list(data["files"].items()):
            if info["kind"] == "click" and info.get("style") == style and info.get("role") == role:
                self._drop(data, fid)
        file_id = self._write(audio)
        data["files"][file_id] = {"kind": "click", "style": style, "styleName": style_name, "role": role,
                                  "original": original, "duration": round(audio.size / SAMPLE_RATE, 3)}
        return {"original": original, "id": file_id, "click": style, "role": role}

    def _write(self, audio: np.ndarray) -> str:
        file_id = secrets.token_hex(6)
        self.audio_dir.mkdir(parents=True, exist_ok=True)
        audio_io.write_wav(self.audio_dir / f"{file_id}.wav", audio[None, :])
        return file_id

    def _drop(self, data: dict, file_id: str) -> None:
        data["files"].pop(file_id, None)
        (self.audio_dir / f"{file_id}.wav").unlink(missing_ok=True)

    def import_files(self, uploads: list[tuple[str, Path]], forced_cue: str | None = None,
                     target_set: str | None = None) -> dict:
        """Importa archivos de audio o .zip con audios (paquetes completos). Devuelve un resumen.

        `forced_cue`: el archivo es esa voz (p. ej. una grabación hecha en la app).
        `target_set`: idioma donde van los archivos que no dicen de qué idioma son.
        """
        if forced_cue is not None and forced_cue not in CUE_NAMES:
            raise ValueError("Tipo de voz desconocido")
        results: list[dict] = []
        with self._lock, tempfile.TemporaryDirectory() as tmp:
            data = self._load()
            if target_set is not None and target_set not in data["sets"] and target_set != DEFAULT_SET[0]:
                raise ValueError("Ese idioma no existe")
            fallback_set = target_set or DEFAULT_SET[0]
            items: list[tuple[str, Path, str | None]] = []  # (ruta dentro del paquete, archivo, idioma del zip)
            for name, path in uploads:
                if Path(name).suffix.lower() == ".zip":
                    extracted, errors = _extract_zip(path, Path(tmp) / f"zip{len(items)}")
                    zip_set = detect_set(Path(name).stem)
                    items.extend((label, p, zip_set[0] if zip_set else None) for label, p in extracted)
                    results.extend(errors)
                    for set_info in filter(None, [zip_set]):
                        data["sets"].setdefault(set_info[0], {"name": set_info[1]})
                elif Path(name).suffix.lower() in AUDIO_EXTS or forced_cue:
                    items.append((name, path, None))
                else:
                    results.append({"original": name, "skipped": "no es un archivo de audio"})
            if len(items) > MAX_FILES:
                results.append({"original": f"{len(items) - MAX_FILES} archivos más",
                                "skipped": f"máximo {MAX_FILES} por vez"})
            for label, path, zip_set in items[:MAX_FILES]:
                original = Path(label.replace("\\", "/")).name
                try:
                    click = None if forced_cue else classify_click(label)
                    if click:
                        results.append(self._store_click(data, path, original, *click))
                        continue
                    detected = None if forced_cue else detect_set(label)
                    if detected:
                        data["sets"].setdefault(detected[0], {"name": detected[1]})
                    set_id = detected[0] if detected else (zip_set or fallback_set)
                    if set_id == DEFAULT_SET[0]:
                        data["sets"].setdefault(DEFAULT_SET[0], {"name": DEFAULT_SET[1]})
                    cue = forced_cue or classify(original) or _classify_with_folder(label)
                    results.append(self._store_voice(data, path, original, cue, set_id))
                except (audio_io.AudioError, sf.LibsndfileError, RuntimeError, ValueError) as exc:
                    results.append({"original": original, "error": f"no se pudo leer ({exc})"[:160]})
            self._prune_sets(data)
            if data["active"] not in data["sets"] or not self._voices(data, data["active"]):
                data["active"] = self._pick_active(data)
            self._save(data)
        added = [r for r in results if "id" in r and "click" not in r]
        clicks = [r for r in results if "click" in r]
        return {
            "added": len(added),
            "recognized": sum(1 for r in added if r.get("cue")),
            "clicks": len({r["click"] for r in clicks}),
            "sets": sorted({r["set"] for r in added}),
            "errors": [r for r in results if "error" in r],
            "skipped": [r for r in results if "skipped" in r],
            "files": added,
        }

    @classmethod
    def _prune_sets(cls, data: dict) -> None:
        for sid in list(data["sets"]):
            if not cls._voices(data, sid):
                data["sets"].pop(sid)

    # -- editar --

    def set_active(self, set_id: str) -> None:
        with self._lock:
            data = self._load()
            if set_id not in data["sets"]:
                raise KeyError(set_id)
            data["active"] = set_id
            self._save(data)

    def assign(self, file_id: str, cue: str | None) -> None:
        if cue is not None and cue not in CUE_NAMES:
            raise ValueError("Tipo de voz desconocido")
        with self._lock:
            data = self._load()
            info = data["files"].get(file_id)
            if info is None or info["kind"] != "voz":
                raise KeyError(file_id)
            if cue:
                for other in self._voices(data, info["set"]).values():
                    if other.get("cue") == cue:
                        other["cue"] = None
            info["cue"] = cue
            self._save(data)

    def remove(self, file_id: str) -> None:
        with self._lock:
            data = self._load()
            self._drop(data, file_id)
            self._prune_sets(data)
            if data["active"] not in data["sets"]:
                data["active"] = self._pick_active(data)
            self._save(data)

    def remove_set(self, set_id: str) -> None:
        with self._lock:
            data = self._load()
            for fid in list(self._voices(data, set_id)):
                self._drop(data, fid)
            data["sets"].pop(set_id, None)
            if data["active"] == set_id:
                data["active"] = self._pick_active(data)
            self._save(data)

    def remove_clicks(self, style: str) -> None:
        with self._lock:
            data = self._load()
            for fid, info in list(data["files"].items()):
                if info["kind"] == "click" and info.get("style") == style:
                    self._drop(data, fid)
            self._save(data)

    def clear(self) -> None:
        with self._lock:
            shutil.rmtree(self.root, ignore_errors=True)


def _classify_with_folder(label: str) -> str | None:
    """Algunos paquetes usan la carpeta para la parte ("Coro/Hombre.wav")."""
    parent = Path(label.replace("\\", "/")).parent.name
    return classify(f"{parent} {_stem(label)}") if parent else None


def _extract_zip(path: Path, tmp: Path) -> tuple[list[tuple[str, Path]], list[dict]]:
    """Extrae los audios de un .zip. Los archivos dañados se saltean (y se informan)."""
    items: list[tuple[str, Path]] = []
    errors: list[dict] = []
    try:
        archive = zipfile.ZipFile(path)
    except zipfile.BadZipFile:
        return items, [{"original": path.name, "error": "el .zip está dañado o incompleto"}]
    tmp.mkdir(parents=True, exist_ok=True)
    with archive:
        for info in archive.infolist():
            name = info.filename.replace("\\", "/")
            parts = [p.lower() for p in name.split("/")[:-1]]
            base = Path(name).name
            if info.is_dir() or base.startswith(".") or any(p in _SKIP_DIRS for p in parts):
                continue
            if Path(base).suffix.lower() not in AUDIO_EXTS or info.file_size > 50 * 1024 * 1024:
                continue
            target = tmp / f"{len(items):04d}{Path(base).suffix.lower()}"
            try:
                with archive.open(info) as src, open(target, "wb") as dst:
                    shutil.copyfileobj(src, dst)
            except (zipfile.BadZipFile, zlib.error, EOFError, OSError, RuntimeError):
                target.unlink(missing_ok=True)
                errors.append({"original": base, "error": "archivo dañado dentro del .zip"})
                continue
            items.append((name, target))
    return items, errors


def _decode_mono(path: Path) -> np.ndarray:
    """Audio mono a la frecuencia de MoiMoi (WAV/FLAC/AIFF directo; lo demás con ffmpeg)."""
    try:
        data, sr = sf.read(str(path), dtype="float32", always_2d=True)
    except Exception:  # noqa: BLE001 - formato que soundfile no lee (mp3, m4a…)
        return audio_io.decode_audio(path, SAMPLE_RATE, 1)[0]
    mono = data.mean(axis=1)
    if sr != SAMPLE_RATE:
        from scipy.signal import resample_poly

        g = math.gcd(int(sr), SAMPLE_RATE)
        mono = resample_poly(mono, SAMPLE_RATE // g, int(sr) // g)
    return np.ascontiguousarray(mono, dtype=np.float32)


def trim_silence(audio: np.ndarray, threshold_db: float = -40.0, keep_start: bool = False) -> np.ndarray:
    if audio.size == 0:
        return audio
    peak = float(np.max(np.abs(audio)))
    if peak <= 1e-5:
        return audio[:0]
    threshold = peak * 10 ** (threshold_db / 20)
    loud = np.nonzero(np.abs(audio) > threshold)[0]
    start = 0 if keep_start else max(0, loud[0] - int(0.008 * SAMPLE_RATE))
    end = min(audio.size, loud[-1] + int(0.06 * SAMPLE_RATE))
    return audio[start:end]


# ---- pista guía -------------------------------------------------------------------------------


@dataclass
class Placement:
    cue: str
    time: float  # segundos en la pista exportada
    label: str


def load_cues(kit: GuideKit) -> dict[str, np.ndarray]:
    cues = {}
    for cue, path in kit.assignments().items():
        audio, _ = audio_io.read_audio(path)
        cues[cue] = audio[0]
    return cues


def plan_guide(
    sections: list[dict],
    beats: list[float],
    beats_per_bar: int,
    available: set[str],
    to_output,
    count_times: list[float],
    numbering: str = "verses",
    extras: dict[int, str] | None = None,
    lead_beats: int | None = None,
) -> list[Placement]:
    """Dónde va cada voz. `to_output(t)` pasa de segundos de la canción a segundos de la pista.

    Cada parte se anuncia en el primer pulso del compás anterior. `extras` = {número de parte:
    indicación} ("Sube tono", "Última vez"…), que suena un compás antes del nombre de la parte.
    Cada parte puede traer `guide` (voz elegida a mano; "" = sin voz) y `guideExtra`.
    """
    placements: list[Placement] = []
    lead = lead_beats if lead_beats is not None else beats_per_bar
    beats_arr = np.asarray(beats, dtype=float)
    period = float(np.median(np.diff(beats_arr))) if beats_arr.size > 1 else 0.5
    bar = beats_per_bar * period

    # Cuenta: en el último compás, un número por pulso; en los anteriores, "1 … 2 …".
    count_end = None
    if count_times:
        per_bar = beats_per_bar
        bars = max(1, len(count_times) // per_bar)
        numbers = [f"n{i + 1}" for i in range(per_bar)]
        if all(n in available for n in numbers):
            for index, t in enumerate(count_times):
                bar_index, beat = divmod(index, per_bar)
                if bar_index < bars - 1:
                    half = per_bar // 2 if per_bar % 2 == 0 else per_bar
                    if beat % half == 0:
                        placements.append(Placement(f"n{beat // half + 1}", t, "cuenta"))
                else:
                    placements.append(Placement(numbers[beat], t, "cuenta"))
        elif "cuenta" in available:
            first_of_last = count_times[-per_bar] if len(count_times) >= per_bar else count_times[0]
            placements.append(Placement("cuenta", first_of_last, "cuenta"))
        if placements:
            step = count_times[-1] - count_times[-2] if len(count_times) > 1 else period
            count_end = count_times[-1] + step

    def anchor_for(start: float, bars_before: int) -> float:
        beats_before = lead * bars_before
        if beats_arr.size:
            j = int(np.searchsorted(beats_arr, start - 0.08))
            anchor = beats_arr[j - beats_before] if j - beats_before >= 0 else start - beats_before * period
        else:
            anchor = start - beats_before * period
        return max(anchor, start - (bars_before + 1) * bar)

    extras = dict(extras or {})
    for index, section in enumerate(sections):
        start = float(section["start"])
        label = str(section.get("label") or "")
        if "guide" in section and section["guide"] is not None:
            chosen = str(section["guide"])
            cue = chosen if chosen in available else None
        else:
            cue = cue_for_label(label, available, numbering)
        extra = section.get("guideExtra") or extras.get(index)
        if extra not in available:
            extra = None
        if start < bar * 0.75:
            continue  # la canción arranca con esta parte: la cubre la cuenta
        for kind, bars_before in ((extra, 2 if cue else 1), (cue, 1)):
            if kind is None:
                continue
            time = to_output(anchor_for(start, bars_before))
            if count_end is not None and time < count_end - 0.05:
                continue  # cae dentro de la cuenta inicial
            placements.append(Placement(kind, time, label))
    return sorted(placements, key=lambda p: p.time)


def render_guide(placements: list[Placement], cues: dict[str, np.ndarray], length: int) -> np.ndarray:
    """Pista guía estéreo (2, length). Si una voz se superpone con la siguiente, se corta con fundido."""
    out = np.zeros(length, dtype=np.float32)
    fade = int(0.02 * SAMPLE_RATE)
    for i, placement in enumerate(placements):
        audio = cues.get(placement.cue)
        if audio is None:
            continue
        start = int(round(placement.time * SAMPLE_RATE))
        if start >= length or start + audio.size <= 0:
            continue
        clip = audio.copy()
        if i + 1 < len(placements):
            limit = int(round(placements[i + 1].time * SAMPLE_RATE)) - start
            if limit <= 0:
                continue  # dos voces en el mismo lugar: queda la siguiente
            if limit < clip.size:
                clip = clip[:limit]
                n = min(fade, clip.size)
                clip[-n:] *= np.linspace(1, 0, n, dtype=np.float32)
        if start < 0:
            clip = clip[-start:]
            start = 0
        end = min(length, start + clip.size)
        out[start:end] += clip[: end - start]
    return np.vstack([out, out])
