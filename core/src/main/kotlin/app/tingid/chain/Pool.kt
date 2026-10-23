// Several nodes of one chain, used as one (Settings → Nodes). Reads go to the
// node furthest ahead and move on to the next when one does not answer;
// transactions go to every node. Answers are checked: each storage value by
// its proof against the state root of the block the node names, that root by
// the block's header, which must hash to the block's id, and the block itself
// by the other nodes, which must have it on their chain too. Records that never
// change, such as a coin's configuration, are read from every node, which must
// agree. A node that fails a check is not used again until the user refreshes.
//
// The pool's own state is only touched on one thread at a time (a dispatcher
// of parallelism 1); the nodes are asked on the IO threads meanwhile.
package app.tingid.chain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred as Async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigInteger

/** Blocks another node must be past a block's height, on another branch, before the nodes count as following different chains. */
const val CONFIRMATIONS = 3

/** How far behind the newest block an answer may come from: an older one could hide what happened since. */
const val STALE_BLOCKS = 6

/** A newest block older than this (seconds) is worth a word: the nodes may be behind, or mining stopped. */
const val OLD_TIP = 3 * 3600L

/** Checked blocks remembered. */
private const val REMEMBER = 64

enum class NodeState {
    /** Not asked yet. */
    Unknown,

    /** Answers, on the chain the pool follows. */
    Ok,

    /** Does not answer. */
    Down,

    /** Lacks a call the app needs. */
    Outdated,

    /** Answers from another chain than the other nodes. */
    OtherChain,

    /** Gave an answer that failed a check. */
    Distrusted,

    /** Plain http to a node elsewhere: anyone on the way could change its answers. */
    Insecure,
}

data class NodeHealth(
    val url: String,
    val state: NodeState,
    /** Its last status. */
    val status: NodeStatus? = null,
    /** What went wrong, in words, when it is not used. */
    val problem: String? = null,
    /** The node reads go to first. */
    val best: Boolean = false,
)

/** A node address as typed, cleaned up; null when it is not one, or is plain http to a node elsewhere. */
fun nodeAddress(text: String): String? {
    val url = trimWhiteSpace(text).trimEnd('/')
    val m = Regex("^(https?)://([^$WHITE_SPACE/?#@]+)(/[^$WHITE_SPACE?#]*)?$", RegexOption.IGNORE_CASE).find(url) ?: return null
    return if (m.groupValues[1].lowercase() == "https" || nearby(m.groupValues[2])) url else null
}

/** Whether an address is plain http to a node elsewhere, which is refused. */
fun remoteHttp(text: String): Boolean {
    val m = Regex("^http://([^$WHITE_SPACE/?#@]+)", RegexOption.IGNORE_CASE).find(trimWhiteSpace(text)) ?: return false
    return !nearby(m.groupValues[1])
}

/** A host on this device or the home network (a private IPv4 address), where plain http is allowed. */
private fun nearby(hostPort: String): Boolean {
    val host = hostPort.replace(Regex(":[0-9]+$"), "").lowercase()
    if (host == "localhost") return true
    val m = Regex("^([0-9]{1,3})\\.([0-9]{1,3})\\.([0-9]{1,3})\\.([0-9]{1,3})$").find(host) ?: return false
    val v4 = m.groupValues.drop(1).map { it.toInt() }
    if (v4.any { it > 255 }) return false
    val (a, b) = v4
    return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
}

/** Something about the nodes the user should know, beyond one not answering. */
sealed class PoolNotice {
    object Distrusted : PoolNotice()
    object Split : PoolNotice()
    data class Old(val since: Long) : PoolNotice()
}

/** A node failed a check: the pool stops using it. */
class NodeDistrusted(message: String) : NodeError(message)

private class KnownHeader(val root: String, val height: Long, val timestamp: Long)

private class PendingBlock(val height: Long, val servedBy: String)

/** A map that forgets its oldest entries past [REMEMBER]. */
private fun <K, V> remembering() = object : LinkedHashMap<K, V>() {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > REMEMBER
}

class NodePool(
    val urls: List<String>,
    make: (String) -> NodeClient = { NodeClient(it) },
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) : ChainApi {
    override val url: String = urls.joinToString(" ")

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val confined = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + confined)

    private val nodes: List<NodeClient> = urls.map(make)
    private val health = LinkedHashMap<String, NodeHealth>()
    private var chain: String? = null

    /** Headers checked: block id → its state root (hex), height and time. */
    private val headers = remembering<String, KnownHeader>()

    /** The highest block an answer came from: no later answer may come from far below it. */
    private var seen = 0L

    /** Header reads on their way, by node and block, so reads at one block fetch it once. */
    private val headerReads = HashMap<String, Async<ByteArray?>>()

    /** Blocks answers came from that no other node has confirmed yet, and which node gave them. */
    private val pending = remembering<String, PendingBlock>()
    private val confirmed = remembering<String, Unit>()

    /** Records that never change, as the nodes agreed on them, by record key. */
    private val fixed = HashMap<String, ByteArray>()
    private var checking = false

    private val _health = MutableStateFlow<List<NodeHealth>>(emptyList())

    /** Every node's health, in the user's order; changes when a node's health changes. */
    val healthFlow: StateFlow<List<NodeHealth>> = _health

    private val _notice = MutableStateFlow<PoolNotice?>(null)
    val notice: StateFlow<PoolNotice?> = _notice

    /** Told when a node is found to have given wrong answers, so what came from it is read again. */
    @Volatile
    var onDistrust: (() -> Unit)? = null

    init {
        for (u in urls) {
            val insecure = remoteHttp(u)
            health[u] = NodeHealth(
                url = u,
                state = if (insecure) NodeState.Insecure else NodeState.Unknown,
                problem = if (insecure) "plain http is only for a node on this device or your home network; use its https address" else null,
            )
        }
        publish()
    }

    private fun publish() {
        _health.value = urls.map { health.getValue(it) }
    }

    private fun h(node: NodeClient) = health.getValue(node.url)

    /** Gives distrusted and outdated nodes another chance and forgets the checks, as the user asked to refresh. */
    suspend fun reset() = withContext(confined) {
        for ((u, h) in health) if (h.state == NodeState.Distrusted || h.state == NodeState.Outdated) health[u] = h.copy(state = NodeState.Unknown, problem = null)
        headers.clear()
        seen = 0
        pending.clear()
        confirmed.clear()
        fixed.clear()
        _notice.value = null
        publish()
    }

    private fun set(node: NodeClient, change: (NodeHealth) -> NodeHealth) {
        health[node.url] = change(h(node))
        publish()
    }

    /** Whether a finding sticks until the user refreshes. */
    private fun stuck(node: NodeClient): Boolean = h(node).state.let { it == NodeState.Distrusted || it == NodeState.Outdated }

    /** Notes why a node failed; another may answer instead. */
    private fun fail(node: NodeClient, e: Throwable) {
        if (e is NodeDistrusted || stuck(node)) return
        if (e is NodeOutdated) set(node) { it.copy(state = NodeState.Outdated, problem = e.message, best = false) }
        else set(node) { it.copy(state = NodeState.Down, problem = e.message, best = false) }
    }

    private fun distrust(node: NodeClient, problem: String): NodeDistrusted {
        set(node) { it.copy(state = NodeState.Distrusted, problem = problem, best = false) }
        fixed.clear()
        _notice.value = PoolNotice.Distrusted
        onDistrust?.invoke()
        return NodeDistrusted(problem)
    }

    /**
     * Every node's status. The pool follows the chain most nodes are on (on a
     * tie, the one of the node listed first) and reports the status of its node
     * with the most work.
     */
    override suspend fun status(): NodeStatus = withContext(confined) {
        val up = coroutineScope {
            nodes.filter { h(it).state != NodeState.Insecure }.map { node ->
                async {
                    try {
                        node to node.status()
                    } catch (e: Exception) {
                        fail(node, e)
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        if (up.isEmpty()) {
            throw NodeError(if (nodes.size == 1) h(nodes[0]).problem ?: "the node does not answer" else "no node answers")
        }
        val votes = LinkedHashMap<String, Int>()
        for ((_, s) in up) votes[s.chainId.hex()] = (votes[s.chainId.hex()] ?: 0) + 1
        val most = votes.values.max()
        val chosen = up.first { votes[it.second.chainId.hex()] == most }.second.chainId.hex()
        if (chosen != chain) {
            fixed.clear()
            seen = 0
        }
        chain = chosen
        // Each node's newest block, by its header: it must carry the chain's proof of work and the
        // height the node reports, so that nodes rank by what they show, not by what they say.
        coroutineScope {
            up.filter { (node, s) -> s.chainId.hex() == chosen && !stuck(node) }.map { (node, s) ->
                async {
                    try {
                        checkTip(node, s)
                    } catch (e: Exception) {
                        fail(node, e)
                    }
                }
            }.awaitAll()
        }
        for ((node, s) in up) {
            when {
                s.chainId.hex() != chain -> set(node) { it.copy(state = NodeState.OtherChain, status = s, problem = "it is on another chain than your other nodes", best = false) }
                stuck(node) -> set(node) { it.copy(status = s) }
                else -> set(node) { it.copy(state = NodeState.Ok, status = s, problem = null) }
            }
        }
        val usable = usable()
        if (usable.isEmpty()) throw NodeError(up.firstNotNullOfOrNull { h(it.first).problem } ?: "no node can be used")
        for (node in nodes) health[node.url] = h(node).copy(best = node == usable[0])
        publish()
        scope.launch { recheck() }
        h(usable[0]).status!!
    }

    /** That a node's newest block is what its status says, with the chain's proof of work; a node that fails this is distrusted. */
    private suspend fun checkTip(node: NodeClient, status: NodeStatus) {
        var known = headers[status.tip]
        if (known == null) {
            val bytes = node.header(status.tip) ?: throw NodeError("the node does not give its newest block")
            val header = checkHeader(status.tip, bytes, Networks.powLimitOf(chain))
                ?: throw distrust(node, "its newest block header is not the block’s, or lacks the work the chain needs")
            known = KnownHeader(header.stateRoot.hex(), header.height, header.timestamp)
            headers[status.tip] = known
        }
        if (known.height != status.height) throw distrust(node, "it names another height than its newest block has")
    }

    /** The nodes answering on the chain: the highest checked newest block first, then the most work, then in the user's order. */
    private fun usable(): List<NodeClient> {
        val height = { n: NodeClient -> h(n).status?.height ?: 0L }
        val work = { n: NodeClient -> h(n).status?.work ?: BigInteger.ZERO }
        return nodes.filter { h(it).state == NodeState.Ok }.sortedWith(
            compareByDescending<NodeClient> { height(it) }.thenByDescending { work(it) }.thenBy { nodes.indexOf(it) },
        )
    }

    /** The nodes to ask, in order: the usable ones, then ones not known to fail. */
    private fun order(): List<NodeClient> {
        val usable = usable()
        val maybe = nodes.filter { it !in usable && h(it).state.let { s -> s == NodeState.Unknown || s == NodeState.Down } }
        return usable + maybe
    }

    /** `ask` of the first node that answers; a refusal of the request itself is the answer. */
    private suspend fun <T> first(ask: suspend (NodeClient) -> T): T = withContext(confined) {
        var failure: Throwable = NodeError("no node answers")
        for (node in order()) {
            try {
                return@withContext ask(node)
            } catch (e: Exception) {
                if (!nodeFailed(e)) throw e
                fail(node, e)
                failure = e
            }
        }
        throw failure
    }

    override suspend fun storage(contract: ByteArray, key: ByteArray): ByteArray? = storageMany(listOf(StorageRead(contract, key)))[0]

    /** Records, each checked by its proof against the state of the block the node names. */
    override suspend fun storageMany(reads: List<StorageRead>): List<ByteArray?> =
        reads.chunked(STORAGE_BATCH).flatMap { chunk -> first { node -> provedFrom(node, chunk) } }

    /**
     * Records that never change once written, from every node, each checked as
     * any read: all nodes that have a record must give the same, so one node
     * alone cannot change, say, a coin's decimals. A record no node has yet is
     * read again next time.
     */
    override suspend fun storageFixed(reads: List<StorageRead>): List<ByteArray?> = withContext(confined) {
        val keys = reads.map { recordKey(it.contract, it.key).hex() }
        val todo = reads.filterIndexed { i, _ -> !fixed.containsKey(keys[i]) }
        if (todo.isNotEmpty()) {
            val usable = usable()
            if (usable.isEmpty()) return@withContext storageMany(reads)
            val got = coroutineScope {
                usable.map { node ->
                    async {
                        try {
                            todo.chunked(STORAGE_BATCH).flatMap { provedFrom(node, it) }
                        } catch (e: Exception) {
                            if (!nodeFailed(e)) throw e
                            fail(node, e)
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            if (got.isEmpty()) throw NodeError("no node answers")
            todo.forEachIndexed { i, r ->
                val key = recordKey(r.contract, r.key).hex()
                val present = got.mapNotNull { it[i] }
                if (present.any { !equal(it, present[0]) }) {
                    if (_notice.value != PoolNotice.Distrusted) _notice.value = PoolNotice.Split
                    throw NodeError("your nodes give different values for a record that never changes")
                }
                if (present.isNotEmpty()) fixed[key] = present[0]
            }
        }
        keys.map { fixed[it] }
    }

    /** `reads` from one node, checked; an answer too large for one response is asked for in halves. */
    private suspend fun provedFrom(node: NodeClient, reads: List<StorageRead>): List<ByteArray?> {
        val answer = try {
            node.storageProved(reads)
        } catch (e: AnswerTooLarge) {
            if (reads.size < 2) throw e
            val half = (reads.size + 1) / 2
            return provedFrom(node, reads.subList(0, half)) + provedFrom(node, reads.subList(half, reads.size))
        }
        reads.forEachIndexed { i, r ->
            if (!verifyRecord(answer.stateRoot, recordKey(r.contract, r.key), answer.values[i], answer.proofs[i], answer.others)) {
                throw distrust(node, "a value it gave does not match the state it read it from")
            }
        }
        checkBlock(node, answer.tip, answer.stateRoot.hex())
        return answer.values
    }

    /** That `root` is the state root of block `tip`, by its header; then has the other nodes confirm the block. */
    private suspend fun checkBlock(node: NodeClient, tip: String, root: String) {
        if (!isHex(tip, 32)) throw distrust(node, "it named a block that is not one")
        var known = headers[tip]
        if (known == null) {
            val asked = "${node.url} $tip"
            val reading = headerReads.getOrPut(asked) {
                scope.async { node.header(tip) }.also { it.invokeOnCompletion { scope.launch { headerReads.remove(asked) } } }
            }
            val bytes = reading.await() ?: throw NodeError("the node does not give the block it read from")
            val header = checkHeader(tip, bytes, Networks.powLimitOf(chain))
                ?: throw distrust(node, "it gave a block header that is not the block’s, or without the work the chain needs")
            if (header.chainId.hex() != chain) throw distrust(node, "it read from a block of another chain")
            known = KnownHeader(header.stateRoot.hex(), header.height, header.timestamp)
            headers[tip] = known
        }
        if (known.root != root) throw distrust(node, "its values are not from the block it named")
        // An answer from far behind the newest block could hide what happened since, such as a
        // withdrawal to cancel: another node is asked instead.
        val newest = maxOf(seen, usable().maxOfOrNull { h(it).status?.height ?: 0L } ?: 0L)
        if (known.height + STALE_BLOCKS < newest) throw NodeError("the node answered from block ${known.height}, behind the newest, $newest")
        seen = maxOf(seen, known.height)
        val old = now() - known.timestamp > OLD_TIP
        val n = _notice.value
        if (old && n == null) _notice.value = PoolNotice.Old(known.timestamp)
        else if (!old && n is PoolNotice.Old) _notice.value = null
        if (!confirmed.containsKey(tip) && !pending.containsKey(tip)) {
            pending[tip] = PendingBlock(known.height, node.url)
            scope.launch { recheck() }
        }
    }

    /**
     * Asks the other nodes whether the blocks answers came from are on their
     * chain. One that has another block there, and has moved on by
     * [CONFIRMATIONS] blocks, follows another chain: the majority of the nodes
     * decides which is wrong, and without one the pool says the nodes split.
     */
    private suspend fun recheck() {
        if (checking) return
        checking = true
        try {
            for ((tip, p) in pending.entries.toList()) {
                val others = nodes.filter { it.url != p.servedBy && h(it).state == NodeState.Ok }
                for (other in others) {
                    val height = h(other).status?.height ?: 0L
                    if (height < p.height) continue
                    val block = runCatching { other.blocks(p.height, 1) }.getOrDefault(emptyList()).firstOrNull() ?: continue
                    if (block.id == tip) {
                        confirmed[tip] = Unit
                        pending.remove(tip)
                        // The nodes agree again.
                        if (_notice.value == PoolNotice.Split) _notice.value = null
                        break
                    }
                    if (height >= p.height + CONFIRMATIONS) {
                        pending.remove(tip)
                        settle(p.height)
                        break
                    }
                }
            }
        } finally {
            checking = false
        }
    }

    /** The nodes have different blocks at `height`: those outvoted are distrusted, or with no majority the pool says they split. */
    private suspend fun settle(height: Long) {
        val ids = LinkedHashMap<NodeClient, String>()
        for (node in nodes.filter { h(it).state == NodeState.Ok }) {
            val block = runCatching { node.blocks(height, 1) }.getOrDefault(emptyList()).firstOrNull()
            if (block != null) ids[node] = block.id
        }
        val ranked = ids.values.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        if (ranked.size < 2) return
        if (ranked[0].value > ranked[1].value) {
            for ((node, id) in ids) if (id != ranked[0].key) distrust(node, "it follows another chain than your other nodes")
        } else if (_notice.value != PoolNotice.Distrusted) {
            _notice.value = PoolNotice.Split
        }
    }

    /** Sent to every node; the answer of the best one that answers decides, the others only pass it on. */
    override suspend fun submit(tx: ByteArray, notBefore: Long?): String = withContext(confined) {
        val targets = order()
        if (targets.isEmpty()) throw NodeError("no node answers")
        val sent = targets.map { node -> scope.async { node.submit(tx, notBefore) } }
        var failure: Throwable? = null
        var maybe: MaybeSent? = null
        for ((i, s) in sent.withIndex()) {
            try {
                return@withContext s.await()
            } catch (e: Exception) {
                if (e is MaybeSent) maybe = maybe ?: e
                else if (!nodeFailed(e)) throw e
                fail(targets[i], e)
                failure = failure ?: e
            }
        }
        // A node that may have it counts for more than one that was not reached.
        throw maybe ?: failure!!
    }

    override suspend fun transaction(id: String): TransactionLookup? = first { it.transaction(id) }
    override suspend fun blocks(from: Long, limit: Int): List<BlockReceipts> = first { it.blocks(from, limit) }
    override suspend fun events(from: Long, limit: Int): List<BlockEvents> = first { it.events(from, limit) }
    override suspend fun contractEvents(contract: ByteArray, topic: ByteArray?, from: Long, limit: Int): EventPage =
        first { it.contractEvents(contract, topic, from, limit) }
    override suspend fun contract(id: ByteArray): ContractManifest? = first { it.contract(id) }
    override suspend fun contractExists(id: ByteArray): Boolean = contract(id) != null
}
