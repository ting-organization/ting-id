// The user's activity on screen: one scan of the chain's events per user and
// chain (Scan.kt), shared by every screen, read forward as blocks arrive and
// backward when the user asks for older activity, and saved on the device so
// the next start reads only the blocks it has not seen.
package app.tingid.state

import app.tingid.chain.BlockEvents
import app.tingid.chain.ChainApi
import app.tingid.chain.hex
import app.tingid.chain.sha256
import app.tingid.chain.utf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Steps run one after another, never two at once, in the order they were
 * added, as a chain of promises runs them. A step's failure is its own: the
 * next one runs all the same.
 */
internal class StepQueue(scope: CoroutineScope) {
    private val steps = Channel<Pair<suspend () -> Unit, CompletableDeferred<Unit>>>(Channel.UNLIMITED)

    init {
        scope.launch {
            for ((step, done) in steps) {
                try {
                    step()
                    done.complete(Unit)
                } catch (e: Throwable) {
                    done.completeExceptionally(e)
                    // Only the end of the scope ends the queue.
                    currentCoroutineContext().ensureActive()
                }
            }
        }
    }

    /** Runs `step` after the ones added before it; it completes, or fails, as the step does. */
    fun add(step: suspend () -> Unit): Deferred<Unit> {
        val done = CompletableDeferred<Unit>()
        if (steps.trySend(step to done).isFailure) done.cancel()
        return done
    }

    /** No step runs after those already added. */
    fun close() {
        steps.close()
    }
}

/** The blocks of a page from `from` to `to`, each above the one before: a node's page is not taken on trust. */
internal fun <B> inOrder(blocks: List<B>, height: (B) -> Long, from: Long, to: Long = Long.MAX_VALUE): List<B> {
    val out = ArrayList<B>()
    for (b in blocks) if (height(b) in from..to && (out.isEmpty() || height(b) > height(out.last()))) out.add(b)
    return out
}

/** One user's scan on one chain, shared by every screen that shows it. */
class Scan internal constructor(
    val key: String,
    val scope: ScanScope,
    private val node: () -> ChainApi,
    private val storage: TextStorage,
    private val noteUsed: (Scan) -> Unit,
    work: CoroutineScope,
) {
    class State(
        /** What was read, once loaded or begun. */
        val saved: SavedScan?,
        val loaded: Boolean,
        /** Reading older blocks now. */
        val searching: Boolean,
        /** What was found, newest first. */
        val activity: List<ActivityItem>,
    )

    private val _state = MutableStateFlow(State(null, false, false, emptyList()))
    val state: StateFlow<State> = _state

    /** The file the scan is saved in: named by a hash of its key, which is too long for a file name. */
    internal val file = "$FILE_PREFIX${sha256(utf8(key)).hex()}.json"

    // Only steps touch these, one at a time.
    private var saved: SavedScan? = null
    private var loaded = false
    private var searching = false
    private var activity: List<ActivityItem> = emptyList()

    /** The scan `activity` was found in: it is found again only when the scan changed. */
    private var activityFrom: SavedScan? = null

    /** The height the scan was last brought up to, so several screens at one height read once. */
    private var synced = 0L
    private var noted = false

    /** Set once everything was deleted: what is still on the way is saved nowhere. */
    @Volatile
    internal var dropped = false
        private set

    private val steps = StepQueue(work)

    /** Runs one step after the ones before it; a step that fails changes nothing more. */
    private fun run(step: suspend () -> Unit): Deferred<Unit> = steps.add {
        try {
            step()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
    }

    private fun changed(save: Boolean) {
        val s = saved
        if (s != null && s !== activityFrom) {
            activity = activityOf(s.items, scope.registry)
            activityFrom = s
        }
        _state.value = State(s, loaded, searching, activity)
        if (!save || s == null || dropped) return
        try {
            storage.write(file, encodeScan(trimmed(s, Activities.KEEP)))
        } catch (e: Exception) {
            // The saved scan is a convenience: what is not saved is read again next time.
        }
        if (!noted) {
            noted = true
            noteUsed(this)
        }
    }

    private fun load() {
        if (loaded) return
        try {
            saved = decodeScan(storage.read(file))
        } catch (e: Exception) {
            // An unreadable saved scan is read again from the chain.
        }
        loaded = true
        changed(false)
    }

    /** Brings the scan up to `height`: on the first run a search back from it, later the new blocks. */
    fun sync(height: Long): Deferred<Unit> = run {
        load()
        if (saved == null) {
            saved = emptyScan(height)
            older(Activities.SEARCH_ITEMS)
            synced = height
            return@run
        }
        if (height != synced) newer(height)
        synced = height
    }

    /** Reads further back, until it finds some more activity or has read a search's worth of blocks. */
    fun searchOlder(): Deferred<Unit> = run {
        load()
        if (saved != null) older(Activities.SEARCH_ITEMS)
    }

    private suspend fun newer(height: Long) {
        var scan = saved!!
        if (height < scan.high) scan = rollBack(scan, height - REORG_DEPTH)
        // Reading from the last block read shows whether the tip changed under it.
        var from = if (scan.tip.isNotEmpty()) scan.high else scan.high + 1
        var moved = scan !== saved
        while (from <= height) {
            val blocks = inOrder(node().events(from, Activities.PAGE), { it.height }, from)
            if (blocks.isEmpty()) break
            if (scan.tip.isNotEmpty() && blocks[0].height == scan.high && blocks[0].block != scan.tip) {
                scan = rollBack(scan, scan.high - REORG_DEPTH)
                from = scan.high + 1
                moved = true
                continue
            }
            scan = addNewer(scan, blocks, this.scope)
            from = blocks.last().height + 1
            moved = true
        }
        if (!moved) return
        saved = scan
        changed(true)
    }

    private suspend fun older(wanted: Int) {
        searching = true
        changed(false)
        try {
            var found = 0
            var budget = Activities.SEARCH_BLOCKS
            while (true) {
                val scan = saved!!
                if (scan.low <= 1 || found >= wanted || budget <= 0) break
                val to = scan.low - 1
                val from = maxOf(1, to - Activities.PAGE * Activities.PARALLEL + 1)
                val blocks = readRange(from, to)
                if (blocks.isEmpty()) break
                val next = addOlder(scan, blocks, this.scope)
                saved = next
                found += next.items.size - scan.items.size
                budget -= to - from + 1
                changed(true)
            }
        } finally {
            searching = false
            changed(false)
        }
    }

    /** Blocks `from` to `to`, all of them, in order: a page the node cuts short is continued. */
    private suspend fun readRange(from: Long, to: Long): List<BlockEvents> = coroutineScope {
        val spans = (from..to step Activities.PAGE.toLong()).map { a -> a to minOf(to, a + Activities.PAGE - 1) }
        spans.chunked(Activities.PARALLEL).flatMap { group ->
            group.map { (a, b) ->
                async {
                    val blocks = ArrayList<BlockEvents>()
                    var next = a
                    while (next <= b) {
                        val page = inOrder(node().events(next, (b - next + 1).toInt()), { it.height }, next, b)
                        if (page.isEmpty()) break
                        blocks.addAll(page)
                        next = page.last().height + 1
                    }
                    blocks
                }
            }.awaitAll().flatten()
        }
    }

    internal fun drop() {
        dropped = true
        steps.close()
    }

    companion object {
        internal const val FILE_PREFIX = "scan-"
    }
}

/** What one list shows of the activity: the coins it is about, the accounts, and whether identity events belong. */
class ActivityFilter(
    val coins: List<ByteArray>,
    /** Only events touching these accounts; every account of the scope when null. */
    val accounts: List<ByteArray>? = null,
    val identity: Boolean,
)

class ActivityPage(
    /** Shown so far, newest first. */
    val items: List<ActivityItem>,
    /** Nothing found yet while the first search is still reading. */
    val loading: Boolean,
    /** Reading older blocks now. */
    val searching: Boolean,
    /** Found but not shown yet. */
    val hidden: Int,
    /** The oldest block read so far, or null once the search has reached the start of the chain. */
    val searchedTo: Long?,
)

/** A list's view of the user's activity: the newest first, more as the user scrolls, older blocks read on request. */
class ActivityList internal constructor(
    private val scan: Scan?,
    states: Flow<Scan.State?>,
    heights: Flow<Long?>,
    filter: ActivityFilter,
) {
    private val shown = MutableStateFlow(Activities.SHOWN)

    /** How many items the filter lets through, as last shown. */
    @Volatile
    private var found = 0

    val page: Flow<ActivityPage> = combine(states, shown, heights) { s, n, height ->
        val coins = filter.coins.map { it.hex() }.toSet()
        val all = (s?.activity ?: emptyList()).filter { item ->
            val coin = item.coin
            when {
                item.identity != null -> filter.identity
                coin == null || item.contract.hex() !in coins -> false
                else -> filter.accounts == null || touches(coin, filter.accounts, emptyList())
            }
        }
        found = all.size
        val low = s?.saved?.low
        ActivityPage(
            items = all.take(n),
            loading = scan != null && (s?.loaded != true || (s.searching && all.isEmpty()) || (s.saved == null && height != null && height > 0)),
            searching = s?.searching == true,
            hidden = maxOf(0, all.size - n),
            searchedTo = if (low != null && low > 1) low else null,
        )
    }

    /** Shows the next items already found. */
    fun showMore() = shown.update { n -> if (n < found) n + Activities.SHOWN else n }

    /** Reads older blocks for more activity. */
    fun searchOlder() {
        if (scan == null) return
        shown.update { n -> maxOf(n, found + Activities.SHOWN) }
        scan.searchOlder()
    }
}

/**
 * Every scan of this device, one per user and chain, shared by every screen.
 * `node` is asked at each read, so a scan follows the user's nodes; `heights`
 * is the chain's height, which each scan in use is brought up to.
 */
class Activities(
    private val node: () -> ChainApi,
    private val heights: Flow<Long?>,
    private val storage: TextStorage,
    /** Where reads and saves run. */
    private val work: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(chain: Chain, storage: TextStorage, work: CoroutineScope) :
        this({ chain.node }, chain.info.map { it?.status?.height }, storage, work)

    private val scans = HashMap<String, Scan>()

    /** One update of the saved scans' use at a time: each reads what the one before wrote. */
    private val usedLock = Mutex()

    /** The scan of `scope`, made when first asked for. */
    @Synchronized
    fun scanOf(scope: ScanScope): Scan {
        val key = scopeKey(scope)
        return scans.getOrPut(key) { Scan(key, scope, node, storage, ::noteUsed, work) }
    }

    /** Everything was deleted: no scan saves again, and each is begun anew when next shown. */
    @Synchronized
    fun forget() {
        for (scan in scans.values) scan.drop()
        scans.clear()
    }

    /** The scan of `scope`, kept up to the chain's height while collected, and its found activity; null without a scope. */
    fun scan(scope: ScanScope?): Flow<Scan.State?> {
        if (scope == null) return flowOf(null)
        val scan = scanOf(scope)
        return channelFlow {
            launch {
                heights.distinctUntilChanged().collect { h -> if (h != null && h > 0) scan.sync(h) }
            }
            scan.state.collect { send(it) }
        }
    }

    /** A list's view of the user's activity in `scope`, as `filter` has it. */
    fun activity(scope: ScanScope?, filter: ActivityFilter): ActivityList =
        ActivityList(scope?.let { scanOf(it) }, scan(scope), heights, filter)

    /** Notes that `scan` is in use, and removes the saved scans least recently used past [KEEP_SCANS]. */
    private fun noteUsed(scan: Scan) {
        work.launch {
            usedLock.withLock {
                if (scan.dropped) return@withLock
                try {
                    var used = LinkedHashMap<String, Long>()
                    try {
                        val text = storage.read(USED)
                        if (text != null) {
                            for ((name, at) in Store.json.parseToJsonElement(text) as JsonObject) {
                                used[name] = (at as? JsonPrimitive)?.longOrNull ?: 0
                            }
                        } else {
                            // Scans saved before their use was noted count as used longest ago.
                            for (name in storage.names()) if (name.startsWith(Scan.FILE_PREFIX)) used[name] = 0
                        }
                    } catch (e: Exception) {
                        used = LinkedHashMap()
                    }
                    used[scan.file] = clock()
                    val old = used.entries.sortedByDescending { it.value }.drop(KEEP_SCANS).map { it.key }
                    for (name in old) {
                        used.remove(name)
                        storage.delete(name)
                    }
                    storage.write(USED, buildJsonObject { for ((name, at) in used) put(name, JsonPrimitive(at)) }.toString())
                } catch (e: Exception) {
                    // Only which saved scans go first when there are too many.
                }
            }
        }
    }

    companion object {
        /** Blocks per events request, the node's page limit. */
        const val PAGE = 100

        /** Event requests sent at once when reading back. */
        const val PARALLEL = 4

        /** Blocks one search for older activity reads at most, about 3.5 days of 60-second blocks. */
        const val SEARCH_BLOCKS = 5_000L

        /** Items a search for older activity looks for before it stops. */
        const val SEARCH_ITEMS = 20

        /** Items a list shows at first, and adds each time it is scrolled to the end. */
        const val SHOWN = 20

        /** Items saved on the device; older ones are found again by searching back. */
        const val KEEP = 1_000

        /** When each saved scan was last used (file name → milliseconds), so the least used go first. */
        const val USED = "scans-used.json"

        /** Saved scans kept at most: one per user and chain, and accounts a user no longer has, leave in time. */
        const val KEEP_SCANS = 12
    }
}
