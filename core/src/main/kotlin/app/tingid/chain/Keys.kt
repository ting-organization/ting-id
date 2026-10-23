// Signature schemes of the standard policies (wiki: programs/policies):
// ECDSA on secp256k1 or P-256, uncompressed SEC1 public keys, low-S compact
// signatures over the signing digest.
//
// Ting ID makes and signs with secp256k1 keys only, through libsecp256k1
// (Bitcoin Core's library: constant-time, RFC 6979 nonces, low-S), so signing
// needs no randomness. P-256 keys only come from elsewhere — another device's
// link, an identity's key list — and are only checked to be on the curve.
package app.tingid.chain

import fr.acinq.secp256k1.Secp256k1
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.math.BigInteger

@Serializable
enum class Scheme(val id: String, val label: String) {
    @SerialName("secp256k1")
    Secp256k1("secp256k1", "secp256k1"),

    @SerialName("p256")
    P256("p256", "P-256");

    companion object {
        fun of(id: String): Scheme? = entries.firstOrNull { it.id == id }
    }
}

/** A secp256k1 secret key: 32 bytes in [1, n). */
fun isValidSecret(secret: ByteArray): Boolean = secret.size == 32 && Secp256k1.secKeyVerify(secret)

/**
 * A fresh secp256k1 secret from `entropy`, drawn again in the astronomically
 * rare case the bytes are not a valid key.
 */
fun randomSecret(entropy: Entropy): ByteArray {
    while (true) {
        val secret = entropy.bytes(32)
        if (isValidSecret(secret)) return secret
        secret.wipe()
    }
}

/** Uncompressed SEC1 public key of a secp256k1 secret: the policy parameters of a key account. */
fun publicKey(secret: ByteArray): ByteArray {
    require(isValidSecret(secret)) { "not a secp256k1 secret key" }
    return Secp256k1.pubkeyCreate(secret)
}

/**
 * Low-S compact `r ‖ s` over a 32-byte digest. The signature is checked
 * against the public key before it leaves: a fault while signing
 * deterministically can give away the key, and a checked signature cannot.
 */
fun signDigest(secret: ByteArray, digest: ByteArray): ByteArray {
    require(digest.size == 32) { "a digest is 32 bytes" }
    require(isValidSecret(secret)) { "not a secp256k1 secret key" }
    val signature = Secp256k1.sign(digest, secret)
    check(signature.size == 64 && Secp256k1.verify(signature, digest, Secp256k1.pubkeyCreate(secret))) { "the signature did not check out" }
    return signature
}

/** Whether `signature` is a valid low-S secp256k1 signature of `digest` by `pub`. */
fun verifyDigest(pub: ByteArray, digest: ByteArray, signature: ByteArray): Boolean {
    if (signature.size != 64 || digest.size != 32 || !isPublicKey(Scheme.Secp256k1, pub)) return false
    return try {
        Secp256k1.verify(signature, digest, pub)
    } catch (e: Exception) {
        false
    }
}

/** Whether `pub` is an uncompressed public key on the curve of `scheme`. */
fun isPublicKey(scheme: Scheme, pub: ByteArray): Boolean {
    if (pub.size != 65 || pub[0] != 4.toByte()) return false
    return when (scheme) {
        Scheme.Secp256k1 -> try {
            Secp256k1.pubkeyParse(pub)
            true
        } catch (e: Exception) {
            false
        }
        Scheme.P256 -> P256.onCurve(pub)
    }
}

/** P-256 (secp256r1) public keys: only checked, never made or used here. */
internal object P256 {
    private val p = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    private val b = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)

    fun onCurve(pub: ByteArray): Boolean {
        val x = BigInteger(1, pub.copyOfRange(1, 33))
        val y = BigInteger(1, pub.copyOfRange(33, 65))
        if (x >= p || y >= p) return false
        // y² = x³ − 3x + b (mod p); the point at infinity has no such encoding.
        val left = y.multiply(y).mod(p)
        val right = x.pow(3).subtract(x.multiply(BigInteger.valueOf(3))).add(b).mod(p)
        return left == right
    }
}
