package com.photosync.uploader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class LocalServer(private val context: Context, private val port: Int = 18000) {
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val random = SecureRandom()
    @Volatile private var pin: String = generatePin()
    private val sessions = ConcurrentHashMap<String, Long>()
    private val failedAttempts = ConcurrentHashMap<String, MutableList<Long>>()
    private val sessionLifetimeMs = 30 * 60 * 1000L
    private val maxAttemptsPerMinute = 5
    private val rootDir = File(context.filesDir, "photosync_local_server")
    private val uploadsDir = File(rootDir, "uploads")
    private val downloadsDir = File(rootDir, "downloads")

    init { uploadsDir.mkdirs(); downloadsDir.mkdirs() }

    fun start(): Boolean {
        if (running) return true
        return try {
            uploadsDir.mkdirs(); downloadsDir.mkdirs()
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(java.net.InetSocketAddress(port), 50)
            serverSocket = socket
            pin = generatePin()
            sessions.clear(); failedAttempts.clear(); running = true
            executor.execute { acceptLoop() }
            true
        } catch (_: Exception) {
            running = false
            try { serverSocket?.close() } catch (_: Exception) {}
            serverSocket = null
            false
        }
    }

    fun stop() {
        running = false; sessions.clear(); failedAttempts.clear()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    /** Generate a new app-owned pairing PIN without restarting the server. */
    fun refreshPin(): String {
        pin = generatePin()
        sessions.clear()
        failedAttempts.clear()
        return pin
    }

    fun isRunning(): Boolean = running && serverSocket?.isClosed == false
    fun currentPin(): String = pin
    fun isAuthorized(token: String?): Boolean = token != null && sessions[token]?.let { System.currentTimeMillis() < it } == true

    fun localIpv4(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val network = interfaces.nextElement()
                if (!network.isUp || network.isLoopback || network.isVirtual) continue
                val addresses = network.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) return address.hostAddress
                }
            }
            null
        } catch (_: Exception) { null }
    }

    fun url(): String? = localIpv4()?.let { "http://$it:$port" }
    private fun generatePin(): String = (100000 + random.nextInt(900000)).toString()
    private fun generateToken(): String = buildString(32) {
        val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        repeat(32) { append(chars[random.nextInt(chars.length)]) }
    }

    private fun acceptLoop() {
        while (running) {
            try {
                val socket = serverSocket?.accept() ?: break
                executor.execute { handle(socket) }
            } catch (_: Exception) { if (!running) break }
        }
    }

    private fun readAsciiLine(input: InputStream): String? {
        val out = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.isEmpty()) null else out.toString()
            if (b == '\n'.code) return out.toString().removeSuffix("\r")
            if (out.length < 8192) out.append(b.toChar())
        }
    }

    private fun handle(socket: Socket) {
        socket.use {
            try {
                it.soTimeout = 15_000
                val input = BufferedInputStream(it.getInputStream(), 64 * 1024)
                val requestLine = readAsciiLine(input) ?: return
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readAsciiLine(input) ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0) ?: ""
                val rawPath = parts.getOrNull(1) ?: "/"
                val path = rawPath.substringBefore('?')
                val query = parseQuery(rawPath.substringAfter('?', ""))
                val clientKey = it.inetAddress?.hostAddress ?: "unknown"
                val token = parseCookie(headers["cookie"], "photosync_session")
                val appTrusted = clientKey == localIpv4() && !headers["x-photosync-device-id"].isNullOrBlank()
                val authorized = isAuthorized(token) || appTrusted

                val response = when {
                    path == "/" || path == "/dashboard" || path == "/dashboard/" -> responseHtml(page())
                    path == "/api/pin" -> responseJson("{\"pin_required\":true,\"message\":\"Enter the PIN shown in the PhotoSync app\"}")
                    path == "/api/pair" && method == "POST" -> pair(clientKey, query["pin"] ?: "")
                    path == "/api/session" -> {
                        if (authorized) responseJson("{\"authorized\":true,\"expires_in_seconds\":${((sessions[token]?.minus(System.currentTimeMillis()) ?: sessionLifetimeMs) / 1000).coerceAtLeast(0)}}")
                        else responseJson("{\"authorized\":false}", "401 Unauthorized")
                    }
                    path == "/api/logout" && method == "POST" -> logoutResponse()
                    !authorized -> responseJson("{\"detail\":\"PIN pairing required\"}", "401 Unauthorized")
                    path == "/files" && method == "GET" -> filesResponse(query["source"])
                    path.startsWith("/files/") && method == "GET" -> fileResponse(path)
                    path == "/upload" && method == "POST" -> uploadResponse(input, headers, query)
                    path == "/api/info" -> infoResponse()
                    else -> Response("404 Not Found", "text/plain; charset=utf-8", "Not found")
                }
                writeResponse(it, response)
            } catch (_: Exception) { }
        }
    }

    private data class Response(val status: String, val contentType: String, val body: String = "", val bytes: ByteArray? = null, val setCookie: String? = null, val extraHeaders: String = "")
    private fun responseJson(body: String, status: String = "200 OK") = Response(status, "application/json; charset=utf-8", body)
    private fun responseHtml(body: String) = Response("200 OK", "text/html; charset=utf-8", body)

    private fun writeResponse(socket: Socket, response: Response) {
        val bodyBytes = response.bytes ?: response.body.toByteArray(Charsets.UTF_8)
        val cookie = response.setCookie?.let { "Set-Cookie: $it\r\n" } ?: ""
        val header = "HTTP/1.1 ${response.status}\r\nContent-Type: ${response.contentType}\r\nContent-Length: ${bodyBytes.size}\r\nCache-Control: no-store, no-cache, must-revalidate\r\nPragma: no-cache\r\n${response.extraHeaders}$cookieConnection: close\r\n\r\n"
        socket.getOutputStream().use { out -> out.write(header.toByteArray(Charsets.US_ASCII)); out.write(bodyBytes); out.flush() }
    }

    private fun pair(clientKey: String, supplied: String): Response {
        val now = System.currentTimeMillis()
        val attempts = failedAttempts.compute(clientKey) { _, old -> (old ?: mutableListOf()).filter { now - it < 60_000 }.toMutableList() } ?: mutableListOf()
        if (attempts.size >= maxAttemptsPerMinute) return responseJson("{\"paired\":false,\"message\":\"Too many attempts; try again later\"}", "429 Too Many Requests")
        if (supplied.length == 6 && supplied == pin) {
            attempts.clear()
            val token = generateToken()
            sessions[token] = now + sessionLifetimeMs
            return Response("200 OK", "application/json; charset=utf-8", "{\"paired\":true,\"expires_in_seconds\":1800}", setCookie = "photosync_session=$token; Max-Age=1800; Path=/; HttpOnly; SameSite=Lax")
        }
        attempts.add(now)
        return responseJson("{\"paired\":false,\"message\":\"Invalid PIN\"}", "403 Forbidden")
    }

    private fun logoutResponse(): Response = Response("200 OK", "application/json; charset=utf-8", "{\"ok\":true}", setCookie = "photosync_session=; Max-Age=0; Path=/; HttpOnly; SameSite=Lax")
    private fun parseCookie(header: String?, name: String): String? = header?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
    private fun parseQuery(query: String): Map<String, String> = query.split('&').mapNotNull { val p = it.split('=', limit = 2); if (p.size == 2) p[0] to URLDecoder.decode(p[1], "UTF-8") else null }.toMap()

    private fun safeName(value: String?): String {
        val raw = File(value ?: "file").name
        return raw.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_').take(180).ifBlank { "file" }
    }

    private fun fileJson(file: File, source: String): JSONObject {
        val name = file.name.substringAfter("__", file.name)
        val ext = name.substringAfterLast('.', "").lowercase()
        val type = when {
            ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp") -> "image"
            ext in setOf("mp4", "mkv", "webm", "mov", "avi") -> "video"
            ext == "pdf" -> "pdf"
            ext in setOf("txt", "json", "xml", "csv", "log") -> "text"
            else -> "file"
        }
        return JSONObject().apply {
            put("filename", name); put("stored_filename", file.name)
            put("url", "/files/$source/${URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")}")
            put("size", file.length()); put("type", type); put("source", source); put("modified", file.lastModified())
        }
    }

    private fun filesResponse(source: String?): Response {
        val dir = if (source == "app") uploadsDir else downloadsDir
        val array = JSONArray()
        dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.forEach { array.put(fileJson(it, if (dir == uploadsDir) "app" else "received")) }
        return responseJson(array.toString())
    }

    private fun fileResponse(path: String): Response {
        val bits = path.removePrefix("/files/").split('/', limit = 2)
        if (bits.size != 2) return Response("404 Not Found", "text/plain", "Not found")
        val source = bits[0]
        if (source != "app" && source != "received") return Response("404 Not Found", "text/plain", "Not found")
        val name = safeName(URLDecoder.decode(bits[1], "UTF-8"))
        val dir = if (source == "app") uploadsDir else downloadsDir
        val file = dir.listFiles()?.firstOrNull { it.name == name }
        if (file == null || !file.isFile) return Response("404 Not Found", "text/plain", "Not found")
        return Response("200 OK", contentType(name), bytes = file.readBytes(), extraHeaders = "Content-Disposition: inline; filename=\"${name.replace("\"", "_")}\"\r\n")
    }

    private fun uploadResponse(input: InputStream, headers: Map<String, String>, query: Map<String, String>): Response {
        val source = if (query["source"] == "app") "app" else "received"
        val original = safeName(query["filename"])
        val length = headers["content-length"]?.toLongOrNull() ?: return responseJson("{\"detail\":\"Content-Length required\"}", "411 Length Required")
        if (length <= 0L) return responseJson("{\"detail\":\"Empty file\"}", "400 Bad Request")
        if (length > 500L * 1024L * 1024L) return responseJson("{\"detail\":\"File too large\"}", "413 Payload Too Large")
        val destination = uniqueDestination(if (source == "app") uploadsDir else downloadsDir, original)
        destination.parentFile?.mkdirs()
        input.copyExactlyTo(destination.outputStream(), length)
        return responseJson(fileJson(destination, source).toString())
    }

    private fun uniqueDestination(dir: File, original: String): File {
        var candidate = File(dir, "${System.currentTimeMillis()}__$original")
        var n = 1
        while (candidate.exists()) {
            candidate = File(dir, "${System.currentTimeMillis()}__${n++}__$original")
        }
        return candidate
    }

    private fun InputStream.copyExactlyTo(output: java.io.OutputStream, expected: Long) {
        output.use { out ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (total < expected) {
                val wanted = minOf(buffer.size.toLong(), expected - total).toInt()
                val read = read(buffer, 0, wanted)
                if (read <= 0) throw java.io.EOFException("Unexpected end of upload")
                out.write(buffer, 0, read); total += read
            }
        }
    }

    private fun contentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"; "bmp" -> "image/bmp"
        "mp4" -> "video/mp4"; "webm" -> "video/webm"; "mov" -> "video/quicktime"; "mkv" -> "video/x-matroska"
        "pdf" -> "application/pdf"; "txt" -> "text/plain; charset=utf-8"; "json" -> "application/json"; "csv" -> "text/csv"; else -> "application/octet-stream"
    }

    private fun infoResponse(): Response {
        val files = (uploadsDir.listFiles()?.filter { it.isFile } ?: emptyList()) + (downloadsDir.listFiles()?.filter { it.isFile } ?: emptyList())
        val used = files.sumOf { it.length() }
        val total = rootDir.usableSpace + rootDir.totalSpace
        return responseJson("{\"server\":\"photosync-android\",\"ip\":${json(localIpv4())},\"port\":$port,\"pin_required\":true,\"running\":${isRunning()},\"used_bytes\":$used,\"free_bytes\":${rootDir.usableSpace},\"total_bytes\":$total}")
    }

    private fun page(): String {
        val address = url() ?: "Waiting for a local network address"
        return """<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'><title>PHOTOSYNC</title><style>
body{font-family:system-ui;background:#0b0f14;color:#f5f7fa;margin:0;padding:20px}main{max-width:950px;margin:auto}.box{background:#172235;border:1px solid #2b3950;border-radius:16px;padding:16px;margin-bottom:16px}.row{display:flex;gap:10px;align-items:center;flex-wrap:wrap}button{background:#bda4ff;border:0;border-radius:10px;padding:10px 16px;font-weight:700;cursor:pointer}button:disabled{opacity:.55;cursor:wait}input{background:#101824;color:white;border:1px solid #33415a;border-radius:10px;padding:12px}.pin{font-size:24px;letter-spacing:6px;text-align:center;width:220px}.files{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:12px}.card{background:#111923;border-radius:12px;overflow:hidden}.thumb{width:100%;aspect-ratio:1;object-fit:cover;display:block;cursor:pointer}.videoThumb{width:100%;aspect-ratio:1;background:#05070a;object-fit:cover}.meta{padding:9px;font-size:13px;word-break:break-word}.muted{color:#aab7ca}.hidden{display:none}.gate{max-width:430px;margin:12vh auto}.danger{background:#44222a;color:#fff}.progress{height:8px;background:#283447;border-radius:8px;overflow:hidden;margin-top:8px}.progress i{display:block;height:100%;width:0;background:#bda4ff}.modal{position:fixed;inset:0;background:rgba(0,0,0,.9);display:flex;flex-direction:column;z-index:10}.modal .top{display:flex;gap:8px;padding:12px;justify-content:center}.viewer{flex:1;display:flex;align-items:center;justify-content:center;overflow:auto}.viewer img{max-width:none;max-height:85vh;transform-origin:center}.viewer video{max-width:95vw;max-height:85vh}.modal a,.modal button{color:#fff;background:#27334a}.stat{display:flex;gap:16px;flex-wrap:wrap;color:#b9c6d8;font-size:13px}</style></head><body><main>
<div id='gate' class='box gate'><h1>PHOTOSYNC</h1><p>Android local server is running.</p><p class='muted'>Web address: <code>$address</code></p><h2>Pair this browser</h2><p class='muted'>Enter the 6-digit PIN shown in the PhotoSync app.</p><div class='row'><input id='pin' class='pin' inputmode='numeric' autocomplete='one-time-code' maxlength='6' placeholder='PIN'><button id='pairButton' type='button'>Connect</button></div><p id='msg' class='muted'></p></div>
<div id='app' class='hidden'><div class='box'><div class='row' style='justify-content:space-between'><div><h1 style='margin:0'>PHOTOSYNC Local Server</h1><p class='muted'>Private Android storage</p></div><button id='logoutButton' class='danger' type='button'>Disconnect</button></div><div id='stats' class='stat'>Loading storage…</div></div>
<div class='box'><h2>Send files to this phone</h2><input id='picker' type='file' multiple><div id='queue'></div></div>
<div class='box'><h2>Sent by app</h2><div id='sent' class='files'></div></div><div class='box'><h2>Received from web</h2><div id='received' class='files'></div></div></div></main>
<div id='modal' class='modal hidden'><div class='top'><button id='zoomOut'>−</button><button id='zoomReset'>100%</button><button id='zoomIn'>+</button><a id='download' download>Download</a><button id='close'>Close</button></div><div id='viewer' class='viewer'></div></div>
<script>(()=>{const $=id=>document.getElementById(id);function esc(s){return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\"/g,'&quot;')}function fmt(n){n=Number(n)||0;const u=['B','KB','MB','GB','TB'];let i=0;while(n>=1024&&i<u.length-1){n/=1024;i++}return n.toFixed(i?1:0)+' '+u[i]}
async function pair(){const pin=$('pin').value.trim(),msg=$('msg'),button=$('pairButton');if(!/^\d{6}$/.test(pin)){msg.textContent='Enter a 6-digit PIN';return}button.disabled=true;msg.textContent='Connecting…';try{const r=await fetch('/api/pair?pin='+encodeURIComponent(pin),{method:'POST',credentials:'same-origin',cache:'no-store',headers:{Accept:'application/json'}});const d=await r.json().catch(()=>({}));if(!r.ok||d.paired!==true)throw Error(d.message||('Pairing failed (HTTP '+r.status+')'));msg.textContent='Connected ✓';show()}catch(e){msg.textContent='Connection failed: '+(e.message||'Network error')}finally{button.disabled=false}}
async function session(){try{const r=await fetch('/api/session?ts='+Date.now(),{credentials:'same-origin',cache:'no-store'});if(r.ok){const d=await r.json();if(d.authorized)show()}}catch(_){} }function show(){$('gate').classList.add('hidden');$('app').classList.remove('hidden');load()}
async function disconnect(){try{await fetch('/api/logout',{method:'POST',credentials:'same-origin',cache:'no-store'})}finally{location.reload()}}
$('pairButton').onclick=pair;$('pin').onkeydown=e=>{if(e.key==='Enter')pair()};$('logoutButton').onclick=disconnect;
$('picker').onchange=()=>{[...$('picker').files].forEach(upload);$('picker').value=''};
function upload(file){const q=document.createElement('div');q.className='box';q.innerHTML='<b>'+esc(file.name)+'</b><div class=progress><i></i></div><div class=muted>0%</div><button class=danger>Cancel</button>';const bar=q.querySelector('i'),label=q.querySelector('.muted'),cancel=q.querySelector('button');$('queue').prepend(q);const xhr=new XMLHttpRequest();xhr.open('POST','/upload?source=web&filename='+encodeURIComponent(file.name));xhr.upload.onprogress=e=>{if(e.lengthComputable){const p=Math.round(e.loaded/e.total*100);bar.style.width=p+'%';label.textContent=p+'% • '+fmt(e.loaded)+' / '+fmt(e.total)}};xhr.onload=()=>{if(xhr.status>=200&&xhr.status<300){label.textContent='Uploaded ✓';bar.style.width='100%';load()}else label.textContent='Failed: '+xhr.responseText};xhr.onerror=()=>label.textContent='Network error';xhr.onabort=()=>label.textContent='Cancelled';cancel.onclick=()=>xhr.abort();xhr.send(file)}
async function getFiles(source){const r=await fetch('/files?source='+encodeURIComponent(source)+'&ts='+Date.now(),{cache:'no-store',credentials:'same-origin'});if(!r.ok)throw Error('HTTP '+r.status);return r.json()}
function openViewer(p){$('modal').classList.remove('hidden');const v=$('viewer');v.innerHTML='';const url=p.url;let el;if(p.type==='image'){el=document.createElement('img');el.src=url;el.style.transform='scale(1)';el.dataset.scale='1'}else if(p.type==='video'){el=document.createElement('video');el.src=url;el.controls=true;el.autoplay=true}else{el=document.createElement('iframe');el.src=url;el.style='width:95vw;height:85vh;border:0;background:white'}v.appendChild(el);$('download').href=url;$('download').download=p.filename||'file';$('zoomIn').onclick=()=>{if(p.type==='image'){let s=Number(el.dataset.scale||1)+.25;el.dataset.scale=s;el.style.transform='scale('+s+')'}};$('zoomOut').onclick=()=>{if(p.type==='image'){let s=Math.max(.25,Number(el.dataset.scale||1)-.25);el.dataset.scale=s;el.style.transform='scale('+s+')'}};$('zoomReset').onclick=()=>{if(p.type==='image'){el.dataset.scale='1';el.style.transform='scale(1)'}}}
$('close').onclick=()=>{$('modal').classList.add('hidden');$('viewer').innerHTML=''};
function cards(a){if(!a.length)return '<div class=muted>None</div>';return a.map((p,i)=>{const u=esc(p.url),name=esc(p.filename||'file');let media='';if(p.type==='image')media='<img class=thumb loading=lazy src="'+u+'" data-i="'+i+'">';else if(p.type==='video')media='<video class=videoThumb muted preload=metadata src="'+u+'"></video>';else media='<div style="aspect-ratio:1;display:grid;place-items:center;font-size:42px">'+(p.type==='pdf'?'📕':'📄')+'</div>';return '<div class=card data-i="'+i+'">'+media+'<div class=meta>'+name+'<br><span class=muted>'+fmt(p.size)+'</span><br><button class=view data-i="'+i+'">View</button> <a class=downloadLink href="'+u+'" download="'+name+'">Download</a></div></div>'}).join('')}
async function render(source,target){const a=await getFiles(source);target.dataset.items=JSON.stringify(a);target.innerHTML=cards(a);target.querySelectorAll('.view').forEach(b=>b.onclick=()=>openViewer(a[Number(b.dataset.i)]));target.querySelectorAll('.thumb,.videoThumb').forEach(e=>e.onclick=()=>openViewer(a[Number(e.closest('.card').dataset.i)]))}
async function load(){try{await Promise.all([render('app',$('sent')),render('received',$('received')),loadInfo()])}catch(e){if(String(e.message).includes('401'))location.reload()}}
async function loadInfo(){try{const r=await fetch('/api/info?ts='+Date.now(),{credentials:'same-origin',cache:'no-store'});if(!r.ok)return;const d=await r.json();$('stats').textContent='Storage: '+fmt(d.used_bytes)+' used • '+fmt(d.free_bytes)+' free • '+(d.ip||'network unavailable')}catch(_){} }
session();setInterval(()=>{if(!$('app').classList.contains('hidden'))load()},5000)})();</script></body></html>"""
    }

    private fun json(value: String?): String = if (value == null) "null" else "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}