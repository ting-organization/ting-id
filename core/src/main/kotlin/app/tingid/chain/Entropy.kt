// Where secrets come from. Every key and every recovery phrase Ting ID makes
// starts as bytes from the operating system's random generator: on Android,
// `SecureRandom` is BoringSSL's generator, fed by the kernel's. Nothing else is
// mixed in and nothing seeds it, since a seed could only make it weaker.
package app.tingid.chain

import java.security.MessageDigest
import java.security.SecureRandom

/** Random bytes for secrets; fixed bytes in tests. */
fun interface Entropy {
    fun bytes(n: Int): ByteArray
}

/** The random generator failed a health test: nothing it gave may become a secret. */
class EntropyFailure(message: String) : Exception(message)

/**
 * The system's generator, with a continuous health test on every draw of 16
 * bytes or more: a draw where every byte is the same, or a draw equal to the
 * one before it, means the generator is broken, and is refused rather than
 * turned into a key. The odds of either from a working generator are below
 * 2^-120. The previous draw is remembered only as its hash.
 */
class SystemEntropy(private val random: SecureRandom = SecureRandom()) : Entropy {
    private var lastHash: ByteArray? = null

    @Synchronized
    override fun bytes(n: Int): ByteArray {
        require(n > 0) { "no bytes asked for" }
        val out = ByteArray(n)
        random.nextBytes(out)
        if (n >= 16) {
            if (out.all { it == out[0] }) {
                out.wipe()
                throw EntropyFailure("the phone's random generator gave the same byte over and over")
            }
            val hash = MessageDigest.getInstance("SHA-256").digest(out)
            if (lastHash?.let { MessageDigest.isEqual(it, hash) } == true) {
                out.wipe()
                throw EntropyFailure("the phone's random generator repeated itself")
            }
            lastHash = hash
        }
        return out
    }
}
