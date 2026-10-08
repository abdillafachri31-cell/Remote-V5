package id.arunika.remote

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Embedded HTTP service bound exclusively to the device's Tailscale address.
 * WireGuard/Tailscale encrypts traffic; use only with both devices in the same tailnet.
 */
class PrivateHttpServer(
    private val service: RemoteAccessibilityService,
    private val address: Inet4Address,
    private val accessKey: String
) {
    companion object { const val PORT = 18765 }
    private val pool = Executors.newFixedThreadPool(4)
    private var server: ServerSocket? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false

    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(address, PORT), 12)
        server = s
        running = true
        Thread({
            while (running) {
                try {
                    val socket = s.accept()
                    if (!running) { socket.close(); break }
                    pool.execute { serve(socket) }
                } catch (_: Exception) { if (!running) break }
            }
        }, "Arunika-tailnet-server").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        pool.shutdownNow()
    }

    private fun secureEquals(actual: String): Boolean =
        MessageDigest.isEqual(accessKey.toByteArray(StandardCharsets.US_ASCII), actual.toByteArray(StandardCharsets.US_ASCII))

    private fun readLine(stream: InputStream, cap: Int = 8192): String? {
        val result = ByteArrayOutputStream()
        while (result.size() < cap) {
            val v = stream.read()
            if (v < 0) return null
            if (v == 10) return result.toString("ISO-8859-1").trimEnd('\r')
            result.write(v)
        }
        return null
    }

    private fun response(out: BufferedOutputStream, status: String, contentType: String, data: ByteArray) {
        val meta = "HTTP/1.1 $status\r\n" +
            "Content-Type: $contentType\r\nContent-Length: ${data.size}\r\n" +
            "Cache-Control: no-store, max-age=0\r\nX-Content-Type-Options: nosniff\r\n" +
            "X-Frame-Options: DENY\r\nReferrer-Policy: no-referrer\r\n" +
            "Content-Security-Policy: default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' blob:; connect-src 'self'; base-uri 'none'; form-action 'none'\r\n" +
            "Connection: close\r\n\r\n"
        out.write(meta.toByteArray(StandardCharsets.US_ASCII))
        out.write(data)
        out.flush()
    }
    private fun sendText(out: BufferedOutputStream, status: String, message: String) =
        response(out, status, "text/plain; charset=utf-8", message.toByteArray(StandardCharsets.UTF_8))

    private fun asset(name: String): ByteArray = service.assets.open(name).use { it.readBytes() }

    private fun serve(socket: Socket) {
        try {
            socket.use { sock ->
                sock.soTimeout = 7000
                val input = BufferedInputStream(sock.getInputStream())
                val output = BufferedOutputStream(sock.getOutputStream())
                val head = readLine(input) ?: return
                val parts = head.split(" ")
                if (parts.size != 3 || parts[2] != "HTTP/1.1") {
                    sendText(output, "400 Bad Request", "Invalid request"); return
                }
                val method = parts[0]
                val path = parts[1]
                var length = 0
                var key = ""
                var total = 0
                var foundHeaderEnd = false
                while (total < 8192) {
                    val line = readLine(input) ?: break
                    total += line.length + 2
                    if (line.isEmpty()) { foundHeaderEnd = true; break }
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        val name = line.substring(0, colon).trim().lowercase(Locale.ROOT)
                        val value = line.substring(colon + 1).trim()
                        if (name == "x-arunika-key") key = value
                        if (name == "content-length") length = value.toIntOrNull() ?: -1
                    }
                }
                if (!foundHeaderEnd || length !in 0..2048) {
                    sendText(output, "400 Bad Request", "Invalid headers"); return
                }
                if (!running) { sendText(output, "503 Service Unavailable", "Remote inactive"); return }
                if (method == "GET" && (path == "/" || path == "/index.html" || path == "/style.css" || path == "/app.js")) {
                    val resource = if (path == "/") "index.html" else path.drop(1)
                    val mime = if (resource.endsWith(".js")) "text/javascript" else if (resource.endsWith(".css")) "text/css" else "text/html"
                    response(output, "200 OK", "$mime; charset=utf-8", asset(resource))
                    return
                }
                if (!secureEquals(key)) { sendText(output, "401 Unauthorized", "Kode akses salah"); return }
                when {
                    method == "GET" && path == "/frame" -> {
                        service.recordViewer()
                        val frame = service.latestFrame()
                        if (frame == null) sendText(output, "503 Service Unavailable", "Menunggu tampilan layar")
                        else response(output, "200 OK", "image/jpeg", frame)
                    }
                    method == "POST" && path == "/action" -> {
                        if (length == 0) { sendText(output, "400 Bad Request", "Empty request"); return }
                        val bytes = ByteArray(length)
                        var read = 0
                        while (read < length) {
                            val got = input.read(bytes, read, length - read)
                            if (got < 0) { sendText(output, "400 Bad Request", "Incomplete body"); return }
                            read += got
                        }
                        val payload = try { JSONObject(String(bytes, StandardCharsets.UTF_8)) } catch (_: Exception) { null }
                        if (payload == null || !service.validAction(payload)) {
                            sendText(output, "400 Bad Request", "Action invalid"); return
                        }
                        main.post { if (running) service.performRemoteAction(payload) }
                        response(output, "200 OK", "application/json", "{\"ok\":true}".toByteArray())
                    }
                    else -> sendText(output, "404 Not Found", "Not found")
                }
            }
        } catch (_: Exception) { /* socket closed or client timed out */ }
    }
}
