package app.marmalade.tts.reader

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// -----------------------------------------------------------------------------
// ArticleFetcher — hand-rolled HTTP GET for the reader-mode pipeline.
//
// Hand-rolled `HttpURLConnection` rather than a client library, matching the
// rest of the app (EngineInstaller, CloudApiEngine). The one thing the JDK
// client will not do for us is follow a redirect that changes protocol
// (http → https and back), which is exactly what publishers' canonical-URL
// chains do — hence the manual redirect loop below.
//
// PRIVACY INVARIANT (F-Droid posture, see PRIVACY.md): this class contacts
// the URL the user shared and nothing else. There are no hardcoded
// endpoints here — no favicon service, no image proxy, no unshortener, no
// third-party reader API. The only hosts ever touched are the shared one and
// whatever it redirects to. Images referenced by the article are never
// fetched; the reader is text-only. Do not add a hostname to this file.
// -----------------------------------------------------------------------------

/** Outcome of an [ArticleFetcher.fetch]. Callers get a value, never an exception. */
sealed class FetchResult {

    /**
     * Body fetched successfully.
     *
     * [bytes] is deliberately *undecoded*: charset detection belongs to jsoup
     * downstream, which reads the document's own `<meta charset>` and the BOM.
     * Decoding here would force a guess and mangle every non-UTF-8 page.
     *
     * [finalUrl] is the URL after redirects — the correct base URI for
     * resolving relative links during extraction.
     */
    class Success(
        val bytes: ByteArray,
        val finalUrl: String,
        val statusCode: Int,
        val contentType: String?,
    ) : FetchResult()

    /** Server answered, but with a non-2xx status (404, 403 bot-wall, …). */
    data class HttpError(val code: Int) : FetchResult()

    /** DNS/TCP/TLS/timeout failure, or a URL we refuse to touch. */
    data class NetworkError(val reason: String) : FetchResult()

    /** Body exceeded [ArticleFetcher.MAX_BODY_BYTES]; reading was abandoned. */
    object TooLarge : FetchResult()

    /** Redirect chain exceeded [ArticleFetcher.MAX_REDIRECTS] hops. */
    object TooManyRedirects : FetchResult()

    /**
     * `Content-Type` says this is unambiguously not a web page (an image, a
     * PDF, a video). Ambiguous or absent types are *not* reported here — they
     * are fetched and handed to the extractor, which decides.
     */
    data class NotHtml(val contentType: String) : FetchResult()
}

/**
 * Fetches the raw bytes of a user-shared article URL.
 *
 * Injected by constructor (no Hilt module entry needed — the graph can build
 * it directly). Stateless and thread-safe; [fetch] does its I/O on
 * [Dispatchers.IO].
 */
@Singleton
open class ArticleFetcher @Inject constructor() {

    /**
     * GET [url], following redirects manually, and return the raw body.
     *
     * Never throws: every failure mode is a [FetchResult] variant.
     */
    open suspend fun fetch(url: String): FetchResult = withContext(Dispatchers.IO) {
        var current = url
        // One iteration per request: the initial GET plus up to MAX_REDIRECTS
        // hops. Falling out of the loop means the chain was longer than that.
        for (attempt in 0..MAX_REDIRECTS) {
            val parsed = try {
                URL(current)
            } catch (e: Exception) {
                return@withContext FetchResult.NetworkError("Malformed URL: ${e.message}")
            }
            if (!isFetchableScheme(parsed.protocol)) {
                return@withContext FetchResult.NetworkError(
                    "Refusing non-http(s) URL scheme: ${parsed.protocol}",
                )
            }

            val conn = try {
                openConnection(parsed)
            } catch (e: IOException) {
                return@withContext FetchResult.NetworkError(e.message ?: "Connection failed")
            }

            try {
                val code = try {
                    conn.responseCode
                } catch (e: IOException) {
                    return@withContext FetchResult.NetworkError(e.message ?: "No response")
                } catch (e: SecurityException) {
                    // Hardened OSes (GrapheneOS "Network" toggle) surface a revoked
                    // INTERNET permission as an unchecked SecurityException from the
                    // socket/DNS layer, not an IOException. Treat it as offline.
                    return@withContext FetchResult.NetworkError(e.message ?: "Network access denied")
                }

                if (code in REDIRECT_CODES) {
                    val location = conn.getHeaderField("Location")
                        ?: return@withContext FetchResult.HttpError(code)
                    // Location is allowed to be relative ("/article/2026/x") —
                    // resolve it against the URL we just requested.
                    current = try {
                        URL(parsed, location).toString()
                    } catch (e: Exception) {
                        return@withContext FetchResult.NetworkError(
                            "Bad redirect target: $location",
                        )
                    }
                    Log.d(TAG, "Redirect $code → $current")
                    continue
                }

                if (code !in 200..299) return@withContext FetchResult.HttpError(code)

                val contentType = conn.getHeaderField("Content-Type")
                rejectedMediaType(contentType)?.let {
                    return@withContext FetchResult.NotHtml(it)
                }

                val body = try {
                    readCapped(conn)
                } catch (e: IOException) {
                    return@withContext FetchResult.NetworkError(e.message ?: "Read failed")
                } catch (e: SecurityException) {
                    return@withContext FetchResult.NetworkError(e.message ?: "Network access denied")
                } ?: return@withContext FetchResult.TooLarge

                return@withContext FetchResult.Success(
                    bytes = body,
                    finalUrl = current,
                    statusCode = code,
                    contentType = contentType,
                )
            } finally {
                conn.disconnect()
            }
        }
        FetchResult.TooManyRedirects
    }

    private fun openConnection(url: URL): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            // We drive redirects ourselves so we can cross http↔https, count
            // hops, and re-validate the scheme of every target.
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", ACCEPT)
            setRequestProperty("Accept-Language", "en;q=0.9,*;q=0.5")
        }

    /**
     * Read the body, giving up as soon as it exceeds [MAX_BODY_BYTES].
     *
     * The counting loop is the point: `Content-Length` is advisory (chunked
     * responses omit it, and a hostile server can lie), so the cap has to be
     * enforced against bytes actually read. Returns `null` when the cap trips.
     */
    private fun readCapped(conn: HttpURLConnection): ByteArray? {
        val out = ByteArrayOutputStream(INITIAL_BUFFER_BYTES)
        conn.inputStream.use { input ->
            val buf = ByteArray(BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buf)
                if (read == -1) break
                total += read
                if (total > MAX_BODY_BYTES) return null
                out.write(buf, 0, read)
            }
        }
        return out.toByteArray()
    }

    private fun isFetchableScheme(scheme: String?): Boolean =
        scheme != null && (scheme.equals("http", true) || scheme.equals("https", true))

    /**
     * Returns the offending media type when [contentType] is *clearly* not a
     * web page, else `null`.
     *
     * Absent, `application/octet-stream`, and anything unrecognised count as
     * "maybe HTML" on purpose: plenty of real sites serve articles with a
     * missing or sloppy Content-Type, and the extractor fails cheaply and
     * accurately on genuine non-HTML. Only types that could never be an
     * article are rejected here — before the body is read, so we don't pull
     * megabytes of a video down the wire.
     */
    private fun rejectedMediaType(contentType: String?): String? {
        if (contentType.isNullOrBlank()) return null
        val mediaType = contentType.substringBefore(';').trim().lowercase()
        val rejected = REJECTED_PREFIXES.any { mediaType.startsWith(it) } ||
            mediaType in REJECTED_TYPES
        return if (rejected) mediaType else null
    }

    companion object {
        private const val TAG = "ArticleFetcher"

        /** Max redirect hops before we assume a loop. Browsers use 20; 5 is plenty for articles. */
        const val MAX_REDIRECTS = 5

        /** Hard ceiling on body size. A long article is well under 1 MB of HTML. */
        const val MAX_BODY_BYTES: Long = 5L * 1024L * 1024L

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val BUFFER_SIZE = 32 * 1024
        private const val INITIAL_BUFFER_BYTES = 64 * 1024

        /**
         * A real Chrome-on-Android UA. Not cosmetic: a default Java UA gets
         * 403'd by a large share of publishers' bot walls, which would make
         * the feature look broken rather than blocked.
         */
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8a) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Mobile Safari/537.36"

        private const val ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"

        /** 308 and 307 preserve the method; for a GET all five are equivalent. */
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        private val REJECTED_PREFIXES = listOf("image/", "video/", "audio/", "font/")
        private val REJECTED_TYPES = setOf(
            "application/pdf",
            "application/zip",
            "application/epub+zip",
        )
    }
}
