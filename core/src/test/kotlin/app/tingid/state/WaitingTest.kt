// What this phone sent and no block has taken yet: how long what it signs
// lasts, when it is cleared and settled as not included, when a queue looks
// stuck, and the same payment or call asked about (state/Watch.kt, Chain). All
// of it goes by what each transaction's bytes say (state/Signed.kt).
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
    private val q = coinQueue("c", "a")

    /** A payment from account `a` in coin `c`, sent `ago` seconds before `t`, signed for three hours. */
    private fun payment(id: String, ago: Long, account: String = "a") =
        SubmittedTx(id, t - ago, "Send 1 DLR", "u", coin = "c", account = account, to = "b", sends = "100", expires = t - ago + Chain.SIGNED_LIFE)

    /** What a payment's bytes say: it takes `nonce` in `queue`, until `expires`. */
    private fun takes(nonce: Long, expires: Long?, queue: String = q) = Signed(listOf(Taken(queue, nonce)), expires)

    /** A payment as the pending list has it: its bytes read as its submission says. */
    private fun item(tx: SubmittedTx, watch: Watch = Watch.Waiting, signed: List<Signed>? = listOf(takes(0, tx.expires, coinQueue(tx.coin!!, tx.account!!)))) =
        PendingItem(tx, watch, canRetry = true, signed = signed)

    @Test
    fun `payments last three hours, calls one`() {
        assertEquals(3 * 3_600L, Chain.SIGNED_LIFE)
        assertEquals(3_600L, Chain.CALL_LIFE)
    }

    @Test
    fun `a submission can go in until its bytes say`() {
        val pay = payment("p1", 0)
        // The bytes go first, whatever was noted.
        assertEquals(t + 60, couldGoInUntil(pay, listOf(takes(0, t + 60))))
        // A batch and the call sent alone: until the later of the two.
        assertEquals(t + 600, couldGoInUntil(pay, listOf(takes(0, t + 60), takes(0, t + 600))))
        // Something in it that never expires, as an identity's change sent alone: no expiry.
        assertNull(couldGoInUntil(pay, listOf(takes(0, t + 60), Signed(emptyList(), null))))
        // Bytes the app cannot read: as noted when it was signed, and nothing when nothing was.
        assertEquals(t + Chain.SIGNED_LIFE, couldGoInUntil(pay, null))
        assertNull(couldGoInUntil(pay.copy(expires = null), null))
    }

    @Test
    fun `a queue looks stuck after ten minutes without a block, and clears with the last in it`() {
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
        val call = SubmittedTx("c1", t - 1_800, "Program call", "u")
        val callQ = callQueue(filled(5), filled(6))
        val paidCall = Signed(listOf(Taken(callQ, 4), Taken(q, 9)), t + 1_800)
        assertEquals(setOf(q, callQ), stuckQueues(listOf(PendingItem(call, Watch.Waiting, true, signed = listOf(paidCall))), emptyMap(), t).keys)
    }

    @Test
    fun `the queues its bytes take go first, and what was noted stands in for bytes the app cannot read`() {
        // Noted as a payment from `a`, signed with a nonce of `z`'s: the bytes say where it waits, and until when.
        val pay = payment("p1", 1_800)
        val bytes = listOf(takes(0, t + 60, coinQueue("c", "z")))
        assertEquals(mapOf(coinQueue("c", "z") to Stuck(t - 1_800, t + 60 + LATE)), stuckQueues(listOf(item(pay, signed = bytes)), emptyMap(), t))
        // Unreadable bytes: as noted.
        assertEquals(mapOf(q to Stuck(t - 1_800, pay.expires!! + LATE)), stuckQueues(listOf(item(pay, signed = null)), emptyMap(), t))
        // Nothing noted either: no queue to tell of.
        assertTrue(stuckQueues(listOf(item(pay.copy(expires = null), signed = null)), emptyMap(), t).isEmpty())
    }

    @Test
    fun `a change whose fee Spending pays waits in Spending's queue until its fee payment expires`() {
        // An identity's change names no coin, and its bytes take no nonce of one: alone it is in no queue.
        val change = SubmittedTx("g1", t - 1_800, "Link a guardian", "u")
        val alone = Signed(emptyList(), null)
        assertTrue(stuckQueues(listOf(PendingItem(change, Watch.Waiting, true, signed = listOf(alone))), emptyMap(), t).isEmpty())
        // Paid for from Spending through a batch, it holds Spending's next nonce until the batch expires.
        val batch = takes(3, t - 1_800 + Chain.SIGNED_LIFE)
        val paid = PendingItem(change, Watch.Waiting, true, signed = listOf(batch, alone))
        assertEquals(mapOf(q to Stuck(t - 1_800, t - 1_800 + Chain.SIGNED_LIFE + LATE)), stuckQueues(listOf(paid), emptyMap(), t))
        // So the first payment behind it is asked about, and clears with whichever of the two is later.
        assertEquals(Stuck(t - 1_800, t - 60 + Chain.SIGNED_LIFE + LATE), stuckQueues(listOf(paid, item(payment("p1", 60))), emptyMap(), t)[q])
        // Once the batch is cleared, it holds nothing there; the change alone may still go in.
        assertTrue(stuckQueues(listOf(paid), emptyMap(), t - 1_800 + Chain.SIGNED_LIFE + LATE + 1).isEmpty())
        assertNull(couldGoInUntil(change, paid.signed))
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
        assertEquals(pay, repeatOf(listOf(item(pay)), t, same))
        assertNull(repeatOf(listOf(item(pay.copy(sends = "101"))), t, same))
        assertNull(repeatOf(listOf(item(pay.copy(to = "x"))), t, same))
        assertNull(repeatOf(listOf(item(pay.copy(sends = null))), t, same))
        assertNull(repeatOf(listOf(item(pay, Watch.Confirming(5))), t, same))
        assertNull(repeatOf(listOf(item(pay, Watch.Failed("no", 5))), t, same))
        assertNull(repeatOf(listOf(item(pay)), t - 600 + Chain.SIGNED_LIFE + LATE + 1, same))
        // Cleared as its bytes say, though nothing was noted when it was signed.
        assertNull(repeatOf(listOf(item(pay.copy(expires = null), signed = listOf(takes(0, t - 60 - LATE)))), t, same))
        val later = pay.copy(id = "p2", at = t - 60)
        assertEquals(later, repeatOf(listOf(item(pay), item(later)), t, same))

        val call = SubmittedTx("c1", t, "Program call", "u", call = "k1", expires = t + 3_600)
        assertEquals(call, repeatOf(listOf(PendingItem(call, Watch.Waiting, true)), t) { it.call == "k1" })
        assertNull(repeatOf(listOf(PendingItem(call, Watch.Waiting, true)), t) { it.call == "k2" })

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
        val until = t + Chain.SIGNED_LIFE
        val cleared = until + LATE + 1
        val held = TransactionLookup(true, null, emptyList())
        // Until then it waits, or is sent again when the node lost it.
        assertEquals(Decision.Follow(Watch.Waiting), decide(pay, held, kept, cleared - 1, 100, null, words, until))
        assertTrue(decide(pay, null, kept, t + 600, 100, null, words, until) is Decision.Resend)
        // Then it is over, whether a node still holds it or lost it.
        assertEquals(Decision.Expired, decide(pay, held, kept, cleared, 100, null, words, until))
        assertEquals(Decision.Expired, decide(pay, null, kept, cleared, 100, null, words, until))
        // A block that has it says otherwise.
        val inBlock = TransactionLookup(false, null, listOf(Occurrence(90, receipt("Success"))))
        assertEquals(Decision.Included(90), decide(pay, inBlock, kept, cleared, 100, null, words, until))
        // One of an earlier version noted no expiry: its bytes' clear it all the same.
        assertEquals(Decision.Expired, decide(pay.copy(expires = null), held, kept, cleared, 100, null, words, until))
        // What nothing says expires goes on.
        assertEquals(Decision.Follow(Watch.Waiting), decide(pay.copy(expires = null), held, kept, cleared, 100, null, words, null))
    }

    /** A test app on an empty chain, at `t`, with a secp256k1 key's account. */
    private class Setup(seed: Int, t: Long) {
        val node = FakeRestNode(filled(seed))
        val app = TestApp(node, clock = t)
        val c = Contracts.canonical(node.chainId)
        val signer = Signer(MemoryKey(filled(1)), null, c.policySecp256k1, publicKey(filled(1)))
        val coin = filled(0x44)
        val payer = FeePayer(signer, coin, BigInteger.valueOf(1_000))
        val program = filled(0x5a)
        val template = Tcr.templateHash("give(item:u64): Give item {item}")

        init {
            node.emptyState = true
            runBlocking { app.chain.node.status() }
        }

        /** A payment, followed as the send screen follows one. */
        suspend fun pay(): Chain.Submitted =
            app.chain.submitCoinAction(node.chainId, coin, signer, Coin.Action(Coin.Effect.Transfer(filled(0x7f), BigInteger.ONE), Coin.NO_TIMING), payer = payer)
                .also { app.store.addSubmitted(SubmittedTx(it.txId, app.clock, "Send", "u", coin = coin.hex(), account = it.account.hex(), expires = it.expires)) }

        /** A call paying only its own fee, as a withdrawal from a listing does, followed as the call screen follows one. */
        suspend fun call(item: Long): Chain.Submitted =
            app.chain.submitProgramCall(node.chainId, program, template, u64le(item), signer, null, coin, payer)
                .also { app.store.addSubmitted(SubmittedTx(it.txId, app.clock, "Withdraw", "u", call = "${program.hex()}:${it.account.hex()}:$item", expires = it.expires)) }

        suspend fun nextPayment() = app.chain.nextNonce(node.chainId, coin, signer.account)
        suspend fun nextCall() = app.chain.nextCallNonce(node.chainId, program, signer.account)

        /** The pending list as Pending gives it: what is followed, each with what its bytes say. */
        fun items(): List<PendingItem> {
            val d = app.store.data.value
            return d.submitted.filter { it.state == TxState.Pending }.map { PendingItem(it, Watch.Waiting, true, signed = readSubmission(c, d.outbox, it.id)) }
        }

        /**
         * The rule: whatever a new payment or call would wait behind, once it
         * has waited ten minutes, the warning shows; and the warning shows
         * nothing a new one would not wait behind.
         */
        suspend fun assertWarningMatchesQueue() {
            val now = app.clock
            val items = items()
            val stuck = stuckQueues(items, app.store.data.value.outbox, now)
            for ((queue, next) in listOf(coinQueue(coin.hex(), signer.account.hex()) to nextPayment(), callQueue(program, signer.account) to nextCall())) {
                // The chain's nonce is 0 here: a higher one waits behind something.
                val behind = next > 0
                // Something the pending list still shows in the queue, sent ten minutes ago or more, whatever its expiry.
                val inQueue = { item: PendingItem -> item.signed?.any { s -> s.takes.any { it.queue == queue } } ?: holdsOf(item.tx, null).any { it.queue == queue } }
                val waited = items.any { now - it.tx.at >= STUCK_AFTER && inQueue(it) }
                if (behind && waited) assertTrue("$queue at $now: a new one waits behind, and the warning says nothing", queue in stuck)
                if (queue in stuck) assertTrue("$queue at $now: the warning shows what a new one would not wait behind", behind)
            }
        }
    }

    @Test
    fun `the next nonce waits behind what was sent until it is cleared, and the warning says so`() = runBlocking {
        val s = Setup(0x2a, t)
        val pay = s.pay()
        s.node.lookups[pay.txId] = pendingLookup()
        // Signed for three hours from the clock: the empty state's block is older.
        assertEquals(t + Chain.SIGNED_LIFE, s.app.chain.signedExpiry())
        assertEquals(t + Chain.SIGNED_LIFE, pay.expires)
        assertEquals(1L, s.nextPayment())
        s.assertWarningMatchesQueue()
        // Ten minutes without a block: the warning shows what the next payment waits behind.
        s.app.clock = t + STUCK_AFTER
        assertTrue(coinQueue(s.coin.hex(), pay.account.hex()) in stuckQueues(s.items(), s.app.store.data.value.outbox, s.app.clock))
        s.assertWarningMatchesQueue()
        s.app.clock = t + Chain.SIGNED_LIFE + LATE
        assertEquals(1L, s.nextPayment())
        s.assertWarningMatchesQueue()
        // Cleared: the chain's nonce is next, though a node still holds it, and the warning is gone.
        s.app.clock = t + Chain.SIGNED_LIFE + LATE + 1
        assertEquals(0L, s.nextPayment())
        s.assertWarningMatchesQueue()
    }

    @Test
    fun `a nonce whose transaction expired is taken again, though one ahead of it still waits`() = runBlocking {
        val s = Setup(0x28, t)
        // A payment at nonce 0, signed for three hours, that no block takes.
        val pay = s.pay()
        s.node.lookups[pay.txId] = pendingLookup()
        assertEquals(0L, pay.nonce)
        // A minute later, a call paying its fee from the same account: the payment queues at nonce 1, for an hour.
        s.app.clock = t + 60
        val call = s.call(7)
        s.node.lookups[call.txId] = pendingLookup()
        assertEquals(2L, s.nextPayment())
        for (at in listOf(t + 60, t + 60 + STUCK_AFTER, t + 60 + Chain.CALL_LIFE + LATE, t + 60 + Chain.CALL_LIFE + LATE + 1, t + Chain.SIGNED_LIFE + LATE + 1)) {
            s.app.clock = at
            s.assertWarningMatchesQueue()
        }
        // The call is cleared and can never take nonce 1; the payment ahead of it still can take 0.
        // The next payment takes 1, behind the payment, instead of waiting behind what cannot go in.
        s.app.clock = t + 60 + Chain.CALL_LIFE + LATE + 1
        assertEquals(1L, s.nextPayment())
        assertEquals(0L, s.nextCall())
        // Once the payment is cleared too, the chain's nonce is free again.
        s.app.clock = t + Chain.SIGNED_LIFE + LATE + 1
        assertEquals(0L, s.nextPayment())
    }

    @Test
    fun `what the node does not know holds its nonce until it is cleared, and what failed in a block holds none`() = runBlocking {
        val s = Setup(0x2b, t)
        // The node does not know it (a 404): Pending sends it again, so it still holds its nonce.
        val lost = s.pay()
        assertEquals(1L, s.nextPayment())
        s.app.clock = t + Chain.SIGNED_LIFE + LATE + 1
        assertEquals(0L, s.nextPayment())
        // One that failed in a block took no nonce, and holds none.
        s.app.clock = t
        s.node.lookups[lost.txId] = includedLookup(5, status = 3)
        assertEquals(0L, s.nextPayment())
        // One that went in is counted in the chain's nonce instead.
        s.node.lookups[lost.txId] = includedLookup(5)
        assertEquals(0L, s.nextPayment())
    }

    @Test
    fun `what the app no longer follows holds nothing, so nothing waits where the warning cannot see`() = runBlocking {
        val s = Setup(0x2f, t)
        val pay = s.pay()
        s.node.lookups[pay.txId] = pendingLookup()
        assertEquals(1L, s.nextPayment())
        // Put away: the warning no longer shows it, and the next payment takes its nonce instead of waiting behind it.
        s.app.store.updateSubmitted(pay.txId) { it.copy(state = TxState.Dismissed) }
        s.app.store.forgetOutbox(pay.txId)
        s.app.clock = t + STUCK_AFTER
        assertEquals(0L, s.nextPayment())
        assertTrue(stuckQueues(s.items(), s.app.store.data.value.outbox, s.app.clock).isEmpty())
        // Failed, as when the node refused it sent again: the same.
        val again = s.pay()
        s.node.lookups[again.txId] = pendingLookup()
        s.app.store.updateSubmitted(again.txId) { it.copy(state = TxState.Failed, problem = "refused") }
        assertEquals(0L, s.nextPayment())
        s.assertWarningMatchesQueue()
    }

    @Test
    fun `a call paying only its own fee takes its coin's nonce too`() = runBlocking {
        val s = Setup(0x2c, t)
        val call = s.call(7)
        val signed = readSubmission(s.c, s.app.store.data.value.outbox, call.txId)!!
        assertEquals(
            listOf(Signed(listOf(Taken(callQueue(s.program, call.account), 0), Taken(coinQueue(s.coin.hex(), call.account.hex()), 0)), call.expires)),
            signed,
        )
        // The stuck queue a question asks about is the one that clears last.
        val stuck = mapOf("a" to Stuck(t, t + 10), "b" to Stuck(t, t + 20))
        assertEquals(Stuck(t, t + 20), stuckIn(stuck, listOf("a", null, "b", "c")))
        assertNull(stuckIn(stuck, listOf(null, "c")))
    }

    /**
     * Withdrawals from listings, sent by Ting ID 1.6.0, which noted no expiry
     * for them; the first can never go in, so all wait behind it in the node.
     * After updating, each is over an hour after its own expiry, as its bytes
     * say, not at the end of the next day, and the warning shows them until then.
     */
    @Test
    fun `what an earlier version left waiting is cleared by its own expiry`() = runBlocking {
        val s = Setup(0x2d, t)
        val sent = (0 until 25).map { i ->
            s.app.clock = t + 60L * i
            s.call(i.toLong()).also { s.node.lookups[it.txId] = pendingLookup() }
        }
        assertEquals((0L until 25).toList(), sent.map { it.nonce })
        val last = sent.last()
        // As 1.6.0 left it: nothing noted of when any of it expires, nor which call it is.
        for (r in sent) s.app.store.updateSubmitted(r.txId) { it.copy(expires = null, call = null) }
        s.app.store.noteNonce("${s.program.hex()}:${last.account.hex()}", NonceNote(last.nonce, last.txId, last.expires - Chain.CALL_LIFE))
        s.app.store.noteNonce("${s.coin.hex()}:${last.account.hex()}", NonceNote(last.nonce, last.txId, last.expires - Chain.CALL_LIFE))
        val outbox = s.app.store.data.value.outbox
        val items = s.items()
        assertEquals(25, items.size)

        // While they can still go in, the next call and the next payment queue behind them, and the queues look stuck.
        s.app.clock = last.expires
        assertEquals(25L, s.nextCall())
        assertEquals(25L, s.nextPayment())
        val stuck = stuckQueues(items, outbox, s.app.clock)
        assertEquals(Stuck(t, last.expires + LATE), stuck[callQueue(s.program, last.account)])
        assertEquals(Stuck(t, last.expires + LATE), stuck[coinQueue(s.coin.hex(), last.account.hex())])
        for (at in listOf(t + STUCK_AFTER, sent[10].expires + LATE + 1, last.expires, last.expires + LATE)) {
            s.app.clock = at
            s.assertWarningMatchesQueue()
        }

        // An hour after the last one's expiry, well before the end of the next day, all of it is over.
        s.app.clock = last.expires + LATE + 1
        assertTrue(s.app.clock < (t / 86_400 + 2) * 86_400)
        s.assertWarningMatchesQueue()
        assertEquals(0L, s.nextCall())
        assertEquals(0L, s.nextPayment())
        assertTrue(stuckQueues(items, outbox, s.app.clock).isEmpty())
        assertNull(repeatOf(items, s.app.clock) { true })
        val held = TransactionLookup(true, null, emptyList())
        for (it in items) assertEquals(Decision.Expired, decide(it.tx, held, outbox[it.tx.id], s.app.clock, 1, null, words, couldGoInUntil(it.tx, it.signed)))
    }

    @Test
    fun `bytes the app cannot read hold what was noted when they were signed, and the warning shows it`() = runBlocking {
        val s = Setup(0x2e, t)
        val account = s.signer.account
        val id = "cc".repeat(32)
        s.app.store.keepSubmission(id, byteArrayOf(9, 9, 9), null)
        s.app.store.addSubmitted(SubmittedTx(id, t, "Program call", "u", call = "${s.program.hex()}:${account.hex()}:h", expires = t + Chain.CALL_LIFE))
        s.app.store.noteNonce("${s.program.hex()}:${account.hex()}", NonceNote(4, id, t, null, t + Chain.CALL_LIFE))
        assertEquals(5L, s.nextCall())
        s.app.clock = t + STUCK_AFTER
        assertTrue(callQueue(s.program, account) in stuckQueues(s.items(), s.app.store.data.value.outbox, s.app.clock))
        s.assertWarningMatchesQueue()
        s.app.clock = t + Chain.CALL_LIFE + LATE + 1
        assertEquals(0L, s.nextCall())
        s.assertWarningMatchesQueue()
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
