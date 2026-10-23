// Client for the node's REST API (wiki: start/explore#the-rest-api).
@file:UseSerializers(HexSerializer::class, BigIntegerSerializer::class)

package app.tingid.chain

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.math.BigInteger

@Serializable
data class NodeStatus(
    val chainId: ByteArray,
    val height: Long,
    val limits: List<Long>,
    val tip: String,
    /** The chain's total work up to the tip. */
    val work: BigInteger,
    val mempoolCount: Long,
    val name: String,
)

class ReceiptEvent(val contract: ByteArray, val payload: ByteArray, val transaction: String? = null)

class Receipt(val status: Int, val statusName: String, val used: List<Long>, val result: ByteArray, val events: List<ReceiptEvent>)

class Occurrence(val height: Long, val receipt: Receipt)

/** A transaction the node holds until it is due and succeeds (wiki: start/explore#the-rest-api, deferred submission). */
class Deferred(
    val notBefore: Long,
    val expires: Long,
    /** Executed successfully on the tip; in the pool awaiting a block. */
    val admitted: Boolean,
    /** Why the last execution on the tip failed, as a receipt status name. */
    val lastResult: String?,
)

class TransactionLookup(val pending: Boolean, val deferred: Deferred?, val occurrences: List<Occurrence>)

class BlockEvents(
    /** The block's id, to notice when a reorganization replaced it. */
    val block: String,
    val height: Long,
    /** The block's timestamp, in seconds. */
    val time: Long,
    val events: List<ReceiptEvent>,
)

/** A page of one program's events: the blocks with matching events, from a height on. */
class EventPage(
    val blocks: List<BlockEvents>,
    /** Where the next page starts: past `height` when there is nothing more. */
    val next: Long,
    /** The canonical height the page was read at. */
    val height: Long,
)

/** A block's receipts, as far as a scan for deployments needs them. */
class BlockReceipts(
    val id: String,
    val height: Long,
    /** The producer data its miner put in it, which names the payout account (wiki: programs/conventions). */
    val producer: ByteArray,
    /** The block's capacity per resource, C / S / D / G. */
    val limits: List<Long>,
    /** Each transaction's status (0 for success) and result (a deployment's is its ContractId), what it used, and its events. */
    val receipts: List<BlockReceipt>,
)

class BlockReceipt(val status: Int, val result: ByteArray, val used: List<Long>, val events: List<ReceiptEvent>)

/** A node's answer, or no answer: `status` is the HTTP status, absent when the node could not be reached. */
open class NodeError(message: String, val status: Int? = null) : Exception(message)

/** A node without a call the app needs, such as proved storage reads. */
class NodeOutdated(message: String, status: Int? = null) : NodeError(message, status)

/** The node gave no answer: it was not reached, or stopped answering. */
class NoAnswer(message: String) : NodeError(message)

/**
 * A transaction was posted but no answer came, so the node may have it. It
 * is kept for sending again, with the same bytes, like any submission
 * (state/pending), and must never be signed anew.
 */
class MaybeSent(
    val txId: String,
    /** For a coin action: its account and nonce. */
    val action: Pair<ByteArray, Long>? = null,
) : NodeError("no answer from the node: it may have the transaction")

/** A read whose answer would be too large for one response: asked again in smaller parts. */
class AnswerTooLarge(message: String, status: Int?) : NodeError(message, status)

/** Whether another node may answer where this one failed: it was not reached, or failed itself, rather than refusing the request. */
fun nodeFailed(e: Throwable): Boolean {
    if (e !is NodeError) return true
    val s = e.status
    return e is NodeOutdated || s == null || s >= 500 || s == 429
}

/** A record's Merkle proof against the state root (wiki: data/state-tree#proofs). */
class StorageProof(
    /** Where the key's path ends: its own leaf, an empty subtree, or another record's leaf. */
    val end: ProofEnd,
    /** For [ProofEnd.Other]: that record's key; its value is in the answer's `others`. */
    val otherKey: ByteArray?,
    /** The other child's hash at each level, from the end of the path up to the root. */
    val siblings: List<ByteArray>,
)

enum class ProofEnd { Present, Empty, Other }

/** Records read together, with their proofs, the state root they were read from and the canonical tip it belongs to. */
class ProvedReads(
    val values: List<ByteArray?>,
    val proofs: List<StorageProof>,
    /** The values of the other records the proofs end at, by record key (hex), each listed once. */
    val others: Map<String, ByteArray>,
    val stateRoot: ByteArray,
    val tip: String,
)

/** One record to read: a contract's storage key. */
class StorageRead(val contract: ByteArray, val key: ByteArray)

class ContractManifest(val codeHash: String, val codeBytes: Long)

/** Size of an encoded block header (wiki: data/blocks). */
const val HEADER_BYTES = 294

/** Records the node reads in one batch request at most. */
const val STORAGE_BATCH = 1024

/** What the app asks of the chain: one node ([NodeClient]) or several used as one ([NodePool]). */
interface ChainApi {
    /** Names what answers: a node's address, or the pool's addresses. */
    val url: String
    suspend fun status(): NodeStatus
    suspend fun storage(contract: ByteArray, key: ByteArray): ByteArray?
    suspend fun storageMany(reads: List<StorageRead>): List<ByteArray?>

    /** Records that never change once written, such as a coin's configuration: with several nodes, every one that has a record must give the same. */
    suspend fun storageFixed(reads: List<StorageRead>): List<ByteArray?>
    suspend fun contract(id: ByteArray): ContractManifest?
    suspend fun contractExists(id: ByteArray): Boolean
    suspend fun submit(tx: ByteArray, notBefore: Long? = null): String
    suspend fun transaction(id: String): TransactionLookup?
    suspend fun blocks(from: Long, limit: Int = 100): List<BlockReceipts>
    suspend fun events(from: Long, limit: Int = 100): List<BlockEvents>

    /** The events of `contract`, of one topic (the payload's first 32 bytes) when given, page by page. */
    suspend fun contractEvents(contract: ByteArray, topic: ByteArray?, from: Long, limit: Int = 100): EventPage
}

/** Told of every transaction a node accepted: its id, bytes and deferral, so the app can keep it until a block includes it. */
fun interface SubmitListener {
    fun submitted(txId: String, tx: ByteArray, notBefore: Long?)
}

class NodeClient(
    override val url: String,
    private val http: Http = UrlConnectionHttp,
    private val listener: () -> SubmitListener? = { null },
) : ChainApi {
    /** The chain of the node's last status: transaction ids are computed for it. */
    @Volatile
    private var chainId: ByteArray? = null

    /** Whether the node answers batch reads; unknown until the first one. */
    @Volatile
    private var batchReads: Boolean? = null

    private suspend fun request(path: String, method: String = "GET", body: JsonObject? = null): JsonObject? {
        val response = try {
            http.exchange(
                HttpRequest(
                    url = url.trimEnd('/') + path,
                    method = method,
                    body = body?.toString()?.encodeToByteArray(),
                    headers = buildMap {
                        put("accept", "application/json")
                        if (body != null) put("content-type", "application/json")
                    },
                ),
            )
        } catch (e: HttpFailure) {
            throw NoAnswer(if (e.timedOut) "the node at $url stopped answering" else "cannot reach the node at $url")
        }
        val json = try {
            parseJson(response.text())
        } catch (e: BadJson) {
            throw NodeError("unexpected reply from the node (${response.status})", response.status)
        }
        if (!response.ok) {
            if (response.status == 404) return null
            val error = (json as? JsonObject)?.let { runCatching { it.strOrNull("error") }.getOrNull() }
            throw NodeError(error ?: "node error ${response.status}", response.status)
        }
        if (json == null) return null
        return json as? JsonObject ?: throw NodeError("unexpected reply from the node", response.status)
    }

    /** Reads an answer; a field of the wrong kind is the node's fault, not the app's. */
    private inline fun <T> reading(read: () -> T): T = try {
        read()
    } catch (e: BadJson) {
        throw NodeError("unexpected reply from the node: ${e.message}")
    } catch (e: IllegalArgumentException) {
        throw NodeError("unexpected reply from the node")
    }

    override suspend fun status(): NodeStatus {
        val s = request("/v1/status") ?: throw NodeError("the node gave no status")
        return reading {
            val chain = s.hex("chain_id")
            if (chain.size != 32) throw BadJson("chain_id is not 32 bytes")
            chainId = chain
            NodeStatus(
                chainId = chain,
                height = s.long("height"),
                limits = s.longs("limits"),
                tip = s.str("tip"),
                work = s.str("work").toBigIntegerOrNull()?.takeIf { it.signum() >= 0 } ?: throw BadJson("work is not a number"),
                mempoolCount = s.longOrNull("mempool_count") ?: 0,
                name = s.strOrNull("name") ?: "",
            )
        }
    }

    /** A contract's current storage record, or null when absent. */
    override suspend fun storage(contract: ByteArray, key: ByteArray): ByteArray? {
        val v = request("/v1/contracts/${contract.hex()}/storage/${key.hex()}") ?: return null
        return reading { v.hexOrNull("value_hex") }
    }

    /** One node is the only word on any record. */
    override suspend fun storageFixed(reads: List<StorageRead>): List<ByteArray?> = storageMany(reads)

    /**
     * Many records at once (`POST /v1/storage`), in the order asked, null where
     * absent. The node reads up to 1,024 from one state, so the records of one
     * request agree with each other; more go in several requests. A node without
     * the call is read one record at a time.
     */
    override suspend fun storageMany(reads: List<StorageRead>): List<ByteArray?> {
        val out = ArrayList<ByteArray?>(reads.size)
        for (chunk in reads.chunked(STORAGE_BATCH)) {
            val values = if (batchReads == false) null else readBatch(chunk)
            out.addAll(values ?: coroutineScope { chunk.map { r -> async { storage(r.contract, r.key) } }.awaitAll() })
        }
        return out
    }

    private fun readsJson(reads: List<StorageRead>, proof: Boolean) = buildJsonObject {
        putJsonArray("reads") {
            for (r in reads) add(buildJsonObject {
                put("contract", r.contract.hex())
                put("key", r.key.hex())
            })
        }
        if (proof) put("proof", true)
    }

    private suspend fun readBatch(reads: List<StorageRead>): List<ByteArray?>? {
        val v = try {
            request("/v1/storage", "POST", readsJson(reads, proof = false))
        } catch (e: NodeError) {
            // A node from before the call refuses the method; anything else is a real failure.
            if (batchReads == null && (e.status == 405 || e.message?.contains("method", ignoreCase = true) == true)) null else throw e
        }
        val values = v?.let { reading { it.arrOrNull("values") } }
        if (values == null || values.size != reads.size) {
            if (batchReads == null) batchReads = false
            return null
        }
        batchReads = true
        return reading { values.map { (it as? JsonObject)?.hexOrNull("value_hex") } }
    }

    /**
     * Up to [STORAGE_BATCH] records with their Merkle proofs, all from one state.
     * A node without proved batch reads is outdated for the app.
     */
    suspend fun storageProved(reads: List<StorageRead>): ProvedReads {
        val v = try {
            request("/v1/storage", "POST", readsJson(reads, proof = true))
        } catch (e: NodeError) {
            val m = e.message ?: ""
            if (e.status == 400 && m.contains("size limit", ignoreCase = true)) throw AnswerTooLarge(m, e.status)
            if (e.status == 405 || (e.status == 400 && m.contains("unknown field", ignoreCase = true))) {
                throw NodeOutdated("the node gives no proofs; it needs an update", e.status)
            }
            throw e
        }
        val outdated = NodeOutdated("the node gives no proofs; it needs an update")
        val items = v?.let { reading { it.arrOrNull("values") } } ?: throw outdated
        if (items.size != reads.size || reading { v.strOrNull("state_root") } == null || reading { v.strOrNull("tip") } == null) throw outdated
        val objects = items.map { it as? JsonObject ?: throw outdated }
        if (objects.any { it["proof"] !is JsonObject }) throw outdated
        // Proofs that carry the other record's leaf hash cannot show an absence (wiki: data/state-tree#proofs).
        if (objects.any { it.objOrNull("proof")!!.containsKey("other_leaf") }) throw NodeOutdated("the node gives proofs in an older form; it needs an update")
        return reading {
            ProvedReads(
                values = objects.map { it.hexOrNull("value_hex") },
                proofs = objects.map {
                    val p = it.objOrNull("proof")!!
                    StorageProof(
                        end = when (p.strOrNull("end")) {
                            "present" -> ProofEnd.Present
                            "empty" -> ProofEnd.Empty
                            "other" -> ProofEnd.Other
                            else -> throw BadJson("a proof ends nowhere known")
                        },
                        otherKey = p.hexOrNull("other_key"),
                        siblings = (p.arrOrNull("siblings") ?: JsonArray(emptyList())).map { s ->
                            val h = (s as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw BadJson("a sibling is not hex")
                            if (!isHex(h)) throw BadJson("a sibling is not hex")
                            unhex(h)
                        },
                    )
                },
                others = (v.objOrNull("others") ?: JsonObject(emptyMap())).entries.associate { (k, value) ->
                    val h = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw BadJson("another record is not hex")
                    if (!isHex(h) || !isHex(k)) throw BadJson("another record is not hex")
                    k.lowercase() to unhex(h)
                },
                stateRoot = v.hex("state_root"),
                tip = v.str("tip"),
            )
        }
    }

    /** The encoded header of block `id` (hex), null when the node does not have it. */
    suspend fun header(id: String): ByteArray? {
        require(isHex(id, 32)) { "a block id is 64 hex digits" }
        val v = request("/v1/blocks/$id/raw?offset=0&length=$HEADER_BYTES") ?: return null
        return reading { v.hexOrNull("hex") }
    }

    override suspend fun contractExists(id: ByteArray): Boolean = contract(id) != null

    /** A contract's manifest: the hash of the code it runs. */
    override suspend fun contract(id: ByteArray): ContractManifest? {
        val v = request("/v1/contracts/${id.hex()}") ?: return null
        return reading { v.strOrNull("code_hash")?.let { ContractManifest(it, v.longOrNull("code_bytes") ?: 0) } }
    }

    /**
     * Submits a canonical transaction; returns its id (hex). With `notBefore`
     * the node defers it: holds it until that time and includes it once it
     * executes successfully. The id is computed here, and the node must name
     * the same.
     */
    override suspend fun submit(tx: ByteArray, notBefore: Long?): String {
        val id = transactionId(chainId ?: status().chainId, tx).hex()
        val body = buildJsonObject {
            put("hex", tx.hex())
            if (notBefore != null) put("not_before", notBefore)
        }
        val r = try {
            request("/v1/transactions", "POST", body)
        } catch (e: NoAnswer) {
            // It may have arrived: kept like a submission, to be sent again as it is.
            listener()?.submitted(id, tx, notBefore)
            throw MaybeSent(id)
        }
        val named = r?.let { reading { it.strOrNull("id") } } ?: throw NodeError("the node did not return a transaction id")
        if (named != id) throw NodeError("the node named another transaction id than the transaction’s")
        listener()?.submitted(id, tx, notBefore)
        return id
    }

    override suspend fun transaction(id: String): TransactionLookup? {
        require(isHex(id, 32)) { "a transaction id is 64 hex digits" }
        val v = request("/v1/transactions/$id") ?: return null
        return reading {
            val occurrences = (v.arrOrNull("occurrences") ?: JsonArray(emptyList())).objects().map { o ->
                val rc = o.objOrNull("receipt") ?: throw BadJson("an occurrence has no receipt")
                Occurrence(
                    height = o.long("height"),
                    receipt = Receipt(
                        status = rc.int("status"),
                        statusName = rc.strOrNull("status_name") ?: "",
                        used = rc.longs("used"),
                        result = rc.hexOrNull("result_hex") ?: ByteArray(0),
                        events = eventsOf(rc.arrOrNull("events")),
                    ),
                )
            }
            val deferred = v.objOrNull("deferred")?.let { d ->
                Deferred(
                    notBefore = d.longOrNull("not_before") ?: 0,
                    expires = d.longOrNull("expires") ?: 0,
                    admitted = d.boolOrNull("admitted") == true,
                    lastResult = d.strOrNull("last_result"),
                )
            }
            TransactionLookup(pending = v.boolOrNull("pending") == true, deferred = deferred, occurrences = occurrences)
        }
    }

    /** Canonical blocks `from` onward (1-based), at most `limit` (the node allows 100), with their receipts. */
    override suspend fun blocks(from: Long, limit: Int): List<BlockReceipts> {
        val v = request("/v1/blocks?from=$from&limit=$limit") ?: return emptyList()
        return reading {
            (v.arrOrNull("items") ?: JsonArray(emptyList())).objects().map { b ->
                BlockReceipts(
                    id = b.str("id"),
                    height = b.long("height"),
                    producer = b.hexOrNull("producer_hex") ?: ByteArray(0),
                    limits = b.longs("limits"),
                    receipts = (b.arrOrNull("receipts") ?: JsonArray(emptyList())).objects().map { r ->
                        BlockReceipt(
                            status = r.int("status"),
                            result = r.hexOrNull("result_hex") ?: ByteArray(0),
                            used = r.longs("used"),
                            events = eventsOf(r.arrOrNull("events")),
                        )
                    },
                )
            }
        }
    }

    /** Events of canonical blocks `from` onward (1-based), at most `limit` blocks. */
    override suspend fun events(from: Long, limit: Int): List<BlockEvents> {
        val v = request("/v1/events?from=$from&limit=$limit") ?: return emptyList()
        return reading { blockEvents(v.arrOrNull("items")) }
    }

    /** `limit` counts blocks with matching events; blocks without any are left out. */
    override suspend fun contractEvents(contract: ByteArray, topic: ByteArray?, from: Long, limit: Int): EventPage {
        val v = try {
            request("/v1/events?contract=${contract.hex()}${topic?.let { "&topic=${it.hex()}" } ?: ""}&from=$from&limit=$limit")
        } catch (e: NodeError) {
            if (e.status == 400 && e.message?.contains("unknown field `contract`") == true) throw NodeOutdated("the node cannot read events by program")
            throw e
        }
        return reading {
            val page = v ?: throw BadJson("no page")
            EventPage(blocks = blockEvents(page.arrOrNull("items")), next = page.long("next"), height = page.long("height"))
        }
    }

    private fun eventsOf(items: JsonArray?): List<ReceiptEvent> = (items ?: JsonArray(emptyList())).objects().map { e ->
        ReceiptEvent(contract = e.hex("contract"), payload = e.hex("payload_hex"), transaction = e.strOrNull("transaction"))
    }

    private fun blockEvents(items: JsonArray?): List<BlockEvents> = (items ?: JsonArray(emptyList())).objects().map { b ->
        BlockEvents(block = b.str("block"), height = b.long("height"), time = b.longOrNull("timestamp") ?: 0, events = eventsOf(b.arrOrNull("events")))
    }
}
