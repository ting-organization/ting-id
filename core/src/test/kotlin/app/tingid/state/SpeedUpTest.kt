// Speeding up what waits unpaid (state/SpeedUp, Chain.speedUp): the same call
// goes again in a batch, followed by a fee payment bound to it and signed only
// then; the submission follows the batch, and whichever of the two goes in
// settles it.
package app.tingid.state

import app.tingid.chain.BatchCall
import app.tingid.chain.Budgets
import app.tingid.chain.Coin
import app.tingid.chain.Contracts
import app.tingid.chain.FeePayer
import app.tingid.chain.Id
import app.tingid.chain.MemoryKey
import app.tingid.chain.NodeStatus
import app.tingid.chain.Reader
import app.tingid.chain.Scheme
import app.tingid.chain.Signer
import app.tingid.chain.SigningKey
import app.tingid.chain.batchPrefix
import app.tingid.chain.decodeCall
import app.tingid.chain.encodeCall
import app.tingid.chain.encodeDeploy
import app.tingid.chain.filled
import app.tingid.chain.hex
import app.tingid.chain.publicKey
import app.tingid.chain.sponsoredCall
import app.tingid.chain.unhex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicInteger

/** A key that counts its signatures. */
private class CountingKey(secret: ByteArray) : SigningKey {
    private val key = MemoryKey(secret)
    val signed = AtomicInteger()
    override val publicKey: ByteArray = key.publicKey
    override fun sign(digest: ByteArray): ByteArray {
        signed.incrementAndGet()
        return key.sign(digest)
    }
}

class SpeedUpTest {
    private val node = FakeRestNode(filled(0x28))
    private val app = TestApp(node)
    private val chainId = node.chainId
    private val c = Contracts.canonical(chainId)
    private val pending = Pending(app.chain, testScope, statusWords = { it }, now = { app.clock }, pollMs = 20, io = Dispatchers.Default)

    private val secret = filled(1)
    private val pub = publicKey(secret)
    private val key = KeyMeta("k1", "Phone", Scheme.Secp256k1, pub.hex(), 0)
    private val user = User("u1", "Bo", "#000", UserKind.Key, keyId = key.id)
    private val dlr = CoinInfo(
        filled(0x44),
        filled(0x44).hex(),
        Coin.Config("Daler", "DLR", 8, Coin.UNCAPPED, BigInteger.ZERO, 0, BigInteger.ZERO, 0),
        Coin.State(BigInteger.ZERO, BigInteger.ZERO, 0, Coin.ZERO_ACCOUNT),
    )
    private val account = userAccounts(user, c, listOf(key), null)[0]

    private val signing = CountingKey(secret)
    private val payer = FeePayer(Signer(signing, null, c.policySecp256k1, pub), dlr.id, BigInteger.valueOf(1_000))
    private val fee = object : FeeSource {
        override val summary = "Fee 0.00001 DLR from Account"
        override suspend fun resolve(): FeePayer = payer
    }

    /** A registry operation anyone may submit, paying nothing: applying a change that is due. */
    private val apply = BatchCall(c.registry, Id.applyInput(filled(0x2e), 3))

    init {
        // The paying account's nonce is read, proved, from a node the pool knows the chain of: the account has none yet.
        node.emptyState = true
        runBlocking { app.chain.node.status() }
        app.secrets.saveKey(key.id, secret)
        app.store.addKey(key)
        app.store.addUser(user)
        app.at(110)
    }

    /** The calls of a batch's input, in order. */
    private fun batchCalls(input: ByteArray): List<BatchCall> {
        val r = Reader(input)
        val out = ArrayList<BatchCall>()
        while (r.remaining > 0) out.add(BatchCall(r.array32(), r.bytes()))
        return out
    }

    private fun posted(n: Int) = unhex(node.posted[n].first)

    /** `call` submitted alone, unpaid, and followed as `summary`; its id. */
    private fun submittedAlone(call: BatchCall, summary: String): String = runBlocking {
        val id = app.chain.submitWithFee(c, chainId, call, null)
        app.store.addSubmitted(SubmittedTx(id, app.clock, summary, user.id))
        id
    }

    @Test
    fun `a waiting call goes again in a batch, followed by a fee payment bound to it`() = runBlocking {
        val alone = submittedAlone(apply, "Apply change")
        val batch = app.chain.speedUp(c, chainId, apply, payer, Budgets.CALL)

        // The batch, then the call alone again, the same bytes as before.
        assertEquals(3, node.posted.size)
        assertArrayEquals(posted(0), posted(2))
        val tx = decodeCall(posted(1))!!
        assertArrayEquals(c.batch, tx.target)
        assertEquals(Budgets.BATCH, tx.budget)
        val (call, payment) = batchCalls(tx.input)
        assertArrayEquals(apply.target, call.target)
        assertArrayEquals(apply.input, call.input)

        // The payment: a transfer of 0 from the paying account to itself, carrying the fee, valid only right after that call in the batch.
        assertArrayEquals(dlr.id, payment.target)
        val r = Reader(payment.input)
        assertEquals(Coin.OP_EXECUTE, r.u8())
        assertEquals(0, r.u8())
        assertArrayEquals(c.policySecp256k1, r.array32())
        assertArrayEquals(pub, r.bytes())
        assertEquals(0L, r.u64())
        r.bytes()
        val action = Coin.readAction(r)
        val transfer = action.effect as Coin.Effect.Transfer
        assertArrayEquals(payer.signer.account, transfer.to)
        assertEquals(BigInteger.ZERO, transfer.amount)
        assertEquals(BigInteger.valueOf(1_000), action.timing.fee)
        assertEquals(Coin.Within(c.batch, batchPrefix(listOf(apply))), action.timing.within)
        assertEquals(app.chain.signedExpiry(), action.timing.validUntil)
        assertArrayEquals(sponsoredCall(chainId, c, listOf(apply), payer, 0, app.chain.signedExpiry()), posted(1))

        // Kept with the call's id, and the payer's nonce noted for the batch.
        assertEquals(alone, app.store.data.value.outbox.getValue(batch).twin)
        val note = app.store.data.value.nonces.getValue("${dlr.hex}:${payer.signer.account.hex()}")
        assertEquals(NonceNote(0, batch, app.clock, batchPrefix(listOf(apply)).hex()), note)
    }

    @Test
    fun `a speed-up needs a fee, and the node refusing the batch is no speed-up`() = runBlocking {
        try {
            app.chain.speedUp(c, chainId, apply, null, Budgets.CALL)
            fail("no fee")
        } catch (e: IllegalStateException) {
            assertEquals("a speed-up needs a fee", e.message)
        }
        assertTrue(node.posted.isEmpty())
        node.accept = { jsonResponse(buildJsonObject { put("error", "the payment fails") }, 400) }
        try {
            app.chain.speedUp(c, chainId, apply, payer, Budgets.CALL)
            fail("refused")
        } catch (e: Exception) {
            assertEquals("the payment fails", e.message)
        }
        // Not sent alone either: it did not go paid, and nothing pairs with it.
        assertEquals(1, node.posted.size)
        assertTrue(app.store.data.value.nonces.isEmpty())
    }

    @Test
    fun `a waiting unpaid submission offers a speed-up, signed only when asked, and the call alone still settles it`(): Unit = runBlocking {
        val alone = submittedAlone(apply, "Apply change")
        node.lookups[alone] = pendingLookup()
        val offered = withTimeout(5_000) { pending.items().first { it.singleOrNull()?.canSpeedUp == true } }.single()
        assertEquals(Watch.Waiting, offered.watch)
        // Nothing is signed ahead: the payment would hold the payer's nonce.
        assertEquals(0, signing.signed.get())
        assertTrue(app.store.data.value.nonces.isEmpty())

        assertNull(pending.speedUp(alone, fee))
        assertEquals(1, signing.signed.get())
        val tx = app.store.data.value.submitted.single()
        val batch = tx.id
        assertEquals("Apply change · Fee 0.00001 DLR from Account", tx.summary)
        assertEquals(TxState.Pending, tx.state)
        assertEquals(alone, app.store.data.value.outbox.getValue(batch).twin)

        // A batch pays for it now: no second speed-up.
        node.lookups[batch] = pendingLookup()
        withTimeout(5_000) { pending.items().first { it.singleOrNull()?.let { i -> i.tx.id == batch && i.watch == Watch.Waiting } == true } }
            .single().let { assertFalse(it.canSpeedUp) }

        // The call alone goes in first: that settles it too.
        node.lookups[alone] = includedLookup(108)
        app.at(111)
        withTimeout(5_000) { pending.items().first { it.isEmpty() } }
        assertEquals(TxState.Included, app.store.data.value.submitted.single().state)
        assertEquals(108L, app.store.data.value.submitted.single().height)
        assertTrue(app.store.data.value.outbox.isEmpty())
    }

    @Test
    fun `the batch going in settles it as well`() = runBlocking {
        val alone = submittedAlone(apply, "Apply change")
        node.lookups[alone] = pendingLookup()
        withTimeout(5_000) { pending.items().first { it.singleOrNull()?.canSpeedUp == true } }
        assertNull(pending.speedUp(alone, fee))
        val batch = app.store.data.value.submitted.single().id
        node.lookups[batch] = includedLookup(109)
        app.at(112)
        withTimeout(5_000) { pending.items().first { it.isEmpty() } }
        assertEquals(TxState.Included, app.store.data.value.submitted.single().state)
        assertEquals(109L, app.store.data.value.submitted.single().height)
    }

    @Test
    fun `only a call that pays nothing itself can be sped up`() {
        val to = filled(0x7f)
        fun execute(paying: Long) = Coin.executeInput(
            Coin.PolicyAuth(c.policySecp256k1, pub, 4, ByteArray(64)),
            Coin.Action(Coin.Effect.Transfer(to, BigInteger.ONE), Coin.Timing(fee = BigInteger.valueOf(paying))),
        )
        fun cancel(paying: Long) =
            Coin.cancelInput(Coin.CancelAuth(c.policySecp256k1, pub, ByteArray(64)), Coin.Cancel(listOf(2L, 5L), BigInteger.valueOf(paying), 0))
        fun unpaid(target: ByteArray, input: ByteArray) = unpaidCall(c, encodeCall(target, input, Budgets.CALL))

        assertNotNull(unpaid(c.registry, apply.input))
        assertNotNull(unpaid(dlr.id, Coin.targetInput(Coin.OP_RUN, to, 3)))
        assertNotNull(unpaid(dlr.id, execute(0)))
        assertNotNull(unpaid(dlr.id, cancel(0)))
        assertNull(unpaid(dlr.id, execute(5)))
        assertNull(unpaid(dlr.id, cancel(5)))
        // A batch may pay already; a deployment cannot go in a batch; what is not a coin's call is left alone.
        assertNull(unpaidCall(c, sponsoredCall(chainId, c, listOf(apply), payer, 0, 0)))
        assertNull(unpaidCall(c, encodeDeploy(ByteArray(8), ByteArray(0), Budgets.DEPLOY)))
        assertNull(unpaid(dlr.id, byteArrayOf(0x7e)))
        assertEquals(Budgets.CLAIM, unpaidCall(c, runTransaction(dlr.id, to, 3))!!.budget)

        // Only while it waits in the pool, with its bytes kept and no batch paying for it yet.
        val tx = SubmittedTx("aa".repeat(32), 0, "Apply change", user.id)
        val kept = OutboxEntry(encodeCall(c.registry, apply.input, Budgets.CALL).hex(), at = 0)
        assertTrue(canSpeedUp(tx, Watch.Waiting, kept, c))
        assertFalse(canSpeedUp(tx, Watch.Checking, kept, c))
        assertFalse(canSpeedUp(tx, Watch.Scheduled(100, null), kept, c))
        assertFalse(canSpeedUp(tx, Watch.Waiting, kept.copy(twin = "bb".repeat(32)), c))
        assertFalse(canSpeedUp(tx, Watch.Waiting, null, c))
        assertFalse(canSpeedUp(tx, Watch.Waiting, kept, null))
        assertFalse(canSpeedUp(tx.copy(state = TxState.Failed), Watch.Waiting, kept, c))
    }

    // ---- Scheduled runs ----------------------------------------------------------

    private val w = WorkspaceState(
        node = app.chain.node,
        info = ChainInfo(NodeStatus(chainId, 10, listOf(1L, 1, 1, 1), "00".repeat(32), BigInteger.ONE, 0, "test"), c),
        coins = listOf(dlr),
        coinById = mapOf(dlr.hex to dlr),
        coinsLoading = false,
        missingCoins = emptyList(),
        user = user,
        otherChain = false,
        keys = listOf(key),
        advanced = false,
        identity = null,
        identityLoading = false,
        accounts = listOf(account),
        scope = null,
        names = emptyMap(),
        nodeError = null,
    )

    /** A scheduled action that fell due a minute ago. */
    private val due = ScheduledAction(
        dlr,
        AccountView(account, Coin.EMPTY_ACCOUNT, emptyList()),
        7,
        Coin.Scheduled(app.clock - 60, BigInteger.ONE, BigInteger.ZERO, Coin.Effect.Transfer(filled(0x7f), BigInteger.ONE)),
    )

    private fun actions(opened: CompletableDeferred<String>) =
        ScheduledActions(app.chain, testScope, { w }, { fee }, { opened.complete(it) }, io = Dispatchers.Default, now = { app.clock })

    @Test
    fun `a due run is sped up in a batch with a bound payment, and the run's submission follows it`() = runBlocking {
        val ran = CompletableDeferred<String>()
        val first = actions(ran)
        first.run(due)
        val run = withTimeout(5_000) { ran.await() }
        until { first.busy.value == null }
        assertEquals(runIdOf(chainId, due), run)
        assertTrue(runsPaidFor(chainId, listOf(due), app.store.data.value.outbox).isEmpty())

        val opened = CompletableDeferred<String>()
        val actions = actions(opened)
        actions.speedUp(due, fee)
        assertEquals("speed-${scheduledActionKey(due)}", actions.busy.value)
        val batch = withTimeout(5_000) { opened.await() }
        until { actions.busy.value == null }
        assertNull(actions.error.value)

        // The run, then the batch of the run and the payment bound to it, then the run alone again.
        assertEquals(3, node.posted.size)
        assertArrayEquals(posted(0), posted(2))
        val (call, payment) = batchCalls(decodeCall(posted(1))!!.input)
        assertArrayEquals(Coin.targetInput(Coin.OP_RUN, account.account, 7), call.input)
        assertArrayEquals(sponsoredCall(chainId, c, listOf(call), payer, 0, app.chain.signedExpiry()), posted(1))
        assertArrayEquals(dlr.id, payment.target)

        // One submission, now the batch, with the run as its twin.
        val tx = app.store.data.value.submitted.single()
        assertEquals(batch, tx.id)
        assertEquals("Run now: Send 0.00000001 DLR to 7f7f7f7f…7f7f7f · Fee 0.00001 DLR from Account", tx.summary)
        assertEquals(run, app.store.data.value.outbox.getValue(batch).twin)
        assertEquals(setOf(scheduledActionKey(due)), runsPaidFor(chainId, listOf(due), app.store.data.value.outbox))
    }

    @Test
    fun `a run never submitted is followed as a new submission`() = runBlocking {
        val opened = CompletableDeferred<String>()
        val actions = actions(opened)
        actions.speedUp(due, fee)
        val batch = withTimeout(5_000) { opened.await() }
        until { actions.busy.value == null }
        assertEquals(2, node.posted.size)
        val tx = app.store.data.value.submitted.single()
        assertEquals(batch, tx.id)
        assertEquals(TxState.Pending, tx.state)
        assertEquals(runIdOf(chainId, due), app.store.data.value.outbox.getValue(batch).twin)
    }
}
