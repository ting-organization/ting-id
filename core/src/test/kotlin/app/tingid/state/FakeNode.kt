// A chain in memory for the tests of what reads blocks and records.
package app.tingid.state

import app.tingid.chain.BlockEvents
import app.tingid.chain.BlockReceipt
import app.tingid.chain.BlockReceipts
import app.tingid.chain.ChainApi
import app.tingid.chain.ContractManifest
import app.tingid.chain.EventPage
import app.tingid.chain.ReceiptEvent
import app.tingid.chain.StorageRead
import app.tingid.chain.TransactionLookup
import app.tingid.chain.hex
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.Collections

/** Blocks 1 to `height` with their events and receipts, records, and the code of contracts. */
internal open class FakeNode : ChainApi {
    override val url = "fake"

    @Volatile
    var height = 0L
    val events = HashMap<Long, List<ReceiptEvent>>()
    val receipts = HashMap<Long, List<BlockReceipt>>()

    /** Block ids other than `b<height>`, as after a reorganization. */
    val ids = HashMap<Long, String>()
    val records = HashMap<String, ByteArray>()

    /** Code hash by contract (hex). */
    val code = HashMap<String, String>()
    val asked: MutableList<String> = Collections.synchronizedList(ArrayList())

    fun blockId(h: Long) = ids[h] ?: "b$h"
    fun timeOf(h: Long) = 1_792_000_000 + h * 60
    fun record(contract: ByteArray, key: ByteArray, value: ByteArray) {
        records["${contract.hex()}:${key.hex()}"] = value
    }

    private fun heights(from: Long, limit: Int) = from until minOf(from + limit, height + 1)

    override suspend fun status() = error("not asked")
    override suspend fun storage(contract: ByteArray, key: ByteArray): ByteArray? = records["${contract.hex()}:${key.hex()}"]
    override suspend fun storageMany(reads: List<StorageRead>): List<ByteArray?> {
        asked.add("storage:${reads.size}")
        return reads.map { storage(it.contract, it.key) }
    }
    override suspend fun storageFixed(reads: List<StorageRead>): List<ByteArray?> = storageMany(reads)
    override suspend fun contract(id: ByteArray): ContractManifest? {
        asked.add("contract:${id.hex().take(4)}")
        return code[id.hex()]?.let { ContractManifest(it, 0) }
    }
    override suspend fun contractExists(id: ByteArray): Boolean = contract(id) != null
    override suspend fun submit(tx: ByteArray, notBefore: Long?): String = error("not asked")
    override suspend fun transaction(id: String): TransactionLookup? = null
    override suspend fun blocks(from: Long, limit: Int): List<BlockReceipts> {
        asked.add("blocks:$from")
        return heights(from, limit).map { h -> BlockReceipts(blockId(h), h, ByteArray(0), listOf(1, 1, 1, 1), receipts[h] ?: emptyList()) }
    }
    override suspend fun events(from: Long, limit: Int): List<BlockEvents> {
        asked.add("events:$from")
        return heights(from, limit).map { h -> BlockEvents(blockId(h), h, timeOf(h), events[h] ?: emptyList()) }
    }
    override suspend fun contractEvents(contract: ByteArray, topic: ByteArray?, from: Long, limit: Int): EventPage = error("not asked")
}

/** Waits until `check` holds, for what runs on other threads. */
internal suspend fun until(check: () -> Boolean) = withTimeout(5_000) {
    while (!check()) delay(5)
}
