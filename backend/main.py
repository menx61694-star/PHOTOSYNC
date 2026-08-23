from pathlib import Path
from uuid import uuid4
import re
import json
import socket
import threading
import hashlib
import secrets
from datetime import datetime, timezone

from fastapi import FastAPI, File, Form, UploadFile, WebSocket, WebSocketDisconnect, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles

BASE_DIR = Path(__file__).resolve().parent
UPLOAD_DIR = BASE_DIR / "uploads"
WEB_DIR = BASE_DIR.parent / "web"
ACCOUNTS_FILE = BASE_DIR / "accounts.json"
FEEDBACK_FILE = BASE_DIR / "feedback.jsonl"
UPLOAD_DIR.mkdir(parents=True, exist_ok=True)

APP_PORT = 8000
DISCOVERY_PORT = 8001
DISCOVERY_TOKEN = "PHOTOSYNC_DISCOVER_V1"

app = FastAPI(title="PHOTOSYNC API", version="0.6.0")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_credentials=True, allow_methods=["*"], allow_headers=["*"])
app.mount("/uploads", StaticFiles(directory=UPLOAD_DIR), name="uploads")
app.mount("/dashboard", StaticFiles(directory=WEB_DIR, html=True), name="dashboard")

_accounts_lock = threading.Lock()

def _load_accounts() -> dict:
    try:
        with ACCOUNTS_FILE.open("r", encoding="utf-8") as f:
            data = json.load(f)
            return data if isinstance(data, dict) else {}
    except (FileNotFoundError, json.JSONDecodeError):
        return {}

def _save_accounts(accounts: dict) -> None:
    tmp = ACCOUNTS_FILE.with_suffix(".tmp")
    with tmp.open("w", encoding="utf-8") as f:
        json.dump(accounts, f, ensure_ascii=False, indent=2)
    tmp.replace(ACCOUNTS_FILE)

def _password_hash(password: str, salt: bytes | None = None) -> tuple[str, str]:
    salt = salt or secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, 120_000)
    return salt.hex(), digest.hex()

def _verify_password(password: str, salt_hex: str, digest_hex: str) -> bool:
    try:
        salt = bytes.fromhex(salt_hex)
        _, digest = _password_hash(password, salt)
        return secrets.compare_digest(digest, digest_hex)
    except ValueError:
        return False

def _clean_text(value: str, max_len: int) -> str:
    return re.sub(r"\s+", " ", (value or "").strip())[:max_len]

class ConnectionManager:
    def __init__(self): self.connections: set[WebSocket] = set()
    async def connect(self, websocket: WebSocket):
        await websocket.accept(); self.connections.add(websocket)
    def disconnect(self, websocket: WebSocket): self.connections.discard(websocket)
    async def broadcast(self, message: dict):
        dead=[]
        for connection in self.connections:
            try: await connection.send_json(message)
            except Exception: dead.append(connection)
        for connection in dead: self.disconnect(connection)

manager = ConnectionManager()

def safe_name(name: str) -> str:
    name = Path(name or "file").name
    name = re.sub(r"[^A-Za-z0-9._-]+", "_", name).strip("._")
    return name[:180] or "file"

def safe_device_id(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9_-]", "", value or "")[:128]

def request_owner_id(request: Request, supplied: str = "") -> str:
    supplied = safe_device_id(supplied)
    if supplied:
        return supplied
    host = request.client.host if request.client else "unknown"
    digest = hashlib.sha256(host.encode("utf-8")).hexdigest()[:24]
    return f"ip_{digest}"

def file_info(path: Path) -> dict:
    stored = path.name
    parts = stored.split("__", 3)
    if len(parts) == 4:
        source, device_id, original = parts[1], parts[2], parts[3]
    elif len(parts) == 3:
        source, device_id, original = parts[1], "", parts[2]
    elif len(parts) == 2:
        source, device_id, original = "unknown", "", parts[1]
    else:
        source, device_id, original = "unknown", "", stored
    suffix = path.suffix.lower()
    image = suffix in {".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp"}
    return {"filename": original, "stored_filename": stored, "url": f"/uploads/{stored}", "size": path.stat().st_size, "type": "image" if image else "file", "source": source, "device_id": device_id}

@app.get("/")
def root(): return {"dashboard":"/dashboard/", "health":"/health", "files":"/files", "discovery_port":DISCOVERY_PORT}
@app.get("/health")
def health(): return {"status":"ok"}
@app.get("/files")
def files(request: Request, source: str | None = None, device_id: str | None = None):
    items = [p for p in UPLOAD_DIR.iterdir() if p.is_file() and not p.name.startswith(".")]
    items.sort(key=lambda p: p.stat().st_mtime, reverse=True)
    result = [file_info(p) for p in items]
    if source == "app":
        owner = request_owner_id(request, device_id or "")
        return [x for x in result if x["source"] == "app" and x["device_id"] == owner]
    if source == "received": return [x for x in result if x["source"] in {"web", "unknown"}]
    return [x for x in result if not source or x["source"] == source]

@app.get("/photos")
def photos(): return [x for x in files.__wrapped__() if x["type"] == "image"]

@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket):
    await manager.connect(websocket)
    try:
        while True: await websocket.receive_text()
    except WebSocketDisconnect: manager.disconnect(websocket)

@app.post("/upload")
async def upload_file(request: Request, file: UploadFile = File(...), source: str = Form("unknown"), device_id: str = Form("")):
    source = source if source in {"web", "app", "unknown"} else "unknown"
    device_id = request_owner_id(request, device_id) if source == "app" else ""
    original = safe_name(file.filename)
    owner = device_id if source == "app" else "web"
    filename = f"{uuid4().hex}__{source}__{owner}__{original}"
    transfer_id = uuid4().hex
    destination = UPLOAD_DIR / filename
    total = int(file.size or 0)
    received = 0
    last_reported = -1
    with destination.open("wb") as output:
        while chunk := await file.read(1024 * 1024):
            output.write(chunk)
            received += len(chunk)
            if total > 0:
                percent = int((received * 100) / total)
                if percent != last_reported:
                    last_reported = percent
                    await manager.broadcast({"type":"upload_progress", "transfer_id":transfer_id, "source":source, "device_id":device_id, "filename":original, "received":received, "total":total, "percent":min(100, percent)})
    info = file_info(destination)
    info["content_type"] = file.content_type or "application/octet-stream"
    info["transfer_id"] = transfer_id
    await manager.broadcast({"type":"file_uploaded", **info})
    return info

@app.post("/account/signup")
def account_signup(name: str = Form(...), mobile: str = Form(...), username: str = Form(...), email: str = Form(...), password: str = Form(...)):
    name = _clean_text(name, 80)
    mobile = re.sub(r"[^0-9+ -]", "", mobile or "").strip()[:20]
    username = re.sub(r"[^A-Za-z0-9_.-]", "", username or "").lower()[:32]
    email = _clean_text(email, 160).lower()
    if not name or not mobile or not username or not email or len(password) < 8:
        raise HTTPException(status_code=400, detail="Name, mobile, username, email and an 8+ character password are required")
    if "@" not in email:
        raise HTTPException(status_code=400, detail="Enter a valid email")
    with _accounts_lock:
        accounts = _load_accounts()
        if username in accounts or any(a.get("email") == email for a in accounts.values()):
            raise HTTPException(status_code=409, detail="Username or email already exists")
        salt, digest = _password_hash(password)
        accounts[username] = {"name": name, "mobile": mobile, "username": username, "email": email, "password_salt": salt, "password_hash": digest, "created_at": datetime.now(timezone.utc).isoformat()}
        _save_accounts(accounts)
    return {"ok": True, "message": "Account created", "account": {"name": name, "mobile": mobile, "username": username, "email": email}}

@app.post("/account/login")
def account_login(email: str = Form(...), password: str = Form(...)):
    email = _clean_text(email, 160).lower()
    with _accounts_lock:
        accounts = _load_accounts()
        account = next((a for a in accounts.values() if a.get("email") == email), None)
    if not account or not _verify_password(password, account.get("password_salt", ""), account.get("password_hash", "")):
        raise HTTPException(status_code=401, detail="Invalid email or password")
    return {"ok": True, "message": "Login successful", "account": {k: account.get(k, "") for k in ("name", "mobile", "username", "email")}}

@app.post("/feedback")
def feedback(message: str = Form(...), email: str = Form(""), username: str = Form("")):
    message = _clean_text(message, 2000)
    email = _clean_text(email, 160).lower()
    username = _clean_text(username, 32)
    if not message:
        raise HTTPException(status_code=400, detail="Feedback message is required")
    entry = {"message": message, "email": email, "username": username, "created_at": datetime.now(timezone.utc).isoformat()}
    with FEEDBACK_FILE.open("a", encoding="utf-8") as f:
        f.write(json.dumps(entry, ensure_ascii=False) + "\n")
    return {"ok": True, "message": "Feedback received"}

def discovery_loop():
    """Small UDP LAN discovery responder; no cloud service or IP scanning required."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("0.0.0.0", DISCOVERY_PORT))
        while True:
            data, addr = sock.recvfrom(1024)
            if data.decode("utf-8", errors="ignore").strip() != DISCOVERY_TOKEN:
                continue
            payload = json.dumps({"service":"PHOTOSYNC", "version":1, "port":APP_PORT}).encode("utf-8")
            sock.sendto(payload, addr)
    except OSError:
        pass
    finally:
        sock.close()

threading.Thread(target=discovery_loop, name="photosync-discovery", daemon=True).start()
