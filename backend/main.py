from pathlib import Path
from uuid import uuid4
import re

from fastapi import FastAPI, File, Form, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles

BASE_DIR = Path(__file__).resolve().parent
UPLOAD_DIR = BASE_DIR / "uploads"
WEB_DIR = BASE_DIR.parent / "web"
UPLOAD_DIR.mkdir(parents=True, exist_ok=True)

app = FastAPI(title="PHOTOSYNC API", version="0.4.0")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_credentials=True, allow_methods=["*"], allow_headers=["*"])
app.mount("/uploads", StaticFiles(directory=UPLOAD_DIR), name="uploads")
app.mount("/dashboard", StaticFiles(directory=WEB_DIR, html=True), name="dashboard")

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

def file_info(path: Path) -> dict:
    stored = path.name
    parts = stored.split("__", 2)
    if len(parts) == 3:
        source, original = parts[1], parts[2]
    elif len(parts) == 2:
        source, original = "unknown", parts[1]
    else:
        source, original = "unknown", stored
    suffix = path.suffix.lower()
    image = suffix in {".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp"}
    return {"filename": original, "stored_filename": stored, "url": f"/uploads/{stored}", "size": path.stat().st_size, "type": "image" if image else "file", "source": source}

@app.get("/")
def root(): return {"dashboard":"/dashboard/", "health":"/health", "files":"/files"}
@app.get("/health")
def health(): return {"status":"ok"}
@app.get("/files")
def files(source: str | None = None):
    items = [p for p in UPLOAD_DIR.iterdir() if p.is_file() and not p.name.startswith(".")]
    items.sort(key=lambda p: p.stat().st_mtime, reverse=True)
    result = [file_info(p) for p in items]
    return [x for x in result if not source or x["source"] == source]
@app.get("/photos")
def photos(): return [x for x in files() if x["type"] == "image"]

@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket):
    await manager.connect(websocket)
    try:
        while True: await websocket.receive_text()
    except WebSocketDisconnect: manager.disconnect(websocket)

@app.post("/upload")
async def upload_file(file: UploadFile = File(...), source: str = Form("unknown")):
    source = source if source in {"web", "app", "unknown"} else "unknown"
    original = safe_name(file.filename)
    filename = f"{uuid4().hex}__{source}__{original}"
    destination = UPLOAD_DIR / filename
    with destination.open("wb") as output:
        while chunk := await file.read(1024 * 1024): output.write(chunk)
    info = file_info(destination)
    info["content_type"] = file.content_type or "application/octet-stream"
    await manager.broadcast({"type":"file_uploaded", **info})
    return info
