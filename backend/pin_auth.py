import ipaddress
import secrets
import threading
import time
from collections import defaultdict, deque
from urllib.parse import urlencode
from urllib.request import ProxyHandler, Request as UrlRequest, build_opener
from fastapi import Request, HTTPException
from fastapi.responses import JSONResponse

# The Android app owns the pairing PIN. The external PC server never generates
# or persists a server-wide PIN. For browser pairing, the PC server forwards
# the entered PIN to the selected phone's embedded Android server.
SESSION_TTL_SECONDS = 30 * 60
MAX_ATTEMPTS_PER_MINUTE = 5
_LOCAL_SERVER_PORT = 18000
_sessions = {}
_attempts = defaultdict(deque)
_lock = threading.Lock()
_direct_opener = build_opener(ProxyHandler({}))


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
    # the challenge and does not store the PIN.
    query = urlencode({"pin": pin})
    url = f"http://{phone_ip}:{_LOCAL_SERVER_PORT}/api/pair?{query}"
    try:
        req = UrlRequest(url, method="POST", headers={"Cache-Control": "no-store"})
        with _direct_opener.open(req, timeout=2.5) as response:
            return 200 <= response.status < 300
    except Exception:
        # Compatibility with older Android local-server builds that accepted
        # the pairing request through GET.
        try:
            with _direct_opener.open(url, timeout=2.5) as response:
                return 200 <= response.status < 300
        except Exception:
            return False


def _create_session(pin: str, phone_ip: str, device_id: str, request: Request):
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
        _sessions[token] = {"expires": now + SESSION_TTL_SECONDS, "device_id": device_id}
    return token


def _set_session_cookie(response, token):
    response.set_cookie(
        key="photosync_session",
        value=token,
        max_age=SESSION_TTL_SECONDS,
        httponly=True,
        samesite="strict",
        secure=False,
        path="/",
    )


def _set_session_header(response, token):
    # Standalone.html can be opened from a different origin (including file://).
    # Such a page cannot rely on same-origin cookies, so expose the short-lived
    # bearer token explicitly. The token is still never persisted by the PC
    # server on disk and expires after SESSION_TTL_SECONDS.
    response.headers["X-PhotoSync-Session"] = token
    response.headers["Access-Control-Expose-Headers"] = "X-PhotoSync-Session"


def pair_with_phone(pin: str, phone_ip: str, device_id: str, request: Request):
    token = _create_session(pin, phone_ip, device_id, request)
    response = JSONResponse({
        "paired": True,
        "session_token": token,
        "device_id": device_id,
        "expires_in_seconds": SESSION_TTL_SECONDS,
    })
    _set_session_cookie(response, token)
    _set_session_header(response, token)
    return response


def logout(token=None):
    if token:
        with _lock:
            _sessions.pop(token, None)
    response = JSONResponse({"ok": True})
    response.delete_cookie("photosync_session", path="/")
    return response


def _live_app_request(request: Request):
    """Trust the app identity only when it matches a live WebSocket peer IP."""
    device_id = (request.headers.get("X-PhotoSync-Device-ID") or "").strip()
    if not device_id:
        return False
    client_ip = _client_ip(request)
    try:
        import main as server_main
        manager = getattr(server_main, "manager", None)
        if manager is None:
            return False
        for ws, did in list(manager.connections.items()):
            ws_ip = manager.connection_ips.get(ws, "")
            if did == device_id and ws_ip == client_ip:
                return True
    except Exception:
        return False
    return False


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
_PAIR_PIN_HEADER = "X-PhotoSync-Pair-PIN"
_PAIR_IP_HEADER = "X-PhotoSync-Device-IP"
_PAIR_DEVICE_HEADER = "X-PhotoSync-Device-ID"


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

    # Compatibility endpoint. It requires a selected phone because the PC
    # server itself does not own a PIN.
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

        # Browser pairing is per-phone. The PIN stays in a request header and
        # is immediately forwarded to the selected phone; it is never stored
        # in the PC server's files or configuration.
        if path == "/web-client/pair":
            pin = request.headers.get(_PAIR_PIN_HEADER, "").strip()
            phone_ip = request.headers.get(_PAIR_IP_HEADER, "").strip()
            device_id = request.headers.get(_PAIR_DEVICE_HEADER, "").strip()
            if request.method != "POST":
                return JSONResponse({"detail": "Method not allowed"}, status_code=405)
            if not pin or not phone_ip or not device_id:
                return JSONResponse({"detail": "Phone PIN, device IP and device ID are required"}, status_code=400)
            try:
                token = _create_session(pin, phone_ip, device_id, request)
            except HTTPException as exc:
                return JSONResponse({"detail": exc.detail}, status_code=exc.status_code)
            response = await call_next(request)
            if response.status_code < 400:
                _set_session_cookie(response, token)
                _set_session_header(response, token)
            else:
                with _lock:
                    _sessions.pop(token, None)
            return response

        # These bootstrap endpoints expose no file contents.
        if path in _PUBLIC_EXACT or any(path.startswith(prefix) for prefix in _PUBLIC_PREFIXES):
            return await call_next(request)

        # A device header alone is not sufficient: browsers can spoof headers.
        # Require the request source IP to match the live WebSocket connection
        # registered for that device ID.
        if request.headers.get(_APP_TRUST_HEADER) and _live_app_request(request):
            return await call_next(request)

        token = _request_session(request)
        if valid_session(token):
            return await call_next(request)
        return JSONResponse({"detail": "PIN pairing required"}, status_code=401)

    return app
