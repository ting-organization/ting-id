// The saved state of the coin index (CoinIndex.kt): how far it has read the
// chain and the coins it found. Pure, so it can be tested on its own.
package app.tingid.state

import app.tingid.chain.BlockReceipts
import app.tingid.chain.hex
import app.tingid.chain.isHex
import kotlinx.serialization.Serializable

/** A block by height and id. */
@Serializable
data class LastBlock(val height: Long, val id: String)

@Serializable
data class SavedIndex(
    val version: Int,
    /** The next block to read. */
    val next: Long,
    /** The last block read, to notice when a reorganization replaced it. */
    val last: LastBlock? = null,
    /** Coins found, hex ContractIds, in the order they were deployed. */
    val coins: List<String>,
)

fun emptyIndex(): SavedIndex = SavedIndex(version = 1, next = 1, last = null, coins = emptyList())

/** Whether a saved index is one of this shape: of this version, past block 0, listing ContractIds. */
fun isSavedIndex(s: SavedIndex?): Boolean = s != null && s.version == 1 && s.next >= 1 && s.coins.all { isHex(it, 32) }

fun encodeIndex(s: SavedIndex): String = Store.json.encodeToString(SavedIndex.serializer(), s)

/** A saved index, or null when the text is not one: a damaged index is read again from the start. */
fun decodeIndex(text: String?): SavedIndex? = try {
    text?.let { Store.json.decodeFromString(SavedIndex.serializer(), it) }?.takeIf { isSavedIndex(it) }
} catch (e: Exception) {
    null
}

/**
 * Results that may be a deployment's ContractId: those of 32 bytes of
 * successful transactions, in block order. A call can return 32 bytes too, so
 * each is checked against the node before it counts.
 */
fun candidates(blocks: List<BlockReceipts>): List<String> {
    val out = ArrayList<String>()
    for (b in blocks) for (r in b.receipts) if (r.status == 0 && r.result.size == 32) out.add(r.result.hex())
    return out
}

/** Reads `depth` blocks again below the last one read, after a reorganization replaced it or the chain got shorter. */
fun rewind(s: SavedIndex, depth: Long, height: Long = Long.MAX_VALUE): SavedIndex {
    val from = minOf(s.last?.height ?: (s.next - 1), height)
    return s.copy(next = maxOf(1, from - depth + 1), last = null)
}

/** The index after reading `blocks` (in order, from `s.next`), with the coins among `found` added. */
fun advance(s: SavedIndex, blocks: List<BlockReceipts>, found: List<String>): SavedIndex {
    if (blocks.isEmpty()) return s
    val last = blocks.last()
    val coins = ArrayList(s.coins)
    for (c in found) if (c !in coins) coins.add(c)
    return s.copy(next = last.height + 1, last = LastBlock(last.height, last.id), coins = coins)
}
