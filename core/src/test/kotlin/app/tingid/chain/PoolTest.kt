// Several nodes used as one: reads move on when a node fails, every answer is
// checked against the block it names, and the other nodes decide whether that
// block is the chain's. Each node is a NodeClient over a fake REST API
// answering from a proved read of the production network (proof-fixture.json).
package app.tingid.chain

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private val fixture = Json.parseToJsonElement(PoolTest::class.java.getResource("/proof-fixture.json")!!.readText()).jsonObject
private fun field(name: String) = fixture.getValue(name).jsonPrimitive.content

/** The fixture's block height. */
private val H = fixture.getValue("height").jsonPrimitive.long
private val fixtureHeader = unhex(field("header"))
private val fixtureTime = Reader(fixtureHeader.copyOfRange(74, 82)).u64()
private val fixtureValues = fixture.getValue("values").jsonArray.map { it.jsonObject }
private val reads = fixtureValues.map { StorageRead(unhex(it.getValue("contract").jsonPrimitive.content), unhex(it.getValue("key").jsonPrimitive.content)) }
private val values: List<String?> = fixtureValues.map { (it["value_hex"] as? JsonPrimitive)?.content }

/**
 * A development chain: it has no work limit (Networks), so a header with the
 * easiest target has the work, and fake nodes can show newest blocks at any
 * height. The fixture's proofs hold on it: they do not depend on the chain.
 */
private val DEV = "d1".repeat(32)

/** Headers fake nodes give, by block id. */
private val fakeHeaders = ConcurrentHashMap<String, ByteArray>()

/**
 * The id of a block at `height` on `chainHex` with the fixture's state root,
 * whose header fake nodes give; another `variant` is another block there.
 */
private fun tipAt(height: Long, chainHex: String = DEV, variant: Int = 0): String {
    val bytes = fixtureHeader.copyOf()
    unhex(chainHex).copyInto(bytes, 2)
    u64le(height).copyInto(bytes, 34)
    bytes.fill(0xff.toByte(), 82, 114)
    bytes[278] = variant.toByte()
    val id = taggedHash("block", bytes).hex()
    fakeHeaders[id] = bytes
    return id
}

/** The block the fixture's values are read from, on the development chain. */
private val TIP = tipAt(H)

private fun json(body: JsonElement, status: Int = 200) = HttpResponse(status, body.toString().encodeToByteArray(), mapOf("content-type" to "application/json"))
private fun error(message: String, status: Int) = json(buildJsonObject { put("error", message) }, status)
private fun unreachable(): Nothing = throw HttpFailure("could not be reached")

/** A proved answer to `asked` (fixture reads), from block `tip`; `change` may alter the values first. */
private fun answer(asked: List<JsonObject>, tip: String = TIP, change: (MutableList<JsonObject>) -> Unit = {}): HttpResponse {
    val items = asked.map { a ->
        fixtureValues.first { it["contract"] == a["contract"] && it["key"] == a["key"] }
    }.toMutableList()
    change(items)
    return json(
        buildJsonObject {
            put("values", JsonArray(items))
            put("others", fixture.getValue("others"))
            put("state_root", field("state_root"))
            put("tip", tip)
        },
    )
}

/** The fixture's first value with its first byte changed: not what the state holds. */
private fun lying(items: MutableList<JsonObject>) {
    val v = unhex(items[0].getValue("value_hex").jsonPrimitive.content)
    v[0] = (v[0].toInt() xor 1).toByte()
    items[0] = JsonObject(items[0] + ("value_hex" to JsonPrimitive(v.hex())))
}

private class Fake(
    val chain: String = DEV,
    val height: Long = H,
    val tip: String = TIP,
    val work: Int = 20,
    val status: (() -> HttpResponse)? = null,
    /** Answers a proved read of these fixture reads. */
    val storage: ((List<JsonObject>) -> HttpResponse)? = null,
    val header: ((String) -> ByteArray?)? = null,
    /** The id of this node's block at the fixture's height. */
    val blockAtH: String = TIP,
    val submit: ((ByteArray) -> HttpResponse)? = null,
)

class PoolTest {
    private var clock = fixtureTime + 60

    /** Which node answered each proved read, and which was asked for a block, in order. */
    private val served: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val blocksAsked: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val submitted: MutableList<String> = Collections.synchronizedList(ArrayList())

    private fun http(url: String, f: Fake) = Http { r ->
        val path = r.url.removePrefix(url)
        when {
            path == "/v1/status" -> f.status?.invoke() ?: json(
                buildJsonObject {
                    put("chain_id", f.chain)
                    put("height", f.height)
                    put("limits", JsonArray(emptyList()))
                    put("tip", f.tip)
                    put("work", f.work.toString())
                    put("mempool_count", 0)
                    put("name", url)
                },
            )
            path == "/v1/storage" -> {
                served.add(url)
                val asked = Json.parseToJsonElement(r.body!!.decodeToString()).jsonObject.getValue("reads").jsonArray.map { it.jsonObject }
                f.storage?.invoke(asked) ?: answer(asked)
            }
            path.startsWith("/v1/blocks/") && path.contains("/raw") -> {
                val id = path.removePrefix("/v1/blocks/").substringBefore('/')
                val bytes = f.header?.invoke(id) ?: if (id == field("tip")) fixtureHeader else fakeHeaders[id]
                if (bytes == null) error("not found", 404) else json(buildJsonObject { put("hex", bytes.hex()) })
            }
            path.startsWith("/v1/blocks?") -> {
                blocksAsked.add(url)
                val from = path.substringAfter("from=").substringBefore('&').toLong()
                json(
                    buildJsonObject {
                        put(
                            "items",
                            buildJsonArray {
                                if (from == H) {
                                    add(
                                        buildJsonObject {
                                            put("id", f.blockAtH)
                                            put("height", from)
                                            put("limits", JsonArray(emptyList()))
                                            put("receipts", JsonArray(emptyList()))
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
            }
            path == "/v1/transactions" && r.method == "POST" -> {
                submitted.add(url)
                val tx = unhex(Json.parseToJsonElement(r.body!!.decodeToString()).jsonObject.getValue("hex").jsonPrimitive.content)
                f.submit?.invoke(tx) ?: json(buildJsonObject { put("id", transactionId(unhex(f.chain), tx).hex()) })
            }
            else -> error("not found", 404)
        }
    }

    /** A status as a node at `height` with newest block `tip` gives it. */
    private fun status(url: String, height: Long, tip: String) = json(
        buildJsonObject {
            put("chain_id", DEV)
            put("height", height)
            put("limits", JsonArray(emptyList()))
            put("tip", tip)
            put("work", "20")
            put("mempool_count", 0)
            put("name", url)
        },
    )

    private fun pool(vararg fakes: Pair<String, Fake>): NodePool {
        val byUrl = fakes.toMap()
        return NodePool(fakes.map { it.first }, make = { url -> NodeClient(url, http(url, byUrl.getValue(url))) }, now = { clock })
    }

    private fun states(p: NodePool) = p.healthFlow.value.associate { it.url to it.state }

    private suspend fun NodePool.read(): List<String?> = storageMany(reads).map { it?.hex() }

    private suspend fun waitFor(what: String, condition: () -> Boolean) {
        repeat(400) {
            if (condition()) return
            delay(5)
        }
        fail("waited in vain: $what")
    }

    private inline fun <reified E : Throwable> failure(block: () -> Unit): E {
        val e = runCatching(block).exceptionOrNull()
        assertTrue("expected ${E::class.simpleName}, got $e", e is E)
        return e as E
    }

    // ---- Failover ------------------------------------------------------------------

    @Test
    fun `reads from the first node, whatever work another claims, and from the next when it does not answer`() = runBlocking {
        var aDown = false
        val p = pool("a" to Fake(storage = { asked -> if (aDown) unreachable() else answer(asked) }), "b" to Fake(work = 25))
        assertEquals("a", p.status().name)
        assertEquals(values, p.read())
        aDown = true
        assertEquals(values, p.read())
        assertEquals(listOf("a", "a", "b"), served)
        assertEquals(mapOf("a" to NodeState.Down, "b" to NodeState.Ok), states(p))
    }

    @Test
    fun `leaves out a node on another chain, and says when no node answers`() = runBlocking {
        val p = pool("a" to Fake(), "b" to Fake(), "c" to Fake(chain = "cd".repeat(32), work = 99))
        assertEquals("a", p.status().name)
        assertEquals(NodeState.OtherChain, states(p)["c"])
        val none = pool("a" to Fake(status = { unreachable() }))
        val e = failure<NodeError> { none.status() }
        assertTrue(e.message!!.contains("cannot reach the node"))
    }

    @Test
    fun `marks a node without proofs as needing an update`() = runBlocking {
        val p = pool("a" to Fake(storage = { error("Method Not Allowed", 405) }), "b" to Fake())
        p.status()
        assertEquals(values, p.read())
        assertEquals(listOf("a", "b"), served)
        assertEquals(NodeState.Outdated, states(p)["a"])
        // It stays so until the user refreshes, though it answers.
        p.status()
        assertEquals(NodeState.Outdated, states(p)["a"])
        p.reset()
        p.status()
        assertEquals(NodeState.Ok, states(p)["a"])
    }

    // ---- Addresses -------------------------------------------------------------------

    @Test
    fun `addresses take plain http only for this device and the home network`() {
        for (ok in listOf("https://node1.ting-node.com", "https://2.29.97.212:18441/", "http://127.0.0.1:18441", "http://localhost:18441", "http://192.168.1.20:18441", "http://10.0.0.5", "http://172.20.1.1:80")) {
            assertEquals(ok.removeSuffix("/"), nodeAddress(ok))
        }
        for (refused in listOf("http://2.29.97.212:18441", "http://node1.ting-node.com", "http://172.32.0.1", "http://192.168.1.20@example.com", "http://300.168.1.1", "http://[::1]:18441", "ftp://127.0.0.1", "https://")) {
            assertNull(refused, nodeAddress(refused))
        }
    }

    @Test
    fun `addresses are cleaned of white space the way Android and the JVM agree on`() {
        assertEquals("https://node1.ting-node.com", nodeAddress("  https://node1.ting-node.com/ "))
        assertNull(nodeAddress("https://node1.ting node.com"))
        assertNull(nodeAddress("https://node1.ting-node.com/a b"))
    }

    @Test
    fun `leaves out a saved node reached over plain http from elsewhere`() = runBlocking {
        val p = pool("http://2.29.97.212:18441" to Fake(), "b" to Fake())
        p.status()
        assertEquals(NodeState.Insecure, states(p)["http://2.29.97.212:18441"])
        assertEquals(values, p.read())
        assertEquals(listOf("b"), served)
        val alone = pool("http://2.29.97.212:18441" to Fake())
        assertTrue(failure<NodeError> { alone.status() }.message!!.contains("plain http"))
    }

    // ---- Records that never change ------------------------------------------------------

    @Test
    fun `records that never change are read from every node once and kept`() = runBlocking {
        val p = pool("a" to Fake(), "b" to Fake())
        p.status()
        assertEquals(values, p.storageFixed(reads).map { it?.hex() })
        assertEquals(listOf("a", "b"), served.sorted())
        // The record found is kept; one no node has yet is read again next time.
        assertEquals(values[0], p.storageFixed(reads.subList(0, 1))[0]?.hex())
        assertEquals(2, served.size)
        assertNull(p.storageFixed(reads.subList(1, 2))[0])
        assertEquals(4, served.size)
    }

    @Test
    fun `records that never change leave out a node whose answer fails its check`() = runBlocking {
        val p = pool("a" to Fake(storage = { answer(it, change = ::lying) }), "b" to Fake())
        p.status()
        assertEquals(values, p.storageFixed(reads).map { it?.hex() })
        assertEquals(NodeState.Distrusted, states(p)["a"])
    }

    // ---- Large answers --------------------------------------------------------------------

    @Test
    fun `large answers are asked for again in halves when the node refuses one as too large`() = runBlocking {
        val asked = Collections.synchronizedList(ArrayList<Int>())
        val halves = { r: List<JsonObject> ->
            asked.add(r.size)
            if (r.size > 2) error("JSON response size limit exceeded", 400) else answer(r)
        }
        val p = pool("a" to Fake(storage = halves))
        p.status()
        assertEquals(values, p.read())
        assertEquals(listOf(4, 2, 2), asked)
    }

    // ---- Checks ----------------------------------------------------------------------------

    @Test
    fun `stop using a node whose values do not match the state it names`() = runBlocking {
        val p = pool("a" to Fake(work = 30, storage = { answer(it, change = ::lying) }), "b" to Fake())
        val distrusted = AtomicInteger()
        p.onDistrust = { distrusted.incrementAndGet() }
        p.status()
        assertEquals(values, p.read())
        assertEquals(listOf("a", "b"), served)
        assertEquals(NodeState.Distrusted, states(p)["a"])
        assertEquals(PoolNotice.Distrusted, p.notice.value)
        assertEquals(1, distrusted.get())
    }

    @Test
    fun `stop using a node whose header is not the block's`() = runBlocking {
        val bytes = fakeHeaders.getValue(TIP).copyOf()
        bytes[180] = (bytes[180].toInt() xor 1).toByte()
        val p = pool("a" to Fake(work = 30, header = { bytes }), "b" to Fake())
        p.status()
        assertEquals(values, p.read())
        // Its own newest block already gives it away.
        assertEquals(listOf("b"), served)
        val a = p.healthFlow.value[0]
        assertEquals(NodeState.Distrusted, a.state)
        assertEquals("its newest block header is not the block’s, or lacks the work the chain needs", a.problem)
    }

    @Test
    fun `stop using a node whose newest block is of another chain than it says`() = runBlocking {
        val p = pool("a" to Fake(tip = tipAt(H, "ee".repeat(32))), "b" to Fake())
        assertEquals("b", p.status().name)
        val a = p.healthFlow.value[0]
        assertEquals(NodeState.Distrusted, a.state)
        assertEquals("its newest block is of another chain", a.problem)
    }

    @Test
    fun `stop using a node whose newest block is not at the height it says`() = runBlocking {
        val p = pool("a" to Fake(height = H + 5), "b" to Fake())
        p.status()
        val a = p.healthFlow.value[0]
        assertEquals(NodeState.Distrusted, a.state)
        assertEquals("it names another height than its newest block has", a.problem)
    }

    @Test
    fun `on the production network, a newest block without the chain's work is refused`() = runBlocking {
        val production = Networks.PRODUCTION
        val p = pool(
            "a" to Fake(chain = production, tip = field("tip")),
            "b" to Fake(chain = production, tip = tipAt(H, production), work = 30),
        )
        assertEquals("a", p.status().name)
        assertEquals(mapOf("a" to NodeState.Ok, "b" to NodeState.Distrusted), states(p))
    }

    @Test
    fun `stop using a node that names a block that is not one, or one of another chain`() = runBlocking {
        val p = pool("a" to Fake(work = 30, storage = { answer(it, "not a block") }), "b" to Fake(work = 25, storage = { answer(it, tipAt(H, "ee".repeat(32))) }), "c" to Fake())
        p.status()
        assertEquals(values, p.read())
        assertEquals(listOf("a", "b", "c"), served)
        assertEquals(listOf("it named a block that is not one", "it read from a block of another chain", null), p.healthFlow.value.map { it.problem })
    }

    @Test
    fun `refuse answers from a block far behind the newest one`() = runBlocking {
        val ahead = tipAt(H + 10)
        val p = pool("a" to Fake(height = H + 10, tip = ahead), "b" to Fake(height = H + 10, tip = ahead))
        p.status()
        val e = failure<NodeError> { p.read() }
        assertTrue(e.message, e.message!!.contains("answered from block $H, behind the newest, ${H + 10}"))
    }

    @Test
    fun `say when the newest block is hours old`() = runBlocking {
        val p = pool("a" to Fake())
        clock = fixtureTime + 4 * 3600
        p.status()
        p.read()
        assertEquals(PoolNotice.Old(fixtureTime), p.notice.value)
        // A newer block takes the word back.
        clock = fixtureTime + 60
        p.read()
        assertNull(p.notice.value)
    }

    @Test
    fun `let the other nodes confirm the block the answers come from`() = runBlocking {
        // b is a block ahead, which a does not have yet; a's newest block b has.
        val p = pool("a" to Fake(), "b" to Fake(height = H + 1, tip = tipAt(H + 1)))
        assertEquals("a", p.status().name)
        assertEquals(values, p.read())
        assertEquals(listOf("a"), served)
        assertTrue("b" in blocksAsked)
        // Confirmed once, the block is not asked about again.
        val asked = blocksAsked.count { it == "b" }
        p.read()
        delay(100)
        assertEquals(asked, blocksAsked.count { it == "b" })
        assertNull(p.notice.value)
    }

    @Test
    fun `a node a block ahead does not answer until the other has its block - the one behind does`() = runBlocking {
        val next = tipAt(H + 1)
        // b answers from its newest block, which a does not have yet.
        val p = pool("b" to Fake(height = H + 1, tip = next, storage = { answer(it, next) }), "a" to Fake())
        assertEquals("a", p.status().name)
        assertEquals(values, p.read())
        assertEquals(listOf("a"), served)
        assertEquals(mapOf("b" to NodeState.Ok, "a" to NodeState.Ok), states(p))
        assertNull(p.notice.value)
    }

    @Test
    fun `an answer from a block no other node has is not taken, and the node is not marked down for it`() = runBlocking {
        val other = tipAt(H, variant = 2)
        val p = pool("a" to Fake(storage = { answer(it, other) }), "b" to Fake())
        p.status()
        assertEquals(values, p.read())
        assertEquals(listOf("a", "b"), served)
        assertEquals(mapOf("a" to NodeState.Ok, "b" to NodeState.Ok), states(p))
    }

    @Test
    fun `one node far ahead of a second, with blocks it does not have, is not believed, and the user is told`() = runBlocking {
        val ahead = tipAt(H + 50)
        val p = pool("a" to Fake(height = H + 50, tip = ahead, work = 99, storage = { answer(it, ahead) }), "b" to Fake())
        assertEquals("b", p.status().name)
        assertEquals(values, p.read())
        assertEquals(listOf("b"), served)
        assertEquals(PoolNotice.Split, p.notice.value)
        // Two nodes cannot outvote each other: neither is put aside, however long it lasts.
        clock += AHEAD_SECONDS + 1
        p.status()
        assertEquals(mapOf("a" to NodeState.Ok, "b" to NodeState.Ok), states(p))
        assertEquals(PoolNotice.Split, p.notice.value)
    }

    @Test
    fun `a second node far behind is told of too - its answers may hide what happened since`() = runBlocking {
        // a, far ahead, has b's newest block; b has none of a's.
        val p = pool("a" to Fake(height = H + 50, tip = tipAt(H + 50)), "b" to Fake())
        assertEquals("b", p.status().name)
        assertEquals(PoolNotice.Split, p.notice.value)
    }

    @Test
    fun `a node that stays ahead with blocks no other node has is outvoted, even with a new one each time`() = runBlocking {
        var step = 0L
        val p = pool(
            "a" to Fake(status = { status("a", H + 5 + step, tipAt(H + 5 + step)) }),
            "b" to Fake(),
            "c" to Fake(),
        )
        assertEquals("b", p.status().name)
        // Five blocks ahead: within what honest nodes may be apart, so nothing is said yet.
        assertNull(p.notice.value)
        repeat(4) {
            step++
            clock += AHEAD_SECONDS / 3
            p.status()
        }
        assertEquals(NodeState.Distrusted, states(p)["a"])
        assertEquals("its newest blocks are not on your other nodes’ chain", p.healthFlow.value[0].problem)
        assertEquals(NodeState.Ok, states(p)["b"])
        assertEquals(NodeState.Ok, states(p)["c"])
    }

    @Test
    fun `a node a block ahead time after time, as the others catch up, is not outvoted`() = runBlocking {
        var step = 0L
        val p = pool(
            "a" to Fake(status = { status("a", H + 1 + step, tipAt(H + 1 + step)) }),
            "b" to Fake(status = { status("b", H + step, tipAt(H + step)) }),
            "c" to Fake(status = { status("c", H + step, tipAt(H + step)) }),
        )
        repeat(10) {
            p.status()
            // The others reach the block that was a's newest a moment ago.
            step++
            clock += AHEAD_SECONDS / 3
        }
        p.status()
        assertEquals(mapOf("a" to NodeState.Ok, "b" to NodeState.Ok, "c" to NodeState.Ok), states(p))
        assertNull(p.notice.value)
    }

    @Test
    fun `outvote a node that follows another chain`() = runBlocking {
        val past = tipAt(H + CONFIRMATIONS)
        // a has another block at the fixture's height than b and c, and answers from it.
        val fork = tipAt(H, variant = 1)
        val p = pool(
            "a" to Fake(height = H + CONFIRMATIONS, tip = past, storage = { answer(it, fork) }, blockAtH = fork),
            "b" to Fake(height = H + CONFIRMATIONS, tip = past),
            "c" to Fake(height = H + CONFIRMATIONS, tip = past),
        )
        p.status()
        assertEquals(values, p.read())
        waitFor("a distrusted") { states(p)["a"] == NodeState.Distrusted }
        assertEquals(PoolNotice.Distrusted, p.notice.value)
    }

    @Test
    fun `say two nodes split, when neither has a majority, and read from neither`() = runBlocking {
        val past = tipAt(H + CONFIRMATIONS)
        val fork = tipAt(H, variant = 1)
        val p = pool(
            "a" to Fake(height = H + CONFIRMATIONS, tip = past),
            "b" to Fake(height = H + CONFIRMATIONS, tip = past, storage = { answer(it, fork) }, blockAtH = fork),
        )
        p.status()
        failure<NodeError> { p.read() }
        waitFor("the split noticed") { p.notice.value == PoolNotice.Split }
        assertEquals(mapOf("a" to NodeState.Ok, "b" to NodeState.Ok), states(p))
    }

    @Test
    fun `wait while the other node has not moved past the block`() = runBlocking {
        // Both are a block short of deciding, and each has another block at the fixture's height.
        val near = tipAt(H + CONFIRMATIONS - 1)
        val fork = tipAt(H, variant = 1)
        val p = pool(
            "a" to Fake(height = H + CONFIRMATIONS - 1, tip = near),
            "b" to Fake(height = H + CONFIRMATIONS - 1, tip = near, storage = { answer(it, fork) }, blockAtH = fork),
        )
        p.status()
        failure<NodeError> { p.read() }
        waitFor("b asked for the block") { "b" in blocksAsked }
        delay(50)
        assertNull(p.notice.value)
        assertEquals(mapOf("a" to NodeState.Ok, "b" to NodeState.Ok), states(p))
    }

    // ---- Transactions -----------------------------------------------------------------------

    @Test
    fun `transactions go to every node, and one any node takes is sent`() = runBlocking {
        val p = pool("b" to Fake(submit = { unreachable() }), "a" to Fake())
        p.status()
        val tx = byteArrayOf(1)
        assertEquals(transactionId(unhex(DEV), tx).hex(), p.submit(tx))
        assertEquals(listOf("a", "b"), submitted.sorted())
    }

    @Test
    fun `a transaction one node refuses and another takes is sent, though the refusal comes first`() = runBlocking {
        val p = pool(
            "b" to Fake(submit = { error("nonce too low", 400) }),
            "a" to Fake(
                submit = { tx ->
                    Thread.sleep(100)
                    json(buildJsonObject { put("id", transactionId(unhex(DEV), tx).hex()) })
                },
            ),
        )
        p.status()
        val tx = byteArrayOf(1)
        assertEquals(transactionId(unhex(DEV), tx).hex(), p.submit(tx))
        assertEquals(mapOf("b" to NodeState.Ok, "a" to NodeState.Ok), states(p))
    }

    @Test
    fun `a transaction is refused when every node answered and refused it, by the first one's reason`() = runBlocking {
        val p = pool("b" to Fake(submit = { error("nonce too low", 400) }), "a" to Fake(submit = { error("fee too low", 400) }))
        p.status()
        assertEquals("nonce too low", failure<NodeError> { p.submit(byteArrayOf(1)) }.message)
    }

    @Test
    fun `a transaction one node refuses while another gives no answer may have gone in`() = runBlocking {
        val p = pool("b" to Fake(submit = { error("nonce too low", 400) }), "a" to Fake(submit = { unreachable() }))
        p.status()
        val tx = byteArrayOf(1)
        assertEquals(transactionId(unhex(DEV), tx).hex(), failure<MaybeSent> { p.submit(tx) }.txId)
        assertEquals(mapOf("b" to NodeState.Ok, "a" to NodeState.Down), states(p))
    }

    @Test
    fun `a transaction no node answered for may have gone in`() = runBlocking {
        val p = pool("a" to Fake(submit = { unreachable() }), "b" to Fake(submit = { unreachable() }))
        p.status()
        val tx = byteArrayOf(1)
        val e = failure<MaybeSent> { p.submit(tx) }
        assertEquals(transactionId(unhex(DEV), tx).hex(), e.txId)
    }
}
