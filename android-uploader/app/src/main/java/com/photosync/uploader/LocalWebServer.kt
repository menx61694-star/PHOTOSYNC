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

class LocalWebServer(private val context: Context, private val port: Int) {
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val random = SecureRandom()
    @Volatile private var pin = generatePin()
    private val sessions = ConcurrentHashMap<String, Long>()
    private val attempts = ConcurrentHashMap<String, MutableList<Long>>()
    private val lifetime = 30 * 60 * 1000L
    private val root = File(context.filesDir, "photosync_local_server")
    private val uploads = File(root, "uploads")
    private val downloads = File(root, "downloads")

    init { uploads.mkdirs(); downloads.mkdirs() }

    fun start(): Boolean {
        if (running) return true
        return try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(java.net.InetSocketAddress(port), 50)
            serverSocket = s
            pin = generatePin()
            sessions.clear(); attempts.clear(); running = true
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
        running = false; sessions.clear(); attempts.clear()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    fun refreshPin(): String { pin = generatePin(); sessions.clear(); attempts.clear(); return pin }
    fun isRunning() = running && serverSocket?.isClosed == false
    fun currentPin() = pin
    fun isAuthorized(token: String?) = token != null && sessions[token]?.let { System.currentTimeMillis() < it } == true

    fun localIpv4(): String? = try {
        val ns = NetworkInterface.getNetworkInterfaces()
        while (ns.hasMoreElements()) {
            val n = ns.nextElement()
            if (!n.isUp || n.isLoopback || n.isVirtual) continue
            val as_ = n.inetAddresses
            while (as_.hasMoreElements()) {
                val a = as_.nextElement()
                if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress
            }
        }
        null
    } catch (_: Exception) { null }

    fun url() = localIpv4()?.let { "http://$it:$port" }
    private fun generatePin() = (100000 + random.nextInt(900000)).toString()
    private fun generateToken() = buildString(32) {
        val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        repeat(32) { append(chars[random.nextInt(chars.length)]) }
    }

    private fun readLine(input: InputStream): String? {
        val out = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.isEmpty()) null else out.toString()
            if (b == '\n'.code) return out.toString().removeSuffix("\r")
            if (out.length < 8192) out.append(b.toChar())
        }
    }

    private fun acceptLoop() {
        while (running) {
            try { serverSocket?.accept()?.let { executor.execute { handle(it) } } }
            catch (_: Exception) { if (!running) break }
        }
    }

    private fun handle(socket: Socket) {
        socket.use {
            try {
                it.soTimeout = 15_000
                val input = BufferedInputStream(it.getInputStream(), 64 * 1024)
                val request = readLine(input) ?: return
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val c = line.indexOf(':')
                    if (c > 0) headers[line.substring(0, c).trim().lowercase()] = line.substring(c + 1).trim()
                }
                val parts = request.split(' ')
                val method = parts.getOrNull(0) ?: ""
                val raw = parts.getOrNull(1) ?: "/"
                val path = raw.substringBefore('?')
                val query = parseQuery(raw.substringAfter('?', ""))
                val clientIp = it.inetAddress?.hostAddress ?: "unknown"
                val token = parseCookie(headers["cookie"])
                val appTrusted = clientIp == localIpv4() && !headers["x-photosync-device-id"].isNullOrBlank()
                val authorized = isAuthorized(token) || appTrusted
                val response = when {
                    path == "/" || path == "/dashboard" || path == "/dashboard/" -> html(page())
                    path == "/api/pair" && method == "POST" -> pair(clientIp, query["pin"] ?: "")
                    path == "/api/session" -> if (authorized) json("{\"authorized\":true,\"expires_in_seconds\":1800}") else json("{\"authorized\":false}", "401 Unauthorized")
                    path == "/api/logout" && method == "POST" -> logout()
                    !authorized -> json("{\"detail\":\"PIN pairing required\"}", "401 Unauthorized")
                    path == "/files" && method == "GET" -> files(query["source"])
                    path.startsWith("/files/") && method == "GET" -> file(path, query["download"] == "1")
                    path == "/upload" && method == "POST" -> upload(input, headers, query)
                    path == "/api/info" -> info()
                    else -> Response("404 Not Found", "text/plain; charset=utf-8", "Not found")
                }
                write(it, response)
            } catch (_: Exception) {}
        }
    }

    private data class Response(val status: String, val type: String, val body: String = "", val bytes: ByteArray? = null, val cookie: String? = null, val extra: String = "")
    private fun json(body: String, status: String = "200 OK") = Response(status, "application/json; charset=utf-8", body)
    private fun html(body: String) = Response("200 OK", "text/html; charset=utf-8", body)

    private fun write(socket: Socket, response: Response) {
        val body = response.bytes ?: response.body.toByteArray(Charsets.UTF_8)
        val cookie = response.cookie?.let { "Set-Cookie: $it\r\n" } ?: ""
        val headers = "HTTP/1.1 ${response.status}\r\nContent-Type: ${response.type}\r\nContent-Length: ${body.size}\r\nCache-Control: no-store, no-cache, must-revalidate\r\nPragma: no-cache\r\n${response.extra}$cookieConnection: close\r\n\r\n"
        socket.getOutputStream().use { out -> out.write(headers.toByteArray(Charsets.US_ASCII)); out.write(body); out.flush() }
    }

    private fun pair(ip: String, supplied: String): Response {
        val now = System.currentTimeMillis()
        val list = attempts.compute(ip) { _, old -> (old ?: mutableListOf()).filter { now - it < 60_000 }.toMutableList() } ?: mutableListOf()
        if (list.size >= 5) return json("{\"paired\":false,\"message\":\"Too many attempts; try again later\"}", "429 Too Many Requests")
        if (supplied.length == 6 && supplied == pin) {
            list.clear()
            val t = generateToken(); sessions[t] = now + lifetime
            return Response("200 OK", "application/json; charset=utf-8", "{\"paired\":true,\"expires_in_seconds\":1800}", cookie = "photosync_session=$t; Max-Age=1800; Path=/; HttpOnly; SameSite=Lax")
        }
        list.add(now)
        return json("{\"paired\":false,\"message\":\"Invalid PIN\"}", "403 Forbidden")
    }

    private fun logout() = Response("200 OK", "application/json; charset=utf-8", "{\"ok\":true}", cookie = "photosync_session=; Max-Age=0; Path=/; HttpOnly; SameSite=Lax")
    private fun parseCookie(h: String?): String? = h?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("photosync_session=") }?.substringAfter('=')
    private fun parseQuery(q: String) = q.split('&').mapNotNull { p -> val x = p.split('=', limit = 2); if (x.size == 2) x[0] to URLDecoder.decode(x[1], "UTF-8") else null }.toMap()
    private fun safeName(v: String?) = File(v ?: "file").name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(180).ifBlank { "file" }

    private fun fileJson(file: File, source: String): JSONObject {
        val name = file.name.substringAfter("__", file.name)
        val ext = name.substringAfterLast('.', "").lowercase()
        val type = when {
            ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp") -> "image"
            ext in setOf("mp4", "mkv", "webm", "mov", "avi") -> "video"
            ext == "pdf" -> "pdf"
            else -> "file"
        }
        val encoded = URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
        return JSONObject().apply {
            put("filename", name); put("stored_filename", file.name); put("url", "/files/$source/$encoded"); put("download_url", "/files/$source/$encoded?download=1"); put("size", file.length()); put("type", type)
        }
    }

    private fun files(source: String?): Response {
        val dir = if (source == "app") uploads else downloads
        val a = JSONArray()
        dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.forEach { a.put(fileJson(it, if (dir == uploads) "app" else "received")) }
        return json(a.toString())
    }

    private fun file(path: String, download: Boolean): Response {
        val bits = path.removePrefix("/files/").split('/', limit = 2)
        if (bits.size != 2 || bits[0] !in setOf("app", "received")) return Response("404 Not Found", "text/plain", "Not found")
        val dir = if (bits[0] == "app") uploads else downloads
        val name = safeName(URLDecoder.decode(bits[1], "UTF-8"))
        val target = dir.listFiles()?.firstOrNull { it.name == name } ?: return Response("404 Not Found", "text/plain", "Not found")
        val ext = name.substringAfterLast('.', "").lowercase()
        val type = when (ext) { "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"; "mp4" -> "video/mp4"; "webm" -> "video/webm"; "mov" -> "video/quicktime"; "pdf" -> "application/pdf"; "txt" -> "text/plain"; else -> "application/octet-stream" }
        return Response("200 OK", type, bytes = target.readBytes(), extra = "Content-Disposition: ${if (download) "attachment" else "inline"}; filename=\"${name.replace("\"", "_")}\"\r\n")
    }

    private fun upload(input: InputStream, headers: Map<String, String>, query: Map<String, String>): Response {
        val source = if (query["source"] == "app") "app" else "received"
        val name = safeName(query["filename"])
        val length = headers["content-length"]?.toLongOrNull() ?: return json("{\"detail\":\"Content-Length required\"}", "411 Length Required")
        if (length <= 0L) return json("{\"detail\":\"Empty file\"}", "400 Bad Request")
        if (length > 500L * 1024L * 1024L) return json("{\"detail\":\"File too large\"}", "413 Payload Too Large")
        val dir = if (source == "app") uploads else downloads
        var target = File(dir, "${System.currentTimeMillis()}__$name"); var n = 1
        while (target.exists()) target = File(dir, "${System.currentTimeMillis()}__${n++}__$name")
        input.use { src -> target.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024); var total = 0L
            while (total < length) { val read = src.read(buffer, 0, minOf(buffer.size.toLong(), length - total).toInt()); if (read <= 0) break; out.write(buffer, 0, read); total += read }
        }}
        return json(fileJson(target, source).toString())
    }

    private fun info(): Response {
        val all = (uploads.listFiles()?.filter { it.isFile } ?: emptyList()) + (downloads.listFiles()?.filter { it.isFile } ?: emptyList())
        return json("{\"server\":\"photosync-android\",\"ip\":${JSONObject.quote(localIpv4() ?: "")},\"port\":$port,\"pin_required\":true,\"running\":${isRunning()},\"used_bytes\":${all.sumOf { it.length()}},\"free_bytes\":${root.usableSpace},\"total_bytes\":${root.totalSpace}}")
    }

    private fun page(): String {
        val address = url() ?: "Waiting for a local network address"
        return """<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'><title>PHOTOSYNC</title><style>body{font-family:system-ui;background:#0b0f14;color:#fff;margin:0;padding:20px}main{max-width:950px;margin:auto}.box{background:#172235;border:1px solid #2b3950;border-radius:16px;padding:16px;margin-bottom:16px}.row{display:flex;gap:10px;align-items:center;flex-wrap:wrap}button,.action{background:#bda4ff;border:0;border-radius:10px;padding:10px 16px;font-weight:700;cursor:pointer;color:#17131f;text-decoration:none}.danger{background:#44222a;color:#fff}.muted{color:#aab7ca}.hidden{display:none}.gate{max-width:430px;margin:12vh auto}.files{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:12px}.card{background:#111923;border-radius:12px;overflow:hidden}.thumb,.videoThumb{width:100%;aspect-ratio:1;object-fit:cover;display:block;cursor:pointer}.meta{padding:9px;font-size:13px}.modal{position:fixed;inset:0;background:rgba(0,0,0,.92);display:flex;flex-direction:column;z-index:10}.modal.hidden{display:none}.top{display:flex;gap:8px;padding:12px;justify-content:center}.viewer{flex:1;display:flex;align-items:center;justify-content:center;overflow:auto}.viewer img{max-width:none;max-height:85vh}.viewer video{max-width:95vw;max-height:85vh}</style></head><body><main><div id=gate class='box gate'><h1>PHOTOSYNC</h1><p>Android local server is running.</p><p class=muted>Web address: <code>$address</code></p><h2>Pair this browser</h2><p class=muted>Enter the 6-digit PIN shown in the PhotoSync app.</p><div class=row><input id=pin inputmode=numeric maxlength=6 placeholder=PIN><button id=pair>Connect</button></div><p id=msg class=muted></p></div><div id=app class=hidden><div class=box><div class=row style='justify-content:space-between'><h1>PHOTOSYNC Local Server</h1><button id=logout class=danger>Disconnect</button></div><div id=stats class=muted></div></div><div class=box><h2>Send files to this phone</h2><input id=pick type=file multiple></div><div class=box><h2>Sent by app</h2><div id=sent class=files></div></div><div class=box><h2>Received from web</h2><div id=received class=files></div></div></div></main><div id=modal class='modal hidden'><div class=top><button id=zo>-</button><button id=zr>100%</button><button id=zi>+</button><a id=dl class=action download>Download</a><button id=cl>Close</button></div><div id=view class=viewer></div></div><script>const $=x=>document.getElementById(x);let files={};async function pair(){let p=$('pin').value.trim();if(!/^\d{6}$/.test(p)){$('msg').textContent='Enter a 6-digit PIN';return}let r=await fetch('/api/pair?pin='+p,{method:'POST',credentials:'same-origin',cache:'no-store'});let d=await r.json().catch(()=>({}));if(!r.ok){$('msg').textContent=d.message||'Pairing failed';return}$('gate').classList.add('hidden');$('app').classList.remove('hidden');load()}async function load(){let [a,b,i]=await Promise.all([fetch('/files?source=app&x='+Date.now()).then(r=>r.json()),fetch('/files?source=received&x='+Date.now()).then(r=>r.json()),fetch('/api/info?x='+Date.now()).then(r=>r.json())]);render($('sent'),a,'app');render($('received'),b,'received');$('stats').textContent='Used '+i.used_bytes+' bytes • Free '+i.free_bytes+' bytes'}function render(g,a,b){files[b]=a;g.innerHTML=a.length?a.map((p,i)=>'<div class=card data-b='+b+' data-i='+i+'>'+(p.type=='image'?'<img class=thumb src="'+p.url+'">':p.type=='video'?'<video class=videoThumb muted preload=metadata src="'+p.url+'"></video>':'<div style="aspect-ratio:1;display:grid;place-items:center;font-size:42px">📄</div>')+'<div class=meta>'+p.filename+'<br><a class=action href="'+p.download_url+'" download>Download</a></div></div>').join(''):'<div class=muted>None</div>';g.querySelectorAll('.card').forEach(c=>c.onclick=e=>{if(e.target.tagName=='A')return;open(files[c.dataset.b][c.dataset.i])})}function open(p){$('modal').classList.remove('hidden');let v=$('view'),e;if(p.type=='image'){e=document.createElement('img');e.src=p.url;e.dataset.s='1';e.style.transform='scale(1)'}else if(p.type=='video'){e=document.createElement('video');e.src=p.url;e.controls=true;e.autoplay=true}else{e=document.createElement('iframe');e.src=p.url;e.style='width:95vw;height:85vh;border:0;background:#fff'}v.innerHTML='';v.appendChild(e);$('dl').href=p.download_url;$('dl').download=p.filename;$('zi').onclick=()=>{if(p.type=='image'){e.dataset.s=Number(e.dataset.s)+.25;e.style.transform='scale('+e.dataset.s+')'}};$('zo').onclick=()=>{if(p.type=='image'){e.dataset.s=Math.max(.25,Number(e.dataset.s)-.25);e.style.transform='scale('+e.dataset.s+')'}};$('zr').onclick=()=>{if(p.type=='image'){e.dataset.s='1';e.style.transform='scale(1)'}}}$('cl').onclick=()=>{$('modal').classList.add('hidden');$('view').innerHTML=''};$('pair').onclick=pair;$('pin').onkeydown=e=>{if(e.key=='Enter')pair()};$('logout').onclick=()=>fetch('/api/logout',{method:'POST'}).finally(()=>location.reload());$('pick').onchange=()=>{[...$('pick').files].forEach(f=>{let x=new XMLHttpRequest();x.open('POST','/upload?source=web&filename='+encodeURIComponent(f.name));x.onload=load;x.send(f)});$('pick').value=''};fetch('/api/session',{cache:'no-store'}).then(r=>r.json()).then(d=>{if(d.authorized){$('gate').classList.add('hidden');$('app').classList.remove('hidden');load()}}).catch(()=>{});setInterval(()=>{if(!$('app').classList.contains('hidden'))load()},3000)</script></body></html>"""
    }
}
