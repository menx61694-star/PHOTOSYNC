import ipaddress
import os
import asyncio
import secrets
import threading
import time
from collections import defaultdict, deque
from urllib.parse import urlencode
from urllib.request import ProxyHandler, Request as UrlRequest, build_opener
from urllib.error import HTTPError
from fastapi import Request, HTTPException
from fastapi.responses import JSONResponse

SESSION_TTL_SECONDS = 30 * 60
MAX_ATTEMPTS_PER_MINUTE = 5
SERVER_PAIRING_PIN = (os.getenv("PHOTOSYNC_SERVER_PIN", "") or "").strip()
if not (len(SERVER_PAIRING_PIN) == 6 and SERVER_PAIRING_PIN.isdigit()):
    SERVER_PAIRING_PIN = f"{secrets.randbelow(900000) + 100000:06d}"
_LOCAL_SERVER_PORT = 18000
_sessions = {}
_attempts = defaultdict(deque)
_lock = threading.Lock()
_direct_opener = build_opener(ProxyHandler({}))
_pending_web_pairs = {}


def _cleanup(now=None):
    now = now or time.time()
    expired = [token for token, data in _sessions.items() if data.get("expires", 0) <= now]
    for token in expired:
        _sessions.pop(token, None)

    expired_pairs = [
        request_id
        for request_id, data in _pending_web_pairs.items()
        if now - data.get("created_at", 0) > 300
    ]
    for request_id in expired_pairs:
        _pending_web_pairs.pop(request_id, None)

    for key, attempts in list(_attempts.items()):
        while attempts and now - attempts[0] >= 60:
            attempts.popleft()
        if not attempts:
            _attempts.pop(key, None)


def _attempt_key(request: Request, device_id: str):
    return f"{_client_ip(request)}|{device_id}"


def _record_attempt(key: str, now=None):
    now = now or time.time()
    with _lock:
        _cleanup(now)
        attempts = _attempts[key]
        if len(attempts) >= MAX_ATTEMPTS_PER_MINUTE:
            return False
        attempts.append(now)
        return True


def _clear_attempts(key: str):
    with _lock:
        _attempts.pop(key, None)


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


def session_phone_cookie(token):
    if not token:
        return None
    with _lock:
        _cleanup()
        data = _sessions.get(token)
        return data.get("phone_cookie") if data else None


def revoke_session(token):
    if not token:
        return
    with _lock:
        _sessions.pop(token, None)


def server_pairing_pin():
    return SERVER_PAIRING_PIN


def valid_server_pairing_pin(pin: str):
    return secrets.compare_digest((pin or "").strip(), SERVER_PAIRING_PIN)


def refresh_server_pairing_pin():
    global SERVER_PAIRING_PIN
    with _lock:
        SERVER_PAIRING_PIN = f"{secrets.randbelow(900000) + 100000:06d}"
        _attempts.clear()
        _sessions.clear()
        return SERVER_PAIRING_PIN


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


def _phone_request(phone_ips, method: str, path: str):
    if isinstance(phone_ips, str):
        phone_ips = [phone_ips]
    candidates = []
    for value in phone_ips or []:
        ip = _safe_phone_ip(value)
        if ip and ip not in candidates:
            candidates.append(ip)
    if not candidates:
        return None, None, None
    last_error = None
    # The WebSocket peer address and Android's advertised Wi-Fi address can
    # differ when VPN/virtual interfaces are present. Try both before declaring
    # the embedded server unreachable.
    for phone_ip in candidates:
        url = f"http://{phone_ip}:{_LOCAL_SERVER_PORT}{path}"
        for attempt in range(2):
            try:
                req = UrlRequest(url, method=method, headers={"Cache-Control": "no-store"})
                with _direct_opener.open(req, timeout=3.0) as response:
                    raw = response.read().decode("utf-8", "replace")
                    raw_cookie = response.headers.get("Set-Cookie", "")
                    cookie = raw_cookie.split(";", 1)[0].strip()
                    return response.status, raw, cookie
            except HTTPError as exc:
                try:
                    raw = exc.read().decode("utf-8", "replace")
                except Exception:
                    raw = str(exc)
                raw_cookie = exc.headers.get("Set-Cookie", "") if exc.headers else ""
                cookie = raw_cookie.split(";", 1)[0].strip()
                return exc.code, raw, cookie
            except Exception as exc:
                last_error = exc
                if attempt == 0:
                    time.sleep(0.25)
                    continue
    return None, str(last_error or "phone embedded server unavailable"), None
def _verify_phone_pin(phone_ips, pin: str):
    pin = (pin or "").strip()
    candidates = phone_ips if isinstance(phone_ips, (list, tuple)) else [phone_ips]
    if not any(_safe_phone_ip(value) for value in candidates):
        return "invalid", None, None
    if not pin.isdigit() or len(pin) != 6:
        return "invalid", None, None
    query = urlencode({"pin": pin})
    status, raw, cookie = _phone_request(candidates, "POST", f"/api/pair?{query}")
    if status is None:
        return "unreachable", None, None
    try:
        import json
        payload = json.loads(raw or "{}")
    except Exception:
        payload = {}
    if status == 403:
        return "invalid", None, None
    if 200 <= status < 300:
        if payload.get("pending") and payload.get("request_id"):
            return "pending", None, str(payload["request_id"])
        return "approved", cookie, None
    return "unreachable", None, None

def _create_session(pin: str, phone_ip: str, device_id: str, request: Request):
    now = time.time()
    key = _attempt_key(request, device_id)
    if not _record_attempt(key, now):
        raise HTTPException(429, "Too many PIN attempts; try again later")

    state, phone_cookie, request_id = _verify_phone_pin(phone_ip, pin)
    if state != "approved":
        if state == "invalid":
            raise HTTPException(403, "Invalid phone PIN")
        if state == "pending":
            raise HTTPException(202, request_id or "Pairing request pending")
        raise HTTPException(503, "Phone embedded server could not be reached")

    _clear_attempts(key)
    with _lock:
        token = secrets.token_urlsafe(32)
        _sessions[token] = {
            "expires": now + SESSION_TTL_SECONDS,
            "device_id": device_id,
            "phone_cookie": phone_cookie or "",
            "phone_ip": phone_ip,
        }
    return token


def _set_session_cookie(response, token):
    response.set_cookie(
        key="photosync_session",
        value=token,
        max_age=SESSION_TTL_SECONDS,
        httponly=True,
        samesite="lax",
        secure=False,
        path="/",
    )


def _set_session_header(response, token):
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
        revoke_session(token)
    response = JSONResponse({"ok": True})
    response.delete_cookie("photosync_session", path="/")
    return response


def _live_app_request(request: Request):
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
    "/api/server-pin",
    "/api/server-pin/refresh",
    "/api/server-qr",
    "/api/pair",
    "/api/session",
    "/api/logout",
    "/web-client/session",
    "/web-client/pair",
    "/web-client/pair-status",
    "/web-client/disconnect",
    "/connections",
}
_PUBLIC_PREFIXES = ("/dashboard",)
_APP_TRUST_HEADER = "X-PhotoSync-Device-ID"
_SESSION_HEADER = "X-PhotoSync-Session"
_SESSION_QUERY = "session"
_PAIR_PIN_HEADER = "X-PhotoSync-Pair-PIN"
_PAIR_IP_HEADER = "X-PhotoSync-Device-IP"
_PAIR_DEVICE_HEADER = "X-PhotoSync-Device-ID"
_PAIR_CLIENT_HEADER = "X-PhotoSync-Web-Client-ID"


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

    @app.get("/api/server-pin")
    def server_pin_info():
        return {"pin_required": True, "pairing_pin": SERVER_PAIRING_PIN, "message": "Enter this PIN in the PhotoSync Android app when connecting to this PC server"}

    @app.post("/api/server-pin/refresh")
    async def refresh_server_pin_info():
        pin = refresh_server_pairing_pin()
        try:
            import main as server_main
            manager = getattr(server_main, "manager", None)
            if manager is not None:
                await manager.disconnect_all()
        except Exception:
            # PIN rotation remains successful even if there are no live sockets
            # or the optional connection manager is unavailable.
            pass
        return {"ok": True, "pairing_pin": pin, "message": "PC server pairing PIN refreshed; existing pairings and live connections were cleared"}

    @app.get("/web-client/pair-status")
    def web_client_pair_status(request: Request, request_id: str, web_client_id: str, device_id: str):
        with _lock:
            _cleanup()
            pending = _pending_web_pairs.get(request_id)
        if not pending or pending.get("device_id") != device_id or pending.get("web_client_id") != web_client_id:
            raise HTTPException(404, "Pairing request not found")
        status, raw, cookie = _phone_request(pending.get("phone_ips", [pending["phone_ip"]]), "GET", f"/api/pair/status?id={request_id}")
        if status is None:
            return JSONResponse({"paired": False, "pending": True}, status_code=202)
        try:
            import json
            payload = json.loads(raw or "{}")
        except Exception:
            payload = {}
        if status == 403:
            _pending_web_pairs.pop(request_id, None)
            return JSONResponse({"paired": False, "pending": False, "message": payload.get("message", "Pairing rejected")}, status_code=403)
        if status == 200 and payload.get("paired"):
            now = time.time()
            _clear_attempts(pending.get("attempt_key", ""))
            token = secrets.token_urlsafe(32)
            with _lock:
                _sessions[token] = {
                    "expires": now + SESSION_TTL_SECONDS,
                    "device_id": pending["device_id"],
                    "phone_cookie": cookie or "",
                    "phone_ip": pending["phone_ip"],
                }
                _attempts.clear()
            try:
                import main as server_main
                meta = server_main.get_web_meta(web_client_id)
                old_token = meta.get("server_session_token", "")
                if old_token:
                    revoke_session(old_token)
                meta["paired_device_id"] = pending["device_id"]
                meta["phone_session_cookie"] = cookie or ""
                meta["phone_ip"] = pending["phone_ip"]
                meta["server_session_token"] = token
                server_main.write_json(server_main.web_meta_path(web_client_id), meta)
            except Exception:
                revoke_session(token)
                raise HTTPException(500, "Could not save web pairing session")
            _pending_web_pairs.pop(request_id, None)
            response = JSONResponse({
                "paired": True,
                "session_token": token,
                "device_id": pending["device_id"],
                "expires_in_seconds": SESSION_TTL_SECONDS,
            })
            # Explicitly return both mechanisms. The browser client may be
            # configured to use either cookie or X-PhotoSync-Session.
            _set_session_cookie(response, token)
            _set_session_header(response, token)
            return response
        return JSONResponse({"paired": False, "pending": True}, status_code=202)

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
        if path == "/web-client/pair":
            pin = request.headers.get(_PAIR_PIN_HEADER, "").strip()
            phone_ip = request.headers.get(_PAIR_IP_HEADER, "").strip()
            device_id = request.headers.get(_PAIR_DEVICE_HEADER, "").strip()
            # Prefer the live WebSocket peer, but retain Android's
            # advertised Wi-Fi address as a fallback when VPN/virtual routing
            # makes the peer address unsuitable for HTTP back to the phone.
            phone_ips = [phone_ip]
            if device_id:
                try:
                    import main as server_main
                    manager = getattr(server_main, "manager", None)
                    if manager and device_id in manager.devices():
                        live_ip = manager.ip_for_device(device_id)
                        advertised_ip = manager.advertised_ip_for_device(device_id)
                        phone_ips = [live_ip, advertised_ip]
                except Exception:
                    pass
            if request.method != "POST":
                return JSONResponse({"detail": "Method not allowed"}, status_code=405)
            if not pin or not phone_ip or not device_id:
                return JSONResponse({"detail": "Phone PIN, device IP and device ID are required"}, status_code=400)
            attempt_key = _attempt_key(request, device_id)
            if not _record_attempt(attempt_key):
                return JSONResponse({"detail": "Too many PIN attempts; try again later"}, status_code=429)
            # Embedded Server and PC Server are intentionally separate.
            # Browser pairing must never start the Embedded Server implicitly.
            # The user must start it explicitly from the Android Server page.
            state, phone_cookie, request_id = await asyncio.to_thread(_verify_phone_pin, phone_ips, pin)
            if state == "pending" and request_id:
                web_client_id = request.headers.get(_PAIR_CLIENT_HEADER, "").strip()
                if not web_client_id:
                    return JSONResponse({"detail": "Web client ID is required"}, status_code=400)
                _pending_web_pairs[request_id] = {
                    "device_id": device_id,
                    "phone_ip": phone_ips[0] if phone_ips else phone_ip,
                    "phone_ips": phone_ips,
                    "web_client_id": web_client_id,
                    "attempt_key": attempt_key,
                    "attempt_key": _attempt_key(request, device_id),
                    "created_at": time.time(),
                }
                return JSONResponse({"paired": False, "pending": True, "request_id": request_id, "message": "Approve the pairing request on the phone"}, status_code=202)
            if state == "invalid":
                _clear_attempts(attempt_key)
                return JSONResponse({"detail": "Invalid phone PIN", "code": "PHONE_PIN_INVALID", "hint": "This field requires the selected phone's Embedded Server PIN, not the PC Server PIN."}, status_code=403)
            if state == "unreachable":
                _clear_attempts(attempt_key)
                return JSONResponse({"detail": "Phone embedded server could not be reached"}, status_code=503)
            if state != "approved":
                _clear_attempts(attempt_key)
                return JSONResponse({"detail": "Phone pairing failed"}, status_code=502)
            _clear_attempts(attempt_key)
            now = time.time()
            token = secrets.token_urlsafe(32)
            with _lock:
                _sessions[token] = {
                    "expires": now + SESSION_TTL_SECONDS,
                    "device_id": device_id,
                    "phone_cookie": phone_cookie or "",
                    "phone_ip": phone_ip,
                }
            request.state.photosync_phone_cookie = phone_cookie or ""
            request.state.photosync_session_token = token
            response = await call_next(request)
            if response.status_code < 400:
                _set_session_cookie(response, token)
                _set_session_header(response, token)
            else:
                revoke_session(token)
            return response

        if path in _PUBLIC_EXACT or any(path == prefix or path.startswith(prefix + "/") for prefix in _PUBLIC_PREFIXES):
            return await call_next(request)

        if request.headers.get(_APP_TRUST_HEADER) and _live_app_request(request):
            supplied_pin = request.headers.get("X-PhotoSync-Server-PIN", "")
            if valid_server_pairing_pin(supplied_pin):
                return await call_next(request)
            return JSONResponse({"detail": "PC server pairing PIN required"}, status_code=401)

        token = _request_session(request)
        if valid_session(token):
            return await call_next(request)
        return JSONResponse({"detail": "PIN pairing required"}, status_code=401)

    return app
