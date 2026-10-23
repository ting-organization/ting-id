// Little-endian wire formats shared by every Ting application
// (wiki: data/encoding): fixed-width integers, `Bytes(x)` = u32 length then
// the bytes, and exact decoding that rejects trailing data.
package app.tingid.chain

import java.math.BigInteger

private const val HEX = "0123456789abcdef"

fun ByteArray.hex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val b = this[i].toInt() and 0xff
        out[2 * i] = HEX[b shr 4]
        out[2 * i + 1] = HEX[b and 15]
    }
    return String(out)
}

private fun hexDigit(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}

fun isHex(text: String, bytes: Int? = null): Boolean {
    val t = text.trim()
    if (t.length % 2 != 0 || t.any { hexDigit(it) < 0 }) return false
    return bytes == null || t.length == bytes * 2
}

fun unhex(text: String): ByteArray {
    val t = text.trim()
    // The text is not repeated: it may be a secret.
    if (!isHex(t)) throw IllegalArgumentException("not hex (${t.length} characters)")
    return ByteArray(t.length / 2) { i -> ((hexDigit(t[2 * i]) shl 4) or hexDigit(t[2 * i + 1])).toByte() }
}

fun concat(vararg parts: ByteArray): ByteArray {
    val out = ByteArray(parts.sumOf { it.size })
    var at = 0
    for (p in parts) {
        p.copyInto(out, at)
        at += p.size
    }
    return out
}

fun equal(a: ByteArray, b: ByteArray): Boolean = a.contentEquals(b)

/** Byte-wise (lexicographic) order: negative, zero or positive. */
fun compare(a: ByteArray, b: ByteArray): Int {
    val n = minOf(a.size, b.size)
    for (i in 0 until n) {
        val x = a[i].toInt() and 0xff
        val y = b[i].toInt() and 0xff
        if (x != y) return x - y
    }
    return a.size - b.size
}

val BYTE_ORDER: Comparator<ByteArray> = Comparator { a, b -> compare(a, b) }

/** Whether every value is above the one before it. */
fun <T : Comparable<T>> strictlyIncreasing(values: List<T>): Boolean =
    values.indices.all { i -> i == 0 || values[i] > values[i - 1] }

fun filled(value: Int, length: Int = 32): ByteArray = ByteArray(length) { value.toByte() }

fun utf8(text: String): ByteArray = text.encodeToByteArray()

fun fromUtf8(bytes: ByteArray): String = bytes.decodeToString()

/** Overwrites a buffer that held a secret. */
fun ByteArray.wipe() = fill(0)

private const val BASE64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

/** `bytes` in base64url without padding (RFC 4648 §5), as links carry them. */
fun base64url(bytes: ByteArray): String {
    val out = StringBuilder()
    var i = 0
    while (i < bytes.size) {
        val b0 = bytes[i].toInt() and 0xff
        val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else 0
        val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else 0
        val n = (b0 shl 16) or (b1 shl 8) or b2
        val chars = minOf(4, ((bytes.size - i) * 8 + 5) / 6)
        for (k in 0 until chars) out.append(BASE64URL[(n shr (18 - 6 * k)) and 63])
        i += 3
    }
    return out.toString()
}

/** The bytes of unpadded base64url text; null when it is not exactly that. */
fun fromBase64url(text: String): ByteArray? {
    if (text.any { BASE64URL.indexOf(it) < 0 } || text.length % 4 == 1) return null
    val out = ByteArray(text.length * 3 / 4)
    var bits = 0
    var acc = 0
    var at = 0
    for (ch in text) {
        acc = ((acc shl 6) or BASE64URL.indexOf(ch)) and 0xffffff
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out[at++] = ((acc shr bits) and 255).toByte()
        }
    }
    // The bits left over must be zero, so each text has one reading.
    if (acc and ((1 shl bits) - 1) != 0) return null
    return out.copyOf(at)
}

val U64_MAX: BigInteger = BigInteger.ONE.shiftLeft(64) - BigInteger.ONE
val U128_MAX: BigInteger = BigInteger.ONE.shiftLeft(128) - BigInteger.ONE

private fun le(value: Long, width: Int): ByteArray {
    var v = value
    return ByteArray(width) { (v and 0xff).toByte().also { v = v ushr 8 } }
}

fun u32le(value: Long): ByteArray {
    require(value in 0..0xffffffffL) { "u32 out of range" }
    return le(value, 4)
}

fun u32le(value: Int): ByteArray = u32le(value.toLong())

/**
 * A u64. Ting's u64s are heights, times, nonces, counts and ids, never near
 * 2^63, so they are Kotlin Longs, and a larger one is refused both ways.
 */
fun u64le(value: Long): ByteArray {
    require(value >= 0) { "u64 out of range" }
    return le(value, 8)
}

fun u128le(value: BigInteger): ByteArray {
    require(value.signum() >= 0 && value <= U128_MAX) { "u128 out of range" }
    val big = value.toByteArray().reversedArray()
    return ByteArray(16) { if (it < big.size) big[it] else 0 }
}

/** Append-only encoder. */
class Writer {
    private val out = java.io.ByteArrayOutputStream()

    fun put(bytes: ByteArray) = apply { out.write(bytes) }

    /** A 32-byte id, account or hash. */
    fun put32(bytes: ByteArray) = apply {
        require(bytes.size == 32) { "expected 32 bytes, not ${bytes.size}" }
        out.write(bytes)
    }
    fun u8(v: Int) = apply {
        require(v in 0..255) { "u8 out of range" }
        out.write(v)
    }
    fun u16(v: Int) = apply {
        require(v in 0..0xffff) { "u16 out of range" }
        put(le(v.toLong(), 2))
    }
    fun u32(v: Long) = put(u32le(v))
    fun u32(v: Int) = put(u32le(v))
    fun u64(v: Long) = put(u64le(v))
    fun u64(v: Int) = put(u64le(v.toLong()))
    fun u128(v: BigInteger) = put(u128le(v))

    /** `Bytes(x)`. */
    fun bytes(v: ByteArray) = u32(v.size).put(v)

    fun finish(): ByteArray = out.toByteArray()
}

class DecodeError(message: String) : Exception(message)

/** Exact decoder: every read is bounds-checked and `end()` rejects leftovers. */
class Reader(private val buf: ByteArray) {
    private var at = 0

    val remaining: Int get() = buf.size - at

    fun take(n: Int): ByteArray {
        if (n < 0 || n > remaining) throw DecodeError("truncated")
        return buf.copyOfRange(at, at + n).also { at += n }
    }

    fun take(n: Long): ByteArray {
        if (n < 0 || n > remaining) throw DecodeError("truncated")
        return take(n.toInt())
    }

    fun rest(): ByteArray = buf.copyOfRange(at, buf.size)
    fun position(): Int = at
    fun array32(): ByteArray = take(32)
    fun u8(): Int = take(1)[0].toInt() and 0xff

    private fun fromLe(bytes: ByteArray): Long {
        var v = 0L
        for (i in bytes.indices.reversed()) v = (v shl 8) or (bytes[i].toLong() and 0xff)
        return v
    }

    fun u16(): Int = fromLe(take(2)).toInt()
    fun u32(): Long = fromLe(take(4))
    /** A u64, refused from 2^63 on (see [u64le]). */
    fun u64(): Long {
        val v = fromLe(take(8))
        if (v < 0) throw DecodeError("u64 too large")
        return v
    }

    fun u128(): BigInteger = BigInteger(1, take(16).reversedArray())

    fun bytes(): ByteArray = take(u32())

    /** `u64 count` of fixed-size items as one slice. */
    fun items(size: Int): ByteArray {
        val count = u64()
        if (count > remaining / maxOf(size, 1)) throw DecodeError("truncated")
        return take(count * size)
    }

    fun end() {
        if (remaining != 0) throw DecodeError("trailing bytes")
    }
}

/** Runs `f` and returns `null` instead of throwing a decode error. */
inline fun <T> tryDecode(f: () -> T): T? = try {
    f()
} catch (e: DecodeError) {
    null
}

/**
 * Equality by content for classes that hold byte arrays, which compare by
 * reference: two are equal when their `toString`s are, so a subclass's
 * `toString` shows every field (bytes in hex).
 */
abstract class ByContent {
    override fun equals(other: Any?): Boolean = other != null && other::class == this::class && other.toString() == toString()
    override fun hashCode(): Int = toString().hashCode()
}

/**
 * Unicode's White_Space property. Spelled out, because what `\s` and `trim()`
 * take for white space differs between the JVM and Android's regex engine.
 */
fun isWhiteSpace(cp: Int): Boolean =
    cp in 0x09..0x0d || cp == 0x20 || cp == 0x85 || cp == 0xa0 || cp == 0x1680 || cp in 0x2000..0x200a ||
        cp == 0x2028 || cp == 0x2029 || cp == 0x202f || cp == 0x205f || cp == 0x3000

/** The same characters as a regex class body, for `[…]` and `[^…]`. */
const val WHITE_SPACE = "\\t\\n\\u000B\\f\\r \\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000"

/** `text` without White_Space at either end. */
fun trimWhiteSpace(text: String): String {
    var start = 0
    var end = text.length
    while (start < end && isWhiteSpace(text.codePointAt(start))) start += Character.charCount(text.codePointAt(start))
    while (end > start && isWhiteSpace(text.codePointBefore(end))) end -= Character.charCount(text.codePointBefore(end))
    return text.substring(start, end)
}
