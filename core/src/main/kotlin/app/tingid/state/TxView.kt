// What the transaction screen makes of a transaction as the node has it:
// where it stands, its events read as coin events where the coin is
// followed, which of them are fees, and the payment it made. Pure, for tests.
package app.tingid.state

import app.tingid.chain.Coin
import app.tingid.chain.Receipt
import app.tingid.chain.TransactionLookup
import app.tingid.chain.equal
import app.tingid.chain.hex
import java.math.BigInteger

/** Where a transaction stands, as its screen says. */
enum class TxStage {
    /** In a block, and it succeeded. */
    Ok,

    /** In a block, and it scheduled a payment for later. */
    Scheduled,

    /** In a block, and it did not succeed. */
    Failed,

    /** In the node's pool, waiting for a block. */
    Pending,

    /** Held by the node until its time. */
    Deferred,

    /** The node does not know it. */
    Unknown,

    /** Not looked up yet. */
    Loading,
}

/** An event of the transaction; `coin` when it is an event of a followed coin. */
class TxEvent(val contract: ByteArray, val coin: Coin.Event?, val payload: ByteArray)

/** The events of `receipt`, read as coin events where the contract is one of `coins` (by hex). */
fun txEvents(receipt: Receipt?, coins: Map<String, CoinInfo>): List<TxEvent> =
    (receipt?.events ?: emptyList()).map { e ->
        TxEvent(e.contract, if (coins.containsKey(e.contract.hex())) Coin.decodeEvent(e.payload) else null, e.payload)
    }

/** The scheduling a payment for later makes: such a transaction is in a block once scheduled, and runs later in another. */
fun scheduledIn(events: List<TxEvent>): Coin.Event.Scheduled? = events.firstNotNullOfOrNull { it.coin as? Coin.Event.Scheduled }

/** The last block that included the transaction, with its receipt. */
fun lastOccurrence(lookup: TransactionLookup?) = lookup?.occurrences?.lastOrNull()

/** Where the transaction stands: `lookup` as last read (null until read), `missing` when the node said it does not know it. */
fun txStage(lookup: TransactionLookup?, missing: Boolean, events: List<TxEvent>): TxStage {
    val receipt = lastOccurrence(lookup)?.receipt
    return when {
        receipt != null -> when {
            receipt.status != 0 -> TxStage.Failed
            scheduledIn(events) != null -> TxStage.Scheduled
            else -> TxStage.Ok
        }
        lookup?.pending == true -> TxStage.Pending
        lookup?.deferred != null -> TxStage.Deferred
        lookup != null || missing -> TxStage.Unknown
        else -> TxStage.Loading
    }
}

/** A fee or tip: a transfer that the producer payment after it pays on. */
fun isFeeEvent(events: List<TxEvent>, i: Int): Boolean {
    val ev = events[i].coin as? Coin.Event.Transfer ?: return false
    val paid = events.getOrNull(i + 1)?.coin as? Coin.Event.ProducerPayment ?: return false
    return equal(paid.payee, ev.to) && paid.amount == ev.amount
}

/** A result that reads as text: printable ASCII only. */
fun printable(result: ByteArray): Boolean = result.isNotEmpty() && result.all { it >= 0x20 && it < 0x7f }

/** The payment of a transaction: whom it went to ("To") or, for one to these accounts, whom it came from ("From"), and how much of which coin. */
class TxPayment(val label: String, val account: ByteArray, val amount: BigInteger? = null, val coin: CoinInfo? = null)

/**
 * The payment of the transaction: who it went to, or for a payment to one of
 * the accounts `mine` who it came from, and how much. A fee is no payment, nor
 * is a burn, which the coin shows as a transfer to the zero account, and a
 * scheduled payment counts as made here.
 */
fun paymentOf(events: List<TxEvent>, mine: List<ByteArray>, coins: Map<String, CoinInfo>): TxPayment? {
    fun isMine(a: ByteArray) = mine.any { equal(it, a) }
    for (i in events.indices) {
        val ev = events[i].coin ?: continue
        if (isFeeEvent(events, i)) continue
        if (ev is Coin.Event.Transfer && equal(ev.to, Coin.ZERO_ACCOUNT)) continue
        val c = coins[events[i].contract.hex()]
        if (ev is Coin.Event.Transfer && isMine(ev.from)) return TxPayment("To", ev.to, ev.amount, c)
        if (ev is Coin.Event.Transfer && isMine(ev.to)) return TxPayment("From", ev.from, ev.amount, c)
        if (ev is Coin.Event.Scheduled) {
            val effect = ev.effect
            if (effect is Coin.Effect.Transfer) return TxPayment("To", effect.to, effect.amount, c)
        }
    }
    return null
}
