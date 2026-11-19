// "Forget waiting transactions" in Settings: what it puts away, the nonces it
// frees, and the question before it (state/Watch.kt, Store.forgetWaiting).
package app.tingid.state

import app.tingid.chain.filled
import app.tingid.chain.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForgetWaitingTest {
    private val app = TestApp(FakeRestNode(filled(0x2a)))
    private val t = app.clock

    /** The end of the day after `at`, as a payment signed then expires (Chain.signedExpiry). */
    private fun endOfNextDay(at: Long) = (at / 86_400 + 2) * 86_400

    init {
        app.store.addUser(User("u", "Anna", "#000", UserKind.Key))
    }

    private fun keep(id: String, notBefore: Long? = null) = app.store.keepOutbox(id, OutboxEntry("00", notBefore = notBefore, at = t))

    @Test
    fun `forgetting what waits stops following payments and calls, frees their nonces, and leaves the rest`() {
        val coinKey = "${filled(0x44).hex()}:${filled(1).hex()}"
        val programKey = "${filled(0x5a).hex()}:${filled(1).hex()}"
        // A payment, and a program's call that pays no fee.
        app.store.addSubmitted(SubmittedTx("p1", t, "Send 1 DLR", "u", coin = "c", account = "a", to = "b"))
        app.store.addSubmitted(SubmittedTx("c1", t, "Program call", "u"))
        // What is left alone: an identity's change, a payment the node holds for later, another user's, and one settled.
        app.store.addSubmitted(SubmittedTx("g1", t, "Link a guardian", "u"))
        app.store.addSubmitted(SubmittedTx("h1", t, "Send 3 DLR", "u", coin = "c", account = "a"))
        app.store.addSubmitted(SubmittedTx("o1", t, "Send 4 DLR", "v", coin = "c", account = "z"))
        app.store.addSubmitted(SubmittedTx("d1", t, "Send 5 DLR", "u", coin = "c", account = "a", state = TxState.Included))
        listOf("p1", "c1", "g1", "o1", "d1").forEach { keep(it) }
        keep("h1", notBefore = t + 9_000)
        app.store.markProgramCall("c1", unpaid = true)
        app.store.noteNonce(coinKey, NonceNote(3, "p1", t))
        app.store.noteNonce(programKey, NonceNote(1, "c1", t))
        app.store.noteNonce("op:registry:identity", NonceNote(2, "g1", t))
        app.store.noteNonce("other:account", NonceNote(9, "o1", t))

        val d = app.store.data.value
        val waiting = waitingToForget(d, "u", app.chainHex, t)
        assertEquals(setOf("p1", "c1"), waiting.map { it.id }.toSet())
        // Until when each could still go in: the end of the day after for a payment, an hour for a call.
        assertEquals(mapOf("p1" to endOfNextDay(t), "c1" to t + Chain.CALL_LIFE), waiting.associate { it.id to couldGoInUntil(it, d.outbox) })
        assertNull(couldGoInUntil(d.submitted.first { it.id == "g1" }, d.outbox))

        app.store.forgetWaiting("u", app.chainHex, t)
        val after = app.store.data.value
        assertEquals(
            mapOf("p1" to TxState.Dismissed, "c1" to TxState.Dismissed, "g1" to TxState.Pending, "h1" to TxState.Pending, "o1" to TxState.Pending, "d1" to TxState.Included),
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
    fun `a call paid through a batch is forgotten with the call sent alone`() {
        // The batch is the submission; the call sent alone is its twin, and carries the mark.
        app.store.addSubmitted(SubmittedTx("b1", t, "Program call", "u", coin = "c", account = "a"))
        keep("b1")
        keep("u1")
        app.store.pairOutbox("b1", "u1")
        app.store.markProgramCall("u1", unpaid = true)
        app.store.noteNonce("coin:payer", NonceNote(4, "b1", t, prefix = "ff"))

        val d = app.store.data.value
        assertEquals(t + Chain.CALL_LIFE, couldGoInUntil(d.submitted.single(), d.outbox))
        app.store.forgetWaiting("u", app.chainHex, t)
        val after = app.store.data.value
        assertEquals(TxState.Dismissed, after.submitted.single().state)
        assertTrue(after.outbox.isEmpty())
        assertTrue(after.nonces.isEmpty())
    }

    @Test
    fun `the question says until when one may still go through, and that it could be paid twice`() {
        val time = { at: Long -> "T$at" }
        val payment = SubmittedTx("p1", t, "Send 1 DLR", "u", coin = "c", account = "a")
        val call = SubmittedTx("c1", t, "Program call", "u")
        val outbox = mapOf("c1" to OutboxEntry("00", at = t, unpaid = true))
        val last = endOfNextDay(t) + 3_600

        val one = forgetWords(listOf(payment), outbox, t, time)
        assertTrue(one.startsWith("Ting ID stops following the transaction that no block has taken yet, and your next one no longer waits behind it."))
        assertTrue(one.contains("This cancels nothing: it may still go through until about T$last."))
        assertTrue(one.endsWith("If you send the same payment again before then, it could be paid twice."))

        // The latest of them counts: here the payment's, not the call's hour.
        val two = forgetWords(listOf(call, payment), outbox, t, time)
        assertTrue(two.contains("the 2 transactions that no block has taken yet") && two.contains("waits behind them."))
        assertTrue(two.contains("any of them may still go through until about T$last."))

        // A call alone: its hour, and as long again as a block's time may trail the clock.
        assertTrue(forgetWords(listOf(call), outbox, t, time).contains("until about T${t + Chain.CALL_LIFE + 3_600}."))

        assertTrue(forgetWords(listOf(payment), outbox, last, time).contains("may still go through"))
        val over = forgetWords(listOf(payment), outbox, last + 1, time)
        assertTrue(over.endsWith("It has expired, so nothing of it can still go through."))
        assertFalse(over.contains("paid twice"))
        assertTrue(forgetWords(listOf(payment, call), outbox, last + 1, time).endsWith("They have expired, so nothing of them can still go through."))
    }

    @Test
    fun `forgetting notes until when each could still go in`() {
        app.store.addSubmitted(SubmittedTx("p1", t, "Send 1 DLR", "u", coin = "c", account = "a", to = "b", sends = "1"))
        keep("p1")
        app.store.forgetWaiting("u", app.chainHex, t)
        val p1 = app.store.data.value.submitted.single()
        assertEquals(TxState.Dismissed, p1.state)
        assertEquals(endOfNextDay(t), p1.forgottenUntil)
    }

    private fun item(tx: SubmittedTx, watch: Watch = Watch.Checking) = PendingItem(tx, watch, canRetry = true)

    @Test
    fun `only the same payment or call is a repeat, while it could still go in`() {
        val pay = SubmittedTx("p1", t, "Send 1 DLR", "u", coin = "c", account = "a", to = "b", sends = "100")
        val same = { tx: SubmittedTx -> tx.coin == "c" && tx.account == "a" && tx.to == "b" && tx.sends == "100" }
        val d = app.store.data.value
        assertEquals(pay, repeatOf(listOf(item(pay)), d, "u", app.chainHex, t, same))
        // Another amount, another recipient, or one noted without what it sends: no question.
        assertNull(repeatOf(listOf(item(pay.copy(sends = "101"))), d, "u", app.chainHex, t, same))
        assertNull(repeatOf(listOf(item(pay.copy(to = "x"))), d, "u", app.chainHex, t, same))
        assertNull(repeatOf(listOf(item(pay.copy(sends = null))), d, "u", app.chainHex, t, same))
        // In a block already, or failed: no second one of it is waiting.
        assertNull(repeatOf(listOf(item(pay, Watch.Confirming(5))), d, "u", app.chainHex, t, same))
        assertNull(repeatOf(listOf(item(pay, Watch.Failed("no", 5))), d, "u", app.chainHex, t, same))
        // Another user's.
        assertNull(repeatOf(listOf(item(pay)), d, "v", app.chainHex, t, same))
        // Past the end of the day after, and the hour a block's time may trail the clock, a payment can no longer go in.
        val over = endOfNextDay(t) + 3_600
        assertEquals(pay, repeatOf(listOf(item(pay)), d, "u", app.chainHex, over, same))
        assertNull(repeatOf(listOf(item(pay)), d, "u", app.chainHex, over + 1, same))
        // The newest is the one asked about.
        val later = pay.copy(id = "p2", at = t + 60)
        assertEquals(later, repeatOf(listOf(item(pay), item(later)), d, "u", app.chainHex, t + 60, same))

        // A call: by its key.
        val call = SubmittedTx("c1", t, "Program call", "u", call = "k1")
        assertEquals(call, repeatOf(listOf(item(call)), d, "u", app.chainHex, t) { it.call == "k1" })
        assertNull(repeatOf(listOf(item(call)), d, "u", app.chainHex, t) { it.call == "k2" })
    }

    @Test
    fun `a forgotten payment counts until its time runs out, one put away by hand does not`() {
        app.store.addSubmitted(SubmittedTx("p1", t, "Send 1 DLR", "u", coin = "c", account = "a", to = "b", sends = "100"))
        keep("p1")
        app.store.forgetWaiting("u", app.chainHex, t)
        app.store.addSubmitted(SubmittedTx("p2", t + 1, "Send 1 DLR", "u", coin = "c", account = "a", to = "b", sends = "100", state = TxState.Dismissed))
        val d = app.store.data.value
        val same = { tx: SubmittedTx -> tx.sends == "100" }
        assertEquals("p1", repeatOf(emptyList(), d, "u", app.chainHex, t, same)?.id)
        assertEquals("p1", repeatOf(emptyList(), d, "u", app.chainHex, endOfNextDay(t) + 3_600, same)?.id)
        assertNull(repeatOf(emptyList(), d, "u", app.chainHex, endOfNextDay(t) + 3_601, same))
        assertNull(repeatOf(emptyList(), d, "v", app.chainHex, t, same))
    }

    @Test
    fun `the questions say where the first one stands, and what a second one does`() {
        val time = { at: Long -> "T$at" }
        val waiting = SubmittedTx("p1", t, "Send 1 DLR", "u", coin = "c", account = "a", to = "b", sends = "100")
        assertEquals(
            "You sent this at T$t, and it is still waiting for a block. If you send it again, it could be paid twice.",
            sendAgainWords(waiting, time),
        )
        val forgotten = waiting.copy(state = TxState.Dismissed, forgottenUntil = t + 100)
        assertEquals(
            "You sent this at T$t and forgot it in Settings, but it could still go through until about T${t + 100 + 3_600}. If you send it again, it could be paid twice.",
            sendAgainWords(forgotten, time),
        )
        assertEquals(
            "You approved this at T$t, and it is still waiting for a block. Approved again, it could run twice.",
            approveAgainWords(SubmittedTx("c1", t, "Program call", "u", call = "k1"), time),
        )
    }
}
