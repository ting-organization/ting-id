// The coin index: its saved state (Deployments.kt), and how it reads the chain
// (CoinIndex.kt).
package app.tingid.state

import app.tingid.chain.BlockReceipt
import app.tingid.chain.BlockReceipts
import app.tingid.chain.ContractName
import app.tingid.chain.Contracts
import app.tingid.chain.NodeError
import app.tingid.chain.filled
import app.tingid.chain.hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DeploymentsTest {
    private fun id(n: Int) = filled(n)
    private fun receipt(status: Int, result: ByteArray) = BlockReceipt(status, result, emptyList(), emptyList())
    private fun block(height: Long, receipts: List<BlockReceipt> = emptyList()) = BlockReceipts("b$height", height, ByteArray(0), emptyList(), receipts)

    @Test
    fun `takes the 32-byte results of successful transactions as candidates`() {
        val blocks = listOf(
            block(1, listOf(receipt(0, id(1)), receipt(7, id(2)), receipt(0, ByteArray(0)))),
            block(2, listOf(receipt(0, id(3)))),
        )
        assertEquals(listOf("01".repeat(32), "03".repeat(32)), candidates(blocks))
    }

    @Test
    fun `moves past the blocks read and keeps each coin once`() {
        var s = advance(emptyIndex(), listOf(block(1), block(2)), listOf("aa"))
        assertEquals(SavedIndex(version = 1, next = 3, last = LastBlock(2, "b2"), coins = listOf("aa")), s)
        s = advance(s, listOf(block(3)), listOf("aa", "bb"))
        assertEquals(listOf("aa", "bb"), s.coins)
        assertSame(s, advance(s, emptyList(), listOf("cc")))
    }

    @Test
    fun `reads again below a replaced block, keeping what it found`() {
        val s = advance(emptyIndex(), listOf(block(50)), listOf("aa"))
        assertEquals(SavedIndex(version = 1, next = 31, last = null, coins = listOf("aa")), rewind(s, 20))
        assertEquals(21L, rewind(s, 20, 40).next)
        assertEquals(1L, rewind(advance(emptyIndex(), listOf(block(5)), emptyList()), 20).next)
    }

    @Test
    fun `accepts only a saved index of its own shape`() {
        assertTrue(isSavedIndex(emptyIndex()))
        assertFalse(isSavedIndex(SavedIndex(version = 1, next = 0, last = null, coins = emptyList())))
        assertFalse(isSavedIndex(SavedIndex(version = 2, next = 1, last = null, coins = emptyList())))
        assertFalse(isSavedIndex(null))
        // As saved on the device.
        assertNotNull(decodeIndex(encodeIndex(advance(emptyIndex(), listOf(block(4)), listOf(id(1).hex())))))
        assertNull(decodeIndex("""{"version":1,"next":0,"coins":[]}"""))
        assertNull(decodeIndex("""{"next":1,"coins":[]}"""))
        assertNull(decodeIndex("""{"version":1,"next":1,"coins":["aa"]}"""))
        assertNull(decodeIndex("[]"))
        assertNull(decodeIndex(null))
    }
}

class CoinIndexTest {
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val coinCode = Contracts.codeHash(ContractName.Coin)

    /** A chain where block `h` of `deployments` deploys the contract filled with `h`; those in `coins` are coins. */
    private fun node(height: Long, deployments: List<Long>, coins: List<Long>) = FakeNode().apply {
        this.height = height
        for (h in deployments) receipts[h] = listOf(BlockReceipt(0, filled(h.toInt()), emptyList(), emptyList()))
        for (h in coins) code[filled(h.toInt()).hex()] = coinCode
        // A program that is not a coin.
        code[filled(0xfe).hex()] = "00".repeat(32)
    }

    @Test
    fun `finds the coins deployed, and starts the next time where it stopped`() = runBlocking {
        val node = node(250, deployments = listOf(3, 120, 200), coins = listOf(3, 200))
        val storage = MemoryStorage()
        val index = CoinIndexes({ node }, MutableStateFlow(null), storage, work).indexOf("cc")
        index.sync(250).await()
        assertEquals(listOf(filled(3).hex(), filled(200).hex()), index.state.value.saved.coins)
        assertEquals(LastBlock(250, "b250"), index.state.value.saved.last)
        // Each candidate is checked once against the node; a later sync only checks the last block read.
        node.height = 260
        node.asked.clear()
        val again = CoinIndexes({ node }, MutableStateFlow(null), storage, work).indexOf("cc")
        again.sync(260).await()
        assertEquals(listOf("blocks:250", "blocks:251"), node.asked)
        assertEquals(261L, again.state.value.saved.next)
        assertEquals(2, again.state.value.saved.coins.size)
    }

    @Test
    fun `reads again below a block that was replaced`() = runBlocking {
        val node = node(50, deployments = listOf(45), coins = listOf(45, 48))
        val index = CoinIndexes({ node }, MutableStateFlow(null), MemoryStorage(), work).indexOf("cc")
        index.sync(50).await()
        assertEquals(listOf(filled(45).hex()), index.state.value.saved.coins)
        node.ids[50] = "x50"
        node.receipts[48] = listOf(BlockReceipt(0, filled(48), emptyList(), emptyList()))
        node.asked.clear()
        index.sync(50).await()
        assertEquals("blocks:31", node.asked[1])
        assertEquals(listOf(filled(45).hex(), filled(48).hex()), index.state.value.saved.coins)
        assertEquals(LastBlock(50, "x50"), index.state.value.saved.last)
    }

    @Test
    fun `stops when a node answers with blocks it did not ask for, and says why a read failed`() = runBlocking {
        val lying = object : FakeNode() {
            override suspend fun blocks(from: Long, limit: Int): List<BlockReceipts> {
                asked.add("blocks:$from")
                return listOf(BlockReceipts("b1", 1, ByteArray(0), emptyList(), emptyList()))
            }
        }
        val index = CoinIndexes({ lying }, MutableStateFlow(null), MemoryStorage(), work).indexOf("cc")
        withTimeout(5_000) { index.sync(50).await() }
        assertTrue(lying.asked.size < 5)
        val failing = object : FakeNode() {
            override suspend fun blocks(from: Long, limit: Int): List<BlockReceipts> = throw NodeError("no blocks here")
        }
        val view = CoinIndexes({ failing }, MutableStateFlow(10L), MemoryStorage(), work).index("dd")
        assertEquals("no blocks here", withTimeout(5_000) { view.first { it.error != null } }.error)
    }
}
