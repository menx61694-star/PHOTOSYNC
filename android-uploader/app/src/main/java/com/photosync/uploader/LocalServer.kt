package com.photosync.uploader

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class LocalServer(private val port: Int = 18000) {
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()

    fun start(): Boolean {
        if (running) return true
        return try {
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
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    fun isRunning(): Boolean = running && serverSocket?.isClosed == false

    fun localIpv4(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList().asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress }
            .firstOrNull { !it.startsWith("127.") }
    } catch (_: Exception) { null }

    fun url(): String? = localIpv4()?.let { "http://$it:$port" }

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
            val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?') ?: "/"
            val body = when (path) {
                "/health" -> "{\"status\":\"ok\",\"server\":\"photosync-android\"}"
                "/api/info" -> "{\"server\":\"photosync-android\",\"ip\":${json(localIpv4())},\"port\":$port}"
                else -> page()
            }
            val contentType = if (body.startsWith("{")) "application/json; charset=utf-8" else "text/html; charset=utf-8"
            val bytes = body.toByteArray(Charsets.UTF_8)
            val header = "HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
            it.getOutputStream().use { out ->
                out.write(header.toByteArray(Charsets.US_ASCII))
                out.write(bytes)
                out.flush()
            }
        }
    }

    private fun page(): String {
        val address = url() ?: "Waiting for a local network address"
        return """<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'><title>PHOTOSYNC</title><style>body{font-family:system-ui;margin:32px;max-width:720px}code{background:#eee;padding:4px 8px;border-radius:6px}</style></head><body><h1>PHOTOSYNC</h1><p>Android local server is running.</p><p>Web address: <code>$address</code></p><p>Internet is not required. Devices only need a reachable local network.</p><p><a href='/health'>Health check</a></p></body></html>"""
    }

    private fun json(value: String?): String = if (value == null) "null" else "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
