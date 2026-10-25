// What became of the transactions this device submitted (`submitted` in the
// store, with their bytes in `outbox`): waiting in the node's pool, held for a
// later time, sent again because the node lost it, failed, or included. The
// app asks the node about each unsettled one on every block and every 20
// seconds. A lost one goes out again with the same bytes, which is safe: its
// nonce lets the chain apply it at most once.
package app.tingid.state

import app.tingid.chain.BatchCall
import app.tingid.chain.ChainApi
import app.tingid.chain.Contracts
import app.tingid.chain.MaybeSent
import app.tingid.chain.unhex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class Pending(
    private val chain: Chain,
    private val scope: CoroutineScope,
    /** Why a transaction ran without succeeding, in words, from its receipt's status name (text/Describe.kt). */
    private val statusWords: (String) -> String,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val pollMs: Long = POLL_MS,
    /** Where a speed-up's payment is signed: the vault blocks while it opens a key. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val store: Store get() = chain.store

    /** Submissions being looked at right now, so two screens do not send one twice. */
    private val busy: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** What the last rounds found of each submission. */
    private val watches = MutableStateFlow<Map<String, Watch>>(emptyMap())

    /** Only what was sent to this chain: another chain's nodes would not know it, and sending it there again is no use. */
    private fun here(t: SubmittedTx, userId: String?, chainHex: String?) = t.userId == userId && (t.chain == null || t.chain == chainHex)

    private suspend fun check(node: ChainApi, tx: SubmittedTx, tip: Long): Watch? {
        if (!busy.add(tx.id)) return null
        try {
            val kept = store.data.value.outbox[tx.id]
            val lookup = node.transaction(tx.id)
            val twin = kept?.twin?.let { Twin(node.transaction(it), store.data.value.outbox[it]) }
            return when (val d = decide(tx, lookup, kept, now(), tip, twin, statusWords)) {
                is Decision.Settle -> {
                    // The bytes go first, so a submission never shows settled with them still kept.
                    store.forgetOutbox(tx.id)
                    kept?.twin?.let { store.forgetOutbox(it) }
                    store.updateSubmitted(tx.id) {
                        when (d) {
                            is Decision.Included -> it.copy(state = TxState.Included, height = d.height, problem = null)
                            is Decision.Failed -> it.copy(state = TxState.Failed, height = d.height, problem = d.reason)
                        }
                    }
                    null
                }
                is Decision.Resend -> {
                    val sent = coroutineScope {
                        d.entries.map { e -> async { resend(node, e) } }.awaitAll()
                    }
                    if (sent.any { it == null }) return Watch.Resent
                    store.updateSubmitted(tx.id) {
                        it.copy(state = TxState.Failed, problem = "the node lost it and refused it when sent again: ${sent.firstNotNullOf { e -> e }.message}")
                    }
                    null
                }
                is Decision.Follow -> d.watch
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // The node did not answer; the next round tries again.
            return null
        } finally {
            busy.remove(tx.id)
        }
    }

    /**
     * Sends kept bytes again; null when the node took them, else why not. An
     * answer that was lost is no refusal: the bytes stay kept, and the next
     * round looks again.
     */
    private suspend fun resend(node: ChainApi, e: OutboxEntry): Exception? = try {
        node.submit(unhex(e.raw), e.notBefore)
        null
    } catch (err: MaybeSent) {
        null
    } catch (err: Exception) {
        if (err is CancellationException) throw err
        err
    }

    /** A submission marked failed because the node refused it may still have gone in: it counts as done once a block has it. */
    private suspend fun recheck(node: ChainApi, tx: SubmittedTx, tip: Long) {
        val kept = store.data.value.outbox[tx.id]
        val lookup = lookupOrNull(node, tx.id)
        val twin = kept?.twin?.let { Twin(lookupOrNull(node, it), store.data.value.outbox[it]) }
        val d = decide(tx, lookup, kept, now(), tip, twin, statusWords)
        if (d is Decision.Included) store.updateSubmitted(tx.id) { it.copy(state = TxState.Included, height = d.height, problem = null) }
    }

    private suspend fun lookupOrNull(node: ChainApi, id: String) = try {
        node.transaction(id)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        null
    }

    /** What a round of checks depends on: a change starts the rounds again. */
    private data class Round(val node: ChainApi, val userId: String?, val chainHex: String?, val pendingIds: String, val height: Long?)

    /** One round: failures that may have gone in after all, then every pending submission. */
    private suspend fun round(r: Round) = coroutineScope {
        val mine = store.data.value.submitted.filter { here(it, r.userId, r.chainHex) }
        val tip = r.height ?: 0
        mine.filter { it.state == TxState.Failed && it.height == null }.map { async { recheck(r.node, it, tip) } }.awaitAll()
        val found = mine.filter { it.state == TxState.Pending }.map { async { it.id to check(r.node, it, tip) } }.awaitAll()
        watches.update { w -> w + found.mapNotNull { (id, watch) -> watch?.let { id to it } } }
    }

    /**
     * The active user's unsettled submissions and failures to show, newest
     * first, kept up to date against the node while collected.
     */
    fun items(): Flow<List<PendingItem>> = channelFlow {
        val open = combine(store.data, chain.caches.current) { d, cache ->
            val chainHex = cache?.chain
            Triple(d, chainHex, d.submitted.filter { here(it, d.activeUserId, chainHex) && (it.state == TxState.Pending || it.state == TxState.Failed) })
        }
        launch {
            combine(open, chain.pool, chain.info) { (d, chainHex, list), node, info ->
                val pendingIds = list.filter { it.state == TxState.Pending || it.height == null }.joinToString(",") { it.id }
                Round(node, d.activeUserId, chainHex, pendingIds, info?.status?.height)
            }.distinctUntilChanged().collectLatest { r ->
                if (r.pendingIds.isEmpty()) return@collectLatest
                while (true) {
                    // A round's findings are kept even when it ends after the screen changed:
                    // they are about the submissions, which do not change with it.
                    scope.launch { round(r) }
                    delay(pollMs)
                }
            }
        }
        // Bytes nothing follows any more are dropped after a while.
        launch {
            store.data.map { it.outbox }.distinctUntilChanged().collect { outbox ->
                val open = store.data.value.submitted.filter { it.state == TxState.Pending || it.state == TxState.Failed }.map { it.id }
                // A batch that pays for an operation follows the operation sent alone too.
                val followed = (open + open.mapNotNull { outbox[it]?.twin }).toSet()
                val old = now() - OUTBOX_AGE
                for ((id, entry) in outbox) if (id !in followed && entry.at < old) store.forgetOutbox(id)
            }
        }
        combine(open, watches) { (d, chainHex, list), w ->
            val c = chainHex?.let { Contracts.canonical(unhex(it)) }
            list.map { tx ->
                val watch = if (tx.state == TxState.Failed) Watch.Failed(tx.problem ?: "it failed", tx.height) else w[tx.id] ?: Watch.Checking
                PendingItem(
                    tx = tx,
                    watch = watch,
                    canRetry = tx.id in d.outbox,
                    canSpeedUp = canSpeedUp(tx, watch, d.outbox[tx.id], c),
                )
            }
        }.distinctUntilChanged().collect { send(it) }
    }

    /** Sends a failed or lost submission again, with the same bytes; null when it went, else why not. */
    suspend fun retry(id: String): String? {
        val kept = store.data.value.outbox[id] ?: return "the app no longer has this transaction"
        val refused = resend(chain.node, kept)
        if (refused == null) {
            store.updateSubmitted(id) { it.copy(state = TxState.Pending, problem = null, height = null) }
            watches.update { it + (id to Watch.Resent) }
            return null
        }
        store.updateSubmitted(id) { it.copy(state = TxState.Failed, problem = "the node refused it: ${refused.message}") }
        return refused.message ?: "the node refused it"
    }

    /**
     * Pays a miner for a waiting submission that pays nothing itself
     * ([PendingItem.canSpeedUp]): its call goes again in a batch with a
     * payment from `fee`, signed now (Chain.speedUp), and the submission
     * follows the batch from then on. Null when it went, else why not. It
     * finishes even when the screen goes, as the batch may be sent already.
     */
    suspend fun speedUp(id: String, fee: FeeSource): String? = scope.async(io) {
        val d = store.data.value
        val tx = d.submitted.firstOrNull { it.id == id } ?: return@async "the app no longer follows this transaction"
        val kept = d.outbox[id] ?: return@async "the app no longer has this transaction"
        val info = chain.info.value ?: return@async "no node answers"
        if (tx.chain != null && tx.chain != info.chainHex) return@async "it was sent to another chain"
        // As canSpeedUp asks, and again from the node: a payment signed for what is in a block already holds its nonce for nothing.
        if (tx.state != TxState.Pending || kept.twin != null) return@async "it is in a block, or paid for, already"
        val included = try {
            chain.node.transaction(id)?.occurrences?.isNotEmpty() == true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return@async e.message ?: "no node answers"
        }
        if (included) return@async "it is in a block already"
        val call = unpaidCall(info.canonical, unhex(kept.raw), kept.unpaid) ?: return@async "it pays its own fee"
        try {
            val r = sent { chain.speedUp(info.canonical, info.status.chainId, BatchCall(call.target, call.input), fee.resolve(), call.budget) }
            store.followPaid(id, tx.copy(id = r.txId, at = now(), summary = withFee(tx.summary, fee)))
            null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            e.message ?: "it failed"
        }
    }.await()

    /** Puts a submission away: the app stops following it. */
    fun dismiss(id: String) {
        store.updateSubmitted(id) { it.copy(state = TxState.Dismissed) }
        store.forgetOutbox(id)
    }

    companion object {
        const val POLL_MS = 20_000L

        /** Bytes of transactions no submission follows, such as one never shown in the list, are dropped after this. */
        const val OUTBOX_AGE = 7 * 86_400L
    }
}
