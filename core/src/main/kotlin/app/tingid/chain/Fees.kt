// Who signs, and paying for an operation that cannot pay for itself: a
// registry operation, or anything from a time-locked account. The
// operation's calls go into a batch and, last, a zero transfer from the
// paying account that carries the fee. The payment is bound to exactly those
// calls (the coin's `within` condition), so the miner is paid only for
// including them.
package app.tingid.chain

import java.math.BigInteger

/**
 * A key that signs digests. The app's keys live sealed in the phone's
 * keystore and are opened only inside [sign], for the one signature; nothing
 * else ever holds the secret.
 */
interface SigningKey {
    /** The uncompressed secp256k1 public key. */
    val publicKey: ByteArray

    /** A low-S compact secp256k1 signature of a 32-byte digest. Blocks while the keystore opens the key: never on the main thread. */
    fun sign(digest: ByteArray): ByteArray
}

/** A secret held in memory, for tests and for keys that are only ever used once. */
class MemoryKey(private val secret: ByteArray) : SigningKey {
    override val publicKey: ByteArray = publicKey(secret)
    override fun sign(digest: ByteArray): ByteArray = signDigest(secret, digest)
}

/** What signs for an account: a key, and the policy and parameters the account is made of. */
class Signer(
    val key: SigningKey,
    /** For an identity account: which key of the identity signs. */
    val keyId: Long?,
    val policy: ByteArray,
    val params: ByteArray,
) {
    /** The account this signer acts for. */
    val account: ByteArray get() = policyAccount(policy, params)

    /** The witness for a message: the signature, with the key id for an identity account. */
    fun witness(message: ByteArray): ByteArray {
        val signature = key.sign(signingDigest(message))
        return if (keyId == null) signature else Id.keyAuth(keyId, signature)
    }
}

/** An instant account that pays `fee` of coin `coinId`. */
class FeePayer(val signer: Signer, val coinId: ByteArray, val fee: BigInteger)

class FeePayment(val call: BatchCall, val action: Coin.Action, val message: ByteArray, val account: ByteArray)

/**
 * The payment that goes last in a batch after `calls`: a transfer of 0 to the
 * payer's own account with the fee, valid only as a call of `c.batch` after
 * exactly `calls`, and only until `validUntil` (seconds), so that one whose
 * operation went in alone does not stay usable (wiki: programs/conventions).
 * Signed with the payer's account nonce `nonce`.
 */
fun feePayment(chain: ByteArray, c: Canonical, calls: List<BatchCall>, payer: FeePayer, nonce: Long, validUntil: Long): FeePayment {
    val account = payer.signer.account
    val action = Coin.Action(
        effect = Coin.Effect.Transfer(account, BigInteger.ZERO),
        timing = Coin.Timing(fee = payer.fee, validUntil = validUntil, within = Coin.Within(c.batch, batchPrefix(calls))),
    )
    val message = Coin.message(chain, payer.coinId, account, nonce, action)
    val s = payer.signer
    val input = Coin.executeInput(Coin.PolicyAuth(s.policy, s.params, nonce, s.witness(message)), action)
    return FeePayment(BatchCall(payer.coinId, input), action, message, account)
}

/** The batch transaction of `calls` followed by the payer's bound fee payment. */
fun sponsoredCall(chain: ByteArray, c: Canonical, calls: List<BatchCall>, payer: FeePayer, payerNonce: Long, validUntil: Long): ByteArray {
    val payment = feePayment(chain, c, calls, payer, payerNonce, validUntil)
    return encodeCall(c.batch, batchInput(calls + payment.call), Budgets.BATCH)
}
