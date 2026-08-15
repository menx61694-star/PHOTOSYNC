from pathlib import Path
from uuid import uuid4

from fastapi import FastAPI, File, HTTPException, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles

BASE_DIR = Path(__file__).resolve().parent
UPLOAD_DIR = BASE_DIR / "uploads"
WEB_DIR = BASE_DIR.parent / "web"
UPLOAD_DIR.mkdir(parents=True, exist_ok=True)

ALLOWED_TYPES = {"image/jpeg", "image/png", "image/webp"}

app = FastAPI(title="PHOTOSYNC API", version="0.2.2")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_credentials=True, allow_methods=["*"], allow_headers=["*"])
app.mount("/uploads", StaticFiles(directory=UPLOAD_DIR), name="uploads")
app.mount("/dashboard", StaticFiles(directory=WEB_DIR, html=True), name="dashboard")

class ConnectionManager:
    def __init__(self): self.connections: set[WebSocket] = set()
    async def connect(self, websocket: WebSocket):
        await websocket.accept(); self.connections.add(websocket)
    def disconnect(self, websocket: WebSocket): self.connections.discard(websocket)
    async def broadcast(self, message: dict[str, str]):
        dead=[]
        for connection in self.connections:
            try: await connection.send_json(message)
            except Exception: dead.append(connection)
        for connection in dead: self.disconnect(connection)

manager = ConnectionManager()

@app.get("/")
def root(): return {"dashboard":"/dashboard/", "health":"/health", "photos":"/photos"}

@app.get("/health")
def health(): return {"status":"ok"}

@app.get("/photos")
def photos():
    files = sorted((p for p in UPLOAD_DIR.iterdir() if p.is_file() and p.suffix.lower() in {".jpg", ".jpeg", ".png", ".webp"}), key=lambda p: p.stat().st_mtime, reverse=True)
    return [{"filename": p.name, "url": f"/uploads/{p.name}"} for p in files]

@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket):
    await manager.connect(websocket)
    try:
        while True: await websocket.receive_text()
    except WebSocketDisconnect: manager.disconnect(websocket)

@app.post("/upload")
async def upload_photo(file: UploadFile = File(...)):
    if file.content_type not in ALLOWED_TYPES: raise HTTPException(status_code=415, detail="Unsupported image type")
    extension={"image/jpeg":".jpg", "image/png":".png", "image/webp":".webp"}[file.content_type]
    filename=f"{uuid4().hex}{extension}"
    destination=UPLOAD_DIR/filename
    with destination.open("wb") as output:
        while chunk := await file.read(1024*1024): output.write(chunk)
    image_url=f"/uploads/{filename}"
    await manager.broadcast({"type":"photo_uploaded","filename":filename,"url":image_url})
    return {"filename":filename,"url":image_url}
