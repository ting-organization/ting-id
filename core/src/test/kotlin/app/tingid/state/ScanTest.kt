// The activity scan: which events it keeps, how it grows forward and back,
// what survives a reorganization, and what it saves on the device.
package app.tingid.state

import app.tingid.chain.BlockEvents
import app.tingid.chain.CallEvent
import app.tingid.chain.Coin
import app.tingid.chain.Id
import app.tingid.chain.ReceiptEvent
import app.tingid.chain.Tcr
import app.tingid.chain.Writer
import app.tingid.chain.contractAccount
import app.tingid.chain.filled
import app.tingid.chain.hex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/** Accounts, IDs and events the scan tests share. */
internal object ScanFixture {
    val COIN = filled(0xc0)
    val OTHER_COIN = filled(0xc1)
    val REGISTRY = filled(0xee)
    val ME = filled(1)
    val SAVINGS = filled(2)
    val STRANGER = filled(9)
    val MY_ID = filled(7)
    val scope = ScanScope(chainId = filled(0xaa), registry = REGISTRY, accounts = listOf(ME, SAVINGS), ids = listOf(MY_ID))

    fun big(n: Long): BigInteger = BigInteger.valueOf(n)
    fun transfer(from: ByteArray, to: ByteArray, amount: Long) = Writer().put(Coin.TRANSFER_TOPIC).put(from).put(to).u128(big(amount)).finish()
    fun payment(payee: ByteArray, amount: Long) = Writer().put(Coin.PRODUCER_PAYMENT_TOPIC).put(payee).u128(big(amount)).finish()
    fun scheduled(account: ByteArray, sequence: Long): ByteArray {
        val w = Writer().put(Coin.SCHEDULED_TOPIC).put(account).u64(sequence).u64(1000).u128(BigInteger.ZERO)
        Coin.encodeEffect(w, Coin.Effect.Transfer(STRANGER, big(5)))
        return w.finish()
    }
    fun executed(account: ByteArray, sequence: Long) = Writer().put(Coin.EXECUTED_TOPIC).put(account).u64(sequence).finish()
    fun created(id: ByteArray) = Writer().put(Id.CREATED_TOPIC).put(id).finish()

    val PROGRAM = filled(0xd0)
    fun callScheduled(account: ByteArray, sequence: Long) = Writer().put(Tcr.SCHEDULED_TOPIC).put(account).u64(sequence).u64(2000).put(filled(0x7e)).put(byteArrayOf(1, 2)).finish()
    fun callExecuted(account: ByteArray, sequence: Long) = Writer().put(Tcr.EXECUTED_TOPIC).put(account).u64(sequence).put(filled(0x0e)).finish()

    /** An event of `contract`, of transaction `tx` (`t<height>` when null). */
    class E(val contract: ByteArray, val payload: ByteArray, val tx: String? = null)

    fun block(height: Long, events: List<E>, id: String = "b$height") =
        BlockEvents(id, height, 1_792_000_000 + height * 60, events.map { ReceiptEvent(it.contract, it.payload, it.tx ?: "t$height") })
}

class ScanTest {
    private val f = ScanFixture
    private fun e(contract: ByteArray, payload: ByteArray, tx: String? = null) = ScanFixture.E(contract, payload, tx)

    @Test
    fun `keeps the events that touch the scope, from any coin, and marks fees`() {
        val items = matchBlocks(
            listOf(
                f.block(
                    5,
                    listOf(
                        e(f.COIN, f.transfer(f.STRANGER, f.ME, 10)),
                        e(f.COIN, f.transfer(f.STRANGER, f.STRANGER, 99)),
                        // A fee: a transfer followed by its producer payment in the same transaction.
                        e(f.COIN, f.transfer(f.ME, f.STRANGER, 3), "fee"),
                        e(f.COIN, f.payment(f.STRANGER, 3), "fee"),
                        // A coin the user does not follow is found too, for discovery.
                        e(f.OTHER_COIN, f.transfer(f.STRANGER, f.SAVINGS, 1)),
                        // A zero transfer to itself only carries a bound fee.
                        e(f.COIN, f.transfer(f.ME, f.ME, 0)),
                        e(f.REGISTRY, f.created(f.MY_ID)),
                        e(f.REGISTRY, f.created(filled(8))),
                    ),
                ),
            ),
            f.scope,
        )
        assertEquals(
            listOf(listOf<Any>(5L, "c0", false), listOf<Any>(5L, "c0", true), listOf<Any>(5L, "c1", false), listOf<Any>(5L, "ee", false)),
            items.map { listOf(it.height, it.contract.take(2), it.fee) },
        )
    }

    @Test
    fun `reads back from the tip first, then forward, without reading a block twice`() {
        var scan = emptyScan(10)
        assertEquals(listOf(11L, 10L), listOf(scan.low, scan.high))
        scan = addOlder(scan, listOf(f.block(7, listOf(e(f.COIN, f.transfer(f.STRANGER, f.ME, 1)))), f.block(8, listOf()), f.block(9, listOf()), f.block(10, listOf())), f.scope)
        assertEquals(listOf<Any>(7L, 10L, "b10"), listOf(scan.low, scan.high, scan.tip))
        // The page from `high` overlaps by one block: it is not read again.
        scan = addNewer(scan, listOf(f.block(10, listOf(e(f.COIN, f.transfer(f.STRANGER, f.ME, 50)))), f.block(11, listOf(e(f.COIN, f.transfer(f.ME, f.STRANGER, 2))))), f.scope)
        assertEquals(listOf<Any>(7L, 11L, "b11"), listOf(scan.low, scan.high, scan.tip))
        assertEquals(listOf(7L, 11L), scan.items.map { it.height })
        scan = addOlder(scan, listOf(f.block(5, listOf(e(f.COIN, f.payment(f.ME, 4)))), f.block(6, listOf())), f.scope)
        assertEquals(5L, scan.low)
        assertEquals(listOf(11L, 7L, 5L), activityOf(scan.items, f.REGISTRY).map { it.height })
    }

    @Test
    fun `forgets the blocks a reorganization may have replaced`() {
        var scan = emptyScan(40)
        val blocks = (1..40L).map { f.block(it, listOf(e(f.COIN, f.transfer(f.STRANGER, f.ME, it)))) }
        scan = addOlder(scan, blocks, f.scope)
        scan = rollBack(scan, 40 - REORG_DEPTH)
        assertEquals(listOf<Any>(20L, "", 20), listOf(scan.high, scan.tip, scan.items.size))
        // Never below what was read.
        assertEquals(3L, rollBack(addOlder(emptyScan(5), listOf(f.block(4, listOf()), f.block(5, listOf())), f.scope), 1).high)
    }

    @Test
    fun `saves the newest items, cut at a block boundary`() {
        val scan = addOlder(
            emptyScan(4),
            listOf(
                f.block(1, listOf(e(f.COIN, f.transfer(f.STRANGER, f.ME, 1)))),
                f.block(2, listOf(e(f.COIN, f.transfer(f.STRANGER, f.ME, 2)), e(f.COIN, f.transfer(f.STRANGER, f.ME, 3)))),
                f.block(3, listOf(e(f.COIN, f.transfer(f.STRANGER, f.ME, 4)))),
                f.block(4, listOf(e(f.COIN, f.transfer(f.STRANGER, f.ME, 5)))),
            ),
            f.scope,
        )
        // Keeping 3 would split block 2, so all of block 2 goes and the scan starts at 3.
        val kept = trimmed(scan, 3)
        assertEquals(3L, kept.low)
        assertEquals(listOf(3L, 4L), kept.items.map { it.height })
        assertSame(scan, trimmed(scan, 10))
    }

    @Test
    fun `marks a scheduled action that a later block ran`() {
        val scan = addOlder(
            emptyScan(3),
            listOf(
                f.block(1, listOf(e(f.COIN, f.scheduled(f.SAVINGS, 0)))),
                f.block(2, listOf(e(f.COIN, f.scheduled(f.SAVINGS, 1)))),
                f.block(3, listOf(e(f.COIN, f.executed(f.SAVINGS, 0)))),
            ),
            f.scope,
        )
        val activity = activityOf(scan.items, f.REGISTRY)
        assertEquals(
            listOf(listOf(3L, "Executed", null), listOf(2L, "Scheduled", null), listOf(1L, "Scheduled", Settled.Executed)),
            activity.map { listOf(it.height, it.coin!!::class.simpleName, it.settled) },
        )
        // What ran is known from its scheduling.
        assertEquals(Coin.Effect.Transfer(f.STRANGER, f.big(5)), activity[0].plan)
    }

    @Test
    fun `joins a fee to the event it paid for, and keeps one paid for nothing shown`() {
        val items = matchBlocks(
            listOf(
                f.block(
                    4,
                    listOf(
                        e(f.COIN, f.transfer(f.ME, f.STRANGER, 25), "pay"),
                        e(f.COIN, f.transfer(f.ME, filled(5), 1), "pay"),
                        e(f.COIN, f.payment(filled(5), 1), "pay"),
                    ),
                ),
                f.block(
                    5,
                    listOf(
                        e(f.OTHER_COIN, f.transfer(f.SAVINGS, filled(5), 2), "alone"),
                        e(f.OTHER_COIN, f.payment(filled(5), 2), "alone"),
                    ),
                ),
            ),
            f.scope,
        )
        val activity = activityOf(items, f.REGISTRY)
        assertEquals(
            listOf(listOf<Any>("alone", true, emptyList<BigInteger>()), listOf<Any>("pay", false, listOf(f.big(1)))),
            activity.map { listOf(it.transaction, it.fee, it.fees.map { fee -> fee.amount }) },
        )
    }

    @Test
    fun `keeps the program calls of the scope's accounts, from any program`() {
        val items = matchBlocks(
            listOf(
                f.block(
                    6,
                    listOf(
                        e(f.PROGRAM, f.callExecuted(f.ME, 0)),
                        e(f.PROGRAM, f.callExecuted(f.STRANGER, 0)),
                        e(filled(0xd1), f.callScheduled(f.SAVINGS, 0)),
                    ),
                ),
            ),
            f.scope,
        )
        assertEquals(listOf("d0", "d1"), items.map { it.contract.take(2) })
    }

    @Test
    fun `a program call takes in what its account burned or paid into the program, and its fee`() {
        val into = contractAccount(f.PROGRAM)
        val items = matchBlocks(
            listOf(
                f.block(
                    6,
                    listOf(
                        e(f.COIN, f.transfer(f.ME, Coin.ZERO_ACCOUNT, 40), "paint"),
                        e(f.COIN, f.transfer(f.ME, filled(5), 1), "paint"),
                        e(f.COIN, f.payment(filled(5), 1), "paint"),
                        e(f.PROGRAM, f.callExecuted(f.ME, 0), "paint"),
                        // A payment into the program, and one to someone else that stays a payment.
                        e(f.COIN, f.transfer(f.ME, into, 7), "bid"),
                        e(f.COIN, f.transfer(f.ME, f.STRANGER, 3), "bid"),
                        e(f.PROGRAM, f.callExecuted(f.ME, 1), "bid"),
                        // A fee payment moves nothing, and its fee joins the call.
                        e(f.COIN, f.transfer(f.ME, into, 0), "name"),
                        e(f.COIN, f.transfer(f.ME, filled(5), 2), "name"),
                        e(f.COIN, f.payment(filled(5), 2), "name"),
                        e(f.PROGRAM, f.callExecuted(f.ME, 2), "name"),
                        // What comes back from the program is received.
                        e(f.COIN, f.transfer(into, f.ME, 7), "refund"),
                    ),
                ),
            ),
            f.scope,
        )
        assertEquals(
            listOf<List<Any>>(
                listOf("refund", "Transfer", emptyList<BigInteger>(), emptyList<BigInteger>()),
                listOf("name", "Executed", emptyList<BigInteger>(), listOf(f.big(2))),
                listOf("bid", "Executed", listOf(f.big(7)), emptyList<BigInteger>()),
                listOf("bid", "Transfer", emptyList<BigInteger>(), emptyList<BigInteger>()),
                listOf("paint", "Executed", listOf(f.big(40)), listOf(f.big(1))),
            ),
            activityOf(items, f.REGISTRY).map {
                listOf(it.transaction, (it.call ?: it.coin)!!::class.simpleName, it.paid.map { p -> p.amount }, it.fees.map { fee -> fee.amount })
            },
        )
    }

    @Test
    fun `marks a program's scheduled operation that a later block ran, and the run`() {
        val scan = addOlder(
            emptyScan(4),
            listOf(
                f.block(1, listOf(e(f.PROGRAM, f.callScheduled(f.SAVINGS, 0)))),
                f.block(2, listOf(e(f.PROGRAM, f.callScheduled(f.SAVINGS, 1)))),
                f.block(3, listOf(e(f.PROGRAM, f.callExecuted(f.SAVINGS, 0)))),
                // A coin's action of the same sequence is another thing.
                f.block(4, listOf(e(f.COIN, f.executed(f.SAVINGS, 1)))),
            ),
            f.scope,
        )
        assertEquals(
            listOf(listOf(4L, false, null), listOf(3L, true, null), listOf(2L, false, null), listOf(1L, false, Settled.Executed)),
            activityOf(scan.items, f.REGISTRY).map { listOf(it.height, it.run, it.settled) },
        )
    }

    @Test
    fun `a program call shows in the lists of the coins it moved, and one that moved none in the wallet's`() = runBlocking {
        val paint = ActivityItem(1, 0, "paint", f.PROGRAM, call = CallEvent.Executed(f.ME, 0, filled(0x0e)), paid = listOf(FeePaid(f.COIN, f.big(40))))
        val run = ActivityItem(2, 0, "run", f.PROGRAM, call = CallEvent.Executed(f.SAVINGS, 1, filled(0x0e)), run = true)
        val states = flowOf(Scan.State(null, true, false, listOf(run, paint)))
        suspend fun shown(filter: ActivityFilter) = ActivityList(null, states, flowOf(null), filter).page.first().items.map { it.transaction }
        assertEquals(listOf("run", "paint"), shown(ActivityFilter(listOf(f.COIN), identity = true)))
        assertEquals(listOf("paint"), shown(ActivityFilter(listOf(f.COIN), listOf(f.ME, f.SAVINGS), identity = false)))
        assertEquals(emptyList<String>(), shown(ActivityFilter(listOf(f.OTHER_COIN), listOf(f.ME), identity = false)))
        assertEquals(emptyList<String>(), shown(ActivityFilter(listOf(f.COIN), listOf(f.SAVINGS), identity = false)))
    }

    @Test
    fun `keeps the time of each block, and reads again a scan saved by an older version`() {
        val items = matchBlocks(listOf(f.block(8, listOf(e(f.COIN, f.transfer(f.STRANGER, f.ME, 1)))), f.block(9, listOf(e(f.COIN, f.transfer(f.STRANGER, f.ME, 2))))), f.scope)
        assertEquals(listOf(listOf(9L, 1_792_000_540L), listOf(8L, 1_792_000_480L)), activityOf(items, f.REGISTRY).map { listOf(it.height, it.time) })
        assertNotNull(decodeScan(encodeScan(emptyScan(9))))
        assertNull(decodeScan(encodeScan(emptyScan(9)).replace("\"v\":3", "\"v\":2")))
    }

    @Test
    fun `names one scan per user and chain, whatever the order of accounts`() {
        val s = f.scope
        assertEquals(scopeKey(s), scopeKey(ScanScope(s.chainId, s.registry, listOf(f.SAVINGS, f.ME), s.ids)))
        assertNotEquals(scopeKey(s), scopeKey(ScanScope(filled(0xab), s.registry, s.accounts, s.ids)))
        assertTrue(scopeKey(s).startsWith(s.chainId.hex()))
    }

    @Test
    fun `comes back from the device as it was saved, and a damaged one is read again`() {
        val scan = addOlder(
            emptyScan(3),
            listOf(f.block(2, listOf(e(f.COIN, f.transfer(f.ME, f.STRANGER, 3), "fee"), e(f.COIN, f.payment(f.STRANGER, 3), "fee"))), f.block(3, listOf())),
            f.scope,
        )
        assertEquals(scan, decodeScan(encodeScan(scan)))
        // An item whose contract is not an id, a fee flag that is neither 0 nor 1, or no JSON at all.
        assertNull(decodeScan(encodeScan(scan).replace("\"${f.COIN.hex()}\"", "\"c0\"")))
        assertNull(decodeScan(encodeScan(scan).replace(",1,", ",2,")))
        assertNull(decodeScan("not json"))
        assertNull(decodeScan(null))
    }
}

class ActivitiesTest {
    private val f = ScanFixture
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var clock = 1_000L

    private fun activities(node: FakeNode, storage: TextStorage = MemoryStorage(), heights: MutableStateFlow<Long?> = MutableStateFlow(null)) =
        Activities({ node }, heights, storage, work) { clock++ }

    private fun pay(node: FakeNode, height: Long, amount: Long, coin: ByteArray = f.COIN) {
        node.events[height] = listOf(ReceiptEvent(coin, f.transfer(f.STRANGER, f.ME, amount), "t$height"))
    }

    @Test
    fun `searches back on the first run, then reads the new blocks, and saves what it found`() = runBlocking {
        val node = FakeNode().apply { height = 600 }
        pay(node, 100, 1)
        pay(node, 550, 2)
        val storage = MemoryStorage()
        val scan = activities(node, storage).scanOf(f.scope)
        scan.sync(600).await()
        // Back to the start of the chain: two finds are fewer than a search looks for.
        assertEquals(listOf(1L, 600L, "b600"), scan.state.value.saved!!.let { listOf(it.low, it.high, it.tip) })
        assertEquals(listOf(550L, 100L), scan.state.value.activity.map { it.height })
        node.height = 650
        pay(node, 640, 3)
        node.asked.clear()
        scan.sync(650).await()
        // Read from the last block read, to see the tip did not change.
        assertEquals(listOf("events:600"), node.asked)
        assertEquals(listOf(640L, 550L, 100L), scan.state.value.activity.map { it.height })
        // A new start begins with what was saved, and reads only from the last block read.
        val again = activities(node, storage).scanOf(f.scope)
        node.asked.clear()
        again.sync(650).await()
        assertEquals(listOf("events:650"), node.asked)
        assertEquals(listOf(640L, 550L, 100L), again.state.value.activity.map { it.height })
    }

    @Test
    fun `reads again the blocks a reorganization replaced`() = runBlocking {
        val node = FakeNode().apply { height = 50 }
        pay(node, 45, 1)
        val scan = activities(node).scanOf(f.scope)
        scan.sync(50).await()
        assertEquals(listOf(45L), scan.state.value.activity.map { it.height })
        // Another branch from block 41 on: the payment is in block 47 there.
        for (h in 41..51L) node.ids[h] = "x$h"
        node.events.remove(45)
        pay(node, 47, 1)
        node.height = 51
        scan.sync(51).await()
        assertEquals(listOf(47L), scan.state.value.activity.map { it.height })
        assertEquals("x51", scan.state.value.saved!!.tip)
    }

    @Test
    fun `stops when a node answers with blocks it did not ask for`() = runBlocking {
        // Whatever is asked, this node gives block 1 again.
        val node = object : FakeNode() {
            override suspend fun events(from: Long, limit: Int): List<BlockEvents> {
                asked.add("events:$from")
                return listOf(BlockEvents("b1", 1, 0, emptyList()))
            }
        }
        val scan = activities(node).scanOf(f.scope)
        withTimeout(5_000) {
            scan.sync(50).await()
            scan.sync(60).await()
        }
        assertTrue(node.asked.size < 10)
    }

    @Test
    fun `keeps the scans used last, and saves nothing once everything was deleted`() = runBlocking {
        val node = FakeNode().apply { height = 5 }
        val storage = MemoryStorage()
        val activities = activities(node, storage)
        val scans = (1..Activities.KEEP_SCANS + 1).map { n -> activities.scanOf(ScanScope(f.scope.chainId, f.REGISTRY, listOf(filled(n)), emptyList())) }
        for (scan in scans) {
            scan.sync(5).await()
            until { storage.read(Activities.USED)?.contains(scan.file) == true }
        }
        until { storage.names().count { it.startsWith(Scan.FILE_PREFIX) } == Activities.KEEP_SCANS }
        assertNull(storage.read(scans[0].file))
        assertNotNull(storage.read(scans.last().file))

        val other = activities.scanOf(ScanScope(filled(0xab), f.REGISTRY, listOf(f.ME), emptyList()))
        activities.forget()
        val late = runCatching { other.sync(5).await() }.exceptionOrNull()
        assertTrue(late is CancellationException)
        assertNull(storage.read(other.file))
        // A scan begun after that is a new one.
        assertFalse(activities.scanOf(other.scope) === other)
    }

    @Test
    fun `lists the activity of the coins asked for, a page at a time`() = runBlocking {
        val node = FakeNode().apply { height = 40 }
        for (h in 1..25L) pay(node, h, h)
        pay(node, 30, 1, f.OTHER_COIN)
        node.events[31] = listOf(ReceiptEvent(f.REGISTRY, f.created(f.MY_ID), "t31"))
        val list = activities(node, heights = MutableStateFlow(40L)).activity(f.scope, ActivityFilter(coins = listOf(f.COIN), identity = false))
        val first = withTimeout(5_000) { list.page.first { !it.loading && !it.searching } }
        assertEquals(20, first.items.size)
        assertEquals(5, first.hidden)
        assertEquals(25L, first.items[0].height)
        assertNull(first.searchedTo)
        list.showMore()
        val all = withTimeout(5_000) { list.page.first { it.hidden == 0 } }
        assertEquals(25, all.items.size)
        assertTrue(all.items.all { it.coin != null && it.contract.contentEquals(f.COIN) })
        val withId = activities(node, heights = MutableStateFlow(40L)).activity(f.scope, ActivityFilter(coins = emptyList(), identity = true))
        assertEquals(listOf(31L), withTimeout(5_000) { withId.page.first { !it.loading && !it.searching } }.items.map { it.height })
    }
}
