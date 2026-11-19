// What this phone sent and no block has taken yet: how long what it signs
// lasts, when it is cleared and settled as not included, when a queue looks
// stuck, and the same payment or call asked about (state/Watch.kt, Chain).
package app.tingid.state

import app.tingid.chain.Coin
import app.tingid.chain.Contracts
import app.tingid.chain.FeePayer
import app.tingid.chain.HEADER_BYTES
import app.tingid.chain.MemoryKey
import app.tingid.chain.Occurrence
import app.tingid.chain.Receipt
import app.tingid.chain.Signer
import app.tingid.chain.Tcr
import app.tingid.chain.TransactionLookup
import app.tingid.chain.filled
import app.tingid.chain.hex
import app.tingid.chain.publicKey
import app.tingid.chain.taggedHash
import app.tingid.chain.u64le
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class WaitingTest {
    private val t = 1_800_000_000L
    private val time = { at: Long -> "T$at" }
    private val words = { status: String -> "it failed ($status)" }

    /** A payment from account `a` in coin `c`, sent `ago` seconds before `t`, signed for three hours. */
    private fun payment(id: String, ago: Long, account: String = "a") =
        SubmittedTx(id, t - ago, "Send 1 DLR", "u", coin = "c", account = account, to = "b", sends = "100", expires = t - ago + Chain.SIGNED_LIFE)

    private fun item(tx: SubmittedTx, watch: Watch = Watch.Waiting) = PendingItem(tx, watch, canRetry = true)

    @Test
    fun `payments last three hours, calls one`() {
        assertEquals(3 * 3_600L, Chain.SIGNED_LIFE)
        assertEquals(3_600L, Chain.CALL_LIFE)
    }

    @Test
    fun `a submission is cleared an hour after its expiry`() {
        val outbox = mapOf("c1" to OutboxEntry("00", at = t, unpaid = true))
        assertEquals(t + Chain.SIGNED_LIFE + LATE, clearsAt(payment("p1", 0), outbox))
        // Of an earlier version: a payment until the end of the day after, a call for an hour.
        assertEquals((t / 86_400 + 2) * 86_400, couldGoInUntil(payment("p0", 0).copy(expires = null), outbox))
        assertEquals(t + Chain.CALL_LIFE, couldGoInUntil(SubmittedTx("c1", t, "Program call", "u"), outbox))
        // An identity's change carries none.
        assertNull(clearsAt(SubmittedTx("g1", t, "Link a guardian", "u"), outbox))
    }

    @Test
    fun `a queue looks stuck after ten minutes without a block, and clears with the last in it`() {
        val q = coinQueue("c", "a")
        assertTrue(stuckQueues(listOf(item(payment("p1", 60))), emptyMap(), t).isEmpty())
        assertTrue(stuckQueues(listOf(item(payment("p1", STUCK_AFTER - 1))), emptyMap(), t).isEmpty())
        assertEquals(Stuck(t - STUCK_AFTER, t - STUCK_AFTER + Chain.SIGNED_LIFE + LATE), stuckQueues(listOf(item(payment("p1", STUCK_AFTER))), emptyMap(), t)[q])

        // What was signed behind the stuck one moves the time on.
        val queue = listOf(item(payment("p1", 1_800)), item(payment("p2", 900)), item(payment("p3", 30)))
        assertEquals(Stuck(t - 1_800, t - 30 + Chain.SIGNED_LIFE + LATE), stuckQueues(queue, emptyMap(), t)[q])

        // Only what still waits counts: not one in a block, failed, held for later, or cleared.
        assertTrue(stuckQueues(listOf(item(payment("p1", 1_800), Watch.Confirming(5))), emptyMap(), t).isEmpty())
        assertTrue(stuckQueues(listOf(item(payment("p1", 1_800), Watch.Failed("no", 5))), emptyMap(), t).isEmpty())
        assertTrue(stuckQueues(listOf(item(payment("p1", 1_800), Watch.Scheduled(t + 600, null))), emptyMap(), t).isEmpty())
        assertTrue(stuckQueues(listOf(item(payment("p1", 1_800).copy(state = TxState.Failed))), emptyMap(), t).isEmpty())
        assertTrue(stuckQueues(listOf(item(payment("p1", 1_800))), mapOf("p1" to OutboxEntry("00", notBefore = t + 600, at = t)), t).isEmpty())
        assertTrue(stuckQueues(listOf(item(payment("p1", Chain.SIGNED_LIFE + LATE + 1))), emptyMap(), t).isEmpty())

        // Each account in each coin is a queue of its own, and an account's calls to a program another.
        val two = stuckQueues(listOf(item(payment("p1", 1_800)), item(payment("p2", 30, account = "z"))), emptyMap(), t)
        assertEquals(setOf(q), two.keys)
        val call = SubmittedTx("c1", t - 1_800, "Program call", "u", coin = "c", account = "a", call = "${filled(5).hex()}:${filled(6).hex()}:hash", expires = t + 1_800)
        assertEquals(setOf(q, callQueue(filled(5), filled(6))), stuckQueues(listOf(item(call)), emptyMap(), t).keys)
    }

    @Test
    fun `a change whose fee Spending pays waits in Spending's queue until its fee payment expires`() {
        val q = coinQueue("c", "a")
        // An identity's change names no coin: alone it is in no queue.
        val change = SubmittedTx("g1", t - 1_800, "Link a guardian", "u")
        assertTrue(stuckQueues(listOf(item(change)), emptyMap(), t).isEmpty())
        // Paid for from Spending through a batch, it holds Spending's next nonce.
        val outbox = mapOf("g1" to OutboxEntry("00", at = t - 1_800, feeFrom = q, feeExpires = t - 1_800 + Chain.SIGNED_LIFE))
        assertEquals(mapOf(q to Stuck(t - 1_800, t - 1_800 + Chain.SIGNED_LIFE + LATE)), stuckQueues(listOf(item(change)), outbox, t))
        // So the first payment behind it is asked about, and clears with whichever of the two is later.
        val behind = stuckQueues(listOf(item(change), item(payment("p1", 60))), outbox, t)[q]
        assertEquals(Stuck(t - 1_800, t - 60 + Chain.SIGNED_LIFE + LATE), behind)
        // Once its fee payment is cleared, it holds nothing there; the change alone may still go in.
        assertTrue(stuckQueues(listOf(item(change)), outbox, t - 1_800 + Chain.SIGNED_LIFE + LATE + 1).isEmpty())
    }

    @Test
    fun `what it says while a queue looks stuck`() {
        assertEquals(
            "A transaction from this account has waited 25 minutes for a block. Anything you sign now waits behind it, and makes the wait longer. " +
                "Everything waiting should be cleared by about T${t + 9_000}.",
            waitWords(Stuck(t - 1_500, t + 9_000), t, time),
        )
        assertTrue(waitWords(Stuck(t - 3 * 3_600, t), t, time).contains("has waited 3 hours for a block"))
        assertEquals(
            "Some transactions look stuck. Wait until about T${t + 9_000}, when they are cleared. Signing more before then makes the wait longer.",
            stuckWords(t + 9_000, time),
        )
    }

    @Test
    fun `only the same payment or call is a repeat, while it waits`() {
        val pay = payment("p1", 600)
        val same = { tx: SubmittedTx -> tx.coin == "c" && tx.account == "a" && tx.to == "b" && tx.sends == "100" }
        assertEquals(pay, repeatOf(listOf(item(pay)), emptyMap(), t, same))
        assertNull(repeatOf(listOf(item(pay.copy(sends = "101"))), emptyMap(), t, same))
        assertNull(repeatOf(listOf(item(pay.copy(to = "x"))), emptyMap(), t, same))
        assertNull(repeatOf(listOf(item(pay.copy(sends = null))), emptyMap(), t, same))
        assertNull(repeatOf(listOf(item(pay, Watch.Confirming(5))), emptyMap(), t, same))
        assertNull(repeatOf(listOf(item(pay, Watch.Failed("no", 5))), emptyMap(), t, same))
        assertNull(repeatOf(listOf(item(pay)), emptyMap(), t - 600 + Chain.SIGNED_LIFE + LATE + 1, same))
        val later = pay.copy(id = "p2", at = t - 60)
        assertEquals(later, repeatOf(listOf(item(pay), item(later)), emptyMap(), t, same))

        val call = SubmittedTx("c1", t, "Program call", "u", call = "k1", expires = t + 3_600)
        assertEquals(call, repeatOf(listOf(item(call)), emptyMap(), t) { it.call == "k1" })
        assertNull(repeatOf(listOf(item(call)), emptyMap(), t) { it.call == "k2" })

        assertEquals(
            "You sent this at T${t - 600}, and it is still waiting for a block. If you send it again, it could be paid twice.",
            sendAgainWords(pay, time),
        )
        assertEquals(
            "You approved this at T$t, and it is still waiting for a block. Approved again, it could run twice.",
            approveAgainWords(call, time),
        )
    }

    private fun receipt(statusName: String) = Receipt(if (statusName == "Success") 0 else 3, statusName, listOf(0L, 0, 0, 0), ByteArray(0), emptyList())

    @Test
    fun `once cleared, what no block took is over, even while a node holds it`() {
        val pay = payment("p1", 0)
        val kept = OutboxEntry("00", at = t)
        val cleared = t + Chain.SIGNED_LIFE + LATE + 1
        val held = TransactionLookup(true, null, emptyList())
        // Until then it waits, or is sent again when the node lost it.
        assertEquals(Decision.Follow(Watch.Waiting), decide(pay, held, kept, cleared - 1, 100, null, words))
        assertTrue(decide(pay, null, kept, t + 600, 100, null, words) is Decision.Resend)
        // Then it is over, whether a node still holds it or lost it.
        assertEquals(Decision.Expired, decide(pay, held, kept, cleared, 100, null, words))
        assertEquals(Decision.Expired, decide(pay, null, kept, cleared, 100, null, words))
        // A block that has it says otherwise.
        val inBlock = TransactionLookup(false, null, listOf(Occurrence(90, receipt("Success"))))
        assertEquals(Decision.Included(90), decide(pay, inBlock, kept, cleared, 100, null, words))
        // One of an earlier version, without an expiry, goes on as before.
        assertEquals(Decision.Follow(Watch.Waiting), decide(pay.copy(expires = null), held, kept, cleared, 100, null, words))
    }

    @Test
    fun `the next nonce waits behind the last action until it is cleared`() {
        val node = FakeRestNode(filled(0x2a))
        val app = TestApp(node, clock = t)
        node.emptyState = true
        runBlocking { app.chain.node.status() }
        val coin = filled(0x44)
        val account = filled(1)
        val waiting = "aa".repeat(32)
        node.lookups[waiting] = pendingLookup()
        app.store.noteNonce("${coin.hex()}:${account.hex()}", NonceNote(0, waiting, t, null, t + Chain.SIGNED_LIFE))
        // Signed for three hours from the clock: the empty state's block is older.
        assertEquals(t + Chain.SIGNED_LIFE, app.chain.signedExpiry())
        assertEquals(1L, runBlocking { app.chain.nextNonce(coin, account) })
        app.clock = t + Chain.SIGNED_LIFE + LATE
        assertEquals(1L, runBlocking { app.chain.nextNonce(coin, account) })
        // Cleared: the chain's nonce is next, though a node still holds it.
        app.clock = t + Chain.SIGNED_LIFE + LATE + 1
        assertEquals(0L, runBlocking { app.chain.nextNonce(coin, account) })
        // A note of an earlier version holds until the end of the day after.
        app.store.noteNonce("${coin.hex()}:${account.hex()}", NonceNote(0, waiting, t, null, null))
        app.clock = (t / 86_400 + 2) * 86_400 + LATE
        assertEquals(1L, runBlocking { app.chain.nextNonce(coin, account) })
        app.clock += 1
        assertEquals(0L, runBlocking { app.chain.nextNonce(coin, account) })
    }

    @Test
    fun `a call's hour signed behind a payment's three hours frees nothing early`() = runBlocking {
        val node = FakeRestNode(filled(0x28))
        val app = TestApp(node, clock = t)
        node.emptyState = true
        app.chain.node.status()
        val c = Contracts.canonical(node.chainId)
        val secret = filled(1)
        val signer = Signer(MemoryKey(secret), null, c.policySecp256k1, publicKey(secret))
        val coin = filled(0x44)
        val payer = FeePayer(signer, coin, BigInteger.valueOf(1_000))
        // A payment at nonce 0, signed for three hours, that no block takes.
        val pay = app.chain.submitCoinAction(node.chainId, coin, signer, Coin.Action(Coin.Effect.Transfer(filled(0x7f), BigInteger.ONE), Coin.NO_TIMING), payer = payer)
        node.lookups[pay.txId] = pendingLookup()
        assertEquals(0L, pay.nonce)
        assertEquals(t + Chain.SIGNED_LIFE, pay.expires)
        // A minute later, a call paying from the same account: its payment queues at nonce 1, signed for an hour.
        app.clock = t + 60
        val template = Tcr.templateHash("give(item:u64): Give item {item}")
        val call = app.chain.submitProgramCall(node.chainId, filled(0x5a), template, u64le(7), signer, coin to Coin.Effect.Burn(BigInteger.ONE), null, payer)
        node.lookups[call.txId] = pendingLookup()
        assertEquals(2L, app.chain.nextNonce(coin, pay.account))
        // The call is cleared, the payment ahead of it is not: the next payment still queues behind both.
        app.clock = t + 60 + Chain.CALL_LIFE + LATE + 1
        assertEquals(2L, app.chain.nextNonce(coin, pay.account))
        // Once the payment is cleared too, the chain's nonce is free again.
        app.clock = t + Chain.SIGNED_LIFE + LATE + 1
        assertEquals(0L, app.chain.nextNonce(coin, pay.account))
    }

    @Test
    fun `a noted transaction the node does not know holds its nonce until it is cleared`() {
        val node = FakeRestNode(filled(0x2b))
        val app = TestApp(node, clock = t)
        node.emptyState = true
        runBlocking { app.chain.node.status() }
        val coin = filled(0x44)
        val account = filled(1)
        val key = "${coin.hex()}:${account.hex()}"
        // The node does not know it (a 404): Pending sends it again, so it still holds its nonce.
        val lost = "bb".repeat(32)
        app.store.noteNonce(key, NonceNote(0, lost, t, null, t + Chain.SIGNED_LIFE))
        assertEquals(1L, runBlocking { app.chain.nextNonce(coin, account) })
        app.clock = t + Chain.SIGNED_LIFE + LATE + 1
        assertEquals(0L, runBlocking { app.chain.nextNonce(coin, account) })
        // A note of an earlier version is let go at once, as before.
        app.clock = t
        app.store.noteNonce(key, NonceNote(0, lost, t))
        assertEquals(0L, runBlocking { app.chain.nextNonce(coin, account) })
        // One that failed in a block took no nonce, and holds none.
        node.lookups[lost] = includedLookup(5, status = 3)
        app.store.noteNonce(key, NonceNote(0, lost, t, null, t + Chain.SIGNED_LIFE))
        assertEquals(0L, runBlocking { app.chain.nextNonce(coin, account) })
    }

    @Test
    fun `a call paying only its own fee waits in its coin's queue`() = runBlocking {
        val node = FakeRestNode(filled(0x2c))
        val app = TestApp(node, clock = t)
        node.emptyState = true
        app.chain.node.status()
        val c = Contracts.canonical(node.chainId)
        val secret = filled(1)
        val signer = Signer(MemoryKey(secret), null, c.policySecp256k1, publicKey(secret))
        val coin = filled(0x44)
        val payer = FeePayer(signer, coin, BigInteger.valueOf(1_000))
        val call = app.chain.submitProgramCall(node.chainId, filled(0x5a), Tcr.templateHash("give(item:u64): Give item {item}"), u64le(7), signer, null, coin, payer)
        val kept = app.store.data.value.outbox.getValue(call.txId)
        assertEquals(coinQueue(coin.hex(), call.account.hex()), kept.feeFrom)
        assertEquals(call.expires, kept.feeExpires)
        // The stuck queue a question asks about is the one that clears last.
        val stuck = mapOf("a" to Stuck(t, t + 10), "b" to Stuck(t, t + 20))
        assertEquals(Stuck(t, t + 20), stuckIn(stuck, listOf("a", null, "b", "c")))
        assertNull(stuckIn(stuck, listOf(null, "c")))
    }

    @Test
    fun `a block only one node shows sets no expiry`() = runBlocking {
        val node = FakeRestNode(filled(0x29))
        val app = TestApp(node, clock = t)
        val year2100 = 4_102_444_800L
        val header = ByteArray(HEADER_BYTES).also { h ->
            h[0] = 1
            node.chainId.copyInto(h, 2)
            u64le(1).copyInto(h, 34)
            u64le(year2100).copyInto(h, 74)
            h.fill(0xff.toByte(), 82, 114)
        }
        val tip = taggedHash("block", header).hex()
        node.other["/v1/status"] = { jsonResponse(buildJsonObject { put("chain_id", node.chainId.hex()); put("height", 1); put("tip", tip); put("work", "1") }) }
        node.other["/v1/blocks/$tip/raw?offset=0&length=$HEADER_BYTES"] = { jsonResponse(buildJsonObject { put("hex", header.hex()) }) }
        app.chain.node.status()
        // Its newest block says 2100: that is the node's word alone, and no answer was taken from it.
        assertEquals(t + Chain.SIGNED_LIFE, app.chain.signedExpiry())
    }
}
