// Finding the identities that list a key, for a restore from a recovery
// phrase (RECOVERY.md). An identity's ID hashes its whole creation, guardians
// included, so the words alone cannot give it: the app asks a node for the
// registry's Created events, one per identity, then reads each identity's
// current keys. Events come from any node; the keys are read with proofs, so
// a node can hide an identity but never invent one. Anyone can list a public
// key in an identity of their own, so each found one comes with the block it
// was created in, the earliest first: a key is public only once an identity
// lists it, so the one created first is normally the phrase's own.
package app.tingid.state

import app.tingid.chain.BlockEvents
import app.tingid.chain.ChainApi
import app.tingid.chain.Id
import app.tingid.chain.NodeError
import app.tingid.chain.StorageRead
import app.tingid.chain.equal
import app.tingid.chain.hex

/** Blocks with matching events per request, the node's most. */
private const val EVENTS_PAGE = 100

/** Keys an identity's core may count at most to be read: far more than any identity has (as [fetchIdentity] has it). */
private const val MAX_KEYS = 4096L

/** The IDs the registry created in these blocks. */
fun createdIds(blocks: List<BlockEvents>, registry: ByteArray): List<ByteArray> {
    val out = ArrayList<ByteArray>()
    for (b in blocks) {
        for (e in b.events) {
            if (!equal(e.contract, registry)) continue
            val event = Id.decodeEvent(e.payload)
            if (event is Id.Event.Created) out.add(event.id)
        }
    }
    return out
}

/**
 * Which of `ids` list `key` in their current key epoch: two batch reads in all.
 * A core counting more keys than any identity has is not read further: the
 * count is the node's word, and it would set how much is read.
 */
suspend fun identitiesWithKey(node: ChainApi, registry: ByteArray, key: Id.Key, ids: List<ByteArray>): List<ByteArray> {
    if (ids.isEmpty()) return emptyList()
    val cores = node.storageMany(ids.map { StorageRead(registry, Id.coreKey(it)) })
    val reads = ArrayList<StorageRead>()
    val owners = ArrayList<Int>()
    ids.forEachIndexed { i, id ->
        val core = cores[i]?.let { Id.decodeCore(it) } ?: return@forEachIndexed
        if (core.nextKeyId > MAX_KEYS) return@forEachIndexed
        for (k in 0 until core.nextKeyId) {
            reads.add(StorageRead(registry, Id.keyKey(id, core.keyEpoch, k)))
            owners.add(i)
        }
    }
    val records = if (reads.isEmpty()) emptyList() else node.storageMany(reads)
    val hits = HashSet<Int>()
    records.forEachIndexed { j, bytes ->
        val record = bytes?.let { Id.decodeKeyRecord(it) }
        if (record != null && equal(record.policy, key.policy) && equal(record.params, key.params)) hits.add(owners[j])
    }
    return ids.filterIndexed { i, _ -> i in hits }
}

/** An identity found to list a key, and the block of its Created event: its height and time (seconds). */
class FoundIdentity(val id: ByteArray, val height: Long, val time: Long)

/**
 * The identities on the chain that list `key`, the earliest created first;
 * `progress` hears the height read up to and the chain's height.
 */
suspend fun findIdentitiesWithKey(
    node: ChainApi,
    registry: ByteArray,
    key: Id.Key,
    progress: ((height: Long, tip: Long) -> Unit)? = null,
): List<FoundIdentity> {
    val ids = LinkedHashMap<String, FoundIdentity>()
    var from = 1L
    while (true) {
        val page = node.contractEvents(registry, Id.CREATED_TOPIC, from, EVENTS_PAGE)
        for (b in page.blocks) for (id in createdIds(listOf(b), registry)) ids.getOrPut(id.hex()) { FoundIdentity(id, b.height, b.time) }
        progress?.invoke(minOf(page.next - 1, page.height), page.height)
        if (page.next > page.height) break
        if (page.next <= from) throw NodeError("the node gave an events page that does not move on")
        from = page.next
    }
    val listing = identitiesWithKey(node, registry, key, ids.values.map { it.id }).map { it.hex() }.toSet()
    return ids.values.filter { it.id.hex() in listing }.sortedBy { it.height }
}
