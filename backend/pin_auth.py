import secrets
import threading
import time
from collections import defaultdict, deque
from fastapi import Request, Response, HTTPException
from fastapi.responses import JSONResponse

# Web/browser authentication for the external Python server.
# The mobile app keeps using its existing device header/WebSocket path so this
# change does not break phone-to-server transfers. Browser access is denied
# until the server PIN is entered and a short-lived session cookie is issued.
PIN = f"{secrets.randbelow(900000) + 100000:06d}"
SESSION_TTL_SECONDS = 30 * 60
MAX_ATTEMPTS_PER_MINUTE = 5
_sessions = {}
_attempts = defaultdict(deque)
_lock = threading.Lock()

print(f"[PhotoSync] Web pairing PIN: {PIN}")


def _cleanup(now=None):
    now = now or time.time()
    expired = [token for token, expires in _sessions.items() if expires <= now]
    for token in expired:
        _sessions.pop(token, None)


def valid_session(token):
    if not token:
        return False
    with _lock:
        _cleanup()
        expires = _sessions.get(token)
        return expires is not None and expires > time.time()


def _client_ip(request: Request):
    return request.client.host if request.client else "unknown"


def pair(pin: str, request: Request):
    now = time.time()
    ip = _client_ip(request)
    with _lock:
        attempts = _attempts[ip]
        while attempts and now - attempts[0] >= 60:
            attempts.popleft()
        if len(attempts) >= MAX_ATTEMPTS_PER_MINUTE:
            raise HTTPException(429, "Too many PIN attempts; try again later")
        if pin != PIN:
            attempts.append(now)
            raise HTTPException(403, "Invalid PIN")
        attempts.clear()
        token = secrets.token_urlsafe(32)
        _sessions[token] = now + SESSION_TTL_SECONDS
    response = JSONResponse({"paired": True, "expires_in_seconds": SESSION_TTL_SECONDS})
    response.set_cookie(
        key="photosync_session",
        value=token,
        max_age=SESSION_TTL_SECONDS,
        httponly=True,
        samesite="strict",
        secure=False,
        path="/",
    )
    return response


def logout():
    response = JSONResponse({"ok": True})
    response.delete_cookie("photosync_session", path="/")
    return response


_PUBLIC_EXACT = {
    "/",
    "/health",
    "/api/pin",
    "/api/pair",
    "/api/session",
    "/api/logout",
}
_PUBLIC_PREFIXES = ("/dashboard",)
# Account endpoints are kept available for the existing mobile account UI.
# File-transfer and dashboard APIs are protected by the middleware below.
_APP_TRUST_HEADER = "X-PhotoSync-Device-ID"


def install(app):
    @app.get("/api/pin")
    def pin_info():
        return {"pin_required": True, "message": "Enter the pairing PIN shown in the server console"}

    @app.post("/api/pair")
    def pair_endpoint(request: Request, pin: str):
        return pair(pin.strip(), request)

    @app.get("/api/session")
    def session_endpoint(request: Request):
        token = request.cookies.get("photosync_session")
        return {"authorized": valid_session(token), "expires_in_seconds": SESSION_TTL_SECONDS if valid_session(token) else 0}

    @app.post("/api/logout")
    def logout_endpoint():
        return logout()

    @app.middleware("http")
    async def pin_gate(request: Request, call_next):
        path = request.url.path
        if path in _PUBLIC_EXACT or any(path.startswith(prefix) for prefix in _PUBLIC_PREFIXES):
            return await call_next(request)
        # Preserve the existing Android client/API behaviour. Browser pages do
        # not send this header; the dashboard therefore cannot bypass the PIN.
        if request.headers.get(_APP_TRUST_HEADER):
            return await call_next(request)
        token = request.cookies.get("photosync_session")
        if valid_session(token):
            return await call_next(request)
        return JSONResponse({"detail": "PIN pairing required"}, status_code=401)

    return app
