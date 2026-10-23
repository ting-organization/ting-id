// Ting links (LINKS.md): what one phone asks of another, as a QR code, as
// copied text, or tapped on the phone, which opens Ting ID.
//
//   ting:<request>?<field>=<value>&…
//
//   pay      net, to, coin?, amount?, note?     pay an account
//   create   net, input, name?, coin?, amount?  create an identity, and send it coins
//   device   net, id, scheme, key               add a device to an identity
//   recover  net, id, keys                      approve a recovery to new keys
//   sign     url                                a site's signing request (TOCS-1.md)
//
// 32-byte IDs and accounts are lowercase hex, longer bytes base64url without
// padding, amounts decimal in the coin's units, text percent-encoded. `net`
// is the first 8 hex digits of the chain ID, so a link for another network is
// refused. The scheme, the request and field names are read without regard to
// case, a `//` after the scheme is tolerated, and unknown fields are ignored.
package app.tingid.chain

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

sealed class Link(val request: String) {
    class Pay(val net: String, val to: String, val coin: String? = null, val amount: String? = null, val note: String? = null) : Link("pay")
    class Create(val net: String, val input: ByteArray, val name: String? = null, val coin: String? = null, val amount: String? = null) : Link("create")
    class Device(val net: String, val id: String, val scheme: Scheme, val key: ByteArray) : Link("device")
    class Recover(val net: String, val id: String, val keys: ByteArray) : Link("recover")
    class Sign(val url: String) : Link("sign")
}

/** Why a text is no link this app can follow. */
class LinkError(message: String) : Exception(message)

object Links {
    const val SCHEME = "ting:"
    private val REQUESTS = listOf("pay", "create", "device", "recover", "sign")

    /** The longest name or note a link carries. */
    const val TEXT_MAX = 80

    /**
     * Characters a name or note may not hold: controls and line breaks, marks
     * that turn the direction of text, and invisible ones, any of which could
     * make a name read as another.
     */
    private val HIDING = Regex("[\\u0000-\\u001f\\u007f-\\u009f\\u061c\\u200b-\\u200f\\u2028-\\u202e\\u2060-\\u2069\\ufeff]")

    /** The network tag of a chain: the first 8 hex digits of its ID. */
    fun netOf(chainId: ByteArray): String = chainId.hex().take(8)

    /** Whether `text` is written as a Ting link at all. */
    fun isLink(text: String): Boolean = trimWhiteSpace(text).lowercase().startsWith(SCHEME)

    fun encode(link: Link): String {
        val fields: List<Pair<String, String?>> = when (link) {
            is Link.Pay -> listOf("to" to link.to, "coin" to link.coin, "amount" to link.amount, "note" to link.note, "net" to link.net)
            is Link.Create -> listOf("input" to base64url(link.input), "name" to link.name, "coin" to link.coin, "amount" to link.amount, "net" to link.net)
            is Link.Device -> listOf("id" to link.id, "scheme" to link.scheme.id, "key" to link.key.hex(), "net" to link.net)
            is Link.Recover -> listOf("id" to link.id, "keys" to base64url(link.keys), "net" to link.net)
            is Link.Sign -> listOf("url" to link.url)
        }
        val query = fields.filter { !it.second.isNullOrEmpty() }.joinToString("&") { (k, v) -> "$k=${encodeComponent(v!!)}" }
        return "$SCHEME${link.request}?$query"
    }

    /** The link `text` holds; throws [LinkError], saying what is wrong, when it holds none this app follows. */
    fun parse(text: String): Link {
        var rest = trimWhiteSpace(text)
        if (!isLink(rest)) throw LinkError("this is not a Ting link")
        rest = rest.substring(SCHEME.length)
        if (rest.startsWith("//")) rest = rest.substring(2)
        val q = rest.indexOf('?')
        val request = (if (q < 0) rest else rest.substring(0, q)).removeSuffix("/").lowercase()
        if (request !in REQUESTS) throw LinkError("this Ting link asks for something this version of Ting ID does not know")
        val fields = HashMap<String, String>()
        for (part in if (q < 0) emptyList() else rest.substring(q + 1).split('&')) {
            if (part.isEmpty()) continue
            val eq = part.indexOf('=')
            val key = (if (eq < 0) part else part.substring(0, eq)).lowercase()
            val value = decodeComponent((if (eq < 0) "" else part.substring(eq + 1)).replace('+', ' '))
                ?: throw LinkError("the link’s $key is not written right")
            if (fields.containsKey(key)) throw LinkError("the link gives $key twice")
            fields[key] = value
        }
        fun need(key: String): String = fields[key]?.takeIf { it.isNotEmpty() } ?: throw LinkError("the link has no $key")
        fun id32(key: String, v: String): String {
            if (!isHex(v, 32)) throw LinkError("the link’s $key is not 64 hex digits")
            return v.lowercase()
        }
        fun bytes(key: String): ByteArray {
            val b = fromBase64url(need(key))
            if (b == null || b.isEmpty()) throw LinkError("the link’s $key is not written right")
            return b
        }
        fun <T> optional(key: String, read: (String) -> T): T? = fields[key]?.takeIf { it.isNotEmpty() }?.let(read)
        fun amount(v: String): String {
            if (!Regex("^[0-9]+(\\.[0-9]+)?$").matches(v)) throw LinkError("the link’s amount is not a number like 12.5")
            return v
        }
        fun textField(key: String): (String) -> String = { v ->
            if (v.length > TEXT_MAX) throw LinkError("the link’s $key is longer than $TEXT_MAX characters")
            if (HIDING.containsMatchIn(v)) throw LinkError("the link’s $key holds characters that could hide or reorder what it says")
            trimWhiteSpace(v)
        }
        if (request == "sign") return Link.Sign(need("url"))
        val net = need("net").lowercase()
        if (!Regex("^[0-9a-f]{8}$").matches(net)) throw LinkError("the link’s network is not 8 hex digits")
        return when (request) {
            "pay" -> Link.Pay(
                net = net,
                to = id32("account", need("to")),
                coin = optional("coin") { id32("coin", it) },
                amount = optional("amount", ::amount),
                note = optional("note", textField("note")),
            )
            "create" -> Link.Create(
                net = net,
                input = bytes("input"),
                name = optional("name", textField("name")),
                coin = optional("coin") { id32("coin", it) },
                amount = optional("amount", ::amount),
            )
            "device" -> {
                val scheme = Scheme.of(need("scheme").lowercase()) ?: throw LinkError("the link names a key scheme this app does not know")
                val key = need("key")
                if (!isHex(key, 65) || !isPublicKey(scheme, unhex(key.lowercase()))) throw LinkError("the link’s key is not a public key")
                Link.Device(net = net, id = id32("identity ID", need("id")), scheme = scheme, key = unhex(key.lowercase()))
            }
            else -> Link.Recover(net = net, id = id32("identity ID", need("id")), keys = bytes("keys"))
        }
    }

    /** The link in `text` when it is one of type `T`; throws [LinkError] otherwise. */
    inline fun <reified T : Link> parseOf(text: String, request: String): T {
        val link = parse(text)
        if (link !is T) throw LinkError("this link is a ${link.request} link, not a $request link")
        return link
    }

    /** The account `text` names: a bare 64-digit account, or the account a pay link pays; null otherwise. */
    fun accountIn(text: String): String? {
        val t = trimWhiteSpace(text)
        if (isHex(t, 32)) return t.lowercase()
        if (!isLink(t)) return null
        return try {
            (parse(t) as? Link.Pay)?.to
        } catch (e: LinkError) {
            null
        }
    }

    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"

    /** As JavaScript's `encodeURIComponent`: UTF-8, everything but the unreserved characters percent-encoded. */
    fun encodeComponent(text: String): String {
        val out = StringBuilder()
        for (b in text.encodeToByteArray()) {
            val c = (b.toInt() and 0xff)
            if (c < 0x80 && UNRESERVED.indexOf(c.toChar()) >= 0) out.append(c.toChar())
            else out.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
        }
        return out.toString()
    }

    /** As JavaScript's `decodeURIComponent`, but null where that throws: a stray `%`, or bytes that are not UTF-8. */
    fun decodeComponent(text: String): String? {
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            if (text[i] == '%') {
                if (i + 2 >= text.length) return null
                val hi = Character.digit(text[i + 1], 16)
                val lo = Character.digit(text[i + 2], 16)
                if (hi < 0 || lo < 0) return null
                bytes.write(hi * 16 + lo)
                i += 3
            } else {
                val end = if (Character.isHighSurrogate(text[i]) && i + 1 < text.length) i + 2 else i + 1
                bytes.write(text.substring(i, end).encodeToByteArray())
                i = end
            }
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }
}
