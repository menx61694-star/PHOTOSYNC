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
            pin = generatePin()
            sessions.clear()
            failedAttempts.clear()
            serverSocket = ServerSocket(port, 50)
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

    fun localIpv4(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList().asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress }
            .firstOrNull { !it.startsWith("127.") }
    } catch (_: Exception) { null }

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

            val body: String
            val status: String
            when {
                path == "/api/pin" -> {
                    body = "{\"pin_required\":true,\"message\":\"Enter the PIN shown on the phone\"}"
                    status = "200 OK"
                }
                path == "/api/pair" && method == "POST" -> {
                    val supplied = query["pin"] ?: ""
                    val now = System.currentTimeMillis()
                    val attempts = failedAttempts.compute(clientKey) { _, old ->
                        val fresh = (old ?: mutableListOf()).filter { now - it < 60_000 }.toMutableList()
                        fresh
                    } ?: mutableListOf()
                    if (attempts.size >= maxAttemptsPerMinute) {
                        body = "{\"paired\":false,\"message\":\"Too many attempts; try again later\"}"
                        status = "429 Too Many Requests"
                    } else if (supplied.length == 6 && supplied == pin) {
                        attempts.clear()
                        val token = generateToken()
                        sessions[token] = now + sessionLifetimeMs
                        body = "{\"paired\":true,\"token\":\"$token\",\"expires_in_seconds\":1800}"
                        status = "200 OK"
                    } else {
                        attempts.add(now)
                        body = "{\"paired\":false,\"message\":\"Invalid PIN\"}"
                        status = "403 Forbidden"
                    }
                }
                path == "/api/session" -> {
                    val token = query["token"]
                    val ok = isAuthorized(token)
                    body = if (ok) "{\"authorized\":true,\"expires_in_seconds\":${((sessions[token]!! - System.currentTimeMillis()) / 1000).coerceAtLeast(0)}}" else "{\"authorized\":false}"
                    status = if (ok) "200 OK" else "401 Unauthorized"
                }
                path == "/health" -> {
                    body = "{\"status\":\"ok\",\"server\":\"photosync-android\"}"
                    status = "200 OK"
                }
                path == "/api/info" -> {
                    body = "{\"server\":\"photosync-android\",\"ip\":${json(localIpv4())},\"port\":$port,\"pin_required\":true}"
                    status = "200 OK"
                }
                else -> {
                    body = page()
                    status = "200 OK"
                }
            }
            val contentType = if (body.startsWith("{")) "application/json; charset=utf-8" else "text/html; charset=utf-8"
            val bytes = body.toByteArray(Charsets.UTF_8)
            val header = "HTTP/1.1 $status\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
            it.getOutputStream().use { out ->
                out.write(header.toByteArray(Charsets.US_ASCII))
                out.write(bytes)
                out.flush()
            }
        }
    }

    private fun parseQuery(query: String): Map<String, String> = query.split('&').mapNotNull {
        val p = it.split('=', limit = 2)
        if (p.size == 2) p[0] to p[1] else null
    }.toMap()

    private fun page(): String {
        val address = url() ?: "Waiting for a local network address"
        return """<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'><title>PHOTOSYNC</title><style>body{font-family:system-ui;margin:32px;max-width:720px}code{background:#eee;padding:4px 8px;border-radius:6px}.pin{font-size:28px;letter-spacing:5px}</style></head><body><h1>PHOTOSYNC</h1><p>Android local server is running.</p><p>Web address: <code>$address</code></p><p>Pairing PIN: <strong class=pin>$pin</strong></p><p>Internet is not required. Devices only need a reachable local network.</p><p><a href='/api/pin'>Pairing information</a></p></body></html>"""
    }

    private fun json(value: String?): String = if (value == null) "null" else "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
