from pathlib import Path
from uuid import uuid4
import re, json, socket, threading, hashlib, secrets
from datetime import datetime, timezone
from fastapi import FastAPI, File, Form, UploadFile, WebSocket, WebSocketDisconnect, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles

BASE_DIR = Path(__file__).resolve().parent
DATA_DIR = BASE_DIR / "data"
DEVICES_DIR = DATA_DIR / "devices"
WEB_DIR = BASE_DIR.parent / "web"
ACCOUNTS_FILE = BASE_DIR / "accounts.json"
FEEDBACK_FILE = BASE_DIR / "feedback.jsonl"
DEVICES_DIR.mkdir(parents=True, exist_ok=True)

APP_PORT = 8000
DISCOVERY_PORT = 8001
DISCOVERY_TOKEN = "PHOTOSYNC_DISCOVER_V1"
app = FastAPI(title="PHOTOSYNC API", version="0.7.1")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_credentials=True, allow_methods=["*"], allow_headers=["*"])
app.mount("/dashboard", StaticFiles(directory=WEB_DIR, html=True), name="dashboard")

_accounts_lock = threading.Lock()

def _load_accounts():
    try:
        with ACCOUNTS_FILE.open("r", encoding="utf-8") as f:
            data=json.load(f); return data if isinstance(data,dict) else {}
    except (FileNotFoundError,json.JSONDecodeError): return {}

def _save_accounts(accounts):
    tmp=ACCOUNTS_FILE.with_suffix('.tmp')
    with tmp.open('w',encoding='utf-8') as f: json.dump(accounts,f,ensure_ascii=False,indent=2)
    tmp.replace(ACCOUNTS_FILE)

def _password_hash(password,salt=None):
    salt=salt or secrets.token_bytes(16); digest=hashlib.pbkdf2_hmac('sha256',password.encode(),salt,120_000); return salt.hex(),digest.hex()
def _verify_password(password,salt_hex,digest_hex):
    try:
        salt=bytes.fromhex(salt_hex); _,digest=_password_hash(password,salt); return secrets.compare_digest(digest,digest_hex)
    except ValueError: return False

def _clean_text(value,max_len): return re.sub(r'\s+',' ',(value or '').strip())[:max_len]
def safe_name(name):
    name=Path(name or 'file').name; name=re.sub(r'[^A-Za-z0-9._-]+','_',name).strip('._'); return name[:180] or 'file'
def safe_device_id(value): return re.sub(r'[^A-Za-z0-9_-]','',value or '')[:128]

def request_owner_id(request, supplied=''):
    supplied=safe_device_id(supplied or request.headers.get('X-PhotoSync-Device-ID',''))
    if supplied: return supplied
    host=request.client.host if request.client else 'unknown'
    return 'ip_'+hashlib.sha256(host.encode()).hexdigest()[:24]

def device_dirs(device_id):
    d=DEVICES_DIR/safe_device_id(device_id); uploads=d/'uploads'; downloads=d/'downloads'; uploads.mkdir(parents=True,exist_ok=True); downloads.mkdir(parents=True,exist_ok=True); return uploads,downloads

def file_info(path, source, device_id):
    return {'filename':path.name.split('__',3)[-1] if '__' in path.name else path.name,'stored_filename':path.name,'url':f'/files/{safe_device_id(device_id)}/{source}/{path.name}','size':path.stat().st_size,'type':'image' if path.suffix.lower() in {'.jpg','.jpeg','.png','.webp','.gif','.bmp'} else 'file','source':source,'device_id':safe_device_id(device_id)}

class ConnectionManager:
    def __init__(self): self.connections={}
    async def connect(self,ws,device_id): await ws.accept(); self.connections[ws]=device_id
    def disconnect(self,ws): self.connections.pop(ws,None)
    def devices(self): return sorted(set(self.connections.values()))
    async def send_to_device(self,device_id,message):
        dead=[]
        for ws,did in list(self.connections.items()):
            if did!=device_id: continue
            try: await ws.send_json(message)
            except Exception: dead.append(ws)
        for ws in dead: self.disconnect(ws)
    async def broadcast(self,message):
        dead=[]
        for ws in list(self.connections):
            try: await ws.send_json(message)
            except Exception: dead.append(ws)
        for ws in dead: self.disconnect(ws)
manager=ConnectionManager()

def list_dir(path,source,device_id):
    if not path.exists(): return []
    items=[p for p in path.iterdir() if p.is_file() and not p.name.startswith('.')]; items.sort(key=lambda p:p.stat().st_mtime,reverse=True)
    return [file_info(p,source,device_id) for p in items]

@app.get('/')
def root(): return {'dashboard':'/dashboard/','health':'/health','files':'/files','connections':'/connections','discovery_port':DISCOVERY_PORT}
@app.get('/health')
def health(): return {'status':'ok'}
@app.get('/connections')
def connections():
    devices=manager.devices(); return {'count':len(devices),'devices':[{'device_id':d} for d in devices]}

@app.get('/files')
def files(request:Request,source:str|None=None,device_id:str|None=None):
    owner=request_owner_id(request,device_id or '')
    uploads,downloads=device_dirs(owner)
    if source=='app': return list_dir(uploads,'app',owner)
    if source=='received': return list_dir(downloads,'received',owner)
    return list_dir(uploads,'app',owner)+list_dir(downloads,'received',owner)

@app.get('/files/{device_id}/{source}/{filename}')
def get_file(device_id:str,source:str,filename:str,request:Request):
    device_id=safe_device_id(device_id); filename=Path(filename).name
    requester=request_owner_id(request,'')
    if requester!=device_id: raise HTTPException(403,'File belongs to another device')
    if source not in {'app','received'}: raise HTTPException(404,'Not found')
    uploads,downloads=device_dirs(device_id); path=(uploads if source=='app' else downloads)/filename
    if not path.is_file(): raise HTTPException(404,'Not found')
    from fastapi.responses import FileResponse
    return FileResponse(path)

@app.websocket('/ws')
async def websocket_endpoint(websocket:WebSocket):
    supplied=safe_device_id(websocket.query_params.get('device_id','') or websocket.headers.get('X-PhotoSync-Device-ID',''))
    host=websocket.client.host if websocket.client else 'unknown'
    device_id=supplied or ('ip_'+hashlib.sha256(host.encode()).hexdigest()[:24])
    device_dirs(device_id)
    await manager.connect(websocket,device_id)
    await websocket.send_json({'type':'connection_info','device_id':device_id,'connections':len(manager.devices())})
    await manager.broadcast({'type':'connections_changed','count':len(manager.devices())})
    try:
        while True: await websocket.receive_text()
    except WebSocketDisconnect:
        manager.disconnect(websocket); await manager.broadcast({'type':'connections_changed','count':len(manager.devices())})

@app.post('/upload')
async def upload_file(request:Request,file:UploadFile=File(...),source:str=Form('unknown'),device_id:str=Form(''),target_device_id:str=Form('')):
    source=source if source in {'web','app','unknown'} else 'unknown'
    if source=='app':
        owner=request_owner_id(request,device_id); folder,_=device_dirs(owner); event_device=owner
    elif source=='web':
        target=safe_device_id(target_device_id); devices=manager.devices(); targets=[target] if target else devices
        if not targets: raise HTTPException(400,'No connected phone')
        owner=targets[0]; event_device=owner; folders=[(did,device_dirs(did)[1]) for did in targets]
    else:
        owner=request_owner_id(request,device_id); folder,_=device_dirs(owner); event_device=owner
    original=safe_name(file.filename); transfer_id=uuid4().hex; total=int(file.size or 0); received=0; last=-1
    if source=='web':
        data=await file.read()
        results=[]
        for did,folder in folders:
            filename=f'{uuid4().hex}__web__{did}__{original}'; destination=folder/filename; destination.write_bytes(data)
            info=file_info(destination,'received',did); info['content_type']=file.content_type or 'application/octet-stream'; info['transfer_id']=transfer_id
            results.append(info); await manager.send_to_device(did,{'type':'file_uploaded',**info})
        return results[0]
    filename=f'{uuid4().hex}__{source}__{owner}__{original}'; destination=folder/filename
    with destination.open('wb') as output:
        while chunk:=await file.read(1024*1024):
            output.write(chunk); received+=len(chunk)
            if total>0:
                percent=int(received*100/total)
                if percent!=last:
                    last=percent
                    await manager.send_to_device(event_device,{'type':'upload_progress','transfer_id':transfer_id,'source':source,'device_id':event_device,'filename':original,'received':received,'total':total,'percent':min(100,percent)})
    info=file_info(destination,'app' if source=='app' else 'received',owner); info['content_type']=file.content_type or 'application/octet-stream'; info['transfer_id']=transfer_id
    await manager.send_to_device(event_device,{'type':'file_uploaded',**info})
    return info

@app.post('/account/signup')
def account_signup(name:str=Form(...),mobile:str=Form(...),username:str=Form(...),email:str=Form(...),password:str=Form(...)):
    name=_clean_text(name,80); mobile=re.sub(r'[^0-9+ -]','',mobile or '').strip()[:20]; username=re.sub(r'[^A-Za-z0-9_.-]','',username or '').lower()[:32]; email=_clean_text(email,160).lower()
    if not name or not mobile or not username or not email or len(password)<8: raise HTTPException(400,'Name, mobile, username, email and an 8+ character password are required')
    if '@' not in email: raise HTTPException(400,'Enter a valid email')
    with _accounts_lock:
        accounts=_load_accounts()
        if username in accounts or any(a.get('email')==email for a in accounts.values()): raise HTTPException(409,'Username or email already exists')
        salt,digest=_password_hash(password); accounts[username]={'name':name,'mobile':mobile,'username':username,'email':email,'password_salt':salt,'password_hash':digest,'created_at':datetime.now(timezone.utc).isoformat()}; _save_accounts(accounts)
    return {'ok':True,'message':'Account created','account':{'name':name,'mobile':mobile,'username':username,'email':email}}

@app.post('/account/login')
def account_login(email:str=Form(...),password:str=Form(...)):
    email=_clean_text(email,160).lower()
    with _accounts_lock:
        accounts=_load_accounts(); account=next((a for a in accounts.values() if a.get('email')==email),None)
    if not account or not _verify_password(password,account.get('password_salt',''),account.get('password_hash','')): raise HTTPException(401,'Invalid email or password')
    return {'ok':True,'message':'Login successful','account':{k:account.get(k,'') for k in ('name','mobile','username','email')}}

@app.post('/feedback')
def feedback(message:str=Form(...),email:str=Form(''),username:str=Form('')):
    message=_clean_text(message,2000); email=_clean_text(email,160).lower(); username=_clean_text(username,32)
    if not message: raise HTTPException(400,'Feedback message is required')
    entry={'message':message,'email':email,'username':username,'created_at':datetime.now(timezone.utc).isoformat()}
    with FEEDBACK_FILE.open('a',encoding='utf-8') as f: f.write(json.dumps(entry,ensure_ascii=False)+'\n')
    return {'ok':True,'message':'Feedback received'}

def discovery_loop():
    sock=socket.socket(socket.AF_INET,socket.SOCK_DGRAM)
    try:
        sock.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1); sock.bind(('0.0.0.0',DISCOVERY_PORT))
        while True:
            data,addr=sock.recvfrom(1024)
            if data.decode('utf-8',errors='ignore').strip()!=DISCOVERY_TOKEN: continue
            payload=json.dumps({'service':'PHOTOSYNC','version':1,'port':APP_PORT}).encode(); sock.sendto(payload,addr)
    except OSError: pass
    finally: sock.close()
threading.Thread(target=discovery_loop,name='photosync-discovery',daemon=True).start()