package com.landosol.toolbox.desktop.app.adapters.captcha

import com.landosol.toolbox.desktop.platform.BrowserLauncher
import com.landosol.toolbox.desktop.shared.CaptchaHost
import com.landosol.toolbox.desktop.shared.CaptchaSession
import com.landosol.toolbox.protocol.bilibili.CaptchaChallenge
import com.landosol.toolbox.protocol.bilibili.GeetestPageBuilder
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class LoopbackCaptchaHost(
    private val sessionTimeoutMillis: Long = 10 * 60_000L,
    private val pageBuilder: (CaptchaChallenge) -> String = GeetestPageBuilder::build,
    private val browser: (String) -> Boolean = BrowserLauncher::open,
) : CaptchaHost {
    private val callbacks = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val worker = Executors.newFixedThreadPool(2) { Thread(it, "desktop-captcha-http").apply { isDaemon = true } }
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "desktop-captcha-timeout").apply { isDaemon = true } }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val sessions = ConcurrentHashMap<String, Session>()
    private val closed = AtomicBoolean(false)
    private val origin = "http://127.0.0.1:${server.address.port}"
    private val expectedHost = "127.0.0.1:${server.address.port}"

    init {
        require(sessionTimeoutMillis > 0)
        server.executor = worker
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    @Synchronized override fun open(challenge: CaptchaChallenge, onSolved: (String) -> Unit, onError: (String) -> Unit): CaptchaSession {
        check(!closed.get()) { "验证码服务已关闭" }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also(SecureRandom()::nextBytes))
        val path = "/c/$token"
        val original = pageBuilder(challenge)
        val head = original.indexOf("<head>")
        check(head >= 0) { "验证页面结构已变化，无法插入桌面回传接口" }
        val bridge = """
            <script>window.AndroidCaptcha={onSolved:function(v){fetch('$path/solved',{method:'POST',headers:{'Content-Type':'text/plain;charset=UTF-8'},body:String(v)});},onError:function(m){fetch('$path/error',{method:'POST',headers:{'Content-Type':'text/plain;charset=UTF-8'},body:String(m)});}};</script>
        """.trimIndent()
        val page = original.substring(0, head + 6) + bridge + original.substring(head + 6)
        val session = Session(path, page.toByteArray(Charsets.UTF_8), onSolved, onError)
        sessions[path] = session
        session.timeout = timer.schedule({
            if (sessions.remove(path, session)) {
                callbacks.launch { onError("验证已超时，请重新发起登录") }
            }
        }, sessionTimeoutMillis, TimeUnit.MILLISECONDS)
        return session
    }

    override fun browse(url: String): Boolean = browser(url)

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            if (exchange.requestHeaders.getFirst("Host") != expectedHost) { respond(exchange, 403, "Forbidden"); return }
            val path = exchange.requestURI.rawPath
            val action = when {
                path.endsWith("/solved") -> "solved"
                path.endsWith("/error") -> "error"
                else -> null
            }
            val sessionPath = if (action == null) path else path.substringBeforeLast('/')
            val session = sessions[sessionPath]
            if (session == null) { respond(exchange, 404, "Not found"); return }
            if (action == null && exchange.requestMethod == "GET") {
                respond(exchange, 200, session.page, "text/html; charset=UTF-8")
                return
            }
            if (action == null || exchange.requestMethod != "POST") { respond(exchange, 405, "Method not allowed"); return }
            if (exchange.requestHeaders.getFirst("Origin") != origin) { respond(exchange, 403, "Forbidden"); return }
            val declaredLength = exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
            if (declaredLength != null && declaredLength > MAX_BODY_BYTES) { respond(exchange, 413, "Payload too large"); return }
            val body = exchange.requestBody.readNBytes(MAX_BODY_BYTES + 1)
            if (body.size > MAX_BODY_BYTES) { respond(exchange, 413, "Payload too large"); return }
            if (!sessions.remove(sessionPath, session)) { respond(exchange, 404, "Not found"); return }
            session.timeout?.cancel(false)
            val text = body.toString(Charsets.UTF_8)
            respond(exchange, 200, "OK")
            callbacks.launch {
                if (action == "error") session.onError(text.take(120))
                else if (text.isBlank() || text.length > 4096) session.onError("验证组件返回了无效结果")
                else session.onSolved(text)
            }
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, text: String) = respond(exchange, status, text.toByteArray(Charsets.UTF_8), "text/plain; charset=UTF-8")
    private fun respond(exchange: HttpExchange, status: Int, bytes: ByteArray, contentType: String) {
        exchange.responseHeaders.set("Content-Type", contentType)
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.responseHeaders.set("Referrer-Policy", "no-referrer")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) return
        sessions.values.forEach { it.timeout?.cancel(false) }
        sessions.clear()
        server.stop(0)
        worker.shutdownNow()
        timer.shutdownNow()
        callbacks.cancel()
    }

    private inner class Session(val path: String, val page: ByteArray, val onSolved: (String) -> Unit, val onError: (String) -> Unit) : CaptchaSession {
        @Volatile var timeout: ScheduledFuture<*>? = null
        override val url: String get() = origin + path
        override fun close() { sessions.remove(path, this); timeout?.cancel(false) }
    }

    companion object { private const val MAX_BODY_BYTES = 8 * 1024 }
}
