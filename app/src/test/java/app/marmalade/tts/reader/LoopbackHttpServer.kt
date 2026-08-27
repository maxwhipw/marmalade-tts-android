package app.marmalade.tts.reader

import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException

/**
 * Minimal loopback HTTP server for [ArticleFetcherTest].
 *
 * Hand-rolled on a plain [ServerSocket] rather than `com.sun.net.httpserver`
 * because the JDK's HTTP server is not on the Android unit-test compile
 * classpath (the bootclasspath is `android.jar`, which has no `com.sun.*`).
 * `EngineInstallerTest` hit the same wall and dodged it with an in-memory
 * fetcher; the reader fetcher can't, because redirect handling, the size cap
 * and header parsing are exactly the behaviours under test — they only mean
 * anything against a real socket and a real `HttpURLConnection`.
 *
 * Deliberately not a general-purpose server: one request per connection,
 * requests handled sequentially, no keep-alive, no chunked encoding.
 */
class LoopbackHttpServer(private val handler: (path: String) -> Response) : AutoCloseable {

    /**
     * A canned reply. [headers] are emitted verbatim, which is how tests set
     * `Location` and `Content-Type`; `Content-Length` and `Connection` are
     * added automatically.
     */
    class Response(
        val status: Int,
        val headers: List<Pair<String, String>> = emptyList(),
        val body: ByteArray = ByteArray(0),
    )

    private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))

    /** Number of requests served — lets tests assert on redirect-chain length. */
    @Volatile
    var requestCount: Int = 0
        private set

    private val thread = Thread({ serve() }, "LoopbackHttpServer").apply {
        isDaemon = true
        start()
    }

    /** Absolute URL for [path] (which must start with `/`). */
    fun url(path: String): String = "http://127.0.0.1:${socket.localPort}$path"

    private fun serve() {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: SocketException) {
                return // closed while blocked in accept()
            }
            try {
                client.use {
                    val path = readRequest(it.getInputStream()) ?: return@use
                    requestCount++
                    val response = handler(path)
                    writeResponse(BufferedOutputStream(it.getOutputStream()), response)
                }
            } catch (e: java.io.IOException) {
                // Expected when the client hangs up mid-body — the size-cap
                // test does exactly that. Nothing to do but serve the next one.
            }
        }
    }

    /** Read the request line + headers, discard them, return the request path. */
    private fun readRequest(input: InputStream): String? {
        val requestLine = readLine(input) ?: return null
        while (true) {
            val header = readLine(input) ?: break
            if (header.isEmpty()) break
        }
        // "GET /path HTTP/1.1"
        return requestLine.split(' ').getOrNull(1)
    }

    /** Read one CRLF-terminated line as ASCII. Returns null at end of stream. */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().removeSuffix("\r")
            sb.append(c.toChar())
        }
    }

    private fun writeResponse(output: BufferedOutputStream, response: Response) {
        val head = buildString {
            append("HTTP/1.1 ${response.status} ${reasonFor(response.status)}\r\n")
            for ((name, value) in response.headers) append("$name: $value\r\n")
            append("Content-Length: ${response.body.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(response.body)
        output.flush()
    }

    private fun reasonFor(status: Int): String = when (status) {
        200 -> "OK"
        301 -> "Moved Permanently"
        302 -> "Found"
        303 -> "See Other"
        307 -> "Temporary Redirect"
        308 -> "Permanent Redirect"
        404 -> "Not Found"
        else -> "Status"
    }

    override fun close() {
        socket.close()
        thread.join(2_000)
    }
}
