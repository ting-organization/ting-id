// What this app signs lasts an hour, and what can no longer get into a block
// is over: it stops holding its account's nonce, and its submission is
// settled, even while a node still holds the transaction (Chain, Pending,
// state/Watch.kt).
package app.tingid.state

import app.tingid.chain.Coin
import app.tingid.chain.Contracts
import app.tingid.chain.MemoryKey
import app.tingid.chain.Reader
import app.tingid.chain.Signer
import app.tingid.chain.Tcr
import app.tingid.chain.decodeCall
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

        // Past it by the clock, but the chain's time is a second short: a block could still be stamped early enough.
        app.clock = first.expires + 1
        chainTime(110, first.expires - 1)
        assertEquals(1L, app.chain.nextNonce(coin, signer.account))
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
        assertEquals(1L, app.chain.nextNonce(coin, signer.account))
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
    fun `an action queued behind one of an earlier version holds the nonce as long as that one does`() = runBlocking {
        // Noted without an expiry, as before: signed until the end of the day after.
        val old = "aa".repeat(32)
        app.store.noteNonce(coinKey, NonceNote(4, old, app.clock))
        node.lookups[old] = pendingLookup()
        val dayAfter = (app.clock / 86_400 + 2) * 86_400
        val queued = app.chain.submitCoinAction(chainId, coin, signer, pay)
        assertEquals(5L, queued.nonce)
        assertEquals(app.clock + 3_600, queued.expires)
        assertEquals(NonceNote(5, queued.txId, app.clock, null, dayAfter), app.store.data.value.nonces.getValue(coinKey))
        // An hour later the new one has expired, but the old one may still go in, and then this nonce's turn comes.
        node.lookups[queued.txId] = pendingLookup()
        app.clock = queued.expires + 1
        chainTime(110, app.clock)
        assertEquals(6L, app.chain.nextNonce(coin, signer.account))
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
        assertFalse(item.canRetry)
        val tx = app.store.data.value.submitted.single()
        assertEquals(TxState.Failed, tx.state)
        assertEquals(EXPIRED_WORDS, tx.problem)
        assertNull(tx.height)
        assertFalse(id in app.store.data.value.outbox)
        // It takes nothing from what the account can send.
        assertEquals(OnTheWay(0, BigInteger.ZERO), onTheWay(listOf(item), "c", "a"))
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
