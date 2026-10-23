// Ting's domain-separated hashing (wiki: data/encoding#domain-separated-hashing):
// H(tag, x) = SHA256("Ting/" || u16le(len(tag)) || tag || x).
package app.tingid.chain

import java.security.MessageDigest

private val PREFIX = utf8("Ting/")

fun sha256(vararg parts: ByteArray): ByteArray {
    val d = MessageDigest.getInstance("SHA-256")
    for (p in parts) d.update(p)
    return d.digest()
}

fun taggedHash(tag: String, vararg parts: ByteArray): ByteArray {
    val t = utf8(tag)
    require(t.size <= 0xffff) { "tag too long" }
    val len = byteArrayOf((t.size and 255).toByte(), (t.size shr 8).toByte())
    val d = MessageDigest.getInstance("SHA-256")
    d.update(PREFIX)
    d.update(len)
    d.update(t)
    for (p in parts) d.update(p)
    return d.digest()
}
