// Off-chain signing, TOCS-1 (TOCS-1.md): what a site asks a Ting ID user to sign, the
// document the app shows for it, and the message the app signs. Nothing here
// is ever valid on chain: the message starts with this convention's own tag,
// and the app builds it itself, so a site never chooses the bytes that are
// signed.
package app.tingid.chain

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.text.Normalizer

class OffchainError(message: String) : Exception(message)

object Offchain {
    const val TAG = "app/offchain-v1"
    private const val DOCUMENT_TAG = "app/offchain-v1/document"
    private const val MATCH_TAG = "app/offchain-v1/match"
    private const val VERSION = 1

    /** The largest document an app shows: everything it signs must be readable. */
    const val MAX_DOCUMENT = 65_536

    /** How far a request's issue time may be ahead of the app's clock, and how long a request may live. */
    private const val CLOCK_SKEW = 300L
    private const val MAX_REQUEST_LIFETIME = 86_400L
    private val SCOPE = Regex("^[a-z0-9][a-z0-9:._/-]{0,63}$")
    private val MEDIA_TYPE = Regex("^[a-z0-9!#$&^_.+-]{1,63}/[a-z0-9!#$&^_.+-]{1,63}$")

    // ---- The document ----------------------------------------------------------

    /** What is asked, with the facts the app states in its own words. */
    sealed class Request : ByContent() {
        /** `key`: a site's session key the user vouches for, an uncompressed P-256 point. */
        class SignIn(val sessionUntil: Long, val key: ByteArray?) : Request()
        class Grant(val until: Long, val scopes: List<String>, val key: ByteArray?) : Request()
        class Statement(val attachment: Attachment?) : Request()

        override fun toString(): String = when (this) {
            is SignIn -> "SignIn($sessionUntil, ${key?.hex()})"
            is Grant -> "Grant($until, $scopes, ${key?.hex()})"
            is Statement -> "Statement($attachment)"
        }
    }

    /** A file a statement covers: its SHA-256, media type and name. The site serves it; the app checks it against the hash. */
    class Attachment(val hash: ByteArray, val mediaType: String, val name: String) : ByContent() {
        override fun toString() = "Attachment(${hash.hex()}, $mediaType, $name)"
    }

    /** A detail the site adds, shown as it wrote it: a label and a value, one line each. */
    data class Field(val label: String, val value: String)

    /** What the user reads before signing. */
    data class Document(
        val request: Request,
        val title: String,
        /** Paragraphs, **bold**, *italic* and lists; anything else shows as written. */
        val body: String,
        val fields: List<Field>,
    )

    private const val KIND_SIGN_IN = 1
    private const val KIND_GRANT = 2
    private const val KIND_STATEMENT = 3
    private const val P256 = 1

    /** Characters that could hide or reorder what is shown: controls, direction overrides, invisible marks. */
    private fun hidden(c: Int, multiline: Boolean): Boolean {
        if (c == 0x0a) return !multiline
        return c < 0x20 ||
            c in 0x7f..0x9f ||
            c == 0xad ||
            c == 0x34f ||
            c == 0x61c ||
            c == 0x115f ||
            c == 0x1160 ||
            c == 0x180e ||
            c in 0x200b..0x200f ||
            c in 0x2028..0x202e ||
            c in 0x2060..0x2069 ||
            c == 0x3164 ||
            c in 0xd800..0xdfff ||
            c in 0xfdd0..0xfdef ||
            c == 0xfeff ||
            c == 0xffa0 ||
            (c and 0xfffe) == 0xfffe ||
            c in 0xe0000..0xe007f
    }

    /** Text as the app shows it: nothing hidden, normalized, and a single line unless `multiline`. */
    private fun checkText(text: String, what: String, multiline: Boolean = false): String {
        // A lone surrogate comes out of codePoints() as itself, and is refused as hidden.
        if (text.codePoints().anyMatch { hidden(it, multiline) }) throw OffchainError("$what holds a character that cannot be shown")
        if (!Normalizer.isNormalized(text, Normalizer.Form.NFC)) throw OffchainError("$what is not normalized text")
        return text
    }

    private fun textOf(bytes: ByteArray, what: String, multiline: Boolean = false): String {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            throw OffchainError("$what is not valid UTF-8")
        }
        // Only the shortest, valid encoding of the same characters comes back the same.
        if (!utf8(text).contentEquals(bytes)) throw OffchainError("$what is not valid UTF-8")
        return checkText(text, what, multiline)
    }

    private fun readText(r: Reader, what: String, multiline: Boolean = false): String = textOf(r.bytes(), what, multiline)

    private fun line(text: String, what: String): String {
        if (text.isEmpty() || trimWhiteSpace(text) != text) throw OffchainError("$what must be a non-empty line without surrounding spaces")
        return checkText(text, what)
    }

    private fun writeKey(w: Writer, key: ByteArray?) {
        if (key == null) {
            w.u8(0)
            return
        }
        if (key.size != 65 || key[0] != 4.toByte()) throw OffchainError("a key must be an uncompressed P-256 point")
        w.u8(P256).bytes(key)
    }

    private fun readKey(r: Reader): ByteArray? {
        val has = r.u8()
        if (has == 0) return null
        if (has != P256) throw OffchainError("unknown key scheme")
        val key = r.bytes()
        if (key.size != 65 || key[0] != 4.toByte()) throw OffchainError("a key must be an uncompressed P-256 point")
        return key
    }

    fun encodeDocument(doc: Document): ByteArray {
        val w = Writer().u8(VERSION)
        when (val q = doc.request) {
            is Request.SignIn -> {
                w.u8(KIND_SIGN_IN).u64(q.sessionUntil)
                writeKey(w, q.key)
            }
            is Request.Grant -> {
                w.u8(KIND_GRANT).u64(q.until).u32(q.scopes.size)
                for (s in q.scopes) {
                    if (!SCOPE.matches(s)) throw OffchainError("scope \"$s\" must be lowercase letters, digits and : . _ / -")
                    w.bytes(utf8(s))
                }
                writeKey(w, q.key)
            }
            is Request.Statement -> {
                w.u8(KIND_STATEMENT)
                val a = q.attachment
                if (a == null) {
                    w.u8(0)
                } else {
                    if (a.hash.size != 32 || !MEDIA_TYPE.matches(a.mediaType)) throw OffchainError("an attachment needs a 32-byte hash and a media type")
                    w.u8(1).put(a.hash).bytes(utf8(a.mediaType)).bytes(utf8(line(a.name, "the attachment name")))
                }
            }
        }
        w.bytes(utf8(line(doc.title, "the title"))).bytes(utf8(checkText(doc.body, "the text", true))).u32(doc.fields.size)
        for (f in doc.fields) w.bytes(utf8(line(f.label, "a label"))).bytes(utf8(line(f.value, "\"${f.label}\"")))
        val bytes = w.finish()
        if (bytes.size > MAX_DOCUMENT) throw OffchainError("the document is too long to show")
        return bytes
    }

    /**
     * The document in `bytes`, or an error saying why the app will not sign it.
     * Decoding is exact: any unknown version or kind, any trailing byte and any
     * text that could hide something is refused, never skipped.
     */
    fun decodeDocument(bytes: ByteArray): Document {
        if (bytes.size > MAX_DOCUMENT) throw OffchainError("the document is too long to show")
        try {
            val r = Reader(bytes)
            if (r.u8() != VERSION) throw OffchainError("unknown document version")
            val request = when (r.u8()) {
                KIND_SIGN_IN -> Request.SignIn(r.u64(), readKey(r))
                KIND_GRANT -> {
                    val until = r.u64()
                    val count = r.u32()
                    // Each scope takes at least 5 bytes.
                    if (count > r.remaining / 5) throw OffchainError("the document is malformed")
                    val scopes = (0 until count).map {
                        val s = readText(r, "a scope")
                        if (!SCOPE.matches(s)) throw OffchainError("a scope may hold only lowercase letters, digits and : . _ / -")
                        s
                    }
                    Request.Grant(until, scopes, readKey(r))
                }
                KIND_STATEMENT -> {
                    val has = r.u8()
                    if (has > 1) throw OffchainError("unknown attachment marker")
                    val attachment = if (has == 1) {
                        val hash = r.array32()
                        val mediaType = readText(r, "the media type")
                        if (!MEDIA_TYPE.matches(mediaType)) throw OffchainError("the media type is not one")
                        Attachment(hash, mediaType, line(readText(r, "the attachment name"), "the attachment name"))
                    } else {
                        null
                    }
                    Request.Statement(attachment)
                }
                else -> throw OffchainError("unknown kind of request")
            }
            val title = line(readText(r, "the title"), "the title")
            val body = readText(r, "the text", true)
            val count = r.u32()
            if (count > r.remaining / 8) throw OffchainError("the document is malformed")
            val fields = (0 until count).map {
                val label = line(readText(r, "a label"), "a label")
                Field(label, line(readText(r, "\"$label\""), "\"$label\""))
            }
            r.end()
            return Document(request, title, body, fields)
        } catch (e: DecodeError) {
            throw OffchainError("the document is malformed")
        }
    }

    fun documentHash(bytes: ByteArray): ByteArray = taggedHash(DOCUMENT_TAG, bytes)

    // ---- The body as the app lays it out -----------------------------------------

    data class Span(val text: String, val bold: Boolean = false, val italic: Boolean = false)

    sealed class Block {
        data class Paragraph(val lines: List<List<Span>>) : Block()
        data class ListBlock(val items: List<Item>) : Block()
    }

    data class Item(val marker: String, val spans: List<Span>)

    /** Markers a line may hold and still be formatted: matching them costs up to this many passes over the line. */
    private const val MARKS_MAX = 200

    /**
     * `**bold**` and `*italic*`, not nested and not starting or ending with
     * white space; anything else stays as written (TOCS-1.md, the body). A line
     * with more markers than [MARKS_MAX] stays plain, so a long line of stray
     * ones cannot make the matching take time in the square of its length.
     */
    fun spans(text: String): List<Span> {
        val stars = text.indices.filter { text[it] == '*' }
        if (stars.size > MARKS_MAX) return listOf(Span(text))
        fun solid(at: Int) = at < text.length && !isWhiteSpace(text.codePointAt(at)) && !Character.isLowSurrogate(text[at])
        fun solidEnd(at: Int) = at >= 0 && !isWhiteSpace(text.codePointBefore(at + 1))
        /** The end (exclusive) of the shortest X from `from` closed by `close`, or -1. */
        fun close(from: Int, close: String): Int {
            if (!solid(from)) return -1
            for (s in stars) {
                if (s <= from || !text.startsWith(close, s)) continue
                if (solidEnd(s - 1)) return s
            }
            return -1
        }
        val out = ArrayList<Span>()
        var last = 0
        var i = 0
        while (i < text.length) {
            if (text[i] != '*') {
                i++
                continue
            }
            val boldEnd = if (text.startsWith("**", i)) close(i + 2, "**") else -1
            if (boldEnd >= 0) {
                if (i > last) out.add(Span(text.substring(last, i)))
                out.add(Span(text.substring(i + 2, boldEnd), bold = true))
                i = boldEnd + 2
                last = i
                continue
            }
            val italicEnd = close(i + 1, "*")
            if (italicEnd >= 0) {
                if (i > last) out.add(Span(text.substring(last, i)))
                out.add(Span(text.substring(i + 1, italicEnd), italic = true))
                i = italicEnd + 1
                last = i
                continue
            }
            i++
        }
        if (last < text.length) out.add(Span(text.substring(last)))
        return out
    }

    private val CHUNK = Regex("\n *\n")
    private val NUMBERED = Regex("^[0-9]{1,4}\\. ")

    /**
     * The body in blocks: paragraphs between blank lines, and lists whose lines
     * all start with `- ` or a number and `. `, numbered as written.
     */
    fun bodyBlocks(body: String): List<Block> {
        val blocks = ArrayList<Block>()
        for (chunk in body.split(CHUNK)) {
            val lines = chunk.split('\n').filter { trimWhiteSpace(it).isNotEmpty() }
            if (lines.isEmpty()) continue
            when {
                lines.all { it.startsWith("- ") } -> blocks.add(Block.ListBlock(lines.map { Item("•", spans(it.substring(2))) }))
                lines.all { NUMBERED.containsMatchIn(it) } -> blocks.add(
                    Block.ListBlock(lines.map { val dot = it.indexOf('.'); Item(it.substring(0, dot + 1), spans(it.substring(dot + 2))) }),
                )
                else -> blocks.add(Block.Paragraph(lines.map(::spans)))
            }
        }
        return blocks
    }

    // ---- The signed message ------------------------------------------------------

    /** `Bytes(tag) ‖ ChainId ‖ account ‖ Bytes(origin) ‖ Bytes(nonce) ‖ u64 issued_at ‖ u64 expires_at ‖ H(document)`. */
    fun signedMessage(r: SignRequest, account: ByteArray): ByteArray = Writer()
        .bytes(utf8(TAG))
        .put32(r.chainId)
        .put32(account)
        .bytes(utf8(r.origin))
        .bytes(r.nonce)
        .u64(r.issuedAt)
        .u64(r.expiresAt)
        .put(documentHash(r.document))
        .finish()

    /** Two digits both the site and the app show for a sign-in, so the user can see they belong together. */
    fun matchCode(nonce: ByteArray): String {
        val h = taggedHash(MATCH_TAG, nonce)
        return (((h[0].toInt() and 0xff) shl 8 or (h[1].toInt() and 0xff)) % 100).toString().padStart(2, '0')
    }

    // ---- Codes, requests and answers -------------------------------------------

    private const val CODE_PREFIX = "ting:sign?url="
    private const val CODE_PREFIX_SLASHES = "ting://sign?url="

    /**
     * An address as the app reads it: scheme, a host of plain ASCII letters,
     * digits, dots and dashes (or a bracketed IPv6 literal), an optional port,
     * then a path, query or fragment of printable ASCII. Nothing else is read
     * as an address, so the host the app connects to is the host it names,
     * whatever another parser would make of a stranger text.
     */
    private val ADDRESS = Regex("^([a-zA-Z]+)://([a-zA-Z0-9.-]+|\\[[0-9a-fA-F:.]+])(?::([0-9]{1,5}))?([/?#][\\x21-\\x7e]*)?$")

    /**
     * The origin of a site's address, `https://host[:port]`, or null when the
     * address may not ask for signatures: only HTTPS, and plain HTTP for this
     * computer while a site is being developed.
     */
    fun originOf(address: String): String? {
        // Plain ASCII only: a name with other letters can look like another site's.
        val m = ADDRESS.matchEntire(address) ?: return null
        val scheme = m.groupValues[1].lowercase()
        val host = m.groupValues[2].lowercase()
        if (host.isEmpty() || host.startsWith('.') || host.startsWith('-')) return null
        val port = m.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull()
        if (m.groupValues[3].isNotEmpty() && (port == null || port !in 1..65535)) return null
        val local = host == "localhost" || host == "127.0.0.1" || host == "[::1]"
        if (scheme != "https" && !(scheme == "http" && local)) return null
        val defaultPort = if (scheme == "https") 443 else 80
        return if (port == null || port == defaultPort) "$scheme://$host" else "$scheme://$host:$port"
    }

    /** The code a site shows: `ting:sign?url=<address of the request>`. */
    fun signCode(requestUrl: String): String = CODE_PREFIX + Links.encodeComponent(requestUrl)

    fun isSignCode(text: String): Boolean = text.trim().lowercase().let { it.startsWith(CODE_PREFIX) || it.startsWith(CODE_PREFIX_SLASHES) }

    /** The request address in a sign code, or null when it is not one the app may fetch. */
    fun parseSignCode(text: String): String? {
        val t = text.trim()
        val lower = t.lowercase()
        val at = when {
            lower.startsWith(CODE_PREFIX) -> CODE_PREFIX.length
            lower.startsWith(CODE_PREFIX_SLASHES) -> CODE_PREFIX_SLASHES.length
            else -> return null
        }
        val address = Links.decodeComponent(t.substring(at)) ?: return null
        return if (originOf(address) != null) address else null
    }

    private const val MAX_SAFE_INTEGER = (1L shl 53) - 1

    /** A whole number written as plain digits: no sign, fraction or exponent. */
    private fun integer(v: JsonElement?, what: String): Long {
        val n = (v as? JsonPrimitive)?.takeIf { !it.isString && it.content.isNotEmpty() && it.content.all { c -> c in '0'..'9' } }?.longOrNull
        if (n == null || n < 0 || n > MAX_SAFE_INTEGER) throw OffchainError("$what must be a whole number of seconds")
        return n
    }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Checks a request the app fetched from `fetchedFrom` at time `now` (seconds), before showing it. */
    fun readRequest(json: JsonElement?, fetchedFrom: String, now: Long): SignRequest {
        val j = json as? JsonObject
        if (j == null || j.text("type") != "ting-sign-request") throw OffchainError("this is not a Ting signing request")
        if ((j["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull != 1L) throw OffchainError("this request needs a newer Ting ID")
        val origin = originOf(fetchedFrom) ?: throw OffchainError("requests come only from secure sites")
        val chain = j.text("chain")
        if (chain == null || !isHex(chain, 32)) throw OffchainError("the request names no chain")
        val nonce = j.text("nonce")
        if (nonce == null || !isHex(nonce) || nonce.length < 32 || nonce.length > 128) throw OffchainError("the request needs a nonce of 16 to 64 bytes")
        val issuedAt = integer(j["issued_at"], "issued_at")
        val expiresAt = integer(j["expires_at"], "expires_at")
        if (expiresAt <= now) throw OffchainError("this request has expired; start again on the site")
        if (issuedAt > now + CLOCK_SKEW || expiresAt <= issuedAt || expiresAt - issuedAt > MAX_REQUEST_LIFETIME) {
            throw OffchainError("the request's times do not make sense; check this device's clock")
        }
        val signer = if (j.containsKey("signer")) {
            j.text("signer")?.takeIf { isHex(it, 32) } ?: throw OffchainError("the requested signer is not an ID")
        } else {
            null
        }
        val callback = j.text("callback")
        if (callback == null || originOf(callback) != origin) throw OffchainError("the answer would go to another site than the one asking")
        val returnTo = if (j.containsKey("return")) {
            j.text("return")?.takeIf { originOf(it) == origin } ?: throw OffchainError("the request would send you back to another site than the one asking")
        } else {
            null
        }
        val documentHex = j.text("document")
        if (documentHex == null || !isHex(documentHex)) throw OffchainError("the request holds no document")
        val document = unhex(documentHex)
        val doc = decodeDocument(document)
        val covers = (doc.request as? Request.Statement)?.attachment != null
        val file = j.text("file")
        if (covers && (file == null || originOf(file) != origin)) throw OffchainError("the statement covers a file the site does not offer")
        if (!covers && j.containsKey("file")) throw OffchainError("the request offers a file its statement does not cover")
        return SignRequest(
            chainId = unhex(chain),
            origin = origin,
            nonce = unhex(nonce),
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            document = document,
            signer = signer?.let(::unhex),
            request = doc,
            callback = callback,
            returnTo = returnTo,
            file = if (covers) file else null,
        )
    }

    /** Whether `bytes` are the file `attachment` names: its SHA-256 is the hash the statement signs. */
    fun isAttachment(bytes: ByteArray, attachment: Attachment): Boolean =
        java.security.MessageDigest.isEqual(sha256(bytes), attachment.hash)

    /** The app's answer: the policy input the site checks, as the chain would. */
    fun answerJson(nonce: ByteArray, account: ByteArray, policy: ByteArray, params: ByteArray, witness: ByteArray): JsonObject = buildJsonObject {
        put("type", "ting-sign-answer")
        put("version", 1)
        put("nonce", nonce.hex())
        put("account", account.hex())
        put("policy", policy.hex())
        put("params", params.hex())
        put("witness", witness.hex())
    }

    fun declineJson(nonce: ByteArray): JsonObject = buildJsonObject {
        put("type", "ting-sign-answer")
        put("version", 1)
        put("nonce", nonce.hex())
        put("declined", true)
    }
}

/** A request the app has checked: its origin is where it was fetched from. */
class SignRequest(
    val chainId: ByteArray,
    val origin: String,
    val nonce: ByteArray,
    val issuedAt: Long,
    val expiresAt: Long,
    val document: ByteArray,
    val signer: ByteArray?,
    val request: Offchain.Document,
    val callback: String,
    val returnTo: String?,
    /** Where to fetch the statement's file, when it covers one. */
    val file: String?,
)
