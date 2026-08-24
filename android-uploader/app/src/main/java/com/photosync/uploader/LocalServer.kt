package com.photosync.uploader

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors

/**
 * Small dependency-free HTTP server used only by the Android local-server branch.
 * It deliberately starts on an unprivileged port and binds to all interfaces so
 * another device on the same reachable LAN/hotspot can open the advertised URL.
 */
class LocalServer(private val context: Context, private val port: Int = 18000) {
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
            serverSocket?.close()
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

    fun port(): Int = serverSocket?.localPort ?: port

    fun localIpv4(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList().asSequence()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress }
                .firstOrNull { !it.startsWith("127.") }
        } catch (_: SocketException) {
            null
        }
    }

    fun url(): String? = localIpv4()?.let { "http://$it:${port()}" }

    private fun acceptLoop() {
        while (running) {
            try {
                val socket = serverSocket?.accept() ?: break
                executor.execute { handle(socket) }
            } catch (_: Exception) {
                if (running) continue
                break
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use {
            socket.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
            val requestLine = reader.readLine() ?: return
            // Consume headers before writing the response.
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?') ?: "/"
            val body = when (path) {
                "/health" -> "{\"status\":\"ok\",\"server\":\"photosync-android\"}"
                "/api/info" -> "{\"server\":\"photosync-android\",\"port\":${port()},\"ip\":${json(localIpv4())}}"
                else -> page()
            }
            val type = if (body.startsWith("{")) "application/json; charset=utf-8" else "text/html; charset=utf-8"
            val bytes = body.toByteArray(Charsets.UTF_8)
            val out: OutputStream = socket.getOutputStream()
            val headers = "HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n"
            out.write(headers.toByteArray(Charsets.US_ASCII))
            out.write(bytes)
            out.flush()
        }
    }

    private fun page(): String {
        val address = url() ?: "Waiting for a local network address..."
        return """
            <!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'>
            <title>PHOTOSYNC Local Server</title>
            <style>body{font-family:system-ui;margin:32px;max-width:720px}code{padding:4px 8px;background:#eee;border-radius:6px}</style>
            </head><body><h1>PHOTOSYNC</h1><p>Android local server is running.</p>
            <p>Web address: <code>$address</code></p><p>Internet is not required for this local server.</p>
            <p>Health: <a href='/health'>/health</a></p></body></html>
        """.trimIndent()
    }

    private fun json(value: String?): String = if (value == null) "null" else "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
