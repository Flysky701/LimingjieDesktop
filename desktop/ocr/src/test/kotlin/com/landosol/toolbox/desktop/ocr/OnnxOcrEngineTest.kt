package com.landosol.toolbox.desktop.ocr

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class OnnxOcrEngineTest {
    @Test fun `loading queues up to 64 requests and rejects only excess request`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(65)
        val failures = AtomicInteger()
        val successes = AtomicInteger()
        val warnings = AtomicInteger()
        val backend = object : StubBackend() {
            override fun warmUp() { entered.countDown(); release.await() }
        }
        OnnxOcrEngine({ backend }, { _, _ -> warnings.incrementAndGet() }).use { engine ->
            engine.warmUp()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertEquals(OcrEngineStatus.Loading, engine.status.value)
            repeat(65) {
                engine.submit(1, 1, intArrayOf(-1)) { value ->
                    if (value == null) failures.incrementAndGet() else successes.incrementAndGet()
                    done.countDown()
                }
            }
            assertEquals(1, failures.get())
            assertEquals(1, warnings.get())
            release.countDown()
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertEquals(64, successes.get())
            assertEquals(OcrEngineStatus.Ready, engine.status.value)
        }
        assertEquals(1, backend.closed.get())
    }

    @Test fun `initialization failure completes queued requests and does not retry`() {
        val creates = AtomicInteger()
        OnnxOcrEngine({ creates.incrementAndGet(); error("model missing") }, { _, _ -> }).use { engine ->
            assertNull(result(engine))
            assertTrue(engine.status.value is OcrEngineStatus.Failed)
            assertNull(result(engine))
            assertEquals(1, creates.get())
        }
    }

    @Test fun `native inference failure completes current and future submissions`() {
        val backend = object : StubBackend() {
            override fun recognize(image: OcrImage): String = throw UnsatisfiedLinkError("native test failure")
        }
        OnnxOcrEngine({ backend }, { _, _ -> }).use { engine ->
            assertNull(result(engine))
            // The result callback runs immediately before the worker publishes failure.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (engine.status.value !is OcrEngineStatus.Failed && System.nanoTime() < deadline) Thread.yield()
            assertTrue(engine.status.value is OcrEngineStatus.Failed)
            if (System.getProperty("os.name").startsWith("Windows")) {
                assertTrue((engine.status.value as OcrEngineStatus.Failed).reason.contains("vc_redist.x64.exe"))
            }
            assertNull(result(engine))
        }
        assertEquals(1, backend.closed.get())
    }

    @Test fun `close completes active and queued requests exactly once`() {
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(4)
        val callbacks = AtomicInteger()
        val backend = object : StubBackend() {
            override fun recognize(image: OcrImage): String { entered.countDown(); CountDownLatch(1).await(); return "unreachable" }
        }
        val engine = OnnxOcrEngine({ backend }, { _, _ -> })
        engine.submit(1, 1, intArrayOf(-1)) { assertNull(it); callbacks.incrementAndGet(); finished.countDown() }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        repeat(3) { engine.submit(1, 1, intArrayOf(-1)) { assertNull(it); callbacks.incrementAndGet(); finished.countDown() } }
        engine.close()
        assertTrue(finished.await(3, TimeUnit.SECONDS))
        assertEquals(4, callbacks.get())
        assertEquals(1, backend.closed.get())
        assertNull(result(engine))
        engine.close()
        assertEquals(1, backend.closed.get())
    }

    @Test fun `bad input fails without initializing models`() {
        val creates = AtomicInteger()
        OnnxOcrEngine({ creates.incrementAndGet(); StubBackend() }, { _, _ -> }).use { engine ->
            val future = CompletableFuture<String?>()
            engine.submit(2, 2, intArrayOf(1)) { future.complete(it) }
            assertNull(future.get(3, TimeUnit.SECONDS))
            assertEquals(0, creates.get())
        }
    }

    @Test fun `callback exception does not abandon later requests`() {
        val caught = CountDownLatch(1)
        OnnxOcrEngine({ StubBackend() }, { _, _ -> caught.countDown() }).use { engine ->
            engine.submit(1, 1, intArrayOf(-1)) { error("consumer failure") }
            assertTrue(caught.await(3, TimeUnit.SECONDS))
            assertEquals("文字", result(engine))
        }
    }

    @Test fun `warmUp loads real packaged models and recognizes a blank image as empty`() {
        OnnxOcrEngine().use { engine ->
            engine.warmUp()
            val future = CompletableFuture<String?>()
            engine.submit(160, 48, IntArray(160 * 48) { -1 }) { future.complete(it) }
            assertEquals("", future.get(45, TimeUnit.SECONDS))
            assertEquals(OcrEngineStatus.Ready, engine.status.value)
            val compact = CompletableFuture<String?>()
            engine.submit(64, 64, IntArray(64 * 64) { -1 }) { compact.complete(it) }
            assertEquals("", compact.get(10, TimeUnit.SECONDS))
        }
    }

    @Test fun `real 1920 pixel map counters survive compact ROI detection misses`() {
        val screenshot = ImageIO.read(File("../../android/app/src/test/resources/labyrinth/final-boss-active-20260909.jpg"))
        OnnxOcrEngine().use { engine ->
            listOf("5", "4", "2", "1").forEachIndexed { index, expected ->
                // Current upstream AndroidLabyrinthNodeRelicStackResolver OCR_DIGIT_RECTS, OCR_SCALE.
                val crop = screenshot.getSubimage(360 + index * 88, 970, 48, 48)
                val scaled = BufferedImage(192, 192, BufferedImage.TYPE_INT_RGB)
                scaled.createGraphics().apply {
                    setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
                    drawImage(crop, 0, 0, 192, 192, null)
                    dispose()
                }
                val result = CompletableFuture<String?>()
                engine.submit(192, 192, scaled.getRGB(0, 0, 192, 192, null, 0, 192)) { result.complete(it) }
                assertEquals(expected, result.get(45, TimeUnit.SECONDS))
            }
        }
    }

    private fun result(engine: OcrEngine): String? {
        val future = CompletableFuture<String?>()
        engine.submit(1, 1, intArrayOf(-1)) { future.complete(it) }
        return future.get(5, TimeUnit.SECONDS)
    }

    private open class StubBackend : OcrBackend {
        val closed = AtomicInteger()
        override fun warmUp() = Unit
        override fun recognize(image: OcrImage) = "文字"
        override fun close() { closed.incrementAndGet() }
    }
}
