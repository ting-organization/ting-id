// HMAC-SHA512 (RFC 2104) and PBKDF2-HMAC-SHA512 (RFC 8018), the two
// functions that turn a recovery phrase into a key (BIP-39, BIP-32). Written
// over the platform's SHA-512 rather than taken from `javax.crypto.Mac`, so
// that every buffer holding the phrase or a key passes through this file and
// is overwritten once used; a `SecretKeySpec` keeps its own copy, which nothing
// can wipe. Checked against RFC 4231 and the BIP-39 vectors.
package app.tingid.chain

import java.security.MessageDigest

internal class HmacSha512(key: ByteArray) {
    private val inner: MessageDigest
    private val outer: MessageDigest

    init {
        val block = ByteArray(BLOCK)
        if (key.size > BLOCK) {
            val hashed = sha512().digest(key)
            hashed.copyInto(block)
            hashed.wipe()
        } else {
            key.copyInto(block)
        }
        val pad = ByteArray(BLOCK)
        inner = sha512()
        for (i in 0 until BLOCK) pad[i] = (block[i].toInt() xor 0x36).toByte()
        inner.update(pad)
        outer = sha512()
        for (i in 0 until BLOCK) pad[i] = (block[i].toInt() xor 0x5c).toByte()
        outer.update(pad)
        pad.wipe()
        block.wipe()
    }

    /** HMAC of the concatenated `parts`, leaving this instance ready for the next message. */
    fun mac(vararg parts: ByteArray): ByteArray {
        val i = inner.clone() as MessageDigest
        for (p in parts) i.update(p)
        val innerHash = i.digest()
        val o = outer.clone() as MessageDigest
        o.update(innerHash)
        innerHash.wipe()
        return o.digest()
    }

    /** Drops the keyed states. */
    fun close() {
        inner.reset()
        outer.reset()
    }

    companion object {
        const val BLOCK = 128
        const val SIZE = 64

        private fun sha512(): MessageDigest = MessageDigest.getInstance("SHA-512")

        fun mac(key: ByteArray, vararg parts: ByteArray): ByteArray {
            val h = HmacSha512(key)
            try {
                return h.mac(*parts)
            } finally {
                h.close()
            }
        }
    }
}

/** PBKDF2-HMAC-SHA512 with a 64-byte output: a single block, which is all BIP-39 asks for. */
internal fun pbkdf2Sha512(password: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
    require(iterations > 0) { "no iterations" }
    val prf = HmacSha512(password)
    try {
        var u = prf.mac(salt, byteArrayOf(0, 0, 0, 1))
        val out = u.copyOf()
        for (round in 1 until iterations) {
            val next = prf.mac(u)
            u.wipe()
            u = next
            for (k in out.indices) out[k] = (out[k].toInt() xor u[k].toInt()).toByte()
        }
        u.wipe()
        return out
    } finally {
        prf.close()
    }
}
