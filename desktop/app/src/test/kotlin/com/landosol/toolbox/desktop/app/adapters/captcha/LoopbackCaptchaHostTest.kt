package com.landosol.toolbox.desktop.app.adapters.captcha

import com.landosol.toolbox.protocol.bilibili.CaptchaChallenge
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities

class LoopbackCaptchaHostTest {
    private val challenge = CaptchaChallenge("fixture-gt", "fixture-challenge", "fixture-user", "geetest")
    private data class Response(val status: Int, val headers: String, val body: String)
    private fun request(url: String, method: String = "GET", body: String = "", host: String? = null, origin: String? = null): Response {
        val uri = URI(url)
        val bytes = body.toByteArray(Charsets.UTF_8)
        return Socket(uri.host, uri.port).use { socket ->
            socket.soTimeout = 5000
            val header = buildString {
                append("$method ${uri.rawPath} HTTP/1.1\r\nHost: ${host ?: "${uri.host}:${uri.port}"}\r\nConnection: close\r\n")
                if (origin != null) append("Origin: $origin\r\n")
                if (method == "POST") append("Content-Type: text/plain; charset=UTF-8\r\nContent-Length: ${bytes.size}\r\n")
                append("\r\n")
            }
            socket.getOutputStream().write(header.toByteArray(Charsets.US_ASCII))
            if (method == "POST") socket.getOutputStream().write(bytes)
            socket.getOutputStream().flush()
            val response = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            Response(response.substringBefore("\r\n").split(' ')[1].toInt(), response.substringBefore("\r\n\r\n"), response.substringAfter("\r\n\r\n"))
        }
    }
    private fun origin(url: String) = URI(url).let { "http://${it.host}:${it.port}" }

    @Test fun pageBridgePrecedesProviderScriptAndResultIsOnceOnMain() {
        LoopbackCaptchaHost().use { host ->
            val count = AtomicInteger(); val latch = CountDownLatch(1); var mainThread = false
            val session = host.open(challenge, { assertEquals("validated", it); mainThread = SwingUtilities.isEventDispatchThread(); count.incrementAndGet(); latch.countDown() }, { fail(it) })
            val page = request(session.url)
            assertEquals(200, page.status)
            assertTrue(page.headers.contains("no-store", ignoreCase = true))
            assertTrue(page.headers.contains("no-referrer", ignoreCase = true))
            assertTrue(page.body.indexOf("window.AndroidCaptcha") < page.body.indexOf("https://static.geetest.com"))
            assertFalse(page.body.contains("fixture-user"))
            assertEquals(200, request(session.url + "/solved", "POST", "validated", origin = origin(session.url)).status)
            assertTrue(latch.await(3, TimeUnit.SECONDS)); assertTrue(mainThread); assertEquals(1, count.get())
            assertEquals(404, request(session.url + "/solved", "POST", "validated", origin = origin(session.url)).status)
            assertEquals(404, request(session.url).status)
        }
    }

    @Test fun rejectsWrongTokenHostOriginAndOversizedBody() {
        LoopbackCaptchaHost().use { host ->
            val session = host.open(challenge, { fail("unexpected solved") }, { fail("unexpected error") })
            assertEquals(404, request(session.url + "wrong").status)
            assertEquals(403, request(session.url, host = "example.invalid").status)
            assertEquals(403, request(session.url + "/solved", "POST", "token", origin = "https://example.invalid").status)
            assertEquals(413, request(session.url + "/solved", "POST", "x".repeat(8193), origin = origin(session.url)).status)
            session.close()
            assertEquals(404, request(session.url).status)
        }
    }

    @Test fun invalidValidateAndErrorTruncationUseErrorCallback() {
        LoopbackCaptchaHost().use { host ->
            val latch = CountDownLatch(1); var message = ""
            val session = host.open(challenge, { fail("unexpected solved") }, { message = it; latch.countDown() })
            assertEquals(200, request(session.url + "/solved", "POST", " ", origin = origin(session.url)).status)
            assertTrue(latch.await(3, TimeUnit.SECONDS)); assertEquals("验证组件返回了无效结果", message)
            val errors = CountDownLatch(1)
            val second = host.open(challenge, { fail("unexpected solved") }, { message = it; errors.countDown() })
            request(second.url + "/error", "POST", "a".repeat(300), origin = origin(second.url))
            assertTrue(errors.await(3, TimeUnit.SECONDS)); assertEquals(120, message.length)
        }
    }

    @Test fun timeoutExpiresAndMalformedPageFailsBeforeRegistration() {
        LoopbackCaptchaHost(sessionTimeoutMillis = 50).use { host ->
            val latch = CountDownLatch(1); var message = ""
            val session = host.open(challenge, { fail("unexpected solved") }, { message = it; latch.countDown() })
            assertTrue(latch.await(3, TimeUnit.SECONDS)); assertEquals("验证已超时，请重新发起登录", message)
            assertEquals(404, request(session.url).status)
        }
        LoopbackCaptchaHost(pageBuilder = { "<html></html>" }).use { host ->
            assertThrows(IllegalStateException::class.java) { host.open(challenge, {}, {}) }
        }
    }
}
