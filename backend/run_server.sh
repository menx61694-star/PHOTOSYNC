#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
PYTHON_BIN="python3"
command -v python3 >/dev/null 2>&1 || PYTHON_BIN="python"
if [ ! -d .venv ]; then
  echo "Creating Python virtual environment..."
  "$PYTHON_BIN" -m venv .venv
fi
source .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -r requirements.txt
echo "PHOTOSYNC server starting on port 8000..."
python -m uvicorn main:app --host 0.0.0.0 --port 8000
