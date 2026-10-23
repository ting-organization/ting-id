// What the state tests run against: a chain answering storage reads from a
// map, and a node's REST API answering transactions, as far as the code under
// test asks.
package app.tingid.state

import app.tingid.chain.BlockEvents
import app.tingid.chain.BlockReceipts
import app.tingid.chain.ChainApi
import app.tingid.chain.ContractManifest
import app.tingid.chain.EventPage
import app.tingid.chain.Http
import app.tingid.chain.HttpRequest
import app.tingid.chain.HttpResponse
import app.tingid.chain.NodeStatus
import app.tingid.chain.StorageRead
import app.tingid.chain.TransactionLookup
import app.tingid.chain.hex
import app.tingid.chain.transactionId
import app.tingid.chain.unhex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigInteger
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** A chain answering storage reads from `records` (key hex → value); anything else is not asked. */
internal class MapChain(val records: Map<String, ByteArray> = emptyMap()) : ChainApi {
    val asked: MutableList<String> = Collections.synchronizedList(ArrayList())
    override val url = "test"
    override suspend fun status(): NodeStatus = error("not asked")
    override suspend fun storage(contract: ByteArray, key: ByteArray): ByteArray? = records[key.hex()]
    override suspend fun storageMany(reads: List<StorageRead>): List<ByteArray?> = reads.map { asked.add(it.key.hex()); records[it.key.hex()] }
    override suspend fun storageFixed(reads: List<StorageRead>): List<ByteArray?> = storageMany(reads)
    override suspend fun contract(id: ByteArray): ContractManifest? = null
    override suspend fun contractExists(id: ByteArray): Boolean = false
    override suspend fun submit(tx: ByteArray, notBefore: Long?): String = error("not asked")
    override suspend fun transaction(id: String): TransactionLookup? = error("not asked")
    override suspend fun blocks(from: Long, limit: Int): List<BlockReceipts> = emptyList()
    override suspend fun events(from: Long, limit: Int): List<BlockEvents> = emptyList()
    override suspend fun contractEvents(contract: ByteArray, topic: ByteArray?, from: Long, limit: Int): EventPage = EventPage(emptyList(), from, from)
}

internal fun jsonResponse(body: JsonElement, status: Int = 200) = HttpResponse(status, body.toString().encodeToByteArray(), mapOf("content-type" to "application/json"))

/**
 * A node's REST API: its status, the transactions it knows (id → lookup JSON;
 * absent, it does not know one) and submissions, which `accept` answers. Every
 * request is noted in `calls` as `METHOD path`.
 */
internal class FakeRestNode(val chainId: ByteArray) : Http {
    val calls: MutableList<String> = Collections.synchronizedList(ArrayList())
    val lookups = ConcurrentHashMap<String, JsonElement>()

    /** Transactions posted, as hex, with their deferral. */
    val posted: MutableList<Pair<String, Long?>> = Collections.synchronizedList(ArrayList())

    /** The answer to a submission; by default the node takes it. */
    @Volatile
    var accept: (ByteArray) -> HttpResponse = { tx -> jsonResponse(buildJsonObject { put("id", transactionId(chainId, tx).hex()) }) }

    /** The blocks from a height on, at most a number of them, as the node gives them. */
    @Volatile
    var blocks: (Long, Int) -> List<JsonElement> = { _, _ -> emptyList() }

    /** The answer to anything else the test sets, by path. */
    val other = ConcurrentHashMap<String, () -> HttpResponse>()

    override suspend fun exchange(request: HttpRequest): HttpResponse {
        val path = request.url.substring(request.url.indexOf("/v1"))
        calls.add("${request.method} $path")
        other[path]?.let { return it() }
        return when {
            path == "/v1/status" -> jsonResponse(
                buildJsonObject {
                    put("chain_id", chainId.hex())
                    put("height", 1)
                    put("tip", "00".repeat(32))
                    put("work", "1")
                },
            )
            path == "/v1/transactions" && request.method == "POST" -> {
                val body = Json.parseToJsonElement(request.body!!.decodeToString()).jsonObject
                val tx = unhex(body.getValue("hex").jsonPrimitive.content)
                posted.add(tx.hex() to body["not_before"]?.jsonPrimitive?.content?.toLong())
                accept(tx)
            }
            path.startsWith("/v1/blocks?") -> {
                val from = path.substringAfter("from=").substringBefore('&').toLong()
                val limit = path.substringAfter("limit=").toInt()
                jsonResponse(buildJsonObject { put("items", JsonArray(blocks(from, limit))) })
            }
            path.startsWith("/v1/transactions/") -> lookups[path.removePrefix("/v1/transactions/")]?.let { jsonResponse(it) }
                ?: jsonResponse(buildJsonObject { put("error", "not found") }, 404)
            else -> jsonResponse(buildJsonObject { put("error", "not found") }, 404)
        }
    }
}

/** A node's lookup of a transaction: in the pool, or included at `height` with `status`. */
internal fun pendingLookup(): JsonElement = buildJsonObject { put("pending", true) }

internal fun includedLookup(height: Long, status: Int = 0, statusName: String = if (status == 0) "Success" else "Revert"): JsonElement =
    Json.parseToJsonElement("""{"pending":false,"occurrences":[{"height":$height,"receipt":{"status":$status,"status_name":"$statusName","used":[1,1,1,1]}}]}""")

internal val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** A store, chain caches and chain over `node`, with the chain's status at `height` in the cache. */
internal class TestApp(
    val node: FakeRestNode,
    val secrets: MemorySecrets<Unit> = MemorySecrets(),
    var clock: Long = 1_800_000_000,
) {
    val storage = MemoryStorage()
    val store = Store(storage, listOf("https://node.test"), currentChain = { caches.currentChain() }, now = { clock }, scope = testScope).also { it.load() }
    val caches = Caches(storage, testScope)
    val chain = Chain(store, caches, secrets, testScope, node, now = { clock })
    val chainHex: String get() = node.chainId.hex()

    /** The node reports `height`: what the app's status poll would put in the cache. */
    fun at(height: Long) {
        caches.select(chainHex).put(Chain.STATUS_KEY, NodeStatus(node.chainId, height, listOf(1L, 1, 1, 1), "%064x".format(height), BigInteger.ONE, 0, "test"), NodeStatus.serializer(), "")
    }
}
