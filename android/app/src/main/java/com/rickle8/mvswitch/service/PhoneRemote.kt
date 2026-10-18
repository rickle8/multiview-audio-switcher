package com.rickle8.mvswitch.service

import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Control from your own phone. The camera phone sits on its stand facing the TV where you
 * can't easily touch it, so it serves a control page on your home Wi-Fi: the boxes, "It's
 * an ad" / "It's the game", where the sound is, start, stop, pause, the camera's view and the
 * diagnostics. Open it on your phone by scanning the code on the camera phone (Camera setup or
 * Settings). Only a device with the link's key can use it.
 */
object PhoneRemote {
    private const val FIRST_PORT = 8765
    @Volatile var port = 0
        private set
    @Volatile private var started = false
    private lateinit var app: Context
    @Volatile private var key = ""
    private var page: ByteArray? = null
    private val workers = Executors.newFixedThreadPool(4) { r -> Thread(r, "phone-remote").apply { isDaemon = true } }

    fun start(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        val prefs = app.getSharedPreferences("mvswitch", Context.MODE_PRIVATE)
        key = prefs.getString("remoteKey", null) ?: (1..10).map { "abcdefghjkmnpqrstuvwxyz23456789".random() }
            .joinToString("").also { prefs.edit().putString("remoteKey", it).apply() }
        Thread({ serve() }, "phone-remote-server").apply { isDaemon = true }.start()
    }

    /** This phone's address on the Wi-Fi, or null when it isn't on one. */
    fun ip(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    /** The link to open on your phone, or null if the camera phone isn't on Wi-Fi yet. */
    fun url(): String? {
        val ip = ip() ?: return null
        if (port == 0 || key.isEmpty()) return null
        return "http://$ip:$port/?k=$key"
    }

    private fun serve() {
        var server: ServerSocket? = null
        for (p in FIRST_PORT until FIRST_PORT + 10) {
            server = runCatching { ServerSocket(p) }.getOrNull()
            if (server != null) { port = p; break }
        }
        val s = server ?: return
        while (true) {
            val sock = try { s.accept() } catch (e: Exception) { Thread.sleep(500); continue }
            try { workers.execute { runCatching { handle(sock) }; runCatching { sock.close() } } }
            catch (e: Exception) { runCatching { sock.close() } }
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun query(q: String): Map<String, String> = q.split('&').filter { it.contains('=') }.associate {
        val k = it.substringBefore('='); val v = it.substringAfter('=')
        runCatching { URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8") }.getOrDefault(k to v)
    }

    private fun handle(sock: Socket) {
        sock.soTimeout = 6000
        val input = BufferedInputStream(sock.getInputStream())
        val out = sock.getOutputStream()
        val first = readLine(input) ?: return
        val target = first.split(' ').getOrNull(1) ?: return
        var length = 0
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            if (h.startsWith("content-length:", ignoreCase = true)) length = h.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        val body = if (length in 1..8192) {
            val buf = ByteArray(length); var read = 0
            while (read < length) { val r = input.read(buf, read, length - read); if (r < 0) break; read += r }
            String(buf, 0, read)
        } else ""
        val path = target.substringBefore('?')
        val q = query(target.substringAfter('?', "")) + query(body)
        if (path == "/favicon.ico") { send(out, 404, "text/plain", "".toByteArray()); return }
        if (q["k"] != key) {
            send(out, 403, "text/html; charset=utf-8",
                ("<html><body style=\"background:#0C1015;color:#E8EDF3;font:18px system-ui;padding:24px\">" +
                    "Scan the code on the camera phone (Camera setup or Settings) to open the controls.</body></html>").toByteArray())
            return
        }
        when (path) {
            "/", "/index.html" -> send(out, 200, "text/html; charset=utf-8", page())
            "/state" -> send(out, 200, "application/json", Engine.remoteState().toByteArray())
            "/do" -> {
                val problem = runCatching { Engine.remoteCommand(q["a"].orEmpty(), q["n"]?.toIntOrNull(), q["v"]) }
                    .getOrElse { it.message ?: "Something went wrong" }
                send(out, 200, "application/json",
                    JSONObject().put("ok", problem == null).put("error", problem ?: "").toString().toByteArray())
            }
            "/config" -> send(out, 200, "application/json", Engine.remoteConfig().toByteArray())
            "/set" -> {
                val problem = runCatching { Engine.remoteSet(q["name"].orEmpty(), q["value"].orEmpty()) }
                    .getOrElse { it.message ?: "Something went wrong" }
                send(out, 200, "application/json",
                    JSONObject().put("ok", problem == null).put("error", problem ?: "").toString().toByteArray())
            }
            "/games" -> send(out, 200, "application/json", Engine.remoteGames(q["q"].orEmpty()).toByteArray())
            "/assign" -> {
                val problem = runCatching { Engine.remoteAssign(q["n"]?.toIntOrNull(), q["id"], q["other"]) }
                    .getOrElse { it.message ?: "Something went wrong" }
                send(out, 200, "application/json",
                    JSONObject().put("ok", problem == null).put("error", problem ?: "").toString().toByteArray())
            }
            "/report" -> send(out, 200, "text/plain; charset=utf-8",
                kotlinx.coroutines.runBlocking { Engine.report() }.toByteArray())
            "/frame.jpg" -> {
                val jpg = Engine.cameraJpeg()
                if (jpg == null) send(out, 404, "text/plain", "No camera picture yet".toByteArray())
                else send(out, 200, "image/jpeg", jpg)
            }
            else -> send(out, 404, "text/plain", "Not found".toByteArray())
        }
    }

    private fun page(): ByteArray = page ?: app.assets.open("remote.html").use { it.readBytes() }.also { page = it }

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val status = when (code) { 200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "Error" }
        val head = "HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }
}
