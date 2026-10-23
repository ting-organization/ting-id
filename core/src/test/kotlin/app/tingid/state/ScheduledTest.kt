// Reading an account's scheduled actions: every pending one is found, however
// many later ones were scheduled after it.
package app.tingid.state

import app.tingid.chain.BlockEvents
import app.tingid.chain.BlockReceipts
import app.tingid.chain.ChainApi
import app.tingid.chain.Coin
import app.tingid.chain.ContractManifest
import app.tingid.chain.EventPage
import app.tingid.chain.NodeStatus
import app.tingid.chain.StorageRead
import app.tingid.chain.TransactionLookup
import app.tingid.chain.Writer
import app.tingid.chain.hex
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class ScheduledTest {
    private val coinId = ByteArray(32) { 0xc0.toByte() }
    private val account = ByteArray(32) { 0xa1.toByte() }
    private val thief = ByteArray(32) { 0x7f }

    /** A scheduled transfer as the coin stores it. */
    private fun transfer(reserved: Long) = Writer().u64(2_000_000_000L).u128(BigInteger.valueOf(reserved)).u128(BigInteger.ZERO).u8(1).put(thief)
        .u128(BigInteger.valueOf(reserved)).finish()

    /** A minter change (op 4), which reserves only its tip. */
    private fun minterChange() = Writer().u64(2_000_000_000L).u128(BigInteger.ZERO).u128(BigInteger.ZERO).u8(4).put(thief).finish()

    /** A node holding `records` (sequence → bytes) for the account, counting what it is asked. */
    private class Node(records: Map<Long, ByteArray>, account: ByteArray) : ChainApi {
        private val keys = records.entries.associate { (seq, bytes) -> Coin.scheduledKey(account, seq).hex() to bytes }
        var reads = 0
        var requests = 0
        override val url = "test"
        override suspend fun storageMany(reads: List<StorageRead>): List<ByteArray?> {
            requests++
            this.reads += reads.size
            return reads.map { keys[it.key.hex()] }
        }
        override suspend fun status(): NodeStatus = error("not asked")
        override suspend fun storage(contract: ByteArray, key: ByteArray): ByteArray? = error("not asked")
        override suspend fun storageFixed(reads: List<StorageRead>): List<ByteArray?> = error("not asked")
        override suspend fun contract(id: ByteArray): ContractManifest? = error("not asked")
        override suspend fun contractExists(id: ByteArray): Boolean = error("not asked")
        override suspend fun submit(tx: ByteArray, notBefore: Long?): String = error("not asked")
        override suspend fun transaction(id: String): TransactionLookup? = error("not asked")
        override suspend fun blocks(from: Long, limit: Int): List<BlockReceipts> = error("not asked")
        override suspend fun events(from: Long, limit: Int): List<BlockEvents> = error("not asked")
        override suspend fun contractEvents(contract: ByteArray, topic: ByteArray?, from: Long, limit: Int): EventPage = error("not asked")
    }

    private fun query(nonce: Long, locked: Long, everything: Boolean = false) = ScheduledQuery(coinId, account, nonce, BigInteger.valueOf(locked), everything)

    @Test
    fun `are all found, even behind more than a first look of later ones`() = runBlocking {
        // The real withdrawal at 10, then 41 small ones: "cancel all" must see the first.
        val records = HashMap<Long, ByteArray>()
        records[10] = transfer(1_000)
        for (seq in 50L until 91L) records[seq] = transfer(1)
        val (found) = fetchScheduledMany(Node(records, account), listOf(query(100, 1_041)))
        assertTrue(found.map { it.sequence }.contains(10L))
        assertEquals(42, found.size)
    }

    @Test
    fun `cost nothing to read for an account with nothing locked`() = runBlocking {
        val node = Node(emptyMap(), account)
        val (found) = fetchScheduledMany(node, listOf(query(5_000, 0)))
        assertEquals(emptyList<ScheduledItem>(), found)
        assertEquals(0, node.requests)
    }

    @Test
    fun `stop once what they reserve adds up to the locked amount`() = runBlocking {
        val node = Node(mapOf(3_998L to transfer(5)), account)
        val (found) = fetchScheduledMany(node, listOf(query(4_000, 5)))
        assertEquals(listOf(3_998L), found.map { it.sequence })
        assertEquals(40, node.reads)
    }

    @Test
    fun `are read back to the first for a minter, whose minter changes may reserve nothing`() = runBlocking {
        val (found) = fetchScheduledMany(Node(mapOf(2L to minterChange()), account), listOf(query(3_000, 0, everything = true)))
        assertEquals(listOf(2L), found.map { it.sequence })
    }
}
