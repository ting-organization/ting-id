// Every coin on the chain. The node keeps no list of deployments, so the index
// reads the blocks once for deployments of the standard coin code
// (Deployments.kt), forward as blocks arrive, and saves what it found on the
// device: the next start reads only the blocks it has not seen.
package app.tingid.state

import app.tingid.chain.ChainApi
import app.tingid.chain.ContractName
import app.tingid.chain.Contracts
import app.tingid.chain.unhex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The coins deployed on one chain, as far as the index has read, oldest first. */
class CoinIndex internal constructor(
    private val file: String,
    private val node: () -> ChainApi,
    private val storage: TextStorage,
    work: CoroutineScope,
) {
    class State(val saved: SavedIndex, val loaded: Boolean, val reading: Boolean)

    private val _state = MutableStateFlow(State(emptyIndex(), loaded = false, reading = false))
    val state: StateFlow<State> = _state

    // Only steps touch these, one at a time.
    private var saved = emptyIndex()
    private var loaded = false
    private var reading = false

    /** Results already checked: whether each is a coin. */
    private val checked = HashMap<String, Boolean>()

    /** Set once everything was deleted: what is still on the way is saved nowhere. */
    @Volatile
    internal var dropped = false
        private set

    private val steps = StepQueue(work)

    private fun changed() {
        _state.value = State(saved, loaded, reading)
    }

    private suspend fun isCoin(id: String): Boolean =
        checked[id] ?: (node().contract(unhex(id))?.codeHash == Contracts.codeHash(ContractName.Coin)).also { checked[id] = it }

    /** Reads the blocks up to `height` it has not read; one sync at a time. It fails as the node does. */
    fun sync(height: Long): Deferred<Unit> = steps.add {
        if (!loaded) {
            val text = try {
                storage.read(file)
            } catch (e: Exception) {
                null
            }
            // A damaged index is read again from the start.
            decodeIndex(text)?.let { saved = it }
            loaded = true
            changed()
        }
        var s = saved
        val last = s.last
        if (last != null) {
            val block = if (last.height <= height) node().blocks(last.height, 1).firstOrNull() else null
            if (block == null || block.height != last.height || block.id != last.id) s = rewind(s, REORG_DEPTH, height)
        }
        if (s.next > height) return@add
        reading = true
        changed()
        try {
            while (s.next <= height) {
                val from = s.next
                val blocks = inOrder(node().blocks(from, minOf(PAGE.toLong(), height - from + 1).toInt()), { it.height }, from, height)
                if (blocks.isEmpty()) break
                val found = candidates(blocks).filter { isCoin(it) }
                s = advance(s, blocks, found)
                saved = s
                changed()
            }
        } finally {
            saved = s
            reading = false
            if (!dropped) {
                try {
                    storage.write(file, encodeIndex(s))
                } catch (e: Exception) {
                    // Read again next time.
                }
            }
            changed()
        }
    }

    internal fun drop() {
        dropped = true
        steps.close()
    }

    companion object {
        /** Blocks per request, the node's page limit. */
        const val PAGE = 100

        /** Blocks read again when the last block read was replaced. */
        const val REORG_DEPTH = 20L
        internal const val FILE_PREFIX = "coins-"
    }
}

/** What a screen shows of the coin index. */
class CoinIndexView(
    val coins: List<String>,
    /** The last block read. */
    val readTo: Long,
    val reading: Boolean,
    val loaded: Boolean,
    /** Why the last read failed, until one succeeds. */
    val error: String?,
)

/**
 * The coin index of each chain, shared by every screen. `node` is asked at
 * each read, so an index follows the user's nodes; `heights` is the chain's
 * height, which each index in use is brought up to.
 */
class CoinIndexes(
    private val node: () -> ChainApi,
    private val heights: Flow<Long?>,
    private val storage: TextStorage,
    /** Where reads and saves run. */
    private val work: CoroutineScope,
) {
    constructor(chain: Chain, storage: TextStorage, work: CoroutineScope) :
        this({ chain.node }, chain.info.map { it?.status?.height }, storage, work)

    private val indexes = HashMap<String, CoinIndex>()

    /** The index of the chain `chainHex`, made when first asked for. */
    @Synchronized
    fun indexOf(chainHex: String): CoinIndex =
        indexes.getOrPut(chainHex) { CoinIndex("${CoinIndex.FILE_PREFIX}$chainHex.json", node, storage, work) }

    /** Everything was deleted: no index saves again, and each is read anew when next shown. */
    @Synchronized
    fun forget() {
        for (index in indexes.values) index.drop()
        indexes.clear()
    }

    /** The coins deployed on the chain `chainHex`, as far as the index has read, oldest first; read on while collected. */
    fun index(chainHex: String?): Flow<CoinIndexView> {
        if (chainHex == null) return flowOf(CoinIndexView(emptyList(), 0, reading = false, loaded = false, error = null))
        val index = indexOf(chainHex)
        val error = MutableStateFlow<String?>(null)
        return channelFlow {
            launch {
                heights.distinctUntilChanged().collect { h ->
                    if (h == null || h <= 0) return@collect
                    val done = index.sync(h)
                    launch {
                        try {
                            done.await()
                            error.value = null
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            error.value = e.message
                        }
                    }
                }
            }
            combine(index.state, error) { s, e -> CoinIndexView(s.saved.coins, s.saved.next - 1, s.reading, s.loaded, e) }.collect { send(it) }
        }
    }
}
