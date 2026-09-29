# MoiMoi — separador de pistas con IA

MoiMoi separa cualquier canción en sus instrumentos (**voz, batería, bajo, guitarra, piano y otros**) y te
deja practicar y tocar encima: mezclador por pista, velocidad y tono en tiempo real, acordes, tempo,
tonalidad, partes de la canción, metrónomo y loops. Todo corre en tu propia computadora.

Está pensado para bandas y ministerios de alabanza: con **"Tocar con mi banda"** silencias lo que ya tocan
ustedes y suena solo lo que les falta, y con **Exportar → Multitrack** generas un `.zip` listo para
**Multitrack Alabanza** (la app del repo `Daw`, también llamada AI Tracks) con **click**, **voz guía** que
anuncia cada parte ("Verso 1", "Coro"…), cuenta inicial y las partes como marcadores. El paquete se envía
directo a Multitrack Alabanza o se comparte por WhatsApp.

Y está la **app de Android**, que funciona como Moises: la instalas, eliges una canción del celular y **el
mismo celular la separa**, sin computadora ni internet (ver [En el celular](#en-el-celular-android)). Si
prefieres, la app también puede usar MoiMoi de la computadora.

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
| **Voz guía y click** | Pista **Guía** con tus voces guía (el paquete que descargaste o grabaciones tuyas): anuncia cada parte un compás antes, cuenta "1, 2, 3, 4" antes de empezar y avisa "Sube tono" donde la canción modula. Click con el sonido que elijas. |
| **Multitrack Alabanza** | **Enviar a Multitrack Alabanza** abre la canción directo en el programa (misma computadora o red), con las pistas en orden, sus nombres y las partes como marcadores. |
| **Celular** | App de Android que **separa en el mismo celular** (sin computadora ni internet), o conectada a la computadora; también desde el navegador del celular. **Compartir** pistas y paquetes por WhatsApp, Drive, etc. "Compartir → MoiMoi" de un audio (WhatsApp, Archivos…) lo agrega y lo separa. |
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

---

## En el celular (Android)

**La app de Android hace todo en el celular**: la instalas, eliges una canción y el celular separa la voz, la
batería, el bajo, la guitarra, el piano y lo demás (Demucs de 6 pistas, el mismo modelo que en la
computadora). No hace falta computadora ni internet.

1. Descarga **MoiMoi.apk** en el celular desde la versión
   [`android`](https://github.com/carok19/moimoi/releases/tag/android) del repositorio (la compila GitHub
   Actions con cada cambio). Como el repositorio es privado, hay que tener la sesión de GitHub iniciada en el
   navegador del celular; si no, descárgalo en la computadora y pásalo al celular (WhatsApp, cable o Drive).
2. Ábrelo e instálalo (Android pide permiso para instalar apps de fuera de Play Store). Si ya tenías una versión
   anterior, se instala encima.
3. En la app toca **Elegir canciones del celular** (MP3, M4A, WAV, FLAC, OGG, audios de WhatsApp o videos), o
   desde WhatsApp / Archivos usa **Compartir → MoiMoi** en un audio. Elige 2, 4 o 6 pistas.

La separación tarda unos minutos por canción y depende del celular (en la computadora de pruebas, con 4 núcleos,
una canción de 4 minutos tarda cerca de 1 minuto; un celular suele ser entre 2 y 4 veces más lento). Puedes minimizar la app o apagar la pantalla: sigue separando y la notificación muestra el
avance. Las canciones se separan de a una, en orden, y si Android cierra la app a mitad de camino, al volver a
abrirla sigue sola. Después la escuchas con el mezclador (volumen, paneo, solo, mute, velocidad y tono en tiempo
real) y en **Exportar** armas las pistas sueltas, la mezcla o el paquete `.zip` para Multitrack Alabanza:
**Compartir** abre el menú de Android (WhatsApp, Drive, correo…) y **Guardar en el celular** lo deja en
*Descargas/MoiMoi*.

Después de separar, la app analiza la canción en el mismo celular, igual que en la computadora: tempo (BPM),
compás, tonalidad y cambios de tonalidad, acordes (con inversiones), partes (intro, verso, coro, puente…) e
instrumentos. Con eso el reproductor muestra los acordes y las partes, el metrónomo sigue el pulso y el paquete
para Multitrack puede llevar el click y la cuenta inicial.

La **voz guía** también funciona en el celular: en *Ajustes → Voz guía y click* toca **Cargar paquete** y elige
el .zip de tu paquete de voces (o los audios sueltos); la app reconoce cada voz por el nombre del archivo ("Coro",
"Verso 1", "1, 2, 3, 4"…, en varios idiomas) y los sonidos de click del paquete. También puedes grabar tus propias
voces con el micrófono. Al exportar el paquete para Multitrack, marca **Guía**.

Todavía no están en la app del celular (sí en la computadora): links y búsqueda de YouTube, exportar con otra
velocidad o tono, MP3, y la letra automática. Llegan en las próximas versiones.

**Usar la computadora desde la app (opcional):** en *Ajustes → Celular o computadora → Usar MoiMoi de la
computadora* la app se conecta a MoiMoi en una computadora de la **misma red WiFi** (más rápido con tarjeta
gráfica NVIDIA, y con todas las funciones). Abre MoiMoi en la computadora: en su ventana (y en *Ajustes →
Celulares y tablets*) aparece la dirección, por ejemplo `192.168.1.20`; escríbela en la app o toca *Buscar
automáticamente*. *Usar solo el celular* vuelve al modo sin computadora.

**Navegador del celular:** también se puede usar MoiMoi de la computadora sin instalar nada. Escanea el código QR
de *Ajustes → Celulares y tablets* (o escribe `https://192.168.1.20:4748`). La primera vez el navegador avisa que
la conexión "no es privada": toca *Configuración avanzada → Continuar* (el certificado lo creó MoiMoi en tu
computadora; hace falta HTTPS para el motor de audio). Desde el navegador no se pueden compartir archivos `.zip`:
descárgalos y compártelos desde tus descargas, o usa la app.

**Si el celular no se conecta a la computadora:** los dos tienen que estar en la misma red WiFi; en Windows, la
primera vez que abres MoiMoi aparece el aviso del Firewall: elige **Permitir** (redes privadas). En *Ajustes →
Celulares y tablets* se puede desactivar el acceso desde otros equipos (por ejemplo en una red pública).

Los celulares tienen menos memoria: una canción de 6 pistas usa unos 65 MB por minuto en el reproductor, y la
separación necesita alrededor de 1 GB libre mientras trabaja (conviene cerrar otras apps pesadas).

---

## Voz guía

La pista **Guía** anuncia cada parte de la canción ("Intro", "Verso 1", "Coro", "Puente"…) un compás antes,
y cuenta "1, 2, 3, 4" en los compases que se agregan antes de empezar. Así, al pasar la canción a Multitrack
Alabanza, la banda escucha la estructura en los auriculares, y las partes quedan como marcadores.

1. En *Ajustes → Voz guía y click*, **Cargar paquete** y elige el `.zip` de voces guía que descargaste (por
   ejemplo el de secuencias.com) o los audios sueltos. MoiMoi reconoce cada archivo por su nombre, en español
   o inglés: `Spanish - Coro 2 (Chorus 2).wav`, `01 - Verso.mp3`, `VG_PreCoro.wav`, `uno.wav`, `4.wav`…
2. Si el paquete trae **varios idiomas** (español, inglés, portugués…), cada uno queda por separado: elige cuál
   usar. Si trae **sonidos de click** (`Click - Classic-accents.wav`…), aparecen para elegir el sonido del
   click.
3. Revisa la lista: se puede escuchar cada voz, cambiarla por otro archivo o **grabarla con el micrófono**.
   Arriba se avisa si falta alguna importante. Los archivos dañados del `.zip` se saltean y se informan.
4. Al exportar para Multitrack deja activada la **pista Guía** y elige la **cuenta** (0, 1 o 2 compases).

Qué dice en cada parte: el **nombre de la parte** (se puede cambiar en el reproductor). Por defecto numera los
versos ("Verso 1", "Verso 2") y no los coros; se cambia en Ajustes. Si escribes una indicación en el nombre,
también la anuncia antes: "Coro (última vez)", "Puente sube tono", "Verso 2 suave", "Coro todos". Donde la
canción cambia de tonalidad avisa "Sube tono" (o "Baja tono") si el paquete trae esa voz.

Las voces se guardan en la carpeta de datos de MoiMoi (`voz-guia/`); MoiMoi no las sube a ningún lado.

---

## Integración con Multitrack Alabanza (AI Tracks)

MoiMoi y Multitrack Alabanza son programas separados que se conectan con un paquete `.zip`:

1. En MoiMoi abre la canción → **Exportar** → pestaña **Multitrack (AI Tracks)**.
2. Elige las pistas. **"Lo que le falta a mi banda"** marca solo las que tu banda no toca.
3. Deja activados **Click** y **Guía** y elige la **cuenta** antes de empezar (los valores por defecto se
   cambian en *Ajustes → Multitrack Alabanza*). **WAV** es la mejor calidad; **MP3** pesa unas 5 veces menos
   (para WhatsApp), pero los celulares conectados a Multitrack Alabanza solo reproducen WAV.
4. Si cambiaste el tono o la velocidad, puedes **aplicar los cambios**: por ejemplo, bajar la canción dos
   semitonos para el cantante, y las pistas se exportan ya transportadas.
5. **Crear paquete** y después:
   - **Enviar a Multitrack Alabanza**: la canción se abre directo en el programa, en una pestaña nueva (si hay
     una canción sonando, no la interrumpe). Por defecto lo busca en la misma computadora
     (`http://127.0.0.1:4848`); si está en otra, pon su dirección en *Ajustes → Multitrack Alabanza*.
   - **Compartir** (WhatsApp…) o **Descargar**, y en Multitrack Alabanza **Cargar canción (.zip)**.

El `.zip` tiene un audio (WAV de 16 bits, 44,1 kHz, estéreo, o MP3) por pista con nombres simples:
`Click.wav`, `Guia.wav`, `Voz.wav`, `Bateria.wav`, `Bajo.wav`, `Guitarra.wav`, `Piano.wav`, `Otros.wav` (o
`Acompanamiento.wav`), todas de la misma duración y sincronizadas (con la cuenta inicial, todas empiezan con
esos compases). El nombre del zip es el nombre de la canción.

Multitrack Alabanza (rama `claude/moises-ai-track-separation-pdnkil` del repo `Daw`) lee **`moimoi.json`**:
el orden y los nombres de las pistas, los volúmenes y **las partes como marcadores**. Las versiones
anteriores lo ignoran sin problema y cargan una pista por archivo. El archivo tiene todo lo detectado:

```json
{
  "formato": "moimoi-multitrack",
  "version": 1,
  "cancion": { "titulo": "…", "artista": "…", "duracionMs": 247450, "bpm": 72.0, "compas": 4,
               "tonalidad": "A", "tonalidadNombre": "La mayor", "tonalidadOriginal": "G",
               "transposicion": 2, "velocidad": 1.0, "cuentaInicialMs": 2450 },
  "pistas": [ { "archivo": "Click.wav", "nombre": "Click", "instrumento": "click",
                "volumen": 70, "pan": 0, "mute": false, "solo": false },
              { "archivo": "Guia.wav", "nombre": "Guía", "instrumento": "guia", "volumen": 80, … },
              { "archivo": "Voz.wav", "nombre": "Voz", "instrumento": "vocals", "volumen": 80, … } ],
  "marcadores": [ { "nombre": "Intro", "tiempoMs": 0, "color": "#8e9aaf" },
                  { "nombre": "Verso 1", "tiempoMs": 12550, "color": "#4fa3ff" } ],
  "acordes": [ { "inicio": 12.55, "fin": 14.95, "nombre": "A" } ],
  "guia": [ { "voz": "n1", "parte": "cuenta", "tiempoMs": 50 },
            { "voz": "verso1", "parte": "Verso 1", "tiempoMs": 9220 } ],
  "origen": { "app": "MoiMoi", "version": "1.0.0", "cancionId": "…", "url": "…" }
}
```

`pistas` y `marcadores` usan los mismos campos que el modelo de datos de Multitrack Alabanza (`Pista` y
`Marcador` en `src/shared/types.ts`). Los tiempos ya incluyen la cuenta inicial (`cuentaInicialMs`).

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
| `POST /api/songs/{id}/exports` | Crear una exportación: `{"type": "multitrack" \| "stems" \| "mix", "stems": [...], "click": true, "clickSound": "classic", "guide": true, "preRollBars": 1, "tempo": 1.0, "semitones": 0}` |
| `GET /api/jobs/{id}` · `GET /api/jobs/{id}/download` | Estado y descarga de una exportación |
| `POST /api/jobs/{id}/enviar` | Enviar el paquete a Multitrack Alabanza (`{"url": "…"}` opcional) |
| `GET /api/multitrack` | ¿Está abierto Multitrack Alabanza? |
| `GET /api/guia` · `POST /api/guia` | Voces guía cargadas · cargar un paquete (multipart: `files`, y `cue`/`set` para una grabación) |
| `PUT /api/guia/activo` · `PUT /api/guia/{archivo}` | Elegir idioma · asignar un archivo a una voz (`{"cue": "coro"}`) |
| `GET /api/red` | Direcciones para abrir MoiMoi desde celulares |
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
| `MOIMOI_HOST` / `MOIMOI_PORT` | `0.0.0.0` / `4747` | Dirección y puerto. `0.0.0.0` = también desde celulares de la red (si está permitido en Ajustes); `127.0.0.1` = solo esta computadora |
| `MOIMOI_HTTPS_PORT` | `4748` | HTTPS para el navegador del celular (`0` lo desactiva) |
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
  android/          app de Android (Capacitor): la misma interfaz, y el "servidor" de MoiMoi en Java
    …/com/moimoi/engine  motor de separación (Demucs en ONNX Runtime: STFT, segmentos, suma ponderada)
    …/com/moimoi/local   canciones, cola de trabajos, pistas, exportaciones y la API, dentro del celular
    …/com/moimoi/app     Android: decodificar audio, servicio en segundo plano, elegir y compartir archivos
scripts/            prueba de la interfaz en un navegador
  android/          conversión de Demucs a ONNX y pruebas del motor y del "servidor" del celular
.github/workflows/  compilación del APK en GitHub Actions
```

```bash
# Backend
.venv/bin/pip install -r backend/requirements-dev.txt
cd backend && ../.venv/bin/python -m pytest

# Interfaz con recarga automática (usa la API de http://127.0.0.1:4747)
cd frontend && npm install && npm run dev

# Prueba de punta a punta en Chromium (requiere Playwright: npm i -g playwright)
.venv/bin/python backend/tests/e2e_server.py --port 4799 --data /tmp/moimoi-e2e --wav /tmp/demo.wav --pack /tmp/voces.zip &
NODE_PATH="$(npm root -g)" node scripts/prueba_navegador.cjs /tmp/demo.wav /tmp/voces.zip

# App de Android (hace falta Android Studio o el SDK de Android y Java 21)
# 1) El modelo de separación (Demucs htdemucs_6s → ONNX, pesos en 16 bits: ~58 MB). Lo hace GitHub Actions.
pip install torch --index-url https://download.pytorch.org/whl/cpu && pip install demucs onnx onnxruntime onnxscript
python scripts/android/modelo_demucs.py modelo --fp16
mkdir -p frontend/android/app/src/main/assets/models && cp modelo/htdemucs_6s.* frontend/android/app/src/main/assets/models/
# 2) La app
cd frontend && npm run build && npx cap sync android
cd android && ./gradlew assembleDebug      # → app/build/outputs/apk/debug/app-debug.apk

# Pruebas del motor y del "servidor" del celular en la computadora (Java 21):
#   onnxruntime-1.30.0.jar y android-json (org.json de Android) están en Maven Central.
python scripts/android/probar_motor.py modelo onnxruntime.jar          # el motor de la app = Demucs original
javac -d clases -cp onnxruntime.jar:android-json.jar frontend/android/app/src/main/java/com/moimoi/{engine,local}/*.java scripts/android/PruebaLocal.java
java -cp clases:onnxruntime.jar:android-json.jar PruebaLocal modelo    # separar, pistas, exportar, cancelar, retomar…
```

`scripts/android/PuenteWeb.java` sirve la interfaz compilada con el "servidor" del celular detrás, para probar el
modo celular en un navegador de la computadora (con un `androidBridge` falso que reenvía los pedidos de los
plugins).

La app de Android usa la clave de prueba `frontend/android/app/moimoi-debug.keystore` (no es secreta): así
cada APK nuevo se instala encima del anterior. Para publicar en Play Store haría falta una clave propia.

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
React (MIT), Lucide (ISC), Capacitor (MIT), qrcode-generator (MIT), cryptography (Apache-2.0/BSD), FFmpeg
(LGPL/GPL, vía imageio-ffmpeg), lameenc (LGPL) y pedalboard (GPL-3.0,
incluye Rubber Band; se usa para exportar con otra velocidad o tono). Si distribuyes MoiMoi, respeta esas
licencias.
