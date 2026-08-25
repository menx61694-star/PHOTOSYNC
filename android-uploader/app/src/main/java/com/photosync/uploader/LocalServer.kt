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

                // The embedded server must remain PIN-protected for browsers, but the
                // PhotoSync app itself must be able to use its own server without a
                // browser session. Require BOTH the persistent device identity header
                // and the connection to originate from this phone's own LAN address.
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
                    path == "/api/info" -> responseJson("{\"server\":\"photosync-android\",\"ip\":${json(localIpv4())},\"port\":$port,\"pin_required\":true}")
                    else -> Response("404 Not Found", "text/plain; charset=utf-8", "Not found")
                }
                writeResponse(it, response)
            } catch (_: Exception) { }
        }
    }

    private data class Response(val status: String, val contentType: String, val body: String = "", val bytes: ByteArray? = null, val setCookie: String? = null)
    private fun responseJson(body: String, status: String = "200 OK") = Response(status, "application/json; charset=utf-8", body)
    private fun responseHtml(body: String) = Response("200 OK", "text/html; charset=utf-8", body)

    private fun writeResponse(socket: Socket, response: Response) {
        val bodyBytes = response.bytes ?: response.body.toByteArray(Charsets.UTF_8)
        val cookie = response.setCookie?.let { "Set-Cookie: $it\r\n" } ?: ""
        val header = "HTTP/1.1 ${response.status}\r\nContent-Type: ${response.contentType}\r\nContent-Length: ${bodyBytes.size}\r\nCache-Control: no-store, no-cache, must-revalidate\r\nPragma: no-cache\r\n${cookie}Connection: close\r\n\r\n"
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
        val type = if (name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")) "image" else "file"
        return JSONObject().apply {
            put("filename", name); put("stored_filename", file.name)
            put("url", "/files/$source/${URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")}")
            put("size", file.length()); put("type", type); put("source", source)
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
        return Response("200 OK", contentType(name), bytes = file.readBytes())
    }

    private fun uploadResponse(input: InputStream, headers: Map<String, String>, query: Map<String, String>): Response {
        val source = if (query["source"] == "app") "app" else "received"
        val original = safeName(query["filename"])
        val length = headers["content-length"]?.toLongOrNull() ?: return responseJson("{\"detail\":\"Content-Length required\"}", "411 Length Required")
        if (length <= 0L) return responseJson("{\"detail\":\"Empty file\"}", "400 Bad Request")
        if (length > 500L * 1024L * 1024L) return responseJson("{\"detail\":\"File too large\"}", "413 Payload Too Large")
        val destination = File(if (source == "app") uploadsDir else downloadsDir, "${System.currentTimeMillis()}__$original")
        destination.parentFile?.mkdirs()
        input.copyExactlyTo(destination.outputStream(), length)
        return responseJson(fileJson(destination, source).toString())
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
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"; "mp4" -> "video/mp4"; "pdf" -> "application/pdf"; "txt" -> "text/plain"; else -> "application/octet-stream"
    }

    private fun page(): String {
        val address = url() ?: "Waiting for a local network address"
        return """<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'><title>PHOTOSYNC</title><style>body{font-family:system-ui;background:#0b0f14;color:#f5f7fa;margin:0;padding:20px}main{max-width:850px;margin:auto}.box{background:#172235;border:1px solid #2b3950;border-radius:16px;padding:16px;margin-bottom:16px}.row{display:flex;gap:10px;align-items:center;flex-wrap:wrap}button{background:#bda4ff;border:0;border-radius:10px;padding:12px 18px;font-weight:700;cursor:pointer}button:disabled{opacity:.6;cursor:wait}input{background:#101824;color:white;border:1px solid #33415a;border-radius:10px;padding:12px}.pin{font-size:24px;letter-spacing:6px;text-align:center;width:220px}.files{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:12px}.card{background:#111923;border-radius:12px;overflow:hidden}.card img{width:100%;aspect-ratio:1;object-fit:cover}.meta{padding:9px;font-size:13px;word-break:break-word}.muted{color:#aab7ca}.hidden{display:none}.gate{max-width:430px;margin:12vh auto}</style></head><body><main><div id='gate' class='box gate'><h1>PHOTOSYNC</h1><p>Android local server is running.</p><p class='muted'>Web address: <code>$address</code></p><h2>Pair this browser</h2><p class='muted'>Enter the 6-digit PIN shown in the PhotoSync app.</p><div class='row'><input id='pin' class='pin' inputmode='numeric' autocomplete='one-time-code' maxlength='6' placeholder='PIN'><button id='pairButton' type='button'>Pair</button></div><p id='msg' class='muted'></p></div><div id='app' class='hidden'><div class='box'><div class='row' style='justify-content:space-between'><div><h1 style='margin:0'>PHOTOSYNC Local Server</h1><p class='muted'>Only this Android app's private server storage is shown.</p></div><button id='logoutButton' type='button'>Unpair</button></div></div><div class='box'><h2>Send files to this phone</h2><input id='picker' type='file' multiple><p class='muted'>Files are stored in the app's private Received folder.</p><div id='queue'></div></div><div class='box'><h2>Sent by app</h2><div id='sent' class='files'></div></div><div class='box'><h2>Received from web</h2><div id='received' class='files'></div></div></div></main><script>(()=>{const $=id=>document.getElementById(id);function esc(s){return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\"/g,'&quot;')}async function pair(){const pin=$('pin').value.trim();const msg=$('msg');const button=$('pairButton');if(!/^\d{6}$/.test(pin)){msg.textContent='Enter a 6-digit PIN';return}button.disabled=true;msg.textContent='Pairing…';try{const r=await fetch('/api/pair?pin='+encodeURIComponent(pin),{method:'POST',credentials:'same-origin',cache:'no-store',headers:{'Accept':'application/json'}});const text=await r.text();let data={};try{data=JSON.parse(text)}catch(_){data={message:text}}if(!r.ok||data.paired!==true){msg.textContent=data.message||('Pairing failed (HTTP '+r.status+')');button.disabled=false;return}msg.textContent='Paired ✓';show()}catch(e){msg.textContent='Pairing error: '+(e&&e.message?e.message:'Network error');button.disabled=false}}async function session(){try{const r=await fetch('/api/session?ts='+Date.now(),{credentials:'same-origin',cache:'no-store'});if(r.ok){const d=await r.json();if(d.authorized)show()}}catch(_){}}function show(){$('gate').classList.add('hidden');$('app').classList.remove('hidden');load()}async function logout(){try{await fetch('/api/logout',{method:'POST',credentials:'same-origin',cache:'no-store'})}finally{location.reload()}}$('pairButton').addEventListener('click',pair);$('pin').addEventListener('keydown',e=>{if(e.key==='Enter')pair()});$('logoutButton').addEventListener('click',logout);$('picker').addEventListener('change',()=>{[...$('picker').files].forEach(upload);$('picker').value='' });async function upload(file){const q=document.createElement('div');q.className='box';q.textContent='Uploading '+file.name+'…';$('queue').prepend(q);try{const r=await fetch('/upload?source=web&filename='+encodeURIComponent(file.name),{method:'POST',body:file,credentials:'same-origin'});if(!r.ok)throw new Error((await r.text())||'Upload failed');q.textContent='Uploaded ✓ '+file.name;load()}catch(e){q.textContent='Failed: '+e.message}}async function getFiles(source){const r=await fetch('/files?source='+encodeURIComponent(source)+'&ts='+Date.now(),{cache:'no-store',credentials:'same-origin'});if(!r.ok)throw new Error('HTTP '+r.status);return r.json()}async function load(){try{const [sent,received]=await Promise.all([getFiles('app'),getFiles('received')]);$('sent').innerHTML=cards(sent);$('received').innerHTML=cards(received)}catch(e){if(e.message.includes('401'))location.reload()}}function cards(a){if(!a.length)return '<div class=muted>None</div>';return a.map(p=>p.type==='image'?'<div class=card><img loading=lazy src="'+esc(p.url)+'"><div class=meta>'+esc(p.filename)+'</div></div>':'<div class=card><div style="aspect-ratio:1;display:grid;place-items:center;font-size:42px">📄</div><div class=meta>'+esc(p.filename)+'</div></div>').join('')}session();setInterval(()=>{if(!$('app').classList.contains('hidden'))load()},3000)})();</script></body></html>"""
    }

    private fun json(value: String?): String = if (value == null) "null" else "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
