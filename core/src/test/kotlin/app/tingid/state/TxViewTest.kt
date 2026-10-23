// What the transaction screen makes of a transaction: where it stands, its
// fees and its payment (state/TxView).
package app.tingid.state

import app.tingid.chain.Coin
import app.tingid.chain.Deferred
import app.tingid.chain.Occurrence
import app.tingid.chain.Receipt
import app.tingid.chain.ReceiptEvent
import app.tingid.chain.TransactionLookup
import app.tingid.chain.Writer
import app.tingid.chain.filled
import app.tingid.chain.hex
import app.tingid.chain.utf8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class TxViewTest {
    private val dlr = CoinInfo(
        id = filled(0x44),
        hex = filled(0x44).hex(),
        config = Coin.Config("daler", "DLR", 2, Coin.UNCAPPED, BigInteger.ZERO, 0, BigInteger.ZERO, 0),
        state = Coin.State(BigInteger.ZERO, BigInteger.ZERO, 0, Coin.ZERO_ACCOUNT),
    )
    private val coins = mapOf(dlr.hex to dlr)
    private val me = filled(0x10)
    private val bob = filled(0x20)
    private val miner = filled(0x30)

    private fun big(n: Long) = BigInteger.valueOf(n)

    private fun transfer(from: ByteArray, to: ByteArray, amount: Long) =
        ReceiptEvent(dlr.id, Writer().put(Coin.TRANSFER_TOPIC).put(from).put(to).u128(big(amount)).finish())

    private fun producer(payee: ByteArray, amount: Long) =
        ReceiptEvent(dlr.id, Writer().put(Coin.PRODUCER_PAYMENT_TOPIC).put(payee).u128(big(amount)).finish())

    private fun receipt(status: Int = 0, events: List<ReceiptEvent> = emptyList(), result: ByteArray = ByteArray(0)) =
        Receipt(status, if (status == 0) "Ok" else "Revert", listOf(1, 2, 3, 4), result, events)

    private fun included(r: Receipt) = TransactionLookup(pending = false, deferred = null, occurrences = listOf(Occurrence(12, r)))

    @Test
    fun `a transaction's stage follows its lookup`() {
        assertEquals(TxStage.Loading, txStage(null, false, emptyList()))
        assertEquals(TxStage.Unknown, txStage(null, true, emptyList()))
        assertEquals(TxStage.Pending, txStage(TransactionLookup(true, null, emptyList()), false, emptyList()))
        assertEquals(TxStage.Deferred, txStage(TransactionLookup(false, Deferred(100, 200, false, null), emptyList()), false, emptyList()))
        assertEquals(TxStage.Unknown, txStage(TransactionLookup(false, null, emptyList()), false, emptyList()))
        assertEquals(TxStage.Ok, txStage(included(receipt()), false, emptyList()))
        assertEquals(TxStage.Failed, txStage(included(receipt(status = 1)), false, emptyList()))
        val scheduled = listOf(TxEvent(dlr.id, Coin.Event.Scheduled(me, 3, 500, BigInteger.ZERO, Coin.Effect.Transfer(bob, big(9))), ByteArray(0)))
        assertEquals(TxStage.Scheduled, txStage(included(receipt()), false, scheduled))
        assertEquals(500L, scheduledIn(scheduled)?.executeAt)
    }

    @Test
    fun `events are read as coin events only for followed coins`() {
        val r = receipt(events = listOf(transfer(me, bob, 5), ReceiptEvent(filled(0x99), transfer(me, bob, 5).payload)))
        val events = txEvents(r, coins)
        assertTrue(events[0].coin is Coin.Event.Transfer)
        assertNull(events[1].coin)
        assertEquals(emptyList<TxEvent>(), txEvents(null, coins))
    }

    @Test
    fun `a transfer the next producer payment pays on is a fee, and no payment`() {
        val events = txEvents(receipt(events = listOf(transfer(me, miner, 3), producer(miner, 3), transfer(me, bob, 500))), coins)
        assertTrue(isFeeEvent(events, 0))
        assertFalse(isFeeEvent(events, 1))
        assertFalse(isFeeEvent(events, 2))
        val p = paymentOf(events, listOf(me), coins)!!
        assertEquals("To", p.label)
        assertEquals(bob.hex(), p.account.hex())
        assertEquals(big(500), p.amount)
        assertEquals(dlr, p.coin)
        // A tip of another amount than the producer was paid is a payment.
        assertFalse(isFeeEvent(txEvents(receipt(events = listOf(transfer(me, miner, 3), producer(miner, 4))), coins), 0))
    }

    @Test
    fun `a payment to one of these accounts is from its sender, and a scheduled one counts as made`() {
        val received = paymentOf(txEvents(receipt(events = listOf(transfer(bob, me, 7))), coins), listOf(me), coins)!!
        assertEquals("From", received.label)
        assertEquals(bob.hex(), received.account.hex())
        val scheduled = listOf(TxEvent(dlr.id, Coin.Event.Scheduled(me, 3, 500, BigInteger.ZERO, Coin.Effect.Transfer(bob, big(9))), ByteArray(0)))
        assertEquals(big(9), paymentOf(scheduled, listOf(me), coins)?.amount)
        assertNull(paymentOf(txEvents(receipt(events = listOf(transfer(bob, miner, 7))), coins), listOf(me), coins))
    }

    @Test
    fun `a result reads as text only when it is printable`() {
        assertTrue(printable(utf8("insufficient balance")))
        assertFalse(printable(ByteArray(0)))
        assertFalse(printable(byteArrayOf(0x41, 0x0a)))
        assertFalse(printable(byteArrayOf(0x41, -0x3f)))
    }
}
