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

    /** No block took it before what it signed expired, and none can now ([clearsAt]): nothing was paid or changed. */
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
 * it, at chain height `tip`, and so does one that failed in a block.
 */
fun decide(
    tx: SubmittedTx,
    lookup: TransactionLookup?,
    kept: OutboxEntry?,
    now: Long,
    tip: Long,
    twin: Twin? = null,
    statusWords: (String) -> String,
): Decision {
    val own = decideOne(tx.at, lookup, kept, now, statusWords)
    val other = twin?.let { decideOne(tx.at, it.lookup, it.kept, now, statusWords) }
    val included = listOfNotNull(own, other).filterIsInstance<Decision.Included>().firstOrNull()
    if (included != null) return if (tip >= included.height + SETTLE_DEPTH) included else Decision.Follow(Watch.Confirming(included.height))
    // Cleared ([clearsAt]): no block can take it any more, though a node may still hold it. The operation
    // carries that expiry alone and in its batch.
    val cleared = tx.expires?.let { now > it + LATE } == true
    // The batch failed, but the operation alone may still go in, until it is cleared.
    if (own is Decision.Settle && other != null && other !is Decision.Settle) return if (cleared) Decision.Expired else other
    if (cleared && (own is Decision.Resend || own is Decision.Follow)) return Decision.Expired
    if (own is Decision.Resend) return Decision.Resend(own.entries + ((other as? Decision.Resend)?.entries ?: emptyList()))
    // A failure in a block settles as deep as an inclusion does: a reorganization can take that block out too.
    if (own is Decision.Failed && tip < own.height + SETTLE_DEPTH) return Decision.Follow(Watch.Failed(own.reason, own.height))
    return own
}

/** Payments from one account in one coin that no block has included yet: how many, and how much. */
data class OnTheWay(val count: Int, val amount: BigInteger)

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

// ---- Waiting, stuck and cleared ------------------------------------------------------
//
// Every action of an account carries the next nonce, and a block takes them in
// order, so one that never goes in keeps the account's later ones waiting
// behind it until they have all expired. Anything signed meanwhile queues
// behind them and moves that time on, so the app asks people to wait instead.
// It never refuses.

/** How long past its expiry a signed action may still get into a block: a block's time may trail the clock. */
const val LATE = 3_600L

/** How long a submission waits for a block before it looks stuck: blocks come about once a minute. */
const val STUCK_AFTER = 600L

/** Why a submission that expired is over, as its problem. */
const val EXPIRED_WORDS = "no block included it before it expired: nothing was paid or changed"

/**
 * Whether the transaction kept as `id` is a program's call (TCR-1), alone or in
 * the batch that pays for it. The app marks the call's own bytes when it sends
 * one (Store.markProgramCall), and a batch names the call sent alone as its twin.
 */
fun programCallKept(outbox: Map<String, OutboxEntry>, id: String): Boolean {
    val kept = outbox[id] ?: return false
    return kept.unpaid != null || kept.twin?.let { outbox[it]?.unpaid } != null
}

/**
 * Until when a submission no block has could still get into one, as far as
 * the app can tell: what it signed says ([SubmittedTx.expires]). One of an
 * earlier version does not say: a program's call was signed for an hour then
 * too ([Chain.CALL_LIFE]), a payment until the end of the day after, both from
 * no later than when the submission was noted. Null for what carries no
 * expiry, such as an identity's change.
 */
fun couldGoInUntil(tx: SubmittedTx, outbox: Map<String, OutboxEntry>): Long? = when {
    tx.expires != null -> tx.expires
    programCallKept(outbox, tx.id) -> tx.at + Chain.CALL_LIFE
    tx.coin != null -> (tx.at / 86_400 + 2) * 86_400
    else -> null
}

/**
 * When a submission is cleared: past its expiry, and as long again as a
 * block's time may trail the clock, no block can take it any more. The app
 * then settles it as not included ([decide]), and the next action no longer
 * queues behind it (Chain.nextNonce).
 */
fun clearsAt(tx: SubmittedTx, outbox: Map<String, OutboxEntry>): Long? = couldGoInUntil(tx, outbox)?.plus(LATE)

/** Whether no block has a submission yet, nor a node holds it for a later time: one in a block already shows in what the chain says. */
private fun PendingItem.onItsWay() =
    tx.state == TxState.Pending && watch !is Watch.Failed && watch !is Watch.Confirming && watch !is Watch.Scheduled

/** The queue of the account `accountHex` in the coin `coinHex`: its actions go in one after the other. */
fun coinQueue(coinHex: String, accountHex: String) = "$coinHex:$accountHex"

/** The queue of the account's calls to a program (TCR-1), which go in one after the other too. */
fun callQueue(program: ByteArray, account: ByteArray) = "call:${program.hex()}:${account.hex()}"

/** The queues a submission waits in: its account's in its coin, and for a program's call that account's calls to the program. */
private fun queuesOf(tx: SubmittedTx): List<String> = listOfNotNull(
    if (tx.coin != null && tx.account != null) coinQueue(tx.coin, tx.account) else null,
    tx.call?.substringBeforeLast(':')?.let { "call:$it" },
)

/** A queue that looks stuck: since when something in it waits (seconds), and when everything waiting in it is cleared. */
data class Stuck(val since: Long, val clearsAt: Long)

/**
 * The queues that look stuck at `now`, by [coinQueue] and [callQueue]:
 * something in them has waited [STUCK_AFTER] without a block. A batch whose
 * fee payment comes from one of this device's accounts waits in that
 * account's queue too ([OutboxEntry.feeFrom]), until its fee payment expires.
 * Each queue is cleared once everything waiting in it is ([clearsAt]): then
 * the next action takes the chain's nonce again, and nothing of the old ones
 * can go in after it. What is signed into it before then waits behind the
 * rest, and moves that time on.
 */
fun stuckQueues(items: List<PendingItem>, outbox: Map<String, OutboxEntry>, now: Long): Map<String, Stuck> {
    val since = HashMap<String, Long>()
    val clears = HashMap<String, Long>()
    for (item in items) {
        val tx = item.tx
        val kept = outbox[tx.id]
        if (!item.onItsWay() || kept?.notBefore?.let { it > now } == true) continue
        // Each queue it waits in, and when it is cleared there.
        val waits = buildList {
            clearsAt(tx, outbox)?.let { cleared -> queuesOf(tx).forEach { add(it to cleared) } }
            val feeFrom = kept?.feeFrom
            val feeExpires = kept?.feeExpires
            if (feeFrom != null && feeExpires != null) add(feeFrom to feeExpires + LATE)
        }
        for ((q, cleared) in waits) {
            if (now > cleared) continue
            clears[q] = maxOf(clears[q] ?: 0L, cleared)
            if (now - tx.at >= STUCK_AFTER) since[q] = minOf(since[q] ?: tx.at, tx.at)
        }
    }
    return since.mapValues { (q, s) -> Stuck(s, clears.getValue(q)) }
}

/** The latest-clearing of `queues` that looks stuck in `stuck` ([stuckQueues]), or null when none does. */
fun stuckIn(stuck: Map<String, Stuck>, queues: List<String?>): Stuck? =
    queues.filterNotNull().mapNotNull { stuck[it] }.maxByOrNull { it.clearsAt }

/** How long since `since`, at `now`, in words. */
private fun waited(since: Long, now: Long): String {
    val minutes = maxOf(1L, (now - since) / 60)
    return if (minutes < 120) "$minutes minutes" else "${minutes / 60} hours"
}

/** The question before something is signed into a stuck queue (`stuck`, from [stuckQueues]), at `now`; `time` writes a time. */
fun waitWords(stuck: Stuck, now: Long, time: (Long) -> String): String =
    "A transaction from this account has waited ${waited(stuck.since, now)} for a block. Anything you sign now waits behind it, " +
        "and makes the wait longer. Everything waiting should be cleared by about ${time(stuck.clearsAt)}."

/** The note on the wallet while something looks stuck: `clearsAt` is the latest of the stuck queues'. */
fun stuckWords(clearsAt: Long, time: (Long) -> String): String =
    "Some transactions look stuck. Wait until about ${time(clearsAt)}, when they are cleared. Signing more before then makes the wait longer."

// ---- The same payment or call again ---------------------------------------------

/** A program's call as a submission notes it ([SubmittedTx.call]): the program, the acting account and the operation's hash (TCR-1), hex. */
fun callKey(program: ByteArray, account: ByteArray, template: ByteArray, values: ByteArray): String =
    "${program.hex()}:${account.hex()}:${Tcr.operationHash(template, values).hex()}"

/**
 * The submission a new payment or call would repeat (`same` picks it out),
 * the newest, while it still waits for a block at `now` (`items`, as Pending
 * follows them) and is not cleared ([clearsAt]). Null when there is none: any
 * other payment or call goes without a question.
 */
fun repeatOf(items: List<PendingItem>, outbox: Map<String, OutboxEntry>, now: Long, same: (SubmittedTx) -> Boolean): SubmittedTx? =
    items.filter { it.onItsWay() && clearsAt(it.tx, outbox)?.let { c -> now <= c } != false }.map { it.tx }.filter(same).maxByOrNull { it.at }

/**
 * The question before the payment `before` ([repeatOf]) is sent again. The
 * second queues behind the first, so both can go through.
 */
fun sendAgainWords(before: SubmittedTx, time: (Long) -> String): String =
    "You sent this at ${time(before.at)}, and it is still waiting for a block. If you send it again, it could be paid twice."

/** The question before the call `before` ([repeatOf]) is approved again: the second queues behind the first, so it can run twice. */
fun approveAgainWords(before: SubmittedTx, time: (Long) -> String): String =
    "You approved this at ${time(before.at)}, and it is still waiting for a block. Approved again, it could run twice."
