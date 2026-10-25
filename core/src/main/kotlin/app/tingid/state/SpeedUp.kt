// Speeding up what waits for a block unpaid: a registry operation, a coin
// action or cancel that carries no fee, or the run of a scheduled action. The
// same call goes again in a batch, followed by a payment bound to it from an
// instant account (chain/Fees, Chain.speedUp). The payment is signed when the
// person asks and never ahead, as it takes the paying account's next nonce.
// The call alone stays where it is: whichever of the two a block takes
// settles the submission (state/Pending). Pure, for tests.
package app.tingid.state

import app.tingid.chain.CallTx
import app.tingid.chain.Canonical
import app.tingid.chain.Coin
import app.tingid.chain.decodeCall
import app.tingid.chain.equal
import app.tingid.chain.unhex

/**
 * The call a kept transaction makes, when a speed-up can pay for it: a call
 * that pays no fee itself. Not a batch, which may pay already, nor a
 * deployment, which no batch can make.
 */
fun unpaidCall(c: Canonical, raw: ByteArray): CallTx? {
    val call = decodeCall(raw) ?: return null
    if (equal(call.target, c.batch)) return null
    // A registry operation pays nothing; anything else this app calls is a coin.
    if (equal(call.target, c.registry)) return call
    return call.takeIf { Coin.feeOf(it.input)?.signum() == 0 }
}

/**
 * Whether a submission can be sped up: it waits in a node's pool, its bytes
 * are kept, no batch pays for it yet and it pays nothing itself. `c`: the
 * chain's contracts, null while the chain is not known.
 */
fun canSpeedUp(tx: SubmittedTx, watch: Watch, kept: OutboxEntry?, c: Canonical?): Boolean =
    tx.state == TxState.Pending && watch == Watch.Waiting && kept != null && kept.twin == null && c != null &&
        unpaidCall(c, unhex(kept.raw)) != null

/** The scheduled actions among `entries` whose run a batch this device sent pays for, by [scheduledActionKey]. */
fun runsPaidFor(chainId: ByteArray, entries: List<ScheduledAction>, outbox: Map<String, OutboxEntry>): Set<String> {
    val paid = outbox.values.mapNotNullTo(HashSet()) { it.twin }
    return entries.filter { runIdOf(chainId, it) in paid }.mapTo(HashSet(), ::scheduledActionKey)
}
