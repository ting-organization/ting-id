// The rates recent transactions paid (chain/FeeMarket), read from the node
// for the chain the app is on: back from the tip until the last `SAMPLE`
// paying transactions are found, at most 3,000 blocks, then each new block as
// it comes. One reading serves every screen.
package app.tingid.state

import app.tingid.chain.FeeMarket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

class FeeRates(private val chain: Chain, private val scope: CoroutineScope) {
    /** The blocks `from` to `to` are read; `ready` once the newest are, as older ones keep coming. */
    private class Window(val chain: String, var from: Long, var to: Long, var rates: List<FeeMarket.Rate>, var ready: Boolean)

    /** What screens see of the window: its rates once ready. */
    private class Seen(val chain: String, val rates: List<FeeMarket.Rate>)

    // Only the one reading coroutine touches the window; screens see copies.
    private var window: Window? = null
    private var reading: Job? = null

    /** The chain and height the last screen asked for, read once the reading on the way ends. */
    private var wanted: Pair<String, Long>? = null
    private val seen = MutableStateFlow<Seen?>(null)

    private fun notify(w: Window) {
        if (w.ready) seen.value = Seen(w.chain, w.rates.toList())
    }

    /** The rates of recent paying transactions on the chain the app is on; null until first read. Reads while collected. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val rates: Flow<List<FeeMarket.Rate>?> = chain.info.map { i -> i?.let { it.chainHex to it.status.height } }.distinctUntilChanged()
        .flatMapLatest { at ->
            if (at == null) return@flatMapLatest flowOf(null)
            seen.onStart { want(at) }.map { s -> s?.takeIf { it.chain == at.first }?.rates }
        }.distinctUntilChanged()

    @Synchronized
    private fun want(at: Pair<String, Long>) {
        wanted = at
        if (reading != null) return
        val w = window
        if (w?.chain == at.first && w.to >= at.second) return
        // Started once noted, so that a reading that ends at once is not noted after it ended.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var next: Pair<String, Long>? = at
            while (next != null) {
                try {
                    read(next.first, next.second)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // Read again with the next block.
                }
                // A block that came while this one was read is read now.
                next = synchronized(this@FeeRates) {
                    val last = wanted
                    val now = window
                    if (last != null && last != next && (now?.chain != last.first || now.to < last.second)) {
                        last
                    } else {
                        reading = null
                        null
                    }
                }
            }
        }
        reading = job
        job.start()
    }

    private suspend fun read(chainHex: String, height: Long) {
        val node = chain.node
        if (window?.chain != chainHex) window = Window(chainHex, height + 1, height, emptyList(), false)
        val c = window!!
        try {
            // The blocks since the last reading.
            var from = c.to + 1
            while (from <= height) {
                val blocks = node.blocks(from, minOf(PAGE, height - from + 1).toInt())
                if (blocks.isEmpty()) break
                c.rates = c.rates + FeeMarket.ratesOf(blocks)
                from = blocks.last().height + 1
                c.to = from - 1
            }
            notify(c)
            // Older ones, until there are enough paying transactions.
            while (c.rates.size < FeeMarket.SAMPLE && c.from > 1 && height - c.from + 1 < REACH) {
                val start = maxOf(1, c.from - PAGE)
                c.rates = c.rates + FeeMarket.ratesOf(node.blocks(start, (c.from - start).toInt()))
                c.from = start
                c.ready = true
                notify(c)
            }
            c.rates = c.rates.filter { it.height > height - REACH }
            c.ready = true
        } finally {
            notify(c)
        }
    }

    companion object {
        /** How far back paying transactions are looked for. */
        const val REACH = 3000L

        /** Blocks per request, the node's most. */
        const val PAGE = 100L
    }
}
