package app.marmalade.tts.reader

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [ArticleFetcher], driven against a real
 * [LoopbackHttpServer] on an ephemeral loopback port.
 *
 * No live-internet access anywhere in here: every URL is 127.0.0.1. That's
 * both a hermeticity requirement (F-Droid's builder has no network) and the
 * only honest way to test the redirect loop and the size cap.
 */
class ArticleFetcherTest {

    private val fetcher = ArticleFetcher()

    private fun html(body: String) = "<html><body>$body</body></html>".toByteArray()

    private fun ok(body: ByteArray, contentType: String = "text/html; charset=utf-8") =
        LoopbackHttpServer.Response(
            status = 200,
            headers = listOf("Content-Type" to contentType),
            body = body,
        )

    private fun redirect(status: Int, location: String) =
        LoopbackHttpServer.Response(status = status, headers = listOf("Location" to location))

    // -- happy path --------------------------------------------------------

    @Test
    fun `200 returns raw bytes, final url, status and content type`() = runTest {
        val body = html("<p>hello</p>")
        LoopbackHttpServer { ok(body) }.use { server ->
            val result = fetcher.fetch(server.url("/article"))

            assertTrue("expected Success, got $result", result is FetchResult.Success)
            result as FetchResult.Success
            assertArrayEquals(body, result.bytes)
            assertEquals(server.url("/article"), result.finalUrl)
            assertEquals(200, result.statusCode)
            assertEquals("text/html; charset=utf-8", result.contentType)
        }
    }

    @Test
    fun `bytes are not decoded so a non-utf8 body survives intact`() = runTest {
        // "café" in ISO-8859-1 — byte 0xE9 is not valid UTF-8, so any
        // premature decode would replace it with U+FFFD.
        val body = "café".toByteArray(Charsets.ISO_8859_1)
        LoopbackHttpServer { ok(body, contentType = "text/html") }.use { server ->
            val result = fetcher.fetch(server.url("/latin1")) as FetchResult.Success
            assertArrayEquals(body, result.bytes)
        }
    }

    // -- redirects ---------------------------------------------------------

    @Test
    fun `redirect chain is followed and the final url is reported`() = runTest {
        val body = html("<p>final</p>")
        LoopbackHttpServer { path ->
            when (path) {
                "/one" -> redirect(301, "/two")
                "/two" -> redirect(302, "/three")
                "/three" -> redirect(308, "/end")
                else -> ok(body)
            }
        }.use { server ->
            val result = fetcher.fetch(server.url("/one"))

            assertTrue("expected Success, got $result", result is FetchResult.Success)
            result as FetchResult.Success
            assertEquals(server.url("/end"), result.finalUrl)
            assertArrayEquals(body, result.bytes)
            assertEquals(4, server.requestCount)
        }
    }

    @Test
    fun `relative location header is resolved against the current url`() = runTest {
        val body = html("<p>resolved</p>")
        LoopbackHttpServer { path ->
            when (path) {
                "/section/old" -> redirect(301, "new")
                "/section/new" -> ok(body)
                else -> LoopbackHttpServer.Response(status = 404)
            }
        }.use { server ->
            val result = fetcher.fetch(server.url("/section/old"))

            assertTrue("expected Success, got $result", result is FetchResult.Success)
            assertEquals(server.url("/section/new"), (result as FetchResult.Success).finalUrl)
        }
    }

    @Test
    fun `five redirects are allowed`() = runTest {
        val body = html("<p>ok</p>")
        LoopbackHttpServer { path ->
            val hop = path.removePrefix("/hop").toIntOrNull()
            when {
                hop == null -> LoopbackHttpServer.Response(status = 404)
                hop < 5 -> redirect(302, "/hop${hop + 1}")
                else -> ok(body)
            }
        }.use { server ->
            val result = fetcher.fetch(server.url("/hop0"))
            assertTrue("expected Success, got $result", result is FetchResult.Success)
        }
    }

    @Test
    fun `more than five redirects gives up`() = runTest {
        LoopbackHttpServer { path ->
            val hop = path.removePrefix("/hop").toIntOrNull() ?: 0
            redirect(302, "/hop${hop + 1}")
        }.use { server ->
            val result = fetcher.fetch(server.url("/hop0"))
            assertEquals(FetchResult.TooManyRedirects, result)
        }
    }

    @Test
    fun `redirect to a non http scheme is refused`() = runTest {
        LoopbackHttpServer { redirect(302, "ftp://files.example.org/doc.txt") }.use { server ->
            val result = fetcher.fetch(server.url("/start"))
            assertTrue("expected NetworkError, got $result", result is FetchResult.NetworkError)
        }
    }

    @Test
    fun `redirect with no location header is reported as an http error`() = runTest {
        LoopbackHttpServer { LoopbackHttpServer.Response(status = 302) }.use { server ->
            assertEquals(FetchResult.HttpError(302), fetcher.fetch(server.url("/nowhere")))
        }
    }

    // -- failures ----------------------------------------------------------

    @Test
    fun `404 is an http error`() = runTest {
        LoopbackHttpServer { LoopbackHttpServer.Response(status = 404) }.use { server ->
            assertEquals(FetchResult.HttpError(404), fetcher.fetch(server.url("/missing")))
        }
    }

    @Test
    fun `body over the cap fails as TooLarge`() = runTest {
        val oversize = ByteArray((ArticleFetcher.MAX_BODY_BYTES + 1024L).toInt()) { 'a'.code.toByte() }
        LoopbackHttpServer { ok(oversize) }.use { server ->
            assertEquals(FetchResult.TooLarge, fetcher.fetch(server.url("/huge")))
        }
    }

    @Test
    fun `a body at exactly the cap still succeeds`() = runTest {
        val atCap = ByteArray(ArticleFetcher.MAX_BODY_BYTES.toInt()) { 'a'.code.toByte() }
        LoopbackHttpServer { ok(atCap) }.use { server ->
            val result = fetcher.fetch(server.url("/big"))
            assertTrue("expected Success, got $result", result is FetchResult.Success)
            assertEquals(atCap.size, (result as FetchResult.Success).bytes.size)
        }
    }

    @Test
    fun `image and pdf content types are rejected before the body is read`() = runTest {
        LoopbackHttpServer { ok(ByteArray(16), contentType = "image/png") }.use { server ->
            assertEquals(
                FetchResult.NotHtml("image/png"),
                fetcher.fetch(server.url("/photo.png")),
            )
        }
        LoopbackHttpServer { ok(ByteArray(16), contentType = "application/pdf") }.use { server ->
            assertEquals(
                FetchResult.NotHtml("application/pdf"),
                fetcher.fetch(server.url("/paper.pdf")),
            )
        }
    }

    @Test
    fun `missing or ambiguous content type is treated as html`() = runTest {
        val body = html("<p>no content type</p>")
        LoopbackHttpServer { LoopbackHttpServer.Response(status = 200, body = body) }.use { s ->
            assertTrue(fetcher.fetch(s.url("/bare")) is FetchResult.Success)
        }
        LoopbackHttpServer { ok(body, contentType = "application/octet-stream") }.use { s ->
            assertTrue(fetcher.fetch(s.url("/blob")) is FetchResult.Success)
        }
    }

    @Test
    fun `non http schemes are refused without opening a connection`() = runTest {
        val result = fetcher.fetch("ftp://files.example.org/doc.txt")
        assertTrue("expected NetworkError, got $result", result is FetchResult.NetworkError)
    }

    @Test
    fun `a dead port is a network error, not a crash`() = runTest {
        // Bind and immediately release a port so nothing is listening on it.
        val deadPort = java.net.ServerSocket(0).use { it.localPort }
        val result = fetcher.fetch("http://127.0.0.1:$deadPort/article")
        assertTrue("expected NetworkError, got $result", result is FetchResult.NetworkError)
    }
}
