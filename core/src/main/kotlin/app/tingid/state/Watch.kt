// Where a submitted transaction stands, from the node's answer about it and
// the bytes the app kept (state/Pending follows them). Pure, for tests.
package app.tingid.state

import app.tingid.chain.Tcr
import app.tingid.chain.TransactionLookup
import app.tingid.chain.hex
import java.math.BigInteger

/** How long after its time a lost transaction is sent again: as long as a node keeps one in its pool. */
private const val RESEND_WINDOW = 86_400L

/** How long a transaction the node does not know yet counts as just sent. */
private const val GRACE = 120L

/** Blocks on top of the one that included a transaction before it counts as settled: a shallower reorganization can still take it out. */
const val SETTLE_DEPTH = 3L

/** The blocks in a row whose timestamps give the chain's time ([chainTime]). */
const val CHAIN_TIME_BLOCKS = 11

/** How long past its expiry a signed action counts as gone while the chain's time cannot be read. */
const val LATE = 3_600L

/**
 * The chain's time after [CHAIN_TIME_BLOCKS] blocks in a row with these
 * timestamps: their median. A block must be stamped later than the median of
 * the eleven before it, so every block that follows them is later than this.
 * Null unless eleven are given.
 */
fun chainTime(times: List<Long>): Long? = if (times.size == CHAIN_TIME_BLOCKS) times.sorted()[CHAIN_TIME_BLOCKS / 2] else null

/**
 * Whether what was signed to expire at `expires` can no longer get into a
 * block. A signed action is valid in a block stamped at or before its expiry
 * (wiki: programs/coin), and a block's stamp may trail the clock, so the
 * clock alone does not say. It is gone once the chain's time has reached the
 * expiry too: `chainTime`, read [SETTLE_DEPTH] blocks below the tip
 * (Chain.chainTime), after which only a deeper reorganization could still
 * stamp a block early enough. While the chain's time cannot be read, an hour
 * past the expiry stands in.
 */
fun expired(expires: Long?, now: Long, chainTime: Long?): Boolean =
    expires != null && now > expires && (if (chainTime != null) chainTime >= expires else now > expires + LATE)

/** Where a submission stands. */
sealed class Watch {
    data object Checking : Watch()
    data object Waiting : Watch()

    /** In a block not yet [SETTLE_DEPTH] deep. */
    data class Confirming(val height: Long) : Watch()
    data class Scheduled(val notBefore: Long, val lastResult: String?) : Watch()
    data object Resent : Watch()
    data object Lost : Watch()
    data class Failed(val reason: String, val height: Long?) : Watch()
}

data class PendingItem(
    val tx: SubmittedTx,
    val watch: Watch,
    /** Whether the app still holds the bytes, to send it again. */
    val canRetry: Boolean,
    /** Whether a fee can pay for it now ([canSpeedUp]). */
    val canSpeedUp: Boolean = false,
)

/** What to do about a submission. */
sealed class Decision {
    /** A block deep enough settled it. */
    sealed class Settle : Decision() {
        abstract val height: Long
    }

    data class Included(override val height: Long) : Settle()
    data class Failed(override val height: Long, val reason: String) : Settle()

    /** The node lost it: these bytes go out again. */
    data class Resend(val entries: List<OutboxEntry>) : Decision()

    /** No block took it before what it signed expired, and none can now: nothing was paid or changed. */
    data object Expired : Decision()

    /** Nothing to do but show where it stands. */
    data class Follow(val watch: Watch) : Decision()
}

/** The node's answer about the operation a paid batch carries, sent alone, and its bytes. */
class Twin(val lookup: TransactionLookup?, val kept: OutboxEntry?)

/**
 * What to make of one transaction, from the node's answer about it and the
 * bytes the app kept. `statusWords` says in words why a transaction ran
 * without succeeding, from its receipt's status name (text/Describe.kt).
 */
private fun decideOne(sentAt: Long, lookup: TransactionLookup?, kept: OutboxEntry?, now: Long, statusWords: (String) -> String): Decision {
    if (lookup != null && lookup.occurrences.isNotEmpty()) {
        val last = lookup.occurrences.last()
        return if (last.receipt.statusName == "Success") {
            Decision.Included(last.height)
        } else {
            Decision.Failed(last.height, "it ran in block ${last.height} without succeeding: ${statusWords(last.receipt.statusName)}")
        }
    }
    val deferred = lookup?.deferred
    if (deferred != null && !deferred.admitted) return Decision.Follow(Watch.Scheduled(deferred.notBefore, deferred.lastResult))
    if (lookup != null && (lookup.pending || deferred != null)) return Decision.Follow(Watch.Waiting)
    if (now - sentAt < GRACE) return Decision.Follow(Watch.Checking)
    if (kept != null && now < (kept.notBefore ?: kept.at) + RESEND_WINDOW) return Decision.Resend(listOf(kept))
    return Decision.Follow(Watch.Lost)
}

/**
 * What to make of a submission, from the node's answer about it and the bytes
 * the app kept; for a batch that pays for an operation, also from the answer
 * about the operation sent alone (`twin`): whichever goes in settles it. A
 * transaction settles once [SETTLE_DEPTH] blocks follow the one that included
 * it, at chain height `tip`, and so does one that failed in a block. One no
 * block has, whose signed action has expired ([expired], with the chain's time
 * `chainTime`), is over as well, even while a node still holds it: the
 * operation carries that expiry alone and in its batch.
 */
fun decide(
    tx: SubmittedTx,
    lookup: TransactionLookup?,
    kept: OutboxEntry?,
    now: Long,
    tip: Long,
    twin: Twin? = null,
    statusWords: (String) -> String,
    chainTime: Long? = null,
): Decision {
    val own = decideOne(tx.at, lookup, kept, now, statusWords)
    val other = twin?.let { decideOne(tx.at, it.lookup, it.kept, now, statusWords) }
    val included = listOfNotNull(own, other).filterIsInstance<Decision.Included>().firstOrNull()
    if (included != null) return if (tip >= included.height + SETTLE_DEPTH) included else Decision.Follow(Watch.Confirming(included.height))
    val gone = expired(tx.expires, now, chainTime)
    // The batch failed, but the operation alone may still go in, until it expires.
    if (own is Decision.Settle && other != null && other !is Decision.Settle && !gone) return other
    if (own is Decision.Resend) return if (gone) Decision.Expired else Decision.Resend(own.entries + ((other as? Decision.Resend)?.entries ?: emptyList()))
    // A failure in a block settles as deep as an inclusion does: a reorganization can take that block out too.
    if (own is Decision.Failed && tip < own.height + SETTLE_DEPTH) return Decision.Follow(Watch.Failed(own.reason, own.height))
    // In no block, and it can no longer get into one: waiting, lost or held, it is over.
    if (own is Decision.Follow && gone) return Decision.Expired
    return own
}

/** Why an expired submission is over, as its problem. */
const val EXPIRED_WORDS = "no block included it before it expired: nothing was paid or changed"

/** Payments from one account in one coin that no block has included yet: how many, and how much. */
data class OnTheWay(val count: Int, val amount: BigInteger)

/** Whether no block has a submission yet: one in a block already shows in what the chain says. */
private fun PendingItem.onItsWay() = tx.state == TxState.Pending && watch !is Watch.Failed && watch !is Watch.Confirming

fun onTheWay(items: List<PendingItem>, coinHex: String, accountHex: String): OnTheWay {
    var count = 0
    var amount = BigInteger.ZERO
    for ((tx, watch) in items) {
        // In a block already, it shows in the balance.
        if (watch is Watch.Failed || watch is Watch.Confirming || tx.coin != coinHex || tx.account != accountHex) continue
        count++
        amount += tx.amount?.toBigIntegerOrNull() ?: BigInteger.ZERO
    }
    return OnTheWay(count, amount)
}

/**
 * The payment still on its way that a new one would repeat: in the same coin,
 * from the same account, to the same account, of the same amount. Both can
 * go through, so the person is asked before the second is signed.
 */
fun samePayment(items: List<PendingItem>, coinHex: String, accountHex: String, to: String, sends: BigInteger): SubmittedTx? =
    items.firstOrNull { it.onItsWay() && it.tx.coin == coinHex && it.tx.account == accountHex && it.tx.to == to && it.tx.sends == sends.toString() }?.tx

/** A program's call as a submission notes it (`call`): the program, the acting account and the operation's hash (TCR-1), hex. */
fun callKey(program: ByteArray, account: ByteArray, template: ByteArray, values: ByteArray): String =
    "${program.hex()}:${account.hex()}:${Tcr.operationHash(template, values).hex()}"

/** The call still on its way that a new one would repeat: the same operation of the same program, from the same account. */
fun sameCall(items: List<PendingItem>, call: String): SubmittedTx? = items.firstOrNull { it.onItsWay() && it.tx.call == call }?.tx
