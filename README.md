# MoiMoi — separador de pistas con IA

MoiMoi separa cualquier canción en sus instrumentos (**voz, batería, bajo, guitarra, piano y otros**) y te
deja practicar y tocar encima: mezclador por pista, velocidad y tono en tiempo real, acordes, tempo,
tonalidad, partes de la canción, metrónomo y loops. Todo corre en tu propia computadora.

Está pensado para bandas y ministerios de alabanza: con **"Tocar con mi banda"** silencias lo que ya tocan
ustedes y suena solo lo que les falta, y con **Exportar → Multitrack** generas un `.zip` listo para abrir en
**Multitrack Alabanza** (la app del repo `Daw`, también llamada AI Tracks), con click y marcadores.

> MoiMoi es un proyecto independiente inspirado en apps como Moises; no está afiliado a ellas.

---

## Qué hace

| | |
|---|---|
| **Entrada** | Links de YouTube (y cualquier sitio que soporte [yt-dlp](https://github.com/yt-dlp/yt-dlp)), búsqueda en YouTube desde la app, o archivos de audio/video (MP3, WAV, FLAC, M4A, OGG, MP4, MOV…). Varios links a la vez. |
| **Separación con IA** | [Demucs v4](https://github.com/facebookresearch/demucs) (Meta AI): 2 pistas (voz / acompañamiento), 4 pistas (voz, batería, bajo, otros) o 6 pistas (+ guitarra y piano). Calidad normal o alta. Usa la GPU NVIDIA si hay. |
| **Mezclador** | Volumen, paneo, mute y solo por pista, medidores, forma de onda de cada instrumento y **Tocar con mi banda**. |
| **Velocidad y tono** | Velocidad de 50 % a 150 % sin cambiar el tono y tono de −12 a +12 semitonos sin cambiar la velocidad, en tiempo real y con todas las pistas sincronizadas. Botón **La 440** si la grabación está desafinada respecto de 440 Hz. |
| **Acordes** | Acorde actual y siguiente, tira de acordes de toda la canción, diagramas de guitarra y piano. Se transportan con el tono. Notación C D E o Do Re Mi. Detecta inversiones (G/B). |
| **Tempo y compás** | BPM, pulsos y el "1" de cada compás (4/4 y 3/4), metrónomo sobre el pulso real de la canción y cuenta antes de empezar. Si el pulso quedó al doble o a la mitad, se corrige con un clic. |
| **Tonalidad** | Tonalidad principal y **cambios de tonalidad** (p. ej. el último coro un tono más arriba). |
| **Partes** | Intro, verso, pre-coro, coro, puente, instrumental y final, detectados automáticamente y **renombrables**. Clic para ir, doble clic para repetir esa parte. Loops arrastrando sobre la forma de onda (se ajustan al pulso). |
| **Instrumentos** | Qué instrumentos suenan, cuánto y en qué partes de la canción. |
| **Letra** (opcional) | Transcripción sincronizada de la voz con Whisper (`faster-whisper`). |
| **Exportar** | Pistas sueltas (WAV/MP3/FLAC), la mezcla que estás escuchando, o el paquete para Multitrack. Opcionalmente con la velocidad y el tono aplicados (p. ej. para bajar la canción a la tonalidad del cantante). |
| **API** | Otras apps pueden listar canciones, bajar pistas y pedir exportaciones (ver [API](#api)). |

---

## Instalación

Necesitas:

- **Python 3.10 a 3.13** (se recomienda 3.12): <https://www.python.org/downloads/>
  - En Windows marca **"Add python.exe to PATH"** al instalarlo.
- **Node.js 18 o superior** (solo para preparar la interfaz la primera vez): <https://nodejs.org>
- Unos **4 GB libres** (PyTorch + modelos) e **internet la primera vez**.

Después:

| Sistema | Cómo abrir MoiMoi |
|---|---|
| **Windows** | Doble clic en **`iniciar.bat`** |
| **macOS** | Doble clic en **`iniciar.command`** (si macOS lo bloquea: clic derecho → Abrir), o `./iniciar.sh` en la Terminal |
| **Linux** | `./iniciar.sh` |

La primera vez el script crea un entorno de Python, instala todo (con GPU NVIDIA instala PyTorch con
CUDA automáticamente) y prepara la interfaz: puede tardar **10 a 20 minutos**. Las siguientes veces abre en
segundos. MoiMoi se abre en el navegador en **<http://127.0.0.1:4747>**.

La primera separación descarga el modelo de IA (una sola vez; unos 80 MB, o 320 MB el de calidad alta).
Para dejarlo descargado antes de usarlo sin internet:

```bash
cd backend
../.venv/bin/python -m moimoi --descargar-modelos        # Mac / Linux
..\.venv\Scripts\python -m moimoi --descargar-modelos    # Windows
```

**¿Cuánto tarda una canción?** Con una GPU NVIDIA, segundos. Solo con el procesador, varios minutos por
canción (depende del equipo); la calidad alta tarda de 2 a 4 veces más. Mientras tanto puedes seguir usando
la app: las canciones se procesan en cola y el progreso se ve en la biblioteca.

---

## Cómo se usa

1. **Agregar una canción:** pega un link de YouTube (o varios), busca por nombre, o arrastra archivos.
   Elige en cuántas pistas separarla (2, 4 o 6) y la calidad.
2. **Abrirla** cuando diga *Lista*. Las pistas se cargan en el navegador.
3. **Mezclar:** sube o baja cada instrumento, silencia (**M**) o escucha solo uno (**S**).
   Con **Tocar con mi banda** suena solo lo que tu banda no toca (se configura en *Ajustes → Mi banda*).
4. **Practicar:** baja la velocidad, cambia el tono, marca un loop (arrastrando sobre la forma de onda o con
   doble clic en una parte), activa la **cuenta** y el **metrónomo**.
5. **Exportar** lo que necesites (ver abajo).

Todo lo que ajustas (volúmenes, velocidad, tono, loop, nombres de las partes) queda guardado en cada canción.

### Atajos de teclado (reproductor)

| Tecla | Acción |
|---|---|
| Espacio | Reproducir / pausa |
| ← / → | 5 segundos atrás / adelante (con Shift: 1 segundo) |
| Inicio | Al principio (o al inicio del loop) |
| L | Loop encendido / apagado (sin loop marcado, repite la parte actual) |
| C | Cuenta antes de empezar |
| M | Metrónomo |
| [ / ] | Más lento / más rápido (5 %) |
| − / + | Bajar / subir medio tono |

### Desde el celular o la tablet

Abre MoiMoi con `iniciar.bat --host 0.0.0.0` (Windows) o `./iniciar.sh --host 0.0.0.0` (Mac/Linux). En la
terminal aparece una dirección como `http://192.168.0.10:4747`: ábrela en el navegador del celular conectado
a la misma red WiFi. La separación la sigue haciendo la computadora. Los celulares tienen menos memoria: una
canción de 6 pistas usa unos 65 MB por minuto en el navegador.

---

## Integración con Multitrack Alabanza (AI Tracks)

MoiMoi y Multitrack Alabanza son programas separados que se conectan con un archivo:

1. En MoiMoi abre la canción → **Exportar** → pestaña **Multitrack (AI Tracks)**.
2. Elige las pistas. **"Lo que le falta a mi banda"** marca solo las que tu banda no toca.
3. Deja activado **Incluir pista de Click** si quieres metrónomo en el multitrack.
4. Si cambiaste el tono o la velocidad, puedes **aplicar los cambios**: por ejemplo, bajar la canción dos
   semitonos para el cantante, y las pistas se exportan ya transportadas.
5. En Multitrack Alabanza usa **Cargar canción (.zip)** y elige el archivo.

El `.zip` tiene un WAV (16 bits, 44,1 kHz, estéreo) por pista con nombres simples: `Voz.wav`,
`Bateria.wav`, `Bajo.wav`, `Guitarra.wav`, `Piano.wav`, `Otros.wav` (o `Acompanamiento.wav`) y `Click.wav`,
todas de la misma duración y sincronizadas. El nombre del zip es el nombre de la canción. Se verificó con el
importador de Multitrack Alabanza (`src/server/zip.ts`).

Además incluye **`moimoi.json`**, que el importador actual ignora sin problema, con todo lo detectado. Sirve para
que Multitrack Alabanza (u otra app) importe también los marcadores y datos de la canción:

```json
{
  "formato": "moimoi-multitrack",
  "version": 1,
  "cancion": { "titulo": "…", "artista": "…", "duracionMs": 245000, "bpm": 72.0, "compas": 4,
               "tonalidad": "A", "tonalidadNombre": "La mayor", "tonalidadOriginal": "G",
               "transposicion": 2, "velocidad": 1.0 },
  "pistas": [ { "archivo": "Voz.wav", "nombre": "Voz", "instrumento": "vocals",
                "volumen": 80, "pan": 0, "mute": false, "solo": false } ],
  "marcadores": [ { "nombre": "Verso 1", "tiempoMs": 10100, "color": "#4fa3ff" } ],
  "acordes": [ { "inicio": 10.1, "fin": 12.5, "nombre": "A" } ],
  "origen": { "app": "MoiMoi", "version": "1.0.0", "cancionId": "…", "url": "…" }
}
```

`pistas` y `marcadores` usan los mismos campos que el modelo de datos de Multitrack Alabanza (`Pista` y
`Marcador` en `src/shared/types.ts`), así que importarlos es directo.

---

## API

La interfaz web usa una API HTTP que también pueden usar otras apps (con CORS abierto). La documentación
interactiva está en <http://127.0.0.1:4747/docs>.

| Método y ruta | Qué hace |
|---|---|
| `GET /api/songs` | Lista de canciones (estado, progreso, pistas, BPM, tonalidad) |
| `POST /api/songs/url` | Agregar por link: `{"url": "…", "preset": "6stems", "quality": "normal"}` |
| `POST /api/songs/upload` | Subir un archivo (multipart: `file`, `preset`, `quality`) |
| `GET /api/songs/{id}` | Detalle de una canción |
| `GET /api/songs/{id}/analysis` | Pulsos, compases, tonalidad, acordes, secciones e instrumentos |
| `GET /api/songs/{id}/audio/{pista}.flac` | Pista para reproducir (acepta rangos) |
| `GET /api/songs/{id}/download/{pista}.{wav\|mp3\|flac}` | Descargar una pista |
| `POST /api/songs/{id}/exports` | Crear una exportación: `{"type": "multitrack" \| "stems" \| "mix", "stems": [...], "click": true, "tempo": 1.0, "semitones": 0}` |
| `GET /api/jobs/{id}` · `GET /api/jobs/{id}/download` | Estado y descarga de una exportación |
| `POST /api/songs/{id}/retry` | Volver a separar (opcional: otro `preset` o `quality`) |
| `DELETE /api/songs/{id}` | Borrar |
| `GET /api/search?q=…` | Buscar en YouTube |

Pistas: `vocals`, `drums`, `bass`, `guitar`, `piano`, `other` (o `instrumental` con 2 pistas).

---

## Configuración

Variables de entorno opcionales:

| Variable | Por defecto | Para qué |
|---|---|---|
| `MOIMOI_DATA_DIR` | `~/MoiMoi` | Dónde se guardan canciones, base de datos, exportaciones y modelos |
| `MOIMOI_HOST` / `MOIMOI_PORT` | `127.0.0.1` / `4747` | Dirección y puerto (`0.0.0.0` para la red local) |
| `MOIMOI_DEVICE` | `auto` | `auto`, `cpu`, `cuda` o `mps` (GPU de Apple, experimental) |
| `MOIMOI_MODEL_REPO` | — | Carpeta con modelos de Demucs ya descargados |
| `MOIMOI_MAX_DURATION_MIN` | `20` | Duración máxima por canción |
| `MOIMOI_TORCH_THREADS` | — | Hilos de CPU para la IA |
| `MOIMOI_OPEN_BROWSER` | `1` | Abrir el navegador al iniciar |
| `MOIMOI_WHISPER_MODEL` | `small` | Modelo de transcripción de letras |
| `MOIMOI_TORCH_INDEX` | CUDA 12.8 | (Windows) índice de PyTorch con CUDA que usa `iniciar.bat` |

**Letras (opcional):** `.venv/bin/pip install -r backend/requirements-letra.txt` (en Windows
`.venv\Scripts\pip ...`) y reinicia MoiMoi. Aparece el botón *Transcribir letra* en el reproductor.

---

## Desarrollo

```
backend/            API (FastAPI), cola de trabajos, separación, análisis y exportaciones
  moimoi/analysis/  tempo, compás, tonalidad, acordes, secciones e instrumentos
  moimoi/separation motor Demucs
  tests/            pruebas (con canciones sintéticas y un motor de separación de prueba)
frontend/           interfaz web (React + TypeScript + Vite)
  src/audio/        motor de audio multipista (Signalsmith Stretch en AudioWorklet)
scripts/            prueba de la interfaz en un navegador
```

```bash
# Backend
.venv/bin/pip install -r backend/requirements-dev.txt
cd backend && ../.venv/bin/python -m pytest

# Interfaz con recarga automática (usa la API de http://127.0.0.1:4747)
cd frontend && npm install && npm run dev

# Prueba de punta a punta en Chromium (requiere Playwright: npm i -g playwright)
.venv/bin/python backend/tests/e2e_server.py --port 4799 --data /tmp/moimoi-e2e --wav /tmp/demo.wav &
NODE_PATH="$(npm root -g)" node scripts/prueba_navegador.cjs /tmp/demo.wav
```

Cómo funciona la reproducción: cada pista va a un nodo de
[Signalsmith Stretch](https://signalsmith-audio.co.uk/code/stretch/) (WASM en un AudioWorklet) y todos
reciben exactamente la misma programación, por eso quedan sincronizadas al cambiar velocidad, tono, loops o
posición. Las pistas se guardan en memoria en 16 bits (un pequeño parche en `vite.config.ts`) para usar la
mitad de RAM.

---

## Limitaciones

- La separación usa Demucs, que es de código abierto y muy bueno, pero no es idéntico a los modelos
  privados de apps comerciales: en algunas mezclas quedan restos de un instrumento en otra pista. El piano
  del modelo de 6 pistas es el más difícil.
- Acordes, tonalidad, compases y partes son estimaciones automáticas: revísalos. Puedes renombrar las partes
  y corregir el pulso (×2, ÷2, mover el "1").
- YouTube cambia seguido. Si deja de descargar, actualiza yt-dlp:
  `.venv/bin/pip install -U "yt-dlp[default]"`. Para YouTube hace falta Deno o Node.js instalado (MoiMoi usa
  Node.js si no encuentra Deno).
- Usa solo música que tengas permiso para usar.

## Licencias de terceros

Demucs (MIT), PyTorch (BSD), Signalsmith Stretch (MIT), yt-dlp (Unlicense), librosa (ISC), FastAPI (MIT),
React (MIT), Lucide (ISC), FFmpeg (LGPL/GPL, vía imageio-ffmpeg), lameenc (LGPL) y pedalboard (GPL-3.0,
incluye Rubber Band; se usa para exportar con otra velocidad o tono). Si distribuyes MoiMoi, respeta esas
licencias.
