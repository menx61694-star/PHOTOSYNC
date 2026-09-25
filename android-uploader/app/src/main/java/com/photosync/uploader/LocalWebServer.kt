package com.photosync.uploader

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.io.ByteArrayOutputStream
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
    @Volatile private var appToken = generateToken()
    private val sessions = ConcurrentHashMap<String, Long>()
    private data class WebClient(val token: String, val ip: String, val connectedAt: Long, @Volatile var lastSeen: Long)
    private val webClients = ConcurrentHashMap<String, WebClient>()
    private val attempts = ConcurrentHashMap<String, MutableList<Long>>()
    private data class PairRequest(val id: String, val ip: String, val createdAt: Long, @Volatile var state: String = "pending", @Volatile var sessionToken: String? = null)
    private val pairRequests = ConcurrentHashMap<String, PairRequest>()
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
            // Keep the PIN stable across stop/start. Explicit refreshPin() rotates it.
            sessions.clear(); webClients.clear(); attempts.clear(); pairRequests.clear(); running = true
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
        sessions.clear(); webClients.clear(); attempts.clear(); pairRequests.clear()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        try { acceptThread?.interrupt() } catch (_: Throwable) {}
        acceptThread = null
        executor?.shutdownNow()
        executor = null
    }

    fun refreshPin(): String { pin = generatePin(); appToken = generateToken(); sessions.clear(); webClients.clear(); attempts.clear(); pairRequests.clear(); return pin }
    fun isRunning() = running && serverSocket?.isClosed == false
    fun currentPin() = pin
    fun localAppToken() = appToken
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
                val appTrusted = headers["x-photosync-local-token"] == appToken
                if (token != null && isAuthorized(token)) webClients[token]?.lastSeen = System.currentTimeMillis()
                val authorized = isAuthorized(token) || appTrusted
                val response = when {
                    path == "/" || path == "/dashboard" || path == "/dashboard/" -> html(page())
                    path == "/health" && method == "GET" -> json("{\"ok\":true,\"running\":"+isRunning()+"}")
                    path == "/api/pair" && method == "POST" -> pair(clientIp, query["pin"] ?: "")
                    path == "/api/pair/status" && method == "GET" -> pairStatus(query["id"])
                    path == "/api/pair/requests" && method == "GET" && appTrusted -> pairRequestsResponse()
                    path == "/api/pair/approve" && method == "POST" && appTrusted -> if (approvePairRequest(query["id"] ?: "")) json("{\"ok\":true}") else json("{\"ok\":false}", "404 Not Found")
                    path == "/api/pair/reject" && method == "POST" && appTrusted -> if (rejectPairRequest(query["id"] ?: "")) json("{\"ok\":true}") else json("{\"ok\":false}", "404 Not Found")
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
                    path == "/api/qr" && method == "GET" -> qr()
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
    private fun Response.withCookie(cookie: String) = copy(cookie = cookie)
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
            pairRequests.values.removeIf { it.ip == ip && now - it.createdAt > 5_000 }
            val id = generateToken().take(16)
            pairRequests[id] = PairRequest(id, ip, now)
            return json(JSONObject().apply {
                put("paired", false); put("pending", true); put("request_id", id)
                put("message", "Pairing request sent to phone")
            }.toString(), "202 Accepted")
        }
        list.add(now)
        return json("{\"paired\":false,\"message\":\"Invalid PIN\"}", "403 Forbidden")
    }

    data class PairRequestInfo(val id: String, val ip: String, val createdAt: Long)
    fun pendingPairRequests(): List<PairRequestInfo> =
        pairRequests.values.filter { it.state == "pending" && System.currentTimeMillis() - it.createdAt < 120_000 }
            .sortedByDescending { it.createdAt }.map { PairRequestInfo(it.id, it.ip, it.createdAt) }

    fun approvePairRequest(id: String): Boolean {
        val req = pairRequests[id] ?: return false
        if (req.state != "pending" || System.currentTimeMillis() - req.createdAt > 120_000) return false
        val now = System.currentTimeMillis(); val t = generateToken()
        req.sessionToken = t; req.state = "approved"
        sessions[t] = now + lifetime; webClients[t] = WebClient(t, req.ip, now, now)
        return true
    }

    fun rejectPairRequest(id: String): Boolean {
        val req = pairRequests[id] ?: return false
        if (req.state != "pending") return false
        req.state = "rejected"; return true
    }

    private fun pairRequestsResponse(): Response = json(JSONArray(pendingPairRequests().map {
        JSONObject().apply { put("id", it.id); put("ip", it.ip); put("created_at", it.createdAt) }
    }).toString())

    private fun pairStatus(id: String?): Response {
        val req = if (id.isNullOrBlank()) null else pairRequests[id]
        if (req == null) return json("{\"paired\":false,\"pending\":false,\"message\":\"Pairing request not found\"}", "404 Not Found")
        return when (req.state) {
            "approved" -> {
                val t = req.sessionToken ?: return json("{\"paired\":false,\"pending\":true}")
                pairRequests.remove(req.id)
                json("{\"paired\":true,\"pending\":false,\"expires_in_seconds\":1800}").withCookie("photosync_session=$t; Max-Age=1800; Path=/; HttpOnly; SameSite=Lax")
            }
            "rejected" -> { pairRequests.remove(req.id); json("{\"paired\":false,\"pending\":false,\"message\":\"Pairing request rejected\"}", "403 Forbidden") }
            else -> json("{\"paired\":false,\"pending\":true}")
        }
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
            val contentType = when (ext) {
                "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"; "bmp" -> "image/bmp"
                "mp4" -> "video/mp4"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"; "mov" -> "video/quicktime"; "avi" -> "video/x-msvideo"
                "pdf" -> "application/pdf"; "txt" -> "text/plain"; else -> "application/octet-stream"
            }
            put("filename", name); put("stored_filename", file.name); put("source", source); put("url", "/files/$source/$encoded"); put("download_url", "/files/$source/$encoded?download=1"); put("size", file.length()); put("type", type); put("content_type", contentType)
        }
    }

    private fun fileArray(source: String?): JSONArray {
        val dir = if (source == "app") uploads else downloads
        val a = JSONArray()
        dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.forEach {
            a.put(fileJson(it, if (dir == uploads) "app" else "received"))
        }
        return a
    }

    private fun files(source: String?): Response = json(fileArray(source).toString())

    /** Direct filesystem listing for the Android app UI; avoids a self-HTTP/auth round trip. */
    fun listFilesForApp(source: String): JSONArray = fileArray(source)

    /** Direct file lookup for Android-local preview; web clients still use the HTTP route. */
    fun localFileForApp(source: String, storedName: String): File? {
        val dir = if (source == "app") uploads else downloads
        val safe = safeName(storedName)
        return dir.listFiles()?.firstOrNull { it.isFile && it.name == safe }
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
            a.put(JSONObject().apply {
                put("id", c.id)
                put("ip", c.ip)
                put("connected_at", c.connectedAt)
                put("last_seen", c.lastSeen)
            })
        }
        return json(JSONObject().apply { put("clients", a) }.toString())
    }

    private fun disconnectWebClientResponse(id: String?): Response {
        if (id.isNullOrBlank() || !disconnectWebClient(id)) return json("{\"ok\":false,\"detail\":\"Web client not found\"}", "404 Not Found")
        return json("{\"ok\":true}")
    }

    private fun qr(): Response {
        return try {
            val base = url() ?: return json("{\"detail\":\"Server address unavailable\"}", "503 Service Unavailable")
            val target = base + "/dashboard?pin=" + URLEncoder.encode(pin, "UTF-8")
            val matrix = MultiFormatWriter().encode(target, BarcodeFormat.QR_CODE, 512, 512)
            val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            for (x in 0 until 512) for (y in 0 until 512) bitmap.setPixel(x, y, if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            json(JSONObject().apply {
                put("url", target)
                put("data", "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
            }.toString())
        } catch (e: Throwable) {
            json(JSONObject().apply { put("detail", e.message ?: "QR generation failed") }.toString(), "500 Internal Server Error")
        }
    }

    private fun info(): Response {
        val all = (uploads.listFiles()?.filter { it.isFile } ?: emptyList()) + (downloads.listFiles()?.filter { it.isFile } ?: emptyList())
        return json("{\"server\":\"photosync-android\",\"ip\":${JSONObject.quote(localIpv4() ?: "")},\"port\":$port,\"pin_required\":true,\"running\":${isRunning()},\"used_bytes\":${all.sumOf { it.length()}},\"free_bytes\":${root.usableSpace},\"total_bytes\":${root.totalSpace}}")
    }

    private fun page(): String {
        val address = url() ?: "Waiting for a local network address"
        return """<!doctype html><html><head><meta name=viewport content="width=device-width,initial-scale=1"><meta http-equiv="Cache-Control" content="no-store"><title>PhotoSync Local</title>
<style>*{box-sizing:border-box}body{margin:0;font-family:system-ui,-apple-system,Segoe UI,sans-serif;background:#07101b;color:#f4f7ff}.app{min-height:100vh;display:flex}.side{width:230px;flex:none;background:#09121e;border-right:1px solid #1d2b40;padding:22px 14px;display:flex;flex-direction:column}.brand{padding:8px 14px 25px;font-size:28px;font-weight:800}.brand b{color:#6b8dff}.tag{font-size:10px;color:#8191aa}.nav{display:grid;gap:6px}.nav div{padding:13px 14px;border-radius:12px;color:#dce6f8}.nav .active{background:linear-gradient(90deg,#263e9c,#24256c)}.sideStatus{margin-top:auto;border:1px solid #29405d;background:#0f1c2d;border-radius:14px;padding:13px}.dot{display:inline-block;width:12px;height:12px;border-radius:50%;background:#1ee39a;margin-right:8px}.main{flex:1;padding:16px;overflow:auto}.top{display:flex;gap:10px;align-items:center;margin-bottom:16px}.server,.pinTop{border:1px solid #2a405d;background:#0d1928;border-radius:14px;padding:11px 14px}.server{min-width:310px}.server code{font-size:12px;color:#bdcce0}.pinTop{display:flex;gap:7px;margin-left:auto}.pinTop input{width:175px;background:#091421;border:1px solid #31445f;color:#fff;border-radius:9px;padding:10px}.btn{border:1px solid #3c5a7c;background:linear-gradient(135deg,#2c55ff,#6647ef);color:#fff;border-radius:10px;padding:10px 15px;font-weight:700;cursor:pointer}.btn.alt{background:#132137}.btn.danger{background:#361b28}.hero{padding:27px 32px;border-radius:20px;background:linear-gradient(105deg,#7148f7,#1c55c9 72%,#18398d);min-height:145px}.hero h1{margin:0 0 6px;font-size:28px}.hero p{margin:0;color:#e9efff}.actions{display:grid;grid-template-columns:repeat(3,1fr);gap:14px;margin:15px 0}.action{border:1px solid #30435d;background:#111d2d;border-radius:18px;padding:17px;display:flex;gap:12px;align-items:center}.action:nth-child(1){background:linear-gradient(135deg,#3e208e,#1d348d)}.action:nth-child(2){background:linear-gradient(135deg,#075b58,#0a3d4c)}.action:nth-child(3){background:#142031}.ico{width:50px;height:50px;border-radius:14px;background:#ffffff25;display:grid;place-items:center;font-size:25px}.action h3{margin:0 0 4px}.action p{margin:0;color:#a4b1c5;font-size:12px}.cols{display:grid;grid-template-columns:1fr 1fr;gap:14px}.panel{border:1px solid #2a3d56;background:#0c1725;border-radius:18px;padding:14px}.head{display:flex;justify-content:space-between;align-items:center;margin-bottom:11px}.head h2{margin:0;font-size:17px}.count{background:#172840;border:1px solid #344a69;border-radius:15px;padding:5px 9px;font-size:11px;color:#b5c8e3}.empty{min-height:130px;border-radius:14px;background:#0f1b2b;border:1px solid #1c3048;display:grid;place-items:center;text-align:center;color:#8798b1;padding:18px}.files{display:grid;grid-template-columns:repeat(2,1fr);gap:10px}.file{background:#101c2b;border:1px solid #243750;border-radius:12px;overflow:hidden;cursor:pointer}.fileIcon{width:100%;aspect-ratio:1;display:grid;place-items:center;font-size:42px;color:#70b7ff}.file img,.file video{width:100%;aspect-ratio:1;object-fit:cover}.meta{padding:8px;font-size:11px;color:#b8c5d8}.meta a{color:#70b7ff}.text{margin-top:14px}.text textarea{width:100%;min-height:45px;max-height:210px;background:#091421;border:1px solid #2b405b;color:#fff;border-radius:11px;padding:11px;resize:none}.row{display:flex;gap:8px;flex-wrap:wrap;margin-top:9px}.gate{max-width:480px;margin:8vh auto;background:#0c1725;border:1px solid #2a3d56;border-radius:20px;padding:28px}.gate input{background:#091421;color:#fff;border:1px solid #31445f;border-radius:9px;padding:11px;width:150px;letter-spacing:4px}.modal{position:fixed;inset:0;background:#02060de8;display:flex;flex-direction:column;z-index:50}.modalTop{padding:12px;display:flex;justify-content:center;gap:8px}.viewer{flex:1;display:flex;align-items:center;justify-content:center;overflow:auto}.viewer img{max-width:92vw;max-height:85vh}.viewer video{max-width:92vw;max-height:85vh}.viewer iframe{width:92vw;height:85vh;border:0;background:#fff}.hidden{display:none!important}@media(max-width:850px){.side{width:170px}.actions,.cols{grid-template-columns:1fr}.top{flex-wrap:wrap}.pinTop{margin-left:0;width:100%}.pinTop input{flex:1}.server{min-width:0;flex:1}}</style></head><body>
<div id=gate class=gate><div class=brand>photo<b>sync</b><div class=tag>Share. Sync. Simple.</div></div><h2>Local Server</h2><p>Connect your browser to this phone.</p><p class=tag>Address: <code>${address}</code></p><div class=row><input id=pin inputmode=numeric maxlength=6 placeholder="PIN"><button class=btn id=pair>Pair Device</button></div><p id=msg class=tag></p></div>
<div id=app class="app hidden"><aside class=side><div class=brand>photo<b>sync</b><div class=tag>Share. Sync. Simple.</div></div><div class=nav><div class=active data-target="dashboard" onclick="navigate('dashboard')">⌂　Dashboard</div><div data-target="send" onclick="navigate('send')">➤　Send Files</div><div data-target="receive" onclick="navigate('receive')">⇩　Receive Files</div><div data-target="text" onclick="navigate('text')">▢　Text Sync</div><div data-target="clients" onclick="navigate('clients')">▱　Web Clients</div><div data-target="settings" onclick="navigate('settings')">⚙　Settings</div></div><div class=sideStatus><span class=dot></span><b>Server Running</b><div class=tag>Ready to receive files</div></div></aside>
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
let pairPoll=null;
async function pair(passed){
  let p=(passed||el('pin').value).trim();
  if(!/^\d{6}$/.test(p)){el('msg').textContent='Enter a 6-digit PIN';return false}
  let r=await fetch('/api/pair?pin='+encodeURIComponent(p),{method:'POST',cache:'no-store',credentials:'include'});
  let d=await r.json().catch(()=>({}));
  if(!r.ok && r.status!==202){el('msg').textContent=d.message||'Pairing failed';return false}
  if(d.pending){
    el('msg').textContent='Pairing request sent to phone. Approve it on the phone…';
    if(pairPoll)clearInterval(pairPoll);
    pairPoll=setInterval(async()=>{
      try{
        let sr=await fetch('/api/pair/status?id='+encodeURIComponent(d.request_id),{cache:'no-store',credentials:'include'});
        let sd=await sr.json().catch(()=>({}));
        if(sd.paired){
          clearInterval(pairPoll);pairPoll=null;
          el('msg').textContent='Paired ✓';
          el('gate').classList.add('hidden');el('app').classList.remove('hidden');load();
        }else if(sr.status===403){
          clearInterval(pairPoll);pairPoll=null;
          el('msg').textContent=sd.message||'Pairing rejected';
        }else if(sr.status===404){
          clearInterval(pairPoll);pairPoll=null;
          el('msg').textContent='Pairing request expired';
        }
      }catch(e){}
    },1000);
    return false
  }
  el('gate').classList.add('hidden');el('app').classList.remove('hidden');load();return true
}
el('pair').onclick=()=>pair();el('pairTop').onclick=()=>pair(el('pinTop').value);el('pin').onkeydown=e=>{if(e.key==='Enter')pair()};async function copyText(v){try{if(navigator.clipboard&&window.isSecureContext){await navigator.clipboard.writeText(v)}else{let t=document.createElement('textarea');t.value=v;t.style.position='fixed';t.style.opacity='0';document.body.appendChild(t);t.select();document.execCommand('copy');t.remove()}el('textMsg').textContent='Copied ✓'}catch(e){el('textMsg').textContent='Copy failed'}}
el('copy').onclick=()=>copyText(location.origin);el('qr').onclick=showQr;
async function jsonResponse(response,name){
  const body=await response.text();
  try{return JSON.parse(body)}
  catch(e){throw Error(name+' returned invalid JSON: '+body.slice(0,180))}
}
async function load(){
  try{
    let rs=await Promise.all([
      fetch('/files?source=app&x='+Date.now(),{cache:'no-store',credentials:'include'}),
      fetch('/files?source=received&x='+Date.now(),{cache:'no-store',credentials:'include'}),
      fetch('/api/web-clients?x='+Date.now(),{cache:'no-store',credentials:'include'})
    ]);
    if(rs.some(r=>r.status===401)){
      el('app').classList.add('hidden');el('gate').classList.remove('hidden');return
    }
    if(rs.some(r=>!r.ok)){throw Error('File list request failed ('+rs.map(r=>r.status).join('/')+')')}
    let a=await jsonResponse(rs[0],'Sent files'),
        b=await jsonResponse(rs[1],'Received files'),
        c=await jsonResponse(rs[2],'Web clients');
    render('sent',a);render('received',b);
    const clients=Array.isArray(c)?c:(Array.isArray(c.clients)?c.clients:[]);
    el('clientCount').textContent=clients.length+' client'+(clients.length===1?'':'s');
    el('clients').innerHTML=clients.length?clients.map(x=>'<div style="padding:10px;text-align:left;border-bottom:1px solid #263750">💻 <b>'+esc(x.ip)+'</b><br><span class=tag>Connected '+new Date(x.connected_at).toLocaleTimeString()+'</span><button class="btn danger" style="float:right;padding:6px 10px" onclick="disconnectClient(\\''+esc(x.id)+'\\')">Disconnect</button></div>').join(''):'No web clients connected.'
  }catch(e){
    el('clients').textContent='Unable to refresh files: '+(e.message||'unknown error')
  }
}
async function disconnectClient(id){await fetch('/api/web-clients/disconnect?id='+encodeURIComponent(id),{method:'POST',cache:'no-store'});load()}
let zoom=1;
function formatBytes(n){if(!n)return '0 B';let u=['B','KB','MB','GB'];let i=Math.min(Math.floor(Math.log(n)/Math.log(1024)),3);return (n/Math.pow(1024,i)).toFixed(i?1:0)+' '+u[i]}
function render(id,a){let g=el(id);if(!a.length){g.innerHTML='<div class=empty style="grid-column:1/-1;min-height:120px">No files yet.</div>';return}g.innerHTML=a.map(p=>{let v=p.type==='image'?'<img src="'+esc(p.url)+'" alt="">':p.type==='video'?'<video src="'+esc(p.url)+'" controls preload="metadata"></video>':p.type==='pdf'?'<div class=fileIcon>PDF</div>':'<div class=fileIcon>📄</div>';return '<div class=file onclick="openViewer(\''+esc(p.url)+'\',\''+esc(p.type)+'\',\''+esc(p.filename)+'\')">'+v+'<div class=meta>'+esc(p.filename)+'<br><span>'+formatBytes(p.size)+'</span><br><a href="'+esc(p.download_url)+'" download onclick="event.stopPropagation()">Download</a></div></div>'}).join('')}
function openViewer(url,type,name){zoom=1;el('dl').href=url;el('dl').download=name;el('dl').classList.remove('hidden');el('view').dataset.type=type;el('view').innerHTML=type==='image'?'<img id="viewerMedia" src="'+esc(url)+'">':type==='video'?'<video id="viewerMedia" src="'+esc(url)+'" controls autoplay></video>':type==='pdf'?'<iframe id="viewerMedia" src="'+esc(url)+'"></iframe>':'<div style="text-align:center"><div class=fileIcon>📄</div><h3>'+esc(name)+'</h3><a class="btn" href="'+esc(url)+'" download>Download File</a></div>';el('modal').classList.remove('hidden');applyZoom()}
function applyZoom(){let v=document.getElementById('viewerMedia');if(v&&el('view').dataset.type!=='pdf')v.style.transform='scale('+zoom+')';el('zr').textContent=Math.round(zoom*100)+'%'}
el('zo').onclick=()=>{zoom=Math.max(.25,zoom-.25);applyZoom()};el('zi').onclick=()=>{zoom=Math.min(3,zoom+.25);applyZoom()};el('zr').onclick=()=>{zoom=1;applyZoom()};
async function showQr(){try{let r=await fetch('/api/qr?x='+Date.now(),{cache:'no-store'}),d=await r.json();if(!r.ok)throw Error(d.detail||'QR failed');el('dl').classList.add('hidden');el('view').innerHTML='<div style="text-align:center"><img style="width:min(78vw,512px);height:min(78vw,512px);background:#fff;padding:14px;border-radius:18px" src="'+d.data+'"><h3>Scan to pair this phone</h3><p class=tag>PIN is included in the QR</p><button class="btn" onclick="copyText(\''+esc(d.url)+'\')">Copy Pairing URL</button></div>';el('modal').classList.remove('hidden')}catch(e){el('textMsg').textContent=e.message||'QR generation failed'}}
function navigate(target){document.querySelectorAll('.nav div').forEach(x=>x.classList.remove('active'));let item=[...document.querySelectorAll('.nav div')].find(x=>x.dataset.target===target);if(item)item.classList.add('active');if(target==='send')el('pick').click();else if(target==='receive'){load();el('sent').scrollIntoView({behavior:'smooth'});}else if(target==='text')el('textPanel').scrollIntoView({behavior:'smooth'});else if(target==='clients')el('clients').scrollIntoView({behavior:'smooth'});else if(target==='settings')showSettings();else window.scrollTo({top:0,behavior:'smooth'})}
el('sendFiles').onclick=()=>el('pick').click();el('receiveFiles').onclick=()=>{load();el('sent').scrollIntoView({behavior:'smooth'})};el('sendTextAction').onclick=()=>navigate('text');
el('pick').onchange=()=>{const fs=[...el('pick').files];if(!fs.length)return;let pending=fs.length,ok=0,failed=0;fs.forEach(f=>{let x=new XMLHttpRequest();x.open('POST','/upload?source=web&filename='+encodeURIComponent(f.name));x.upload.onprogress=e=>{if(e.lengthComputable)el('textMsg').textContent='Uploading '+f.name+' '+Math.round(e.loaded*100/e.total)+'%'};x.onload=()=>{if(x.status>=200&&x.status<300)ok++;else failed++;pending--;if(pending===0){el('textMsg').textContent=failed?'Uploaded '+ok+'; failed '+failed:'Uploaded '+ok+' file'+(ok===1?'':'s')+' ✓';load()}};x.onerror=()=>{failed++;pending--;if(pending===0){el('textMsg').textContent='Upload failed';load()}};x.send(f)});el('pick').value=''};
function autoText(){let e=el('textInput');e.style.height='auto';e.style.height=Math.min(e.scrollHeight,210)+'px';e.style.overflowY=e.scrollHeight>210?'auto':'hidden'}el('textInput').oninput=autoText;
el('paste').onclick=async()=>{try{el('textInput').value=await navigator.clipboard.readText();autoText();el('textMsg').textContent='Pasted ✓'}catch(e){el('textMsg').textContent='Clipboard permission denied'}};
el('clearText').onclick=()=>{el('textInput').value='';autoText();el('textMsg').textContent=''};
el('sendText').onclick=async()=>{let t=el('textInput').value;if(!t.trim())return;let r=await fetch('/text',{method:'POST',headers:{'Content-Type':'text/plain; charset=utf-8'},body:t});el('textMsg').textContent=r.ok?'Sent to phone ✓':'Send failed';if(r.ok){el('textInput').value='';autoText();load()}};
el('receiveText').onclick=async()=>{let a=await (await fetch('/files?source=received&x='+Date.now())).json();let t=a.filter(x=>(x.filename||'').toLowerCase().endsWith('.txt'));el('textReceived').innerHTML=t.length?t.map(x=>'<a href="'+esc(x.url)+'" download>'+esc(x.filename)+'</a>').join('<br>'):'No received text yet'};
el('cl').onclick=()=>{el('modal').classList.add('hidden');el('view').innerHTML='';el('dl').classList.remove('hidden')};
async function showSettings(){let r=await fetch('/api/info?x='+Date.now(),{cache:'no-store'}),d=await r.json();el('dl').classList.add('hidden');el('view').innerHTML='<div style="max-width:520px;width:90%;text-align:left;background:#0c1725;padding:24px;border-radius:18px"><h2>⚙ Server Settings</h2><p>Address: <code>'+esc(d.ip)+':'+d.port+'</code></p><p>Used: '+formatBytes(d.used_bytes)+'<br>Free: '+formatBytes(d.free_bytes)+'<br>Total: '+formatBytes(d.total_bytes)+'</p><div class=row><button class="btn alt" onclick="copyText(location.origin)">Copy Address</button><button class="btn danger" onclick="logout()">Logout</button><button class="btn" onclick="el(\'cl\').click()">Close</button></div></div>';el('modal').classList.remove('hidden')}
async function logout(){await fetch('/api/logout',{method:'POST'});location.reload()}
const qp=new URLSearchParams(location.search);if(qp.get('pin')){el('pin').value=qp.get('pin');pair(qp.get('pin'))}
setInterval(()=>{if(!el('app').classList.contains('hidden'))load()},4000);fetch('/api/session',{cache:'no-store'}).then(r=>r.json()).then(d=>{if(d.authorized){el('gate').classList.add('hidden');el('app').classList.remove('hidden');load()}}).catch(()=>{});
</script></body></html>"""
    }
}