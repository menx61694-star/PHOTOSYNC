import ipaddress
import secrets
import threading
import time
from collections import defaultdict, deque
from urllib.parse import urlencode
from urllib.request import Request as UrlRequest, urlopen
from fastapi import Request, HTTPException
from fastapi.responses import JSONResponse

# The Android app owns the pairing PIN. The external PC server must never
# generate or persist its own PIN. For browser pairing, the PC server forwards
# the entered PIN to the selected phone's embedded Android server and accepts
# the pairing only when that phone validates it.
SESSION_TTL_SECONDS = 30 * 60
MAX_ATTEMPTS_PER_MINUTE = 5
_LOCAL_SERVER_PORT = 18000
_sessions = {}
_attempts = defaultdict(deque)
_lock = threading.Lock()


def _cleanup(now=None):
    now = now or time.time()
    expired = [token for token, data in _sessions.items() if data.get("expires", 0) <= now]
    for token in expired:
        _sessions.pop(token, None)


def valid_session(token, device_id=None):
    if not token:
        return False
    with _lock:
        _cleanup()
        data = _sessions.get(token)
        if not data or data.get("expires", 0) <= time.time():
            return False
        if device_id and data.get("device_id") != device_id:
            return False
        return True


def session_device(token):
    if not token:
        return None
    with _lock:
        _cleanup()
        data = _sessions.get(token)
        return data.get("device_id") if data else None


def _client_ip(request: Request):
    return request.client.host if request.client else "unknown"


def _safe_phone_ip(value: str):
    try:
        ip = ipaddress.ip_address((value or "").strip())
    except ValueError:
        return None
    if ip.version != 4 or not ip.is_private:
        return None
    return str(ip)


def _verify_phone_pin(phone_ip: str, pin: str):
    phone_ip = _safe_phone_ip(phone_ip)
    pin = (pin or "").strip()
    if not phone_ip or not pin.isdigit() or len(pin) != 6:
        return False

    # The PIN is checked by the Android app itself. The PC server only relays
    # the challenge; it does not know, generate, or store the PIN.
    query = urlencode({"pin": pin})
    url = f"http://{phone_ip}:{_LOCAL_SERVER_PORT}/api/pair?{query}"
    try:
        req = UrlRequest(url, method="POST", headers={"Cache-Control": "no-store"})
        with urlopen(req, timeout=2.5) as response:
            return 200 <= response.status < 300
    except Exception:
        # Android LocalServer currently exposes /api/pair as a POST endpoint;
        # if an older build only accepts GET, retry without changing ownership
        # of the PIN. This keeps compatibility with older app builds.
        try:
            with urlopen(url, timeout=2.5) as response:
                return 200 <= response.status < 300
        except Exception:
            return False


def pair_with_phone(pin: str, phone_ip: str, device_id: str, request: Request):
    now = time.time()
    ip = _client_ip(request)
    key = f"{ip}|{device_id}"
    with _lock:
        attempts = _attempts[key]
        while attempts and now - attempts[0] >= 60:
            attempts.popleft()
        if len(attempts) >= MAX_ATTEMPTS_PER_MINUTE:
            raise HTTPException(429, "Too many PIN attempts; try again later")

    if not _verify_phone_pin(phone_ip, pin):
        with _lock:
            _attempts[key].append(now)
        raise HTTPException(403, "Invalid PIN or phone is unreachable")

    with _lock:
        _attempts[key].clear()
        token = secrets.token_urlsafe(32)
        _sessions[token] = {
            "expires": now + SESSION_TTL_SECONDS,
            "device_id": device_id,
        }

    response = JSONResponse({
        "paired": True,
        "session_token": token,
        "device_id": device_id,
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


def logout(token=None):
    if token:
        with _lock:
            _sessions.pop(token, None)
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
    "/web-client/pair",
    "/connections",
}
_PUBLIC_PREFIXES = ("/dashboard",)
_APP_TRUST_HEADER = "X-PhotoSync-Device-ID"
_SESSION_HEADER = "X-PhotoSync-Session"
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
        return {
            "pin_required": True,
            "owner": "android_app",
            "message": "Enter the PIN shown for the selected phone in the PhotoSync app",
        }

    # Kept for compatibility with older clients. The PC server does not own a
    # PIN, so this endpoint cannot authenticate a browser by itself.
    @app.post("/api/pair")
    def pair_endpoint(request: Request, pin: str, device_ip: str = "", device_id: str = ""):
        if not device_ip or not device_id:
            raise HTTPException(400, "device_ip and device_id are required")
        return pair_with_phone(pin.strip(), device_ip, device_id, request)

    @app.get("/api/session")
    def session_endpoint(request: Request):
        token = _request_session(request)
        device_id = session_device(token)
        authorized = valid_session(token)
        return {
            "authorized": authorized,
            "device_id": device_id if authorized else None,
            "expires_in_seconds": SESSION_TTL_SECONDS if authorized else 0,
        }

    @app.post("/api/logout")
    def logout_endpoint(request: Request):
        return logout(_request_session(request))

    @app.middleware("http")
    async def pin_gate(request: Request, call_next):
        path = request.url.path

        # Public bootstrap endpoints expose no file contents. Browser pairing
        # itself is handled by the /web-client/pair endpoint in main.py, which
        # supplies the selected phone IP and calls pair_with_phone().
        if path in _PUBLIC_EXACT or any(path.startswith(prefix) for prefix in _PUBLIC_PREFIXES):
            return await call_next(request)

        # Android app API calls use the existing device identity header. The
        # browser can never use this bypass because it does not have the app's
        # device identity.
        if request.headers.get(_APP_TRUST_HEADER):
            return await call_next(request)

        token = _request_session(request)
        if valid_session(token):
            return await call_next(request)
        return JSONResponse({"detail": "PIN pairing required"}, status_code=401)

    return app
