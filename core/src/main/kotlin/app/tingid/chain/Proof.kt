// Checking a node's answers against what the chain commits to: a storage value
// against the state root by its Merkle proof (wiki: data/state-tree), and a
// block header against the block's id (wiki: data/blocks). A node can then only
// answer what the block it names holds; which block is the chain's is for the
// other nodes to confirm (NodePool).
package app.tingid.chain

/** The state record of storage key `key` of contract `contract` (namespace 2, wiki: data/records). */
fun recordKey(contract: ByteArray, key: ByteArray): ByteArray = taggedHash("state-key", byteArrayOf(2), contract, key)

private val EMPTY = taggedHash("state-empty")

private fun leaf(key: ByteArray, value: ByteArray): ByteArray = taggedHash("state-leaf", key, Writer().bytes(value).finish())

private fun node(depth: Int, left: ByteArray, right: ByteArray): ByteArray = taggedHash("state-node", Writer().u16(depth).finish(), left, right)

/** Bit `depth` of a key, from the most significant bit of its first byte. */
private fun bit(key: ByteArray, depth: Int): Int = ((key[depth shr 3].toInt() and 0xff) shr (7 - (depth and 7))) and 1

/**
 * Whether `proof` shows record `key` to hold `value`, or with null to be
 * absent, in the state whose root is `root`. A proof that ends at another
 * record's leaf takes that record's value from `others` (record key, hex) and
 * the leaf is computed here, so a node cannot pass one record's leaf off as
 * another's.
 */
fun verifyRecord(root: ByteArray, key: ByteArray, value: ByteArray?, proof: StorageProof, others: Map<String, ByteArray>): Boolean {
    val depth = proof.siblings.size
    if (key.size != 32 || depth > 256 || proof.siblings.any { it.size != 32 }) return false
    var hash = when {
        proof.end == ProofEnd.Present && value != null -> leaf(key, value)
        proof.end == ProofEnd.Empty && value == null -> EMPTY
        proof.end == ProofEnd.Other && value == null && proof.otherKey?.size == 32 -> {
            // Another record's leaf ends the path: its key must differ and share the path so far.
            val other = proof.otherKey
            val otherValue = others[other.hex()]
            if (otherValue == null || equal(other, key)) return false
            for (d in 0 until depth) if (bit(other, d) != bit(key, d)) return false
            leaf(other, otherValue)
        }
        else -> return false
    }
    proof.siblings.forEachIndexed { i, sibling ->
        val d = depth - 1 - i
        hash = if (bit(key, d) == 1) node(d, sibling, hash) else node(d, hash, sibling)
    }
    return equal(hash, root)
}

/** What the app reads from a block header. */
class Header(
    val chainId: ByteArray,
    val height: Long,
    /** Seconds. */
    val timestamp: Long,
    /** The proof-of-work target, 32 bytes big-endian. */
    val target: ByteArray,
    val stateRoot: ByteArray,
)

/**
 * The header `bytes` if they are the header of block `id` (hex) and carry its
 * proof of work: the id, read as a 256-bit number, at most the target, the
 * target not zero and, with `powLimit` (hex, the chain's), at most that
 * (wiki: consensus). Else null.
 */
fun checkHeader(id: String, bytes: ByteArray, powLimit: String? = null): Header? {
    if (bytes.size != HEADER_BYTES) return null
    val blockId = taggedHash("block", bytes).hex()
    if (blockId != id.lowercase()) return null
    // protocol_version u16, chain_id, height u64, parent, timestamp u64, target, transactions_root, receipts_root, state_root, …
    val target = bytes.copyOfRange(82, 114)
    // Equal-length lowercase hex compares as the numbers do.
    val targetHex = target.hex()
    if (targetHex.all { it == '0' } || blockId > targetHex) return null
    if (powLimit != null && targetHex > powLimit.lowercase()) return null
    val r = Reader(bytes)
    r.take(34)
    val height = r.u64()
    r.take(32)
    val timestamp = r.u64()
    return Header(chainId = bytes.copyOfRange(2, 34), height = height, timestamp = timestamp, target = target, stateRoot = bytes.copyOfRange(178, 210))
}
