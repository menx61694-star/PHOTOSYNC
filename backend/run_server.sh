#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
PYTHON_BIN="python3"
command -v python3 >/dev/null 2>&1 || PYTHON_BIN="python"
FIRST_RUN=0
if [ ! -d .venv ]; then
  FIRST_RUN=1
  echo "Creating Python virtual environment..."
  "$PYTHON_BIN" -m venv .venv
fi
source .venv/bin/activate
if [ "$FIRST_RUN" = "1" ]; then
  echo "Installing PHOTOSYNC dependencies - this happens only on first run..."
  python -m pip install -r requirements.txt
fi
echo "PHOTOSYNC server starting on port 8000..."
python -m uvicorn main:app --host 0.0.0.0 --port 8000
