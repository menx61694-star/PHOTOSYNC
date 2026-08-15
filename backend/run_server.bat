@echo off
setlocal
cd /d "%~dp0"
where py >nul 2>nul
if %errorlevel%==0 (set PY=py) else (set PY=python)
if not exist .venv (
  echo Creating Python virtual environment...
  %PY% -m venv .venv
)
call .venv\Scripts\activate.bat
python -m pip install --upgrade pip
python -m pip install -r requirements.txt
if errorlevel 1 (
  echo Failed to install dependencies.
  pause
  exit /b 1
)
echo.
echo PHOTOSYNC server starting on port 8000...
echo Keep this window open while using the app.
python -m uvicorn main:app --host 0.0.0.0 --port 8000
pause
