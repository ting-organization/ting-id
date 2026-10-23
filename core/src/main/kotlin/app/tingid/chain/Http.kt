// HTTP as the app uses it, for nodes and for sites' signing requests: no
// cookies, no cache, no redirects followed, a time limit on the whole answer
// and a limit on its size. Tests put a fake in its place.
package app.tingid.chain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

class HttpRequest(
    val url: String,
    val method: String = "GET",
    val body: ByteArray? = null,
    val headers: Map<String, String> = emptyMap(),
    /** The whole exchange, the body included. */
    val timeoutMs: Long = 8_000,
    /** An answer larger than this is refused. */
    val maxBytes: Int = 32 * 1024 * 1024,
)

class HttpResponse(
    val status: Int,
    val body: ByteArray,
    /** Header names in lower case. */
    val headers: Map<String, String> = emptyMap(),
) {
    val ok: Boolean get() = status in 200..299

    /** The answer was a redirect, which is never followed. */
    val redirect: Boolean get() = status in 300..399 && headers.containsKey("location")

    fun text(): String = body.decodeToString()
}

/** Why there was no answer: not reached, timed out, cut off, or too large. */
class HttpFailure(message: String, val timedOut: Boolean = false, val tooLarge: Boolean = false) : IOException(message)

fun interface Http {
    suspend fun exchange(request: HttpRequest): HttpResponse
}

/** [Http] over `HttpURLConnection`, on the IO dispatcher. */
object UrlConnectionHttp : Http {
    override suspend fun exchange(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val uri = try {
            URI(request.url)
        } catch (e: Exception) {
            throw HttpFailure("not an address")
        }
        if (uri.scheme != "https" && uri.scheme != "http") throw HttpFailure("not an http address")
        val deadline = System.nanoTime() + request.timeoutMs * 1_000_000
        val c = try {
            uri.toURL().openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw HttpFailure("not an address")
        }
        try {
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.connectTimeout = request.timeoutMs.toInt()
            c.readTimeout = request.timeoutMs.toInt()
            c.requestMethod = request.method
            c.setRequestProperty("cookie", null)
            for ((k, v) in request.headers) c.setRequestProperty(k, v)
            if (request.body != null) {
                c.doOutput = true
                c.setFixedLengthStreamingMode(request.body.size)
                c.outputStream.use { it.write(request.body) }
            }
            val status = c.responseCode
            val headers = c.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.joinToString(", ") }
            val declared = headers["content-length"]?.toLongOrNull()
            if (declared != null && declared > request.maxBytes) throw HttpFailure("the answer is too large", tooLarge = true)
            val stream = (if (status >= 400) c.errorStream else c.inputStream) ?: return@withContext HttpResponse(status, ByteArray(0), headers)
            val out = ByteArrayOutputStream()
            stream.use {
                val buf = ByteArray(16 * 1024)
                while (true) {
                    if (System.nanoTime() > deadline) throw HttpFailure("no answer in time", timedOut = true)
                    val n = it.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > request.maxBytes) throw HttpFailure("the answer is too large", tooLarge = true)
                }
            }
            HttpResponse(status, out.toByteArray(), headers)
        } catch (e: HttpFailure) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            throw HttpFailure("no answer in time", timedOut = true)
        } catch (e: IOException) {
            throw HttpFailure("could not be reached")
        } finally {
            c.disconnect()
        }
    }
}
