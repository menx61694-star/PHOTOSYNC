package com.photosync.uploader

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class LocalServer(private val port: Int = 18000) {
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val random = SecureRandom()
    @Volatile private var pin: String = generatePin()
    private val sessions = ConcurrentHashMap<String, Long>()
    private val failedAttempts = ConcurrentHashMap<String, MutableList<Long>>()
    private val sessionLifetimeMs = 30 * 60 * 1000L
    private val maxAttemptsPerMinute = 5

    fun start(): Boolean {
        if (running) return true
        return try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(java.net.InetSocketAddress(port), 50)
            serverSocket = socket
            pin = generatePin()
            sessions.clear()
            failedAttempts.clear()
            running = true
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
        running = false
        sessions.clear()
        failedAttempts.clear()
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
            } catch (_: Exception) {
                if (!running) break
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use {
            try {
                it.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.US_ASCII))
                val requestLine = reader.readLine() ?: return
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0) ?: ""
                val rawPath = parts.getOrNull(1) ?: "/"
                val path = rawPath.substringBefore('?')
                val query = parseQuery(rawPath.substringAfter('?', ""))
                val clientKey = socket.inetAddress?.hostAddress ?: "unknown"

                val response = when {
                    path == "/api/pin" -> responseJson("{\"pin_required\":true,\"message\":\"Enter the PIN shown in the PhotoSync app\"}")
                    path == "/api/pair" && method == "POST" -> pair(clientKey, query["pin"] ?: "")
                    path == "/api/session" -> {
                        val token = query["token"]
                        val ok = isAuthorized(token)
                        if (ok) responseJson("{\"authorized\":true,\"expires_in_seconds\":${((sessions[token]!! - System.currentTimeMillis()) / 1000).coerceAtLeast(0)}}")
                        else responseJson("{\"authorized\":false}", "401 Unauthorized")
                    }
                    path == "/health" -> responseJson("{\"status\":\"ok\",\"server\":\"photosync-android\"}")
                    path == "/api/info" -> responseJson("{\"server\":\"photosync-android\",\"ip\":${json(localIpv4())},\"port\":$port,\"pin_required\":true}")
                    else -> Response("200 OK", "text/html; charset=utf-8", page())
                }

                val bytes = response.body.toByteArray(Charsets.UTF_8)
                val header = "HTTP/1.1 ${response.status}\r\nContent-Type: ${response.contentType}\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
                it.getOutputStream().use { out ->
                    out.write(header.toByteArray(Charsets.US_ASCII))
                    out.write(bytes)
                    out.flush()
                }
            } catch (_: Exception) {
                // Never allow a malformed/disconnected browser socket to crash the app.
            }
        }
    }

    private data class Response(val status: String, val contentType: String, val body: String)
    private fun responseJson(body: String, status: String = "200 OK") = Response(status, "application/json; charset=utf-8", body)

    private fun pair(clientKey: String, supplied: String): Response {
        val now = System.currentTimeMillis()
        val attempts = failedAttempts.compute(clientKey) { _, old ->
            (old ?: mutableListOf()).filter { now - it < 60_000 }.toMutableList()
        } ?: mutableListOf()
        if (attempts.size >= maxAttemptsPerMinute) return responseJson("{\"paired\":false,\"message\":\"Too many attempts; try again later\"}", "429 Too Many Requests")
        if (supplied.length == 6 && supplied == pin) {
            attempts.clear()
            val token = generateToken()
            sessions[token] = now + sessionLifetimeMs
            return responseJson("{\"paired\":true,\"token\":\"$token\",\"expires_in_seconds\":1800}")
        }
        attempts.add(now)
        return responseJson("{\"paired\":false,\"message\":\"Invalid PIN\"}", "403 Forbidden")
    }

    private fun parseQuery(query: String): Map<String, String> = query.split('&').mapNotNull {
        val p = it.split('=', limit = 2)
        if (p.size == 2) p[0] to java.net.URLDecoder.decode(p[1], "UTF-8") else null
    }.toMap()

    private fun page(): String {
        val address = url() ?: "Waiting for a local network address"
        return """<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'><title>PHOTOSYNC</title><style>body{font-family:system-ui;margin:32px;max-width:720px}input{font-size:22px;padding:12px;width:180px;letter-spacing:5px}button{font-size:18px;padding:12px 20px;margin-left:8px}.ok{padding:12px;margin-top:16px}.pin{font-size:28px;letter-spacing:5px}</style></head><body><h1>PHOTOSYNC</h1><p>Android local server is running.</p><p>Web address: <code>$address</code></p><hr><h2>Pair this browser</h2><p>Enter the 6-digit PIN shown in the PhotoSync app.</p><input id='pin' inputmode='numeric' maxlength='6' autocomplete='one-time-code' placeholder='PIN'><button onclick='pair()'>Pair</button><div id='result'></div><script>async function pair(){const p=document.getElementById('pin').value.trim();const r=document.getElementById('result');if(!/^\\d{6}$/.test(p)){r.textContent='Enter a 6-digit PIN';return;}try{const x=await fetch('/api/pair?pin='+encodeURIComponent(p),{method:'POST'});const d=await x.json();r.textContent=d.message+(d.token?' ✓':'');if(d.token)localStorage.setItem('photosync_token',d.token);}catch(e){r.textContent='Connection failed';}}</script></body></html>"""
    }

    private fun json(value: String?): String = if (value == null) "null" else "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
