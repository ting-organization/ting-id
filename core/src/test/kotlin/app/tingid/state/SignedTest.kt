// What a transaction's own bytes say it signed: the nonces it takes, and until
// when a block can take it (state/Signed.kt).
package app.tingid.state

import app.tingid.chain.BatchCall
import app.tingid.chain.Budgets
import app.tingid.chain.Coin
import app.tingid.chain.Contracts
import app.tingid.chain.FeePayer
import app.tingid.chain.MemoryKey
import app.tingid.chain.Signer
import app.tingid.chain.Tcr
import app.tingid.chain.Writer
import app.tingid.chain.encodeCall
import app.tingid.chain.encodeDeploy
import app.tingid.chain.filled
import app.tingid.chain.hex
import app.tingid.chain.publicKey
import app.tingid.chain.sponsoredCall
import app.tingid.chain.u64le
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigInteger

class SignedTest {
    private val t = 1_800_000_000L
    private val chain = filled(0x31)
    private val c = Contracts.canonical(chain)
    private val me = Signer(MemoryKey(filled(1)), null, c.policySecp256k1, publicKey(filled(1)))
    private val other = Signer(MemoryKey(filled(2)), null, c.policySecp256k1, publicKey(filled(2)))
    private val coin = filled(0x44)
    private val program = filled(0x5a)
    private val template = Tcr.templateHash("give(item:u64): Give item {item}")
    private val witness = ByteArray(64) { 7 }

    private fun read(tx: ByteArray) = readSigned(c, tx.hex())
    private fun coinQ(s: Signer) = coinQueue(coin.hex(), s.account.hex())
    private val callQ get() = callQueue(program, me.account)

    private fun transfer(validUntil: Long) = Coin.Action(Coin.Effect.Transfer(filled(0x7f), BigInteger.ONE), Coin.NO_TIMING.copy(validUntil = validUntil))
    private fun payment(nonce: Long, validUntil: Long, by: Signer = me) =
        Coin.executeInput(Coin.PolicyAuth(by.policy, by.params, nonce, witness), transfer(validUntil))

    @Test
    fun `a payment takes its account's nonce in its coin, until it expires`() {
        assertEquals(Signed(listOf(Taken(coinQ(me), 3)), t), read(encodeCall(coin, payment(3, t), Budgets.EXECUTE)))
        // One that never expires.
        assertEquals(Signed(listOf(Taken(coinQ(me), 3)), null), read(encodeCall(coin, payment(3, 0), Budgets.EXECUTE)))
    }

    @Test
    fun `a cancel takes no nonce, but expires`() {
        val input = Coin.cancelInput(Coin.CancelAuth(me.policy, me.params, witness), Coin.Cancel(listOf(1L, 2L), BigInteger.ZERO, t))
        assertEquals(Signed(emptyList(), t), read(encodeCall(coin, input, Budgets.EXECUTE)))
    }

    @Test
    fun `a batch takes what its calls take, until the first of them expires`() {
        val payer = FeePayer(other, coin, BigInteger.TEN)
        val batch = sponsoredCall(chain, c, listOf(BatchCall(coin, payment(3, t + 600))), payer, 8, t)
        assertEquals(Signed(listOf(Taken(coinQ(me), 3), Taken(coinQ(other), 8)), t), read(batch))
        // An identity's change takes no nonce of a coin and never expires: only its fee payment counts.
        val change = sponsoredCall(chain, c, listOf(BatchCall(c.registry, byteArrayOf(1, 2, 3))), payer, 9, t)
        assertEquals(Signed(listOf(Taken(coinQ(other), 9)), t), read(change))
    }

    @Test
    fun `a program's call takes its nonce there, and its payment's in the coin`() {
        val signed = Tcr.callInput(template, u64le(7), Tcr.Auth(me.policy, me.params, 4, t, witness), null)
        assertEquals(Signed(listOf(Taken(callQ, 4)), t), read(encodeCall(program, signed, Budgets.BATCH)))

        val fee = Coin.executeInput(Coin.PolicyAuth(me.policy, me.params, 6, ByteArray(0)), Coin.Action(Tcr.feeEffect(program), Coin.NO_TIMING.copy(validUntil = t)))
        val paid = Tcr.callInput(template, u64le(7), Tcr.Auth(me.policy, me.params, 4, t, ByteArray(0)), Tcr.Payment(coin, fee))
        assertEquals(Signed(listOf(Taken(callQ, 4), Taken(coinQ(me), 6)), t), read(encodeCall(program, paid, Budgets.BATCH)))

        // A payment must be the acting account's own: one from another is no reading of a call.
        val theirs = Tcr.callInput(template, u64le(7), Tcr.Auth(me.policy, me.params, 4, t, ByteArray(0)), Tcr.Payment(coin, payment(6, t, other)))
        assertNull(read(encodeCall(program, theirs, Budgets.BATCH)))
    }

    @Test
    fun `values that read as an approval too make a call unreadable, never read wrong`() {
        val auth = Tcr.Auth(me.policy, me.params, 4, t, witness)
        val honest = Tcr.callInput(template, ByteArray(0), auth, null)
        assertEquals(Signed(listOf(Taken(callQ, 4)), t), read(encodeCall(program, honest, Budgets.BATCH)))
        // A site's values: an approval by one of the chain's policies, of a nonce and expiry of its choosing,
        // whose witness runs over the true approval up to its last byte, the 00 that says the call carries no payment.
        val approval = honest.size - 32
        val forged = { policy: ByteArray -> Writer().u8(0).put32(policy).bytes(ByteArray(0)).u64(99).u64(t + 999_999).u32(approval - 1).finish() }
        assertNull(read(encodeCall(program, Tcr.callInput(template, forged(c.registry), auth, null), Budgets.BATCH)))
        // By a policy that is none of the chain's, it is no approval this app signs, and the true one is read.
        assertEquals(Signed(listOf(Taken(callQ, 4)), t), read(encodeCall(program, Tcr.callInput(template, forged(filled(9)), auth, null), Budgets.BATCH)))
    }

    @Test
    fun `a deployment, a run and an identity's change take no nonce and never expire`() {
        assertEquals(Signed(emptyList(), null), read(encodeDeploy(byteArrayOf(0, 1, 2), ByteArray(0), Budgets.DEPLOY)))
        assertEquals(Signed(emptyList(), null), read(encodeCall(coin, Coin.targetInput(Coin.OP_RUN, me.account, 5), Budgets.CLAIM)))
        assertEquals(Signed(emptyList(), null), read(encodeCall(c.registry, byteArrayOf(1, 2, 3), Budgets.CALL)))
    }

    @Test
    fun `what is no transaction this app sends is no reading`() {
        assertNull(read(byteArrayOf(9, 9, 9)))
        assertNull(read(encodeCall(coin, byteArrayOf(0x10, 5), Budgets.CALL)))
        assertNull(readSigned(c, "not hex"))
        // A submission whose bytes the app has not got.
        assertNull(readSubmission(c, emptyMap(), "aa".repeat(32)))
        assertNull(readSubmission(null, mapOf("a" to OutboxEntry(encodeCall(coin, payment(3, t), Budgets.EXECUTE).hex(), at = t)), "a"))
    }

    @Test
    fun `a submission is read with the call sent beside its batch`() {
        val batch = sponsoredCall(chain, c, listOf(BatchCall(coin, payment(3, t + 600))), FeePayer(other, coin, BigInteger.TEN), 8, t).hex()
        val alone = encodeCall(coin, payment(3, t + 600), Budgets.EXECUTE).hex()
        val outbox = mapOf("b" to OutboxEntry(batch, at = t, twin = "a"), "a" to OutboxEntry(alone, at = t))
        val parts = readSubmission(c, outbox, "b")!!
        assertEquals(listOf(Signed(listOf(Taken(coinQ(me), 3), Taken(coinQ(other), 8)), t), Signed(listOf(Taken(coinQ(me), 3)), t + 600)), parts)
        // The call alone can go in after the batch's fee payment expires.
        assertEquals(t + 600, expiryOf(parts))
    }
}
