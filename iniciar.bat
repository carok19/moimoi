@echo off
setlocal EnableExtensions
cd /d "%~dp0"
title MoiMoi

rem MoiMoi: instala todo la primera vez y abre la app en el navegador.
rem Para usarlo tambien desde celulares de la misma red:  iniciar.bat --host 0.0.0.0

set "PYTHON="
where py >nul 2>nul && set "PYTHON=py -3"
if not defined PYTHON (
  where python >nul 2>nul && set "PYTHON=python"
)
if not defined PYTHON (
  echo Falta Python 3.10 o superior. Descargalo de https://www.python.org/downloads/
  echo Al instalarlo marca la opcion "Add python.exe to PATH".
  pause
  exit /b 1
)

if not exist ".venv\Scripts\python.exe" (
  echo Creando el entorno de Python, solo la primera vez...
  %PYTHON% -m venv .venv
  if errorlevel 1 goto :error
)
set "PY=%CD%\.venv\Scripts\python.exe"
"%PY%" -c "import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)"
if errorlevel 1 (
  echo MoiMoi necesita Python 3.10 o superior.
  pause
  exit /b 1
)

if exist ".venv\moimoi-instalado.txt" goto :frontend
echo Instalando MoiMoi. La primera vez tarda varios minutos porque descarga PyTorch...
"%PY%" -m pip install --upgrade pip wheel
"%PY%" -c "import torch" >nul 2>nul
if not errorlevel 1 goto :deps
where nvidia-smi >nul 2>nul
if errorlevel 1 goto :torchcpu
if not defined MOIMOI_TORCH_INDEX set "MOIMOI_TORCH_INDEX=https://download.pytorch.org/whl/cu128"
echo Se detecto una tarjeta NVIDIA: instalando PyTorch con CUDA...
"%PY%" -m pip install torch torchaudio --index-url %MOIMOI_TORCH_INDEX%
if not errorlevel 1 goto :deps
echo No se pudo instalar la version con CUDA; se usa la version para procesador.
:torchcpu
"%PY%" -m pip install torch torchaudio
if errorlevel 1 goto :error
:deps
"%PY%" -m pip install -r backend\requirements.txt
if errorlevel 1 goto :error
echo ok> ".venv\moimoi-instalado.txt"

:frontend
if exist "frontend\dist\index.html" goto :run
where npm >nul 2>nul
if errorlevel 1 (
  echo Falta Node.js para preparar la interfaz: https://nodejs.org - version 18 o superior.
  pause
  exit /b 1
)
echo Preparando la interfaz web...
pushd frontend
call npm install --no-audit --no-fund
if errorlevel 1 goto :errorpop
call npm run build
if errorlevel 1 goto :errorpop
popd

:run
cd backend
"%PY%" -m moimoi %*
goto :eof

:errorpop
popd
:error
echo.
echo Hubo un error durante la instalacion. Revisa los mensajes de arriba.
pause
exit /b 1
