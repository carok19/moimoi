#!/usr/bin/env bash
# MoiMoi: instala todo la primera vez y abre la app en el navegador.
# Uso:  ./iniciar.sh                    (también desde celulares/tablets de la misma red WiFi)
#       ./iniciar.sh --host 127.0.0.1   (solo en esta computadora)
set -euo pipefail
cd "$(dirname "$0")"

PYTHON="${PYTHON:-}"
if [ -z "$PYTHON" ]; then
  for candidate in python3.12 python3.11 python3.13 python3.10 python3; do
    if command -v "$candidate" >/dev/null 2>&1; then PYTHON="$candidate"; break; fi
  done
fi
if [ -z "$PYTHON" ]; then
  echo "Falta Python 3.10 o superior: https://www.python.org/downloads/"
  exit 1
fi
if ! "$PYTHON" -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)'; then
  echo "MoiMoi necesita Python 3.10 o superior (tienes $("$PYTHON" --version))."
  exit 1
fi

if [ ! -x .venv/bin/python ]; then
  echo "→ Creando el entorno de Python (solo la primera vez)…"
  "$PYTHON" -m venv .venv
fi
PY="$PWD/.venv/bin/python"

MARK=.venv/.moimoi-instalado
if [ ! -f "$MARK" ] || [ backend/requirements.txt -nt "$MARK" ]; then
  echo "→ Instalando MoiMoi (la primera vez tarda varios minutos: descarga PyTorch)…"
  "$PY" -m pip install --upgrade pip wheel
  if ! "$PY" -c "import torch" >/dev/null 2>&1; then
    if [ "$(uname)" = "Linux" ] && ! command -v nvidia-smi >/dev/null 2>&1; then
      # Sin GPU NVIDIA: la versión solo-procesador pesa mucho menos.
      "$PY" -m pip install torch torchaudio --index-url https://download.pytorch.org/whl/cpu \
        || "$PY" -m pip install torch torchaudio
    else
      "$PY" -m pip install torch torchaudio
    fi
  fi
  "$PY" -m pip install -r backend/requirements.txt
  touch "$MARK"
fi

needs_build=0
if [ ! -f frontend/dist/index.html ]; then
  needs_build=1
elif [ -n "$(find frontend/src frontend/index.html frontend/package.json -newer frontend/dist/index.html -print -quit 2>/dev/null)" ]; then
  needs_build=1
fi
if [ "$needs_build" = 1 ]; then
  if command -v npm >/dev/null 2>&1; then
    echo "→ Preparando la interfaz web…"
    (cd frontend && npm install --no-audit --no-fund && npm run build)
  elif [ ! -f frontend/dist/index.html ]; then
    echo "Falta Node.js para preparar la interfaz: https://nodejs.org (versión 18 o superior)."
    exit 1
  fi
fi

cd backend
exec "$PY" -m moimoi "$@"
