@echo off
setlocal
cd /d "%~dp0"
where py >nul 2>nul
if %errorlevel%==0 (set PY=py) else (set PY=python)
set FIRST_RUN=0
if not exist .venv (
  set FIRST_RUN=1
  echo Creating Python virtual environment...
  %PY% -m venv .venv
)
call .venv\Scripts\activate.bat

rem Always sync the server environment. Existing .venv folders may contain an
rem incompatible "multipart" package, which breaks FastAPI File/Form uploads.
python -m pip uninstall -y multipart >nul 2>&1
python -m pip install -r requirements.txt --upgrade
if errorlevel 1 (
  echo Failed to install/sync dependencies.
  pause
  exit /b 1
)

echo.
echo Configuring PHOTOSYNC private-LAN firewall rules...
net session >nul 2>&1
if %errorlevel%==0 (
  netsh advfirewall firewall delete rule name="PhotoSync TCP 8000" >nul 2>&1
  netsh advfirewall firewall add rule name="PhotoSync TCP 8000" dir=in action=allow protocol=TCP localport=8000 profile=private >nul
  netsh advfirewall firewall delete rule name="PhotoSync UDP 8001" >nul 2>&1
  netsh advfirewall firewall add rule name="PhotoSync UDP 8001" dir=in action=allow protocol=UDP localport=8001 profile=private >nul
  echo Private-LAN firewall rules ready.
) else (
  echo WARNING: Administrator permission is required to add Windows Firewall rules.
  echo If phones cannot reach this PC, run this BAT file once as Administrator.
)

echo.
echo PHOTOSYNC server starting on port 8000...
echo LAN access requires the PC and phone to be on the same reachable network.
echo Keep this window open while using the app.
python -m uvicorn main:app --host 0.0.0.0 --port 8000
pause
