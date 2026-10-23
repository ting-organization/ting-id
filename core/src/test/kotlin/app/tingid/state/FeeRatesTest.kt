// The rates recent transactions paid, read back from the tip until enough
// paying ones are found, then block by block as the chain grows.
package app.tingid.state

import app.tingid.chain.Coin
import app.tingid.chain.Writer
import app.tingid.chain.concat
import app.tingid.chain.filled
import app.tingid.chain.hex
import app.tingid.chain.utf8
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger

class FeeRatesTest {
    private val node = FakeRestNode(filled(0x26))
    private val app = TestApp(node)
    private val miner = filled(0x4d)
    private val daler = filled(0xd1)
    private val producer = concat(utf8("OCP1"), Writer().u32(1).u32(32).put(miner).finish())

    /** A block whose one transaction paid the miner `fee` for a tenth of the block, when `paying`. */
    private fun block(height: Long, paying: Boolean): JsonElement {
        val payment = Writer().put(Coin.PRODUCER_PAYMENT_TOPIC).put(miner).u128(BigInteger.valueOf(height)).finish()
        val receipts = if (paying) """[{"status":0,"used":[15000000,20,829,0],"events":[{"contract":"${daler.hex()}","payload_hex":"${payment.hex()}"}]}]""" else "[]"
        return Json.parseToJsonElement(
            """{"id":"${"%064x".format(height)}","height":$height,"producer_hex":"${producer.hex()}","limits":[150000000,1024,262144,131072],"receipts":$receipts}""",
        )
    }

    private fun chainOf(paying: (Long) -> Boolean, tip: () -> Long) {
        node.blocks = { from, limit -> (from until minOf(from + limit, tip() + 1)).map { block(it, paying(it)) } }
    }

    private fun blockReads() = node.calls.filter { it.startsWith("GET /v1/blocks?") }

    @Test
    fun `reads back from the tip until enough paying transactions, then each new block`() = runBlocking {
        var tip = 250L
        chainOf({ true }, { tip })
        app.at(tip)
        val fees = FeeRates(app.chain, testScope)
        val first = withTimeout(5_000) { fees.rates.filterNotNull().first() }
        assertEquals(100, first.size)
        assertEquals((151L..250L).toList(), first.map { it.height }.sorted())
        assertEquals(listOf("GET /v1/blocks?from=151&limit=100"), blockReads())
        tip = 260
        app.at(tip)
        val next = withTimeout(5_000) { fees.rates.filterNotNull().first { it.size == 110 } }
        assertEquals(260L, next.maxOf { it.height })
        assertEquals(listOf("GET /v1/blocks?from=151&limit=100", "GET /v1/blocks?from=251&limit=10"), blockReads())
        // Another screen gets the same reading at once.
        assertEquals(110, withTimeout(5_000) { fees.rates.filterNotNull().first() }.size)
        assertEquals(2, blockReads().size)
    }

    @Test
    fun `stops at the first block`() = runBlocking {
        chainOf({ it % 20 == 0L }, { 250 })
        app.at(250)
        // 12 paying transactions, fewer than the sample: every block is read.
        val rates = withTimeout(5_000) { FeeRates(app.chain, testScope).rates.filterNotNull().first { it.size == 12 } }
        assertEquals((1L..250L).filter { it % 20 == 0L }, rates.map { it.height }.sorted())
        assertEquals(listOf("GET /v1/blocks?from=151&limit=100", "GET /v1/blocks?from=51&limit=100", "GET /v1/blocks?from=1&limit=50"), blockReads())
    }

    @Test
    fun `looks no further back than 3,000 blocks`() = runBlocking {
        // The only paying transactions: the oldest in reach, and one just past it.
        chainOf({ it == 7_001L || it == 6_950L }, { 10_000 })
        app.at(10_000)
        val rates = withTimeout(5_000) { FeeRates(app.chain, testScope).rates.filterNotNull().first { it.isNotEmpty() } }
        assertEquals(listOf(7_001L), rates.map { it.height })
        assertEquals(30, blockReads().size)
        assertEquals("GET /v1/blocks?from=7001&limit=100", blockReads().last())
    }
}
