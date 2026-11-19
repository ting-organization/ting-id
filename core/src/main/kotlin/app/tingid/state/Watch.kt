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
    // The batch failed, but the operation alone may still go in.
    if (own is Decision.Settle && other != null && other !is Decision.Settle) return other
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

// ---- Forgetting what waits (Settings) --------------------------------------------
//
// Every action of an account carries the next nonce, and a block takes them in
// order, so one that never goes in keeps the account's later ones waiting
// behind it. Forgetting lets the next one take the chain's nonce again; it
// cancels nothing.

/** How long past its expiry a signed action may still get into a block: a block's time may trail the clock. */
private const val LATE = 3_600L

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
 * the app can tell: a program's call is signed for an hour ([Chain.CALL_LIFE]),
 * a payment until the end of the day after ([Chain.signedExpiry]), both from
 * no later than when the submission was noted. Null for what is neither, such
 * as an identity's change.
 */
fun couldGoInUntil(tx: SubmittedTx, outbox: Map<String, OutboxEntry>): Long? = when {
    programCallKept(outbox, tx.id) -> tx.at + Chain.CALL_LIFE
    tx.coin != null -> (tx.at / 86_400 + 2) * 86_400
    else -> null
}

/**
 * What [Store.forgetWaiting] forgets for `userId` on `chain`: the payments and
 * program calls no block has yet. Not what the node holds for a later time,
 * such as the run of a scheduled payment, and not what is neither, such as an
 * identity's change.
 */
fun waitingToForget(d: AppData, userId: String, chain: String?, now: Long): List<SubmittedTx> = d.submitted.filter {
    it.userId == userId && (it.chain == null || it.chain == chain) && it.state == TxState.Pending &&
        couldGoInUntil(it, d.outbox) != null && d.outbox[it.id]?.notBefore?.let { held -> held > now } != true
}

/**
 * The question before `items` ([waitingToForget]) are forgotten at `now`: that
 * this cancels nothing, until when one of them could still go through, and
 * that the same payment sent again before then could be paid twice. Once a
 * block's time can no longer trail the clock that far ([LATE]), none can.
 * `time` writes a time.
 */
fun forgetWords(items: List<SubmittedTx>, outbox: Map<String, OutboxEntry>, now: Long, time: (Long) -> String): String {
    val many = items.size > 1
    val them = if (many) "them" else "it"
    val last = (items.mapNotNull { couldGoInUntil(it, outbox) }.maxOrNull() ?: now) + LATE
    val what = "Ting ID stops following ${if (many) "the ${items.size} transactions" else "the transaction"} that no block has taken yet, " +
        "and your next one no longer waits behind $them."
    return if (now > last) {
        "$what ${if (many) "They have" else "It has"} expired, so nothing of $them can still go through."
    } else {
        "$what This cancels nothing: ${if (many) "any of them" else "it"} may still go through until about ${time(last)}. " +
            "If you send the same payment again before then, it could be paid twice."
    }
}

// ---- The same payment or call again ---------------------------------------------

/** A program's call as a submission notes it ([SubmittedTx.call]): the program, the acting account and the operation's hash (TCR-1), hex. */
fun callKey(program: ByteArray, account: ByteArray, template: ByteArray, values: ByteArray): String =
    "${program.hex()}:${account.hex()}:${Tcr.operationHash(template, values).hex()}"

/** Whether no block has a submission yet: one in a block already shows in what the chain says. */
private fun PendingItem.onItsWay() = tx.state == TxState.Pending && watch !is Watch.Failed && watch !is Watch.Confirming

/**
 * The submission of `userId` on `chain` that a new payment or call would
 * repeat (`same` picks it out), the newest, while it could still get into a
 * block at `now`: on its way (`items`, as Pending follows them), or forgotten
 * in Settings ([Store.forgetWaiting]) with its time not yet run out, and as
 * long again as a block's time may trail the clock ([LATE]). Null when there
 * is none: any other payment or call goes without a question.
 */
fun repeatOf(items: List<PendingItem>, d: AppData, userId: String, chain: String?, now: Long, same: (SubmittedTx) -> Boolean): SubmittedTx? {
    val waiting = items.filter { it.onItsWay() && it.tx.userId == userId }.map { it.tx }
        .filter { tx -> couldGoInUntil(tx, d.outbox)?.let { now <= it + LATE } != false }
    val forgotten = d.submitted.filter {
        it.userId == userId && (it.chain == null || it.chain == chain) && it.state == TxState.Dismissed &&
            it.forgottenUntil?.let { until -> now <= until + LATE } == true
    }
    return (waiting + forgotten).filter(same).maxByOrNull { it.at }
}

/** Where `before` ([repeatOf]) stands, for the question before it is repeated: `time` writes a time. */
private fun standing(before: SubmittedTx, verb: String, time: (Long) -> String): String {
    val until = before.forgottenUntil
    return if (before.state == TxState.Dismissed && until != null) {
        "You $verb this at ${time(before.at)} and forgot it in Settings, but it could still go through until about ${time(until + LATE)}."
    } else {
        "You $verb this at ${time(before.at)}, and it is still waiting for a block."
    }
}

/**
 * The question before the payment `before` ([repeatOf]) is sent again. The
 * second queues behind the first, so both can go through, and a forgotten
 * one may go through as well.
 */
fun sendAgainWords(before: SubmittedTx, time: (Long) -> String): String =
    "${standing(before, "sent", time)} If you send it again, it could be paid twice."

/** The question before the call `before` ([repeatOf]) is approved again: the second queues behind the first, so it can run twice. */
fun approveAgainWords(before: SubmittedTx, time: (Long) -> String): String =
    "${standing(before, "approved", time)} Approved again, it could run twice."
