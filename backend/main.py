from pathlib import Path
from uuid import uuid4
import asyncio
import re, json, socket, threading, hashlib, secrets
from datetime import datetime, timezone
from urllib.parse import quote
from urllib.request import ProxyHandler, Request as UrlRequest, build_opener
from fastapi import FastAPI, File, Form, UploadFile, WebSocket, WebSocketDisconnect, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse
from pin_auth import install as install_pin_auth, session_phone_cookie

BASE_DIR = Path(__file__).resolve().parent
DATA_DIR = BASE_DIR / 'data'
DEVICES_DIR = DATA_DIR / 'devices'
WEB_CLIENTS_DIR = DATA_DIR / 'web_clients'
WEB_DIR = BASE_DIR.parent / 'web'
ACCOUNTS_FILE = BASE_DIR / 'accounts.json'
FEEDBACK_FILE = BASE_DIR / 'feedback.jsonl'
DEVICES_DIR.mkdir(parents=True, exist_ok=True)
WEB_CLIENTS_DIR.mkdir(parents=True, exist_ok=True)
APP_PORT=8000
DISCOVERY_PORT=8001
DISCOVERY_TOKEN='PHOTOSYNC_DISCOVER_V1'
app=FastAPI(title='PHOTOSYNC API',version='0.9.1')
app.add_middleware(CORSMiddleware,allow_origins=['*'],allow_credentials=True,allow_methods=['*'],allow_headers=['*'])
app.mount('/dashboard',StaticFiles(directory=WEB_DIR,html=True),name='dashboard')
install_pin_auth(app)
_accounts_lock=threading.Lock()
_direct_opener=build_opener(ProxyHandler({}))

def _load_accounts():
    try:
        with ACCOUNTS_FILE.open('r',encoding='utf-8') as f:
            data=json.load(f); return data if isinstance(data,dict) else {}
    except (FileNotFoundError,json.JSONDecodeError): return {}
def _save_accounts(accounts):
    tmp=ACCOUNTS_FILE.with_suffix('.tmp'); tmp.write_text(json.dumps(accounts,ensure_ascii=False,indent=2),encoding='utf-8'); tmp.replace(ACCOUNTS_FILE)
def _password_hash(password,salt=None):
    salt=salt or secrets.token_bytes(16); digest=hashlib.pbkdf2_hmac('sha256',password.encode(),salt,120000); return salt.hex(),digest.hex()
def _verify_password(password,salt_hex,digest_hex):
    try:
        salt=bytes.fromhex(salt_hex); _,digest=_password_hash(password,salt); return secrets.compare_digest(digest,digest_hex)
    except ValueError:return False
def _clean_text(value,max_len):return re.sub(r'\s+',' ',(value or '').strip())[:max_len]
def safe_name(name):
    name=Path(name or 'file').name; name=re.sub(r'[^A-Za-z0-9._-]+','_',name).strip('._'); return name[:180] or 'file'
def safe_device_id(value):return re.sub(r'[^A-Za-z0-9_-]','',value or '')[:128]
def ip_owner_id(host):return 'ip_'+hashlib.sha256((host or 'unknown').encode()).hexdigest()[:24]
def request_owner_id(request,supplied=''):
    supplied=safe_device_id(supplied or request.headers.get('X-PhotoSync-Device-ID',''))
    if supplied:return supplied
    host=request.client.host if request.client else 'unknown'; return ip_owner_id(host)
def device_dirs(device_id):
    d=DEVICES_DIR/safe_device_id(device_id); uploads=d/'uploads'; downloads=d/'downloads'; uploads.mkdir(parents=True,exist_ok=True); downloads.mkdir(parents=True,exist_ok=True); return uploads,downloads
def web_client_dir(client_id):
    client_id=safe_device_id(client_id)
    if not client_id:raise HTTPException(400,'web_client_id required')
    d=WEB_CLIENTS_DIR/client_id; d.mkdir(parents=True,exist_ok=True); return d
def web_meta_path(client_id):return web_client_dir(client_id)/'client.json'
def web_history_path(client_id,kind):return web_client_dir(client_id)/(f'{kind}.json')
def read_json(path,default):
    try:
        data=json.loads(path.read_text(encoding='utf-8')) if path.exists() else default; return data
    except (OSError,json.JSONDecodeError):return default
def write_json(path,data):
    tmp=path.with_suffix(path.suffix+'.tmp'); tmp.write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8'); tmp.replace(path)
def get_web_meta(client_id):
    path=web_meta_path(client_id); meta=read_json(path,{})
    if not isinstance(meta,dict):meta={}
    if 'created_at' not in meta:
        meta={'created_at':datetime.now(timezone.utc).isoformat(),'paired_device_id':None}; write_json(path,meta)
    return meta
def add_web_history(client_id,kind,entry):
    history=read_json(web_history_path(client_id,kind),[])
    if not isinstance(history,list):history=[]
    tid=entry.get('transfer_id'); history=[x for x in history if not tid or x.get('transfer_id')!=tid]; history=[entry]+history[:499]; write_json(web_history_path(client_id,kind),history)
def file_info(path,source,device_id,web_client_id=None):
    parts=path.name.split('__'); info={'filename':parts[-1] if len(parts)>=2 else path.name,'stored_filename':path.name,'url':f'/files/{safe_device_id(device_id)}/{source}/{path.name}','size':path.stat().st_size,'type':'image' if path.suffix.lower() in {'.jpg','.jpeg','.png','.webp','.gif','.bmp'} else 'file','source':source,'device_id':safe_device_id(device_id)}
    if source=='web' and parts and re.fullmatch(r'[0-9a-f]{32}',parts[0] or ''):info['transfer_id']=parts[0]
    if web_client_id:info['url']=f'/web-client/file/{safe_device_id(web_client_id)}/{safe_device_id(device_id)}/{source}/{path.name}'
    return info
class ConnectionManager:
    def __init__(self):self.connections={};self.connection_ips={}
    async def connect(self,ws,device_id):await ws.accept();self.connections[ws]=device_id;self.connection_ips[ws]=ws.client.host if ws.client else 'unknown'
    def disconnect(self,ws):self.connections.pop(ws,None);self.connection_ips.pop(ws,None)
    def devices(self):return sorted(set(self.connections.values()))
    def ip_for_device(self,device_id):
        for ws,did in list(self.connections.items()):
            if did==device_id:return self.connection_ips.get(ws,'unknown')
        return 'unknown'
    async def send_to_device(self,device_id,message):
        dead=[]
        for ws,did in list(self.connections.items()):
            if did!=device_id:continue
            try:await ws.send_json(message)
            except Exception:dead.append(ws)
        for ws in dead:self.disconnect(ws)
    async def broadcast(self,message):
        dead=[]
        for ws in list(self.connections):
            try:await ws.send_json(message)
            except Exception:dead.append(ws)
        for ws in dead:self.disconnect(ws)
manager=ConnectionManager()
def list_dir(path,source,device_id):
    if not path.exists():return []
    items=[p for p in path.iterdir() if p.is_file() and not p.name.startswith('.')];items.sort(key=lambda p:p.stat().st_mtime,reverse=True);return [file_info(p,source,device_id) for p in items]
def append_received_to_paired_web_clients(device_id,info):
    for d in WEB_CLIENTS_DIR.iterdir() if WEB_CLIENTS_DIR.exists() else []:
        if not d.is_dir():continue
        cid=safe_device_id(d.name);meta=get_web_meta(cid)
        if safe_device_id(meta.get('paired_device_id',''))!=safe_device_id(device_id):continue
        entry=dict(info);entry['source']='phone';entry['received_from_device']=safe_device_id(device_id);entry['url']=f'/web-client/file/{cid}/{safe_device_id(device_id)}/app/{info["stored_filename"]}';add_web_history(cid,'received',entry)

@app.get('/')
def root():return {'dashboard':'/dashboard/','standalone':'/dashboard/standalone.html','health':'/health','files':'/files','connections':'/connections','devices':'/devices','discovery_port':DISCOVERY_PORT}
@app.get('/health')
def health():return {'status':'ok'}
@app.get('/connections')
def connections():
    devices=manager.devices();return {'count':len(devices),'devices':[{'device_id':d,'ip':manager.ip_for_device(d)} for d in devices]}
@app.get('/devices')
def devices():
    stored=[]
    for d in DEVICES_DIR.iterdir() if DEVICES_DIR.exists() else []:
        if d.is_dir() and safe_device_id(d.name)==d.name:stored.append(d.name)
    return {'devices':sorted(set(stored)|set(manager.devices()))}
@app.post('/web-client/session')
def web_client_session():
    client_id=uuid4().hex;d=web_client_dir(client_id);write_json(d/'client.json',{'created_at':datetime.now(timezone.utc).isoformat(),'paired_device_id':None});write_json(d/'sent.json',[]);write_json(d/'received.json',[]);return {'web_client_id':client_id}
@app.get('/web-client/state')
def web_client_state(web_client_id:str):
    meta=get_web_meta(web_client_id);paired=safe_device_id(meta.get('paired_device_id',''));return {'web_client_id':safe_device_id(web_client_id),'paired_device_id':paired or None,'sent':read_json(web_history_path(web_client_id,'sent'),[]),'received':read_json(web_history_path(web_client_id,'received'),[])}
@app.post('/web-client/pair')
def web_client_pair(request:Request,web_client_id:str=Form(...),device_id:str=Form(...)):
    cid=safe_device_id(web_client_id);did=safe_device_id(device_id)
    if not cid or not did:raise HTTPException(400,'web_client_id and device_id required')
    if did not in manager.devices():raise HTTPException(409,'Selected phone is not connected')
    phone_cookie=getattr(request.state,'photosync_phone_cookie','') or ''
    if not phone_cookie:raise HTTPException(502,'Phone pairing succeeded but its session cookie was not returned')
    meta=get_web_meta(cid);meta['paired_device_id']=did;meta['phone_session_cookie']=phone_cookie;meta['phone_ip']=manager.ip_for_device(did);write_json(web_meta_path(cid),meta)
    return {'ok':True,'web_client_id':cid,'paired_device_id':did}
@app.post('/web-client/unpair')
def web_client_unpair(web_client_id:str=Form(...)):
    cid=safe_device_id(web_client_id);meta=get_web_meta(cid);meta['paired_device_id']=None;meta.pop('phone_session_cookie',None);meta.pop('phone_ip',None);write_json(web_meta_path(cid),meta);return {'ok':True}
@app.get('/web-client/files')
def web_client_files(web_client_id:str,kind:str='sent'):
    if kind not in {'sent','received'}:raise HTTPException(400,'invalid kind')
    get_web_meta(web_client_id);return read_json(web_history_path(web_client_id,kind),[])
@app.get('/web-client/file/{web_client_id}/{device_id}/{source}/{filename}')
def web_client_file(web_client_id:str,device_id:str,source:str,filename:str):
    cid=safe_device_id(web_client_id);did=safe_device_id(device_id);filename=Path(filename).name
    if source not in {'app','received','web'}:raise HTTPException(404,'Not found')
    get_web_meta(cid);allowed=False
    for kind in ('sent','received'):
        for item in read_json(web_history_path(cid,kind),[]):
            if safe_device_id(item.get('device_id',''))==did and item.get('stored_filename')==filename:allowed=True;break
        if allowed:break
    if not allowed:raise HTTPException(403,'File is not part of this web client')
    uploads,downloads=device_dirs(did);path=(uploads if source in {'app','received'} else downloads)/filename
    if not path.is_file():raise HTTPException(404,'Not found')
    return FileResponse(path)
@app.get('/files')
def files(request:Request,source:str|None=None,device_id:str|None=None,all:bool=False):
    if all:return []
    owner=request_owner_id(request,device_id or '');uploads,downloads=device_dirs(owner)
    if source=='app':return list_dir(uploads,'app',owner)
    if source=='received':return list_dir(downloads,'received',owner)
    if source=='web':return [file_info(p,'web',owner) for p in downloads.iterdir() if p.is_file() and '__web__' in p.name]
    return list_dir(uploads,'app',owner)+list_dir(downloads,'received',owner)
@app.get('/files/{device_id}/{source}/{filename}')
def get_file(device_id:str,source:str,filename:str):
    device_id=safe_device_id(device_id);filename=Path(filename).name
    if source not in {'app','received','web'}:raise HTTPException(404,'Not found')
    uploads,downloads=device_dirs(device_id);path=(uploads if source=='app' else downloads)/filename
    if not path.is_file():raise HTTPException(404,'Not found')
    return FileResponse(path)
@app.websocket('/ws')
async def websocket_endpoint(websocket:WebSocket):
    supplied=safe_device_id(websocket.query_params.get('device_id','') or websocket.headers.get('X-PhotoSync-Device-ID',''));host=websocket.client.host if websocket.client else 'unknown';device_id=supplied or ip_owner_id(host);device_dirs(device_id);await manager.connect(websocket,device_id);await websocket.send_json({'type':'connection_info','device_id':device_id,'ip':host,'connections':len(manager.devices())});await manager.broadcast({'type':'connections_changed','count':len(manager.devices())})
    try:
        while True:await websocket.receive_text()
    except WebSocketDisconnect:
        manager.disconnect(websocket);await manager.broadcast({'type':'connections_changed','count':len(manager.devices())})

def _forward_to_phone(phone_ip,phone_cookie,filename,data,content_type):
    if not phone_ip or not phone_cookie:raise RuntimeError('Phone is not paired for file transfer')
    url=f'http://{phone_ip}:18000/upload?source=web&filename={quote(filename,safe="")}'
    req=UrlRequest(url,data=data,method='POST',headers={'Cookie':phone_cookie,'Content-Type':content_type or 'application/octet-stream','Content-Length':str(len(data)),'Cache-Control':'no-store'})
    with _direct_opener.open(req,timeout=max(15,min(300,15+len(data)//(1024*1024)))) as response:
        body=response.read()
        if not 200 <= response.status < 300:raise RuntimeError(f'Phone HTTP {response.status}')
        try:return json.loads(body.decode('utf-8'))
        except Exception:raise RuntimeError('Phone returned invalid upload response')

@app.post('/upload')
async def upload_file(request:Request,file:UploadFile=File(...),source:str=Form('unknown'),device_id:str=Form(''),target_device_id:str=Form(''),web_client_id:str=Form('')):
    source=source if source in {'web','app','unknown'} else 'unknown'
    if source=='web':
        cid=safe_device_id(web_client_id);meta=get_web_meta(cid);targets=[safe_device_id(target_device_id)] if target_device_id else ([safe_device_id(meta.get('paired_device_id',''))] if meta.get('paired_device_id') else manager.devices());targets=[d for d in targets if d]
        if not targets:raise HTTPException(400,'No connected phone')
        data=await file.read();original=safe_name(file.filename);transfer_id=uuid4().hex;results=[]
        for did in targets:
            if did not in manager.devices():raise HTTPException(409,f'Phone {did} is not connected')
            phone_ip=manager.ip_for_device(did)
            cookie=meta.get('phone_session_cookie','') if did==safe_device_id(meta.get('paired_device_id','')) else ''
            if not cookie:raise HTTPException(403,'Phone must be paired again before sending files')
            await manager.send_to_device(did,{'type':'upload_progress','transfer_id':transfer_id,'source':'web','device_id':did,'filename':original,'received':0,'total':len(data),'percent':0})
            try:
                phone_info=await asyncio.to_thread(_forward_to_phone,phone_ip,cookie,original,data,file.content_type or 'application/octet-stream')
            except Exception as exc:
                raise HTTPException(502,f'Phone transfer failed: {exc}')
            phone_info['device_id']=did;phone_info['source']='received';phone_info['transfer_id']=transfer_id;phone_info['content_type']=file.content_type or 'application/octet-stream';phone_info['url']=f'http://{phone_ip}:18000{phone_info.get("url","")}'
            await manager.send_to_device(did,{'type':'upload_progress','transfer_id':transfer_id,'source':'web','device_id':did,'filename':original,'received':len(data),'total':len(data),'percent':100})
            await manager.send_to_device(did,{'type':'file_uploaded',**phone_info})
            results.append(phone_info)
        entry=dict(results[0]);entry['targets']=[r['device_id'] for r in results];entry['source']='web';add_web_history(cid,'sent',entry);return entry
    owner=request_owner_id(request,device_id);folder,_=device_dirs(owner);original=safe_name(file.filename);transfer_id=uuid4().hex;total=int(file.size or 0);received=0;last=-1;destination=folder/f'{transfer_id}__{source}__{owner}__{original}'
    with destination.open('wb') as output:
        while chunk:=await file.read(1024*1024):
            output.write(chunk);received+=len(chunk)
            if total>0:
                percent=int(received*100/total)
                if percent!=last:last=percent;await manager.send_to_device(owner,{'type':'upload_progress','transfer_id':transfer_id,'source':source,'device_id':owner,'filename':original,'received':received,'total':total,'percent':min(100,percent)})
    info=file_info(destination,'app' if source=='app' else 'received',owner);info['content_type']=file.content_type or 'application/octet-stream';info['transfer_id']=transfer_id;await manager.send_to_device(owner,{'type':'file_uploaded',**info});append_received_to_paired_web_clients(owner,info);return info
@app.post('/account/signup')
def account_signup(name:str=Form(...),mobile:str=Form(...),username:str=Form(...),email:str=Form(...),password:str=Form(...)):
    name=_clean_text(name,80);mobile=re.sub(r'[^0-9+ -]','',mobile or '').strip()[:20];username=re.sub(r'[^A-Za-z0-9_.-]','',username or '').lower()[:32];email=_clean_text(email,160).lower()
    if not name or not mobile or not username or not email or len(password)<8:raise HTTPException(400,'Name, mobile, username, email and an 8+ character password are required')
    if '@' not in email:raise HTTPException(400,'Enter a valid email')
    with _accounts_lock:
        accounts=_load_accounts()
        if username in accounts or any(a.get('email')==email for a in accounts.values()):raise HTTPException(409,'Username or email already exists')
        salt,digest=_password_hash(password);accounts[username]={'name':name,'mobile':mobile,'username':username,'email':email,'password_salt':salt,'password_hash':digest,'created_at':datetime.now(timezone.utc).isoformat()};_save_accounts(accounts)
    return {'ok':True,'message':'Account created','account':{'name':name,'mobile':mobile,'username':username,'email':email}}
@app.post('/account/login')
def account_login(email:str=Form(...),password:str=Form(...)):
    email=_clean_text(email,160).lower()
    with _accounts_lock:accounts=_load_accounts();account=next((a for a in accounts.values() if a.get('email')==email),None)
    if not account or not _verify_password(password,account.get('password_salt',''),account.get('password_hash','')):raise HTTPException(401,'Invalid email or password')
    return {'ok':True,'message':'Login successful','account':{k:account.get(k,'') for k in ('name','mobile','username','email')}}
@app.post('/feedback')
def feedback(message:str=Form(...),email:str=Form(''),username:str=Form('')):
    message=_clean_text(message,2000);email=_clean_text(email,160).lower();username=_clean_text(username,32)
    if not message:raise HTTPException(400,'Feedback message is required')
    entry={'message':message,'email':email,'username':username,'created_at':datetime.now(timezone.utc).isoformat()}
    with FEEDBACK_FILE.open('a',encoding='utf-8') as f:f.write(json.dumps(entry,ensure_ascii=False)+'\n')
    return {'ok':True,'message':'Feedback received'}
def discovery_loop():
    sock=socket.socket(socket.AF_INET,socket.SOCK_DGRAM)
    try:
        sock.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);sock.bind(('0.0.0.0',DISCOVERY_PORT))
        while True:
            data,addr=sock.recvfrom(1024)
            if data.decode('utf-8',errors='ignore').strip()!=DISCOVERY_TOKEN:continue
            sock.sendto(json.dumps({'service':'PHOTOSYNC','version':1,'port':APP_PORT}).encode(),addr)
    except OSError:pass
    finally:sock.close()
threading.Thread(target=discovery_loop,name='photosync-discovery',daemon=True).start()
