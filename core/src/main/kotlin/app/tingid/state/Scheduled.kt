// Acting on the user's scheduled actions, wherever a screen lists them: cancel
// (one signature per account and coin, any number of actions), run one that is
// due, or hand its run to the node for when it falls due.
package app.tingid.state

import app.tingid.chain.Coin
import app.tingid.chain.nowSeconds
import app.tingid.text.describeEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Scheduled actions one cancel takes at most, to stay within a transaction's budget. */
private const val ACTIONS_PER_CANCEL = 16

/** A scheduled action of one of the user's accounts. */
class ScheduledAction(val coin: CoinInfo, val view: AccountView, val sequence: Long, val record: Coin.Scheduled)

fun scheduledActionKey(e: ScheduledAction): String = "${e.coin.hex}-${e.view.account.slot}-${e.sequence}"

/** Every pending scheduled action across the given coins, soonest first. */
fun scheduledOf(coins: List<CoinInfo>, views: Map<String, List<AccountView>>): List<ScheduledAction> {
    val out = ArrayList<ScheduledAction>()
    for (c in coins) for (view in views[c.hex] ?: emptyList()) for (s in view.scheduled) out.add(ScheduledAction(c, view, s.sequence, s.record))
    return out.sortedBy { it.record.executeAt }
}

/** Which run of the chain a scheduled action's is (Chain.runStatuses). */
fun runRefOf(e: ScheduledAction): RunRef = RunRef(e.coin.id, e.view.account.account, e.sequence)

/**
 * Cancel, run and queue for scheduled actions. `busy` names the action in
 * progress (`cancel-<key>`, `run-<key>`, `queue-<key>`, or the label given to
 * a cancel); `opened` is told the transaction to show, from `scope`. The
 * workspace and the fee are taken as they are when an action starts. What was
 * begun finishes in `scope` even when the screen goes, as the transaction may
 * be sent already.
 */
class ScheduledActions(
    private val chain: Chain,
    private val scope: CoroutineScope,
    private val workspace: () -> WorkspaceState,
    private val fee: () -> FeeSource,
    private val opened: (txId: String) -> Unit,
    /** Where keys sign: the vault blocks while it opens one. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = ::nowSeconds,
) {
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // One action at a time: a second button pressed meanwhile does nothing.
    private val running = AtomicBoolean(false)

    private fun attempt(label: String, step: suspend () -> String?) {
        if (!running.compareAndSet(false, true)) return
        _busy.value = label
        _error.value = null
        scope.launch {
            try {
                val txId = withContext(io) { step() }
                if (txId != null) opened(txId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _error.value = e.message ?: "it failed"
            } finally {
                running.set(false)
                _busy.value = null
            }
        }
    }

    private fun submitted(txId: String, summary: String, userId: String) =
        chain.store.addSubmitted(SubmittedTx(id = txId, at = now(), summary = summary, userId = userId))

    /** Cancels `entries`: one cancel per account and coin, each paying the fee. */
    fun cancel(entries: List<ScheduledAction>, label: String) = attempt(label) {
        val w = workspace()
        val user = w.user
        val info = w.info
        if (user == null || info == null || entries.isEmpty()) return@attempt null
        val fee = fee()
        val groups = LinkedHashMap<String, MutableList<ScheduledAction>>()
        for (e in entries) groups.getOrPut("${e.coin.hex}:${e.view.account.slot}") { ArrayList() }.add(e)
        var last: String? = null
        for (all in groups.values) {
            val c = all[0].coin
            val view = all[0].view
            val signer = chain.resolveSigner(user, view.account.slot, info.canonical, w.keys, w.identity)
            // A long list goes in several cancels, each within one transaction's budget.
            for (group in all.chunked(ACTIONS_PER_CANCEL)) {
                val payer = fee.resolve()
                val r = sent { chain.submitCoinCancel(info.status.chainId, c.id, signer, group.map { it.sequence }, payer).first }
                val summary = if (group.size == 1) "Cancel: ${describeEffect(group[0].record.effect, c, w.names)}"
                else "Cancel ${group.size} scheduled actions from ${view.account.label} (${c.config.symbol})"
                submitted(r.txId, withFee(summary, fee), user.id)
                last = r.txId
            }
        }
        last
    }

    /** Submits the run of `e`, which is due, now. */
    fun run(e: ScheduledAction) = attempt("run-${scheduledActionKey(e)}") {
        val w = workspace()
        val r = sent { chain.submitRun(e.coin.id, e.view.account.account, e.sequence) }
        w.user?.let { submitted(r.txId, "Run now: ${describeEffect(e.record.effect, e.coin, w.names)}", it.id) }
        r.txId
    }

    /** Hands the run of `e` to the node, which runs it when it falls due. */
    fun queue(e: ScheduledAction) = attempt("queue-${scheduledActionKey(e)}") {
        val w = workspace()
        val r = sent { chain.queueRun(e.coin.id, e.view.account.account, e.sequence, e.record.executeAt) }
        w.user?.let { submitted(r.txId, "Run when due: ${describeEffect(e.record.effect, e.coin, w.names)}", it.id) }
        r.txId
    }
}
