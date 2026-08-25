import secrets
import threading
import time
from collections import defaultdict, deque
from fastapi import Request, HTTPException
from fastapi.responses import JSONResponse

# Browser authentication for the external Python server.
# The PIN protects browser pairing. A successful pairing creates a short-lived
# session which is carried by an HttpOnly cookie or an explicit session header.
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


def _check_pin(pin: str, request: Request):
    now = time.time()
    ip = _client_ip(request)
    with _lock:
        attempts = _attempts[ip]
        while attempts and now - attempts[0] >= 60:
            attempts.popleft()
        if len(attempts) >= MAX_ATTEMPTS_PER_MINUTE:
            raise HTTPException(429, "Too many PIN attempts; try again later")
        if pin.strip() != PIN:
            attempts.append(now)
            raise HTTPException(403, "Invalid PIN")
        attempts.clear()
        token = secrets.token_urlsafe(32)
        _sessions[token] = now + SESSION_TTL_SECONDS
    return token


def pair(pin: str, request: Request):
    token = _check_pin(pin, request)
    response = JSONResponse({
        "paired": True,
        "session_token": token,
        "expires_in_seconds": SESSION_TTL_SECONDS,
    })
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
    "/web-client/session",
    "/connections",
}
_PUBLIC_PREFIXES = ("/dashboard",)
_APP_TRUST_HEADER = "X-PhotoSync-Device-ID"
_SESSION_HEADER = "X-PhotoSync-Session"
_PAIR_PIN_HEADER = "X-PhotoSync-Pair-PIN"
_SESSION_QUERY = "session"


def _request_session(request: Request):
    return (
        request.cookies.get("photosync_session")
        or request.headers.get(_SESSION_HEADER, "")
        or request.query_params.get(_SESSION_QUERY, "")
    )


def install(app):
    @app.get("/api/pin")
    def pin_info():
        return {"pin_required": True, "message": "Enter the pairing PIN shown in the server console"}

    @app.post("/api/pair")
    def pair_endpoint(request: Request, pin: str):
        return pair(pin.strip(), request)

    @app.get("/api/session")
    def session_endpoint(request: Request):
        token = _request_session(request)
        authorized = valid_session(token)
        return {"authorized": authorized, "expires_in_seconds": SESSION_TTL_SECONDS if authorized else 0}

    @app.post("/api/logout")
    def logout_endpoint():
        return logout()

    @app.middleware("http")
    async def pin_gate(request: Request, call_next):
        path = request.url.path

        # These endpoints are needed to bootstrap the browser pairing flow.
        # They do not expose file contents.
        if path in _PUBLIC_EXACT or any(path.startswith(prefix) for prefix in _PUBLIC_PREFIXES):
            return await call_next(request)

        # Android app API calls use the existing device identity header.
        if request.headers.get(_APP_TRUST_HEADER):
            return await call_next(request)

        # Browser pairing is allowed only when the current server PIN is sent
        # in a dedicated header. On success we create the same short-lived
        # session used by the normal /api/pair endpoint, then let the actual
        # /web-client/pair route run normally.
        if path == "/web-client/pair":
            pair_pin = request.headers.get(_PAIR_PIN_HEADER, "")
            if pair_pin:
                token = _check_pin(pair_pin, request)
                response = await call_next(request)
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

        token = _request_session(request)
        if valid_session(token):
            return await call_next(request)
        return JSONResponse({"detail": "PIN pairing required"}, status_code=401)

    return app
