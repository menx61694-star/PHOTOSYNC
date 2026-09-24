package com.photosync.uploader

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class LocalWebServer(private val context: Context, private val port: Int) {
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
     @Volatile private var executor: ExecutorService? = null
    @Volatile private var acceptThread: Thread? = null
    private val random = SecureRandom()
    @Volatile private var pin = generatePin()
    private val sessions = ConcurrentHashMap<String, Long>()
    private data class WebClient(val token: String, val ip: String, val connectedAt: Long, @Volatile var lastSeen: Long)
    private val webClients = ConcurrentHashMap<String, WebClient>()
    private val attempts = ConcurrentHashMap<String, MutableList<Long>>()
    private val lifetime = 30 * 60 * 1000L
    private val root = File(context.filesDir, "photosync_local_server")
    private val uploads = File(root, "uploads")
    private val downloads = File(root, "downloads")

    init { uploads.mkdirs(); downloads.mkdirs() }

    @Synchronized
    fun start(): Boolean {
        if (running) return true
        return try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.receiveBufferSize = 1024 * 1024
            s.bind(java.net.InetSocketAddress(port), 50)
            serverSocket = s
            pin = generatePin()
            sessions.clear(); webClients.clear(); attempts.clear(); running = true
            executor?.shutdownNow()
            executor = Executors.newCachedThreadPool()
            val acceptor = Thread({ acceptLoop() }, "PhotoSync-Embedded-Acceptor")
            acceptThread = acceptor
            acceptor.start()
            true
        } catch (_: Throwable) {
            running = false
            try { serverSocket?.close() } catch (_: Throwable) {}
            serverSocket = null
            try { acceptThread?.interrupt() } catch (_: Throwable) {}
            acceptThread = null
            try { executor?.shutdownNow() } catch (_: Throwable) {}
            executor = null
            false
        }
    }

    @Synchronized
    fun stop() {
        running = false
        sessions.clear(); webClients.clear(); attempts.clear()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        try { acceptThread?.interrupt() } catch (_: Throwable) {}
        acceptThread = null
        executor?.shutdownNow()
        executor = null
    }

    fun refreshPin(): String { pin = generatePin(); sessions.clear(); webClients.clear(); attempts.clear(); return pin }
    fun isRunning() = running && serverSocket?.isClosed == false
    fun currentPin() = pin
    fun isAuthorized(token: String?) = token != null && sessions[token]?.let { System.currentTimeMillis() < it } == true
    data class WebClientInfo(val id: String, val ip: String, val connectedAt: Long, val lastSeen: Long)
    fun webClients(): List<WebClientInfo> = webClients.values.sortedByDescending { it.lastSeen }.map { WebClientInfo(it.token.take(8), it.ip, it.connectedAt, it.lastSeen) }
    fun disconnectWebClient(id: String): Boolean { val key = webClients.keys.firstOrNull { it.take(8) == id } ?: return false; webClients.remove(key); sessions.remove(key); return true }

    fun localIpv4(): String? {
        return try {
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
    }

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
            try {
                val socket = serverSocket?.accept() ?: break
                val workers = executor
                if (workers != null && !workers.isShutdown) {
                    try {
                        workers.execute { handle(socket) }
                    } catch (_: Throwable) {
                        try { socket.close() } catch (_: Throwable) { }
                    }
                } else {
                    try { socket.close() } catch (_: Throwable) { }
                }
            } catch (_: Exception) {
                if (!running) break
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use {
            try {
                it.soTimeout = 5 * 60 * 1000
                it.receiveBufferSize = 1024 * 1024
                it.sendBufferSize = 1024 * 1024
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
                if (token != null && isAuthorized(token)) webClients[token]?.lastSeen = System.currentTimeMillis()
                val authorized = isAuthorized(token) || appTrusted
                val response = when {
                    path == "/" || path == "/dashboard" || path == "/dashboard/" -> html(page())
                    path == "/health" && method == "GET" -> json("{\"ok\":true,\"running\":"+isRunning()+"}")
                    path == "/api/pair" && method == "POST" -> pair(clientIp, query["pin"] ?: "")
                    path == "/api/session" -> if (authorized) json("{\"authorized\":true,\"expires_in_seconds\":1800}") else json("{\"authorized\":false}", "401 Unauthorized")
                    path == "/api/logout" && method == "POST" -> logout(token)
                    path == "/api/web-clients" && method == "GET" -> webClientsResponse()
                    path == "/api/web-clients/disconnect" && method == "POST" -> disconnectWebClientResponse(query["id"])
                    !authorized -> json("{\"detail\":\"PIN pairing required\"}", "401 Unauthorized")
                    path == "/files" && method == "GET" -> files(query["source"])
                    path.startsWith("/files/") && method == "GET" -> file(path, query["download"] == "1")
                    path == "/upload" && method == "POST" -> upload(input, headers, query)
                    path == "/text" && method == "POST" -> receiveText(input, headers)
                    path == "/api/info" -> info()
                    else -> Response("404 Not Found", "text/plain; charset=utf-8", "Not found")
                }
                write(it, response)
            } catch (e: Exception) {
                try { write(it, json("{\"detail\":\"Server error\"}", "500 Internal Server Error")) } catch (_: Exception) { }
            }
        }
    }

    private data class Response(val status: String, val type: String, val body: String = "", val bytes: ByteArray? = null, val file: File? = null, val cookie: String? = null, val extra: String = "")
    private fun json(body: String, status: String = "200 OK") = Response(status, "application/json; charset=utf-8", body)
    private fun html(body: String) = Response("200 OK", "text/html; charset=utf-8", body)

    private fun write(socket: Socket, response: Response) {
        val body = response.bytes ?: response.body.toByteArray(Charsets.UTF_8)
        val file = response.file
        val length = file?.length() ?: body.size.toLong()
        val cookie = response.cookie?.let { "Set-Cookie: $it\r\n" } ?: ""
        val headers = "HTTP/1.1 ${response.status}\r\nContent-Type: ${response.type}\r\nContent-Length: $length\r\nConnection: close\r\nCache-Control: no-store, no-cache, must-revalidate\r\nPragma: no-cache\r\n${response.extra}$cookie\r\n\r\n"
        socket.getOutputStream().use { out ->
            out.write(headers.toByteArray(Charsets.US_ASCII))
            if (file != null) {
                file.inputStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                    }
                }
            } else out.write(body)
            out.flush()
        }
    }

    private fun pair(ip: String, supplied: String): Response {
        val now = System.currentTimeMillis()
        val list = attempts.compute(ip) { _, old -> (old ?: mutableListOf()).filter { now - it < 60_000 }.toMutableList() } ?: mutableListOf()
        if (list.size >= 5) return json("{\"paired\":false,\"message\":\"Too many attempts; try again later\"}", "429 Too Many Requests")
        if (supplied.length == 6 && supplied == pin) {
            list.clear()
            val t = generateToken(); sessions[t] = now + lifetime
            webClients[t] = WebClient(t, ip, now, now)
            return Response("200 OK", "application/json; charset=utf-8", "{\"paired\":true,\"expires_in_seconds\":1800}", cookie = "photosync_session=$t; Max-Age=1800; Path=/; HttpOnly; SameSite=Lax")
        }
        list.add(now)
        return json("{\"paired\":false,\"message\":\"Invalid PIN\"}", "403 Forbidden")
    }

    private fun logout(token: String?): Response { if (token != null) { sessions.remove(token); webClients.remove(token) }; return Response("200 OK", "application/json; charset=utf-8", "{\"ok\":true}", cookie = "photosync_session=; Max-Age=0; Path=/; HttpOnly; SameSite=Lax") }
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
        return Response("200 OK", type, file = target, extra = "Content-Disposition: ${if (download) "attachment" else "inline"}; filename=\"${name.replace("\"", "_")}\"\r\n")
    }

    private fun upload(input: InputStream, headers: Map<String, String>, query: Map<String, String>): Response {
        val source = if (query["source"] == "app") "app" else "received"
        val name = safeName(query["filename"])
        val length = headers["content-length"]?.toLongOrNull() ?: return json("{\"detail\":\"Content-Length required\"}", "411 Length Required")
        if (length <= 0L) return json("{\"detail\":\"Empty file\"}", "400 Bad Request")
        val dir = if (source == "app") uploads else downloads
        var target = File(dir, "${System.currentTimeMillis()}__$name"); var n = 1
        while (target.exists()) target = File(dir, "${System.currentTimeMillis()}__${n++}__$name")
        var total = 0L
        // Do not close the request input here. Closing Socket.getInputStream()
        // also closes the socket, which would prevent the HTTP response from
        // reaching the uploader after a successful upload.
        target.outputStream().use { out ->
            val buffer = ByteArray(256 * 1024)
            while (total < length) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), length - total).toInt())
                if (read <= 0) break
                out.write(buffer, 0, read)
                total += read
            }
        }
        if (total != length) {
            try { target.delete() } catch (_: Exception) {}
            return json("{\"detail\":\"Incomplete upload\"}", "400 Bad Request")
        }
        return json(fileJson(target, source).toString())
    }

    fun storeAppFile(
        resolver: ContentResolver,
        uri: Uri,
        filename: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): JSONObject {
        val name = safeName(filename)
        val total = try {
            resolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getLong(0).takeIf { it >= 0L } ?: -1L else -1L
            } ?: -1L
        } catch (_: Exception) { -1L }
        var target = File(uploads, "${System.currentTimeMillis()}__$name")
        var n = 1
        while (target.exists()) target = File(uploads, "${System.currentTimeMillis()}__${n++}__$name")
        var written = 0L
        try {
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Unable to open file" }
                target.outputStream().use { out ->
                    val buffer = ByteArray(256 * 1024)
                    var nextProgress = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        written += read
                        if (written >= nextProgress || (total > 0L && written >= total)) {
                            onProgress(written, total)
                            nextProgress = written + 1024L * 1024L
                        }
                    }
                }
            }
            if (total >= 0L && written != total) throw java.io.EOFException("File changed or ended early during embedded upload ($written/$total bytes)")
            return fileJson(target, "app")
        } catch (e: Exception) {
            try { target.delete() } catch (_: Exception) {}
            throw e
        }
    }

    private fun receiveText(input: InputStream, headers: Map<String, String>): Response {
        val length = headers["content-length"]?.toLongOrNull()
            ?: return json("{\"detail\":\"Content-Length required\"}", "411 Length Required")
        if (length <= 0L) return json("{\"detail\":\"Empty text\"}", "400 Bad Request")
        if (length > 1024L * 1024L) return json("{\"detail\":\"Text is limited to 1 MB\"}", "413 Payload Too Large")
        val bytes = ByteArray(length.toInt())
        var offset = 0
        while (offset < bytes.size) {
            val read = input.read(bytes, offset, bytes.size - offset)
            if (read <= 0) break
            offset += read
        }
        if (offset != bytes.size) return json("{\"detail\":\"Incomplete text\"}", "400 Bad Request")
        val text = String(bytes, Charsets.UTF_8)
        if (text.isBlank()) return json("{\"detail\":\"Empty text\"}", "400 Bad Request")
        val name = "text_" + System.currentTimeMillis() + ".txt"
        val target = File(downloads, name)
        target.writeText(text, Charsets.UTF_8)
        return json(fileJson(target, "received").toString())
    }

    private fun webClientsResponse(): Response {
        val a = JSONArray()
        webClients().forEach { c ->
            a.put(JSONObject().apply { put("id", c.id); put("ip", c.ip); put("connected_at", c.connectedAt); put("last_seen", c.lastSeen) })
        }
        return json(a.toString())
    }

    private fun disconnectWebClientResponse(id: String?): Response {
        if (id.isNullOrBlank() || !disconnectWebClient(id)) return json("{\"ok\":false,\"detail\":\"Web client not found\"}", "404 Not Found")
        return json("{\"ok\":true}")
    }

    private fun info(): Response {
        val all = (uploads.listFiles()?.filter { it.isFile } ?: emptyList()) + (downloads.listFiles()?.filter { it.isFile } ?: emptyList())
        return json("{\"server\":\"photosync-android\",\"ip\":${JSONObject.quote(localIpv4() ?: "")},\"port\":$port,\"pin_required\":true,\"running\":${isRunning()},\"used_bytes\":${all.sumOf { it.length()}},\"free_bytes\":${root.usableSpace},\"total_bytes\":${root.totalSpace}}")
    }

    private fun page(): String {
        val address = url() ?: "Waiting for a local network address"
        return """<!doctype html><html><head><meta name=viewport content="width=device-width,initial-scale=1"><meta http-equiv="Cache-Control" content="no-store"><title>PhotoSync Local</title>
<style>*{box-sizing:border-box}body{margin:0;font-family:system-ui,-apple-system,Segoe UI,sans-serif;background:#07101b;color:#f4f7ff}.app{min-height:100vh;display:flex}.side{width:230px;flex:none;background:#09121e;border-right:1px solid #1d2b40;padding:22px 14px;display:flex;flex-direction:column}.brand{padding:8px 14px 25px;font-size:28px;font-weight:800}.brand b{color:#6b8dff}.tag{font-size:10px;color:#8191aa}.nav{display:grid;gap:6px}.nav div{padding:13px 14px;border-radius:12px;color:#dce6f8}.nav .active{background:linear-gradient(90deg,#263e9c,#24256c)}.sideStatus{margin-top:auto;border:1px solid #29405d;background:#0f1c2d;border-radius:14px;padding:13px}.dot{display:inline-block;width:12px;height:12px;border-radius:50%;background:#1ee39a;margin-right:8px}.main{flex:1;padding:16px;overflow:auto}.top{display:flex;gap:10px;align-items:center;margin-bottom:16px}.server,.pinTop{border:1px solid #2a405d;background:#0d1928;border-radius:14px;padding:11px 14px}.server{min-width:310px}.server code{font-size:12px;color:#bdcce0}.pinTop{display:flex;gap:7px;margin-left:auto}.pinTop input{width:175px;background:#091421;border:1px solid #31445f;color:#fff;border-radius:9px;padding:10px}.btn{border:1px solid #3c5a7c;background:linear-gradient(135deg,#2c55ff,#6647ef);color:#fff;border-radius:10px;padding:10px 15px;font-weight:700;cursor:pointer}.btn.alt{background:#132137}.btn.danger{background:#361b28}.hero{padding:27px 32px;border-radius:20px;background:linear-gradient(105deg,#7148f7,#1c55c9 72%,#18398d);min-height:145px}.hero h1{margin:0 0 6px;font-size:28px}.hero p{margin:0;color:#e9efff}.actions{display:grid;grid-template-columns:repeat(3,1fr);gap:14px;margin:15px 0}.action{border:1px solid #30435d;background:#111d2d;border-radius:18px;padding:17px;display:flex;gap:12px;align-items:center}.action:nth-child(1){background:linear-gradient(135deg,#3e208e,#1d348d)}.action:nth-child(2){background:linear-gradient(135deg,#075b58,#0a3d4c)}.action:nth-child(3){background:#142031}.ico{width:50px;height:50px;border-radius:14px;background:#ffffff25;display:grid;place-items:center;font-size:25px}.action h3{margin:0 0 4px}.action p{margin:0;color:#a4b1c5;font-size:12px}.cols{display:grid;grid-template-columns:1fr 1fr;gap:14px}.panel{border:1px solid #2a3d56;background:#0c1725;border-radius:18px;padding:14px}.head{display:flex;justify-content:space-between;align-items:center;margin-bottom:11px}.head h2{margin:0;font-size:17px}.count{background:#172840;border:1px solid #344a69;border-radius:15px;padding:5px 9px;font-size:11px;color:#b5c8e3}.empty{min-height:130px;border-radius:14px;background:#0f1b2b;border:1px solid #1c3048;display:grid;place-items:center;text-align:center;color:#8798b1;padding:18px}.files{display:grid;grid-template-columns:repeat(2,1fr);gap:10px}.file{background:#101c2b;border:1px solid #243750;border-radius:12px;overflow:hidden}.file img,.file video{width:100%;aspect-ratio:1;object-fit:cover}.meta{padding:8px;font-size:11px;color:#b8c5d8}.meta a{color:#70b7ff}.text{margin-top:14px}.text textarea{width:100%;min-height:45px;max-height:210px;background:#091421;border:1px solid #2b405b;color:#fff;border-radius:11px;padding:11px;resize:none}.row{display:flex;gap:8px;flex-wrap:wrap;margin-top:9px}.gate{max-width:480px;margin:8vh auto;background:#0c1725;border:1px solid #2a3d56;border-radius:20px;padding:28px}.gate input{background:#091421;color:#fff;border:1px solid #31445f;border-radius:9px;padding:11px;width:150px;letter-spacing:4px}.modal{position:fixed;inset:0;background:#02060de8;display:flex;flex-direction:column;z-index:50}.modalTop{padding:12px;display:flex;justify-content:center;gap:8px}.viewer{flex:1;display:flex;align-items:center;justify-content:center;overflow:auto}.viewer img{max-width:92vw;max-height:85vh}.viewer video{max-width:92vw;max-height:85vh}.viewer iframe{width:92vw;height:85vh;border:0;background:#fff}.hidden{display:none!important}@media(max-width:850px){.side{width:170px}.actions,.cols{grid-template-columns:1fr}.top{flex-wrap:wrap}.pinTop{margin-left:0;width:100%}.pinTop input{flex:1}.server{min-width:0;flex:1}}</style></head><body>
<div id=gate class=gate><div class=brand>photo<b>sync</b><div class=tag>Share. Sync. Simple.</div></div><h2>Local Server</h2><p>Connect your browser to this phone.</p><p class=tag>Address: <code>${address}</code></p><div class=row><input id=pin inputmode=numeric maxlength=6 placeholder="PIN"><button class=btn id=pair>Pair Device</button></div><p id=msg class=tag></p></div>
<div id=app class="app hidden"><aside class=side><div class=brand>photo<b>sync</b><div class=tag>Share. Sync. Simple.</div></div><div class=nav><div class=active>⌂　Dashboard</div><div>➤　Send Files</div><div>⇩　Receive Files</div><div>▢　Text Sync</div><div>▱　Web Clients</div><div>⚙　Settings</div></div><div class=sideStatus><span class=dot></span><b>Server Running</b><div class=tag>Ready to receive files</div></div></aside>
<main class=main><div class=top><div class=server><b>🛜　Local Server</b><br><code>${address}</code></div><button class="btn alt" id=copy>▣</button><button class="btn alt" id=qr>▦ Show QR</button><div class=pinTop><input id=pinTop placeholder="Enter PIN from phone…" maxlength=6><button class=btn id=pairTop>→</button></div></div>
<section class=hero><h1>Transfer Files Between Devices</h1><p>Send photos, videos, documents and more — fast and secure.</p></section>
<section class=actions><div class=action id=sendFiles><div class=ico>▤</div><div><h3>Send Files</h3><p>Choose files to send to this phone</p></div></div><div class=action id=receiveFiles><div class=ico>⇩</div><div><h3>Receive Files</h3><p>Get files from this phone</p></div></div><div class=action id=sendTextAction><div class=ico>☁</div><div><h3>Send Text</h3><p>Send text messages instantly</p></div></div></section>
<section class=cols><div class=panel><div class=head><h2>▯　Connected Phone</h2><span class=count>This Device</span></div><div class=empty>📱<br><b>This Android phone is running the local server.</b><br><span class=tag>${address}</span></div></div><div class=panel><div class=head><h2>▱　Web Clients</h2><span class=count id=clientCount>0 client</span></div><div id=clients class=empty>Loading connected browsers…</div></div></section>
<section class=cols style="margin-top:14px"><div class=panel><div class=head><h2>⇧　Sent Files</h2><span class=count>From Phone</span></div><div id=sent class=files></div></div><div class=panel><div class=head><h2>⇩　Received Files</h2><span class=count>From Web</span></div><div id=received class=files></div></div></section>
<section class="panel text" id=textPanel><div class=head><h2>▢　Text / Clipboard Sync</h2></div><textarea id=textInput rows=1 maxlength=1048576 placeholder="Type or paste text here…"></textarea><div class=row><button class="btn alt" id=paste>Paste</button><button class=btn id=sendText>Send Text</button><button class="btn alt" id=receiveText>Receive</button><button class="btn danger" id=clearText>Clear</button><span id=textMsg class=tag></span></div><div id=textReceived class=tag style="margin-top:10px"></div></section>
<input id=pick type=file multiple hidden></main></div>
<div id=modal class="modal hidden"><div class=modalTop><button class=btn id=zo>−</button><button class=btn id=zr>100%</button><button class=btn id=zi>+</button><a class=btn id=dl download>Download</a><button class="btn danger" id=cl>Close</button></div><div id=view class=viewer></div></div>
<script>
const el=id=>document.getElementById(id);
function esc(s){return String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))}
async function pair(){let p=el('pin').value.trim();if(!/^\d{6}$/.test(p)){el('msg').textContent='Enter a 6-digit PIN';return}let r=await fetch('/api/pair?pin='+encodeURIComponent(p),{method:'POST',cache:'no-store'});let d=await r.json().catch(()=>({}));if(!r.ok){el('msg').textContent=d.message||'Pairing failed';return}el('gate').classList.add('hidden');el('app').classList.remove('hidden');load()}
el('pair').onclick=pair;el('pairTop').onclick=async()=>{el('pin').value=el('pinTop').value;await pair()};el('pin').onkeydown=e=>{if(e.key==='Enter')pair()};el('copy').onclick=()=>navigator.clipboard?.writeText('${address}');el('qr').onclick=()=>alert('Use the PhotoSync PIN pairing or scan this local address.');
async function load(){try{let rs=await Promise.all([fetch('/files?source=app&x='+Date.now(),{cache:'no-store'}),fetch('/files?source=received&x='+Date.now(),{cache:'no-store'}),fetch('/api/web-clients?x='+Date.now(),{cache:'no-store'})]);if(rs.some(r=>r.status===401)){el('app').classList.add('hidden');el('gate').classList.remove('hidden');return}let a=await rs[0].json(),b=await rs[1].json(),c=await rs[2].json();render('sent',a);render('received',b);el('clientCount').textContent=c.clients.length+' client'+(c.clients.length===1?'':'s');el('clients').innerHTML=c.clients.length?c.clients.map(x=>'<div style="padding:10px;text-align:left;border-bottom:1px solid #263750">💻 <b>'+esc(x.ip)+'</b><br><span class=tag>Connected '+new Date(x.connected_at).toLocaleTimeString()+'</span><button class="btn danger" style="float:right;padding:6px 10px" onclick="disconnectClient(\''+esc(x.id)+'\')">Disconnect</button></div>').join(''):'No web clients connected.'}catch(e){el('clients').textContent='Unable to refresh local server data'}}
async function disconnectClient(id){await fetch('/api/web-clients/disconnect?id='+encodeURIComponent(id),{method:'POST',cache:'no-store'});load()}
function render(id,a){let g=el(id);if(!a.length){g.innerHTML='<div class=empty style="grid-column:1/-1;min-height:120px">No files yet.</div>';return}g.innerHTML=a.slice(0,8).map(p=>'<div class=file>'+(p.type==='image'?'<img src="'+esc(p.url)+'">':p.type==='video'?'<video src="'+esc(p.url)+'" controls muted></video>':'<div style="aspect-ratio:1;display:grid;place-items:center;font-size:42px">📄</div>')+'<div class=meta>'+esc(p.filename)+'<br><a href="'+esc(p.download_url)+'" download>Download</a></div></div>').join('')}
el('sendFiles').onclick=()=>el('pick').click();el('receiveFiles').onclick=()=>el('received').scrollIntoView({behavior:'smooth'});el('sendTextAction').onclick=()=>el('textPanel').scrollIntoView({behavior:'smooth'});
el('pick').onchange=()=>{[...el('pick').files].forEach(f=>{let x=new XMLHttpRequest();x.open('POST','/upload?source=web&filename='+encodeURIComponent(f.name));x.onload=()=>load();x.onerror=()=>{};x.send(f)});el('pick').value=''};
function autoText(){let e=el('textInput');e.style.height='auto';e.style.height=Math.min(e.scrollHeight,210)+'px';e.style.overflowY=e.scrollHeight>210?'auto':'hidden'}el('textInput').oninput=autoText;
el('paste').onclick=async()=>{try{el('textInput').value=await navigator.clipboard.readText();autoText();el('textMsg').textContent='Pasted ✓'}catch(e){el('textMsg').textContent='Clipboard permission denied'}};
el('clearText').onclick=()=>{el('textInput').value='';autoText();el('textMsg').textContent=''};
el('sendText').onclick=async()=>{let t=el('textInput').value;if(!t.trim())return;let r=await fetch('/text',{method:'POST',headers:{'Content-Type':'text/plain; charset=utf-8'},body:t});el('textMsg').textContent=r.ok?'Sent to phone ✓':'Send failed';if(r.ok){el('textInput').value='';autoText();load()}};
el('receiveText').onclick=async()=>{let a=await (await fetch('/files?source=received&x='+Date.now())).json();let t=a.filter(x=>(x.filename||'').toLowerCase().endsWith('.txt'));el('textReceived').innerHTML=t.length?t.map(x=>'<a href="'+esc(x.url)+'" download>'+esc(x.filename)+'</a>').join('<br>'):'No received text yet'};
el('cl').onclick=()=>{el('modal').classList.add('hidden');el('view').innerHTML=''};setInterval(()=>{if(!el('app').classList.contains('hidden'))load()},4000);fetch('/api/session',{cache:'no-store'}).then(r=>r.json()).then(d=>{if(d.authorized){el('gate').classList.add('hidden');el('app').classList.remove('hidden');load()}}).catch(()=>{});
</script></body></html>"""
    }
}