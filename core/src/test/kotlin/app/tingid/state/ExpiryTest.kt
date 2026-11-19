// What this app signs lasts an hour, and what can no longer get into a block
// is over: it stops holding its account's nonce, and its submission is
// settled, even while a node still holds the transaction (Chain, Pending,
// state/Watch.kt).
package app.tingid.state

import app.tingid.chain.BatchCall
import app.tingid.chain.Budgets
import app.tingid.chain.Coin
import app.tingid.chain.Contracts
import app.tingid.chain.FeePayer
import app.tingid.chain.Id
import app.tingid.chain.MemoryKey
import app.tingid.chain.Reader
import app.tingid.chain.Signer
import app.tingid.chain.Tcr
import app.tingid.chain.decodeCall
import app.tingid.chain.encodeCall
import app.tingid.chain.filled
import app.tingid.chain.hex
import app.tingid.chain.publicKey
import app.tingid.chain.transactionId
import app.tingid.chain.u64le
import app.tingid.chain.unhex
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class ExpiryTest {
    private val node = FakeRestNode(filled(0x2a))
    private val app = TestApp(node)
    private val chainId = node.chainId
    private val c = Contracts.canonical(chainId)
    private val secret = filled(1)
    private val signer = Signer(MemoryKey(secret), null, c.policySecp256k1, publicKey(secret))
    private val coin = filled(0x44)
    private val coinKey = "${coin.hex()}:${signer.account.hex()}"
    private val pending = Pending(app.chain, testScope, statusWords = { it }, now = { app.clock }, pollMs = 20)
    private val pay = Coin.Action(Coin.Effect.Transfer(filled(9), BigInteger.TEN), Coin.NO_TIMING)

    init {
        // Nonces are read, proved, from a state that holds no records: every account's is 0.
        node.emptyState = true
        runBlocking { app.chain.node.status() }
        app.store.addUser(User("u", "Anna", "#000", UserKind.Key))
        tip(110)
    }

    /** The node reports `height`, and the app has heard. */
    private fun tip(height: Long) {
        app.at(height)
        runBlocking { withTimeout(5_000) { app.chain.info.first { it?.status?.height == height } } }
    }

    /** The eleven blocks that end three below a tip at `height` are stamped a minute apart, the middle one at `median`. */
    private fun chainTime(height: Long, median: Long) {
        val first = height - 13
        node.other["/v1/events?from=$first&limit=11"] = {
            jsonResponse(
                buildJsonObject {
                    put(
                        "items",
                        JsonArray(
                            (first..first + 10).map { h ->
                                buildJsonObject {
                                    put("block", "b$h")
                                    put("height", h)
                                    put("timestamp", median + (h - first - 5) * 60)
                                    put("events", JsonArray(emptyList()))
                                }
                            },
                        ),
                    )
                },
            )
        }
    }

    private fun askedForTime() = node.calls.count { it.startsWith("GET /v1/events") }

    @Test
    fun `a payment is signed for an hour, and its nonce noted until then`() = runBlocking {
        val sent = app.chain.submitCoinAction(chainId, coin, signer, pay)
        assertEquals(app.clock + 3_600, sent.expires)
        assertEquals(app.clock + 3_600, app.chain.signedExpiry())
        assertEquals(app.chain.signedExpiry(), app.chain.callExpiry())
        // As signed: the action in the transaction carries that expiry.
        val r = Reader(decodeCall(unhex(node.posted.single().first))!!.input)
        r.u8()
        r.u8()
        r.array32()
        r.bytes()
        assertEquals(0L, r.u64())
        r.bytes()
        assertEquals(sent.expires, Coin.readAction(r).timing.validUntil)
        assertEquals(NonceNote(0, sent.txId, app.clock, null, sent.expires), app.store.data.value.nonces.getValue(coinKey))
        // An expiry the action brings is kept.
        val own = app.chain.submitCoinAction(chainId, coin, signer, pay.copy(timing = Coin.NO_TIMING.copy(validUntil = app.clock + 60)))
        assertEquals(app.clock + 60, own.expires)
    }

    @Test
    fun `the next action queues behind the last one only while that can still go in`() = runBlocking {
        val first = app.chain.submitCoinAction(chainId, coin, signer, pay)
        node.lookups[first.txId] = pendingLookup()
        assertEquals(1L, app.chain.nextNonce(coin, signer.account))
        // Nothing is past its expiry by the clock: the chain's time is not asked.
        assertEquals(0, askedForTime())

        // Past it by the clock, but the chain's time is a second short: a block could still be stamped early
        // enough, so its nonce is not free; and behind one past its expiry nothing more is signed.
        app.clock = first.expires + 1
        chainTime(110, first.expires - 1)
        assertTrue(runCatching { app.chain.nextNonce(coin, signer.account) }.exceptionOrNull() is StillExpiring)
        assertTrue(node.calls.contains("GET /v1/events?from=97&limit=11"))

        // The chain's time reaches it: the node still holds the transaction, but no block can take it, and the chain's nonce is next.
        tip(111)
        chainTime(111, first.expires)
        assertEquals(0L, app.chain.nextNonce(coin, signer.account))
        assertTrue(node.calls.contains("GET /v1/events?from=98&limit=11"))
        // Read once for a height.
        val asked = askedForTime()
        assertEquals(0L, app.chain.nextNonce(coin, signer.account))
        assertEquals(asked, askedForTime())
    }

    @Test
    fun `while the chain's time cannot be read, an hour past the expiry stands in`() = runBlocking {
        val first = app.chain.submitCoinAction(chainId, coin, signer, pay)
        node.lookups[first.txId] = pendingLookup()
        app.clock = first.expires + LATE
        val held = runCatching { app.chain.nextNonce(coin, signer.account) }.exceptionOrNull() as StillExpiring
        assertEquals("an earlier one from this account did not go in, and what was signed after it is still expiring: try again in about a minute", held.message)
        assertNull(app.chain.chainTime())
        app.clock = first.expires + LATE + 1
        assertEquals(0L, app.chain.nextNonce(coin, signer.account))
        // A chain shorter than the blocks it takes has no time to read.
        tip(13)
        val asked = askedForTime()
        assertNull(app.chain.chainTime())
        assertEquals(asked, askedForTime())
    }

    @Test
    fun `an action queued behind one of an earlier version remembers how long that one holds its nonce`() = runBlocking {
        // Noted without an expiry, as before: signed until the end of the day after.
        val old = "aa".repeat(32)
        app.store.noteNonce(coinKey, NonceNote(4, old, app.clock))
        node.lookups[old] = pendingLookup()
        val dayAfter = (app.clock / 86_400 + 2) * 86_400
        val queued = app.chain.submitCoinAction(chainId, coin, signer, pay)
        assertEquals(5L, queued.nonce)
        assertEquals(app.clock + 3_600, queued.expires)
        assertEquals(NonceNote(5, queued.txId, app.clock, null, queued.expires, listOf(HeldNonce(4, dayAfter))), app.store.data.value.nonces.getValue(coinKey))
        // An hour later the new one has expired, but the old one may still go in: its nonce is not free,
        // and behind the expired one nothing more is signed.
        node.lookups[queued.txId] = pendingLookup()
        app.clock = queued.expires + 1
        chainTime(110, app.clock)
        val held = runCatching { app.chain.nextNonce(coin, signer.account) }.exceptionOrNull() as StillExpiring
        assertEquals(dayAfter, held.until)
        assertTrue(held.message!!.endsWith(" hours"))
        // Past the old one's time too, nothing waits.
        tip(111)
        app.clock = dayAfter + 1
        chainTime(111, dayAfter)
        assertEquals(0L, app.chain.nextNonce(coin, signer.account))
    }

    @Test
    fun `a program's call is noted with its expiry for the program and for its payment's coin`() = runBlocking {
        val program = filled(0x5a)
        val t = Tcr.templateHash("give(item:u64): Give item {item}")
        val sent = app.chain.submitProgramCall(chainId, program, t, u64le(7), signer, coin to Coin.Effect.Burn(BigInteger.ONE), null, null)
        assertEquals(app.clock + 3_600, sent.expires)
        val notes = app.store.data.value.nonces
        assertEquals(NonceNote(0, sent.txId, app.clock, null, sent.expires), notes.getValue("${program.hex()}:${signer.account.hex()}"))
        assertEquals(NonceNote(0, sent.txId, app.clock, null, sent.expires), notes.getValue(coinKey))
        // The next call queues behind it, until it has expired.
        node.lookups[sent.txId] = pendingLookup()
        assertEquals(1L, app.chain.nextCallNonce(program, signer.account))
        app.clock = sent.expires + 1
        chainTime(110, sent.expires)
        assertEquals(0L, app.chain.nextCallNonce(program, signer.account))
        assertEquals(0L, app.chain.nextNonce(coin, signer.account))
    }

    @Test
    fun `a program's call of an earlier version, noted without an expiry, holds its nonces for its hour only`() = runBlocking {
        // As an earlier version left it: the call and its payment noted without an expiry, the call's bytes kept and marked.
        val program = filled(0x5a)
        val programKey = "${program.hex()}:${signer.account.hex()}"
        val old = "bb".repeat(32)
        app.store.keepOutbox(old, OutboxEntry("00", at = app.clock))
        app.store.markProgramCall(old, unpaid = false)
        app.store.noteNonce(programKey, NonceNote(44, old, app.clock))
        app.store.noteNonce(coinKey, NonceNote(45, old, app.clock))
        node.lookups[old] = pendingLookup()
        assertEquals(45L, app.chain.nextCallNonce(program, signer.account))
        assertEquals(46L, app.chain.nextNonce(coin, signer.account))
        // An hour on, by the clock and the chain: the node still holds it, and both nonces are the chain's again.
        app.clock += 3_601
        chainTime(110, app.clock)
        assertEquals(0L, app.chain.nextCallNonce(program, signer.account))
        assertEquals(0L, app.chain.nextNonce(coin, signer.account))
    }

    @Test
    fun `a call of an earlier version whose fee went through a batch is freed after its hour too`() = runBlocking {
        // As submitProgramCall left it then: the batch kept and paired with the call sent alone, which carries the mark; the note on the batch.
        val program = filled(0x5a)
        val batch = "cc".repeat(32)
        val alone = "dd".repeat(32)
        app.store.keepOutbox(batch, OutboxEntry("00", at = app.clock))
        app.store.keepOutbox(alone, OutboxEntry("01", at = app.clock))
        app.store.pairOutbox(batch, alone)
        app.store.markProgramCall(alone, unpaid = true)
        app.store.noteNonce("${program.hex()}:${signer.account.hex()}", NonceNote(44, batch, app.clock))
        app.store.addSubmitted(SubmittedTx(batch, app.clock, "Program call", "u"))
        node.lookups[batch] = pendingLookup()
        assertEquals(45L, app.chain.nextCallNonce(program, signer.account))
        app.clock += 3_601
        chainTime(110, app.clock)
        assertEquals(0L, app.chain.nextCallNonce(program, signer.account))
        // And its submission is over.
        assertEquals(Watch.Failed(EXPIRED_WORDS, null), items { it.singleOrNull()?.watch is Watch.Failed }.single().watch)
    }

    @Test
    fun `nothing is signed behind one already past its expiry, until what queued behind it has run out`() = runBlocking {
        val first = app.chain.submitCoinAction(chainId, coin, signer, pay)
        node.lookups[first.txId] = pendingLookup()
        assertEquals(NonceNote(0, first.txId, app.clock, null, first.expires), app.store.data.value.nonces.getValue(coinKey))
        // Half an hour on, a second one queues behind it, and remembers the first and when it expires.
        app.clock += 1_800
        val second = app.chain.submitCoinAction(chainId, coin, signer, pay)
        node.lookups[second.txId] = pendingLookup()
        assertEquals(1L, second.nonce)
        assertEquals(NonceNote(1, second.txId, app.clock, null, second.expires, listOf(HeldNonce(0, first.expires))), app.store.data.value.nonces.getValue(coinKey))

        // The first is past its expiry and no block took it: a third could not go in either, and is not signed.
        app.clock = first.expires + 1
        val held = runCatching { app.chain.submitCoinAction(chainId, coin, signer, pay) }.exceptionOrNull() as StillExpiring
        assertEquals(second.expires, held.until)
        assertEquals("an earlier one from this account did not go in, and what was signed after it is still expiring: try again in about 40 minutes", held.message)
        assertEquals(2, node.posted.size)
        // Nor a call's payment from the same account, and the note stands as it was.
        assertTrue(runCatching { app.chain.nextNonce(coin, signer.account) }.exceptionOrNull() is StillExpiring)
        assertEquals(1L, app.store.data.value.nonces.getValue(coinKey).nonce)

        // Once the second has expired too, by the clock and the chain, the chain's nonce is next.
        app.clock = second.expires + 1
        tip(111)
        chainTime(111, second.expires)
        val third = app.chain.submitCoinAction(chainId, coin, signer, pay)
        assertEquals(0L, third.nonce)
        assertEquals(NonceNote(0, third.txId, app.clock, null, third.expires), app.store.data.value.nonces.getValue(coinKey))
    }

    @Test
    fun `one that went in holds nothing back`() = runBlocking {
        val first = app.chain.submitCoinAction(chainId, coin, signer, pay)
        node.lookups[first.txId] = includedLookup(109)
        app.clock = first.expires + 10
        val next = app.chain.submitCoinAction(chainId, coin, signer, pay)
        assertEquals(NonceNote(0, next.txId, app.clock, null, next.expires), app.store.data.value.nonces.getValue(coinKey))
    }

    @Test
    fun `behind one that went in, the next is signed while the one whose turn it is can still go in`() = runBlocking {
        // As it stands once the first of two went in: the second is at the chain's nonce (always 0 here, so
        // the first is noted below it), still waits, and expires in half an hour. The first's expiry has passed.
        val second = "ee".repeat(32)
        val expires = app.clock + 1_800
        app.store.noteNonce(coinKey, NonceNote(0, second, app.clock - 1_800, null, expires, listOf(HeldNonce(-1, app.clock - 1))))
        node.lookups[second] = pendingLookup()
        val third = app.chain.submitCoinAction(chainId, coin, signer, pay)
        assertEquals(1L, third.nonce)
        // It remembers only what still waits ahead of it, and the chain's time was not needed.
        assertEquals(NonceNote(1, third.txId, app.clock, null, third.expires, listOf(HeldNonce(0, expires))), app.store.data.value.nonces.getValue(coinKey))
        assertEquals(0, askedForTime())
        // Once the second is past its own expiry without a block, nothing more is signed behind it.
        node.lookups[third.txId] = pendingLookup()
        app.clock = expires + 1
        assertTrue(runCatching { app.chain.nextNonce(coin, signer.account) }.exceptionOrNull() is StillExpiring)
    }

    @Test
    fun `a payer whose queue is held up leaves the call to go unpaid, unless the payment is required`() = runBlocking {
        // The paying account's last payment is past its expiry, and no block took it.
        val stuck = app.chain.submitCoinAction(chainId, coin, signer, pay)
        node.lookups[stuck.txId] = pendingLookup()
        app.clock = stuck.expires + 1
        val payer = FeePayer(signer, coin, BigInteger.valueOf(1_000))
        val call = BatchCall(c.registry, Id.applyInput(filled(0x2e), 3))
        val alone = encodeCall(call.target, call.input, Budgets.CALL)
        // What it would pay for still goes: alone, unpaid.
        val id = app.chain.submitWithFee(c, chainId, call, payer)
        assertEquals(transactionId(chainId, alone).hex(), id)
        assertEquals(listOf(alone.hex()), node.posted.drop(1).map { it.first })
        // A speed-up is the payment itself: that waits.
        assertTrue(runCatching { app.chain.speedUp(c, chainId, call, payer, Budgets.CALL) }.exceptionOrNull() is StillExpiring)
        assertEquals(2, node.posted.size)
    }

    @Test
    fun `a cancel is signed until the expiry its submission is told`() = runBlocking {
        val until = app.clock + 1_234
        app.chain.submitCoinCancel(chainId, coin, signer, listOf(7L), validUntil = until)
        val r = Reader(decodeCall(unhex(node.posted.single().first))!!.input)
        assertEquals(Coin.OP_CANCEL, r.u8())
        r.u8()
        r.array32()
        r.bytes()
        r.bytes()
        assertEquals(until, Coin.readCancel(r).validUntil)
    }

    @Test
    fun `forgetting what waits stops following payments and calls, frees their nonces, and leaves the rest`() {
        val t = app.clock
        val programKey = "${filled(0x5a).hex()}:${signer.account.hex()}"
        fun keep(id: String, notBefore: Long? = null) = app.store.keepOutbox(id, OutboxEntry("00", notBefore = notBefore, at = t))
        // A payment of this version, a program call of an earlier one, and a payment of an earlier one.
        app.store.addSubmitted(SubmittedTx("p1", t, "Send 1 DLR", "u", coin = "c", account = "a", to = "b", sends = "1", expires = t + 3_600))
        app.store.addSubmitted(SubmittedTx("c1", t, "Program call", "u"))
        app.store.addSubmitted(SubmittedTx("p0", t, "Send 2 DLR", "u", coin = "c", account = "a", to = "b"))
        // What is left alone: an identity's change, a payment the node holds for later, another user's, and one settled.
        app.store.addSubmitted(SubmittedTx("g1", t, "Link a guardian", "u"))
        app.store.addSubmitted(SubmittedTx("h1", t, "Send 3 DLR", "u", coin = "c", account = "a", expires = t + 3_600))
        app.store.addSubmitted(SubmittedTx("o1", t, "Send 4 DLR", "v", coin = "c", account = "z", expires = t + 3_600))
        app.store.addSubmitted(SubmittedTx("d1", t, "Send 5 DLR", "u", coin = "c", account = "a", expires = t + 3_600, state = TxState.Included))
        listOf("p1", "c1", "p0", "g1", "o1", "d1").forEach { keep(it) }
        keep("h1", notBefore = t + 9_000)
        app.store.markProgramCall("c1", unpaid = false)
        app.store.noteNonce(coinKey, NonceNote(3, "p1", t, null, t + 3_600))
        app.store.noteNonce(programKey, NonceNote(1, "c1", t))
        app.store.noteNonce("op:registry:identity", NonceNote(2, "g1", t))
        app.store.noteNonce("other:account", NonceNote(9, "o1", t, null, t + 3_600))

        val d = app.store.data.value
        val waiting = waitingToForget(d, "u", app.chainHex, t)
        assertEquals(setOf("p1", "c1", "p0"), waiting.map { it.id }.toSet())
        // Until when each could still go in: its expiry, a call's hour, and the end of the day after for an old payment.
        assertEquals(mapOf("p1" to t + 3_600, "c1" to t + 3_600, "p0" to (t / 86_400 + 2) * 86_400), waiting.associate { it.id to couldGoInUntil(it, d.outbox) })
        assertNull(couldGoInUntil(d.submitted.first { it.id == "g1" }, d.outbox))

        app.store.forgetWaiting("u", app.chainHex, t)
        val after = app.store.data.value
        assertEquals(
            mapOf("p1" to TxState.Dismissed, "c1" to TxState.Dismissed, "p0" to TxState.Dismissed, "g1" to TxState.Pending, "h1" to TxState.Pending, "o1" to TxState.Pending, "d1" to TxState.Included),
            after.submitted.associate { it.id to it.state },
        )
        assertEquals(setOf("g1", "h1", "o1", "d1"), after.outbox.keys)
        // This user's coin and program nonces are the chain's again; the identity's own, and another user's, stay.
        assertEquals(setOf("op:registry:identity", "other:account"), after.nonces.keys)
        // Nothing left to forget, and asking again changes nothing.
        assertTrue(waitingToForget(after, "u", app.chainHex, t).isEmpty())
        app.store.forgetWaiting("u", app.chainHex, t)
        assertEquals(after, app.store.data.value)
    }

    @Test
    fun `a waiting program call of an earlier version is over after its hour`() {
        val id = submitted(null)
        app.store.markProgramCall(id, unpaid = false)
        node.lookups[id] = pendingLookup()
        assertEquals(Watch.Waiting, items { it.singleOrNull()?.watch == Watch.Waiting }.single().watch)
        // Sent five minutes ago: an hour after that, by the clock and the chain.
        app.clock += 3_400
        chainTime(111, app.clock - 100)
        tip(111)
        assertEquals(Watch.Failed(EXPIRED_WORDS, null), items { it.singleOrNull()?.watch is Watch.Failed }.single().watch)
    }

    /** A submission of five minutes ago that signed an action expiring at `expires`, its bytes kept; its id. */
    private fun submitted(expires: Long?): String {
        val tx = byteArrayOf(1)
        val id = transactionId(chainId, tx).hex()
        app.store.addSubmitted(SubmittedTx(id, app.clock - 300, "Send 1 DLR", "u", expires = expires))
        app.store.keepOutbox(id, OutboxEntry(tx.hex(), at = app.clock - 300))
        return id
    }

    private fun items(what: (List<PendingItem>) -> Boolean): List<PendingItem> = runBlocking {
        withTimeout(5_000) { pending.items().first(what) }
    }

    @Test
    fun `a waiting submission is over once what it signed has expired, though the node still holds it`() {
        val id = submitted(app.clock + 100)
        node.lookups[id] = pendingLookup()
        assertEquals(Watch.Waiting, items { it.singleOrNull()?.watch == Watch.Waiting }.single().watch)
        // Not past its expiry: the chain's time is not asked.
        assertEquals(0, askedForTime())

        // Past it by the clock while the chain's time is behind: asked now, and it still waits.
        app.clock += 200
        chainTime(110, app.clock - 101)
        val following = testScope.launch { pending.items().collect {} }
        runBlocking { until { askedForTime() > 0 } }
        following.cancel()
        assertEquals(TxState.Pending, app.store.data.value.submitted.single().state)
        assertEquals(Watch.Waiting, items { it.isNotEmpty() }.single().watch)

        // The chain's time reaches it: over, with nothing paid, and not to be sent again.
        chainTime(111, app.clock - 100)
        tip(111)
        val item = items { it.singleOrNull()?.watch is Watch.Failed }.single()
        assertEquals(Watch.Failed(EXPIRED_WORDS, null), item.watch)
        // Its bytes are kept, to find it again, but it is not offered to be sent again, and is refused if asked.
        assertFalse(item.canRetry)
        assertTrue(id in app.store.data.value.outbox)
        assertEquals("it has expired: no block can take it any more", runBlocking { pending.retry(id) })
        val posted = node.posted.size
        val tx = app.store.data.value.submitted.single()
        assertEquals(TxState.Failed, tx.state)
        assertEquals(EXPIRED_WORDS, tx.problem)
        assertNull(tx.height)
        // It takes nothing from what the account can send.
        assertEquals(OnTheWay(0, BigInteger.ZERO), onTheWay(listOf(item), "c", "a"))

        // A block took it after all, as a reorganization deep enough could bring about: the block is believed.
        node.lookups[id] = includedLookup(108)
        val looking = testScope.launch { pending.items().collect {} }
        runBlocking { until { app.store.data.value.submitted.single().state == TxState.Included } }
        looking.cancel()
        assertEquals(108L, app.store.data.value.submitted.single().height)
        assertEquals(posted, node.posted.size)
    }

    @Test
    fun `one that signed no expiry waits as long as the node holds it`() {
        val id = submitted(null)
        node.lookups[id] = pendingLookup()
        app.clock += 10 * 86_400
        chainTime(110, app.clock)
        assertEquals(Watch.Waiting, items { it.singleOrNull()?.watch == Watch.Waiting }.single().watch)
        assertEquals(0, askedForTime())
    }
}
