// One node with a header of its own making on the production chain: a block
// at the chain's proof-of-work limit (2^228, about 2^28 hashes, a second's
// work for one graphics card) with a state root of its choosing, so its
// "proved" records say what it likes. With the honest nodes beside it, the
// pool takes none of them, keeps the honest nodes in use, and puts it aside.
package app.tingid.chain

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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigInteger

class ForgedHeaderTest {
    private val fixture = Json.parseToJsonElement(ForgedHeaderTest::class.java.getResource("/proof-fixture.json")!!.readText()).jsonObject
    private fun field(name: String) = fixture.getValue(name).jsonPrimitive.content
    private val height = fixture.getValue("height").jsonPrimitive.long
    private val realHeader = unhex(field("header"))
    private val realTip = field("tip")
    private val fixtureValues = fixture.getValue("values").jsonArray.map { it.jsonObject }
    private val daler = unhex(fixtureValues[0].getValue("contract").jsonPrimitive.content)
    private val configKey = unhex(fixtureValues[0].getValue("key").jsonPrimitive.content)
    private val realConfig = unhex(fixtureValues[0].getValue("value_hex").jsonPrimitive.content)

    // An account the real state proves absent (the fixture's second record).
    private val accountKey = unhex(fixtureValues[1].getValue("key").jsonPrimitive.content)

    /** The made-up record: a balance of 10^20 on that account. */
    private val fakeAccount = Writer().u128(BigInteger.TEN.pow(20)).u128(BigInteger.ZERO).u64(0).finish()

    private val empty = taggedHash("state-empty")
    private fun leaf(key: ByteArray, value: ByteArray) = taggedHash("state-leaf", key, Writer().bytes(value).finish())
    private fun node(depth: Int, l: ByteArray, r: ByteArray) = taggedHash("state-node", Writer().u16(depth).finish(), l, r)
    private fun bit(key: ByteArray, d: Int) = ((key[d shr 3].toInt() and 0xff) shr (7 - (d and 7))) and 1

    /** A state of two records, `a` and `b`: its root and each record's siblings, from the bottom up. */
    private fun twoRecords(a: ByteArray, va: ByteArray, b: ByteArray, vb: ByteArray): Triple<ByteArray, List<ByteArray>, List<ByteArray>> {
        val k = (0 until 256).first { bit(a, it) != bit(b, it) }
        val la = leaf(a, va)
        val lb = leaf(b, vb)
        var h = if (bit(a, k) == 0) node(k, la, lb) else node(k, lb, la)
        val upper = ArrayList<ByteArray>()
        for (d in k - 1 downTo 0) {
            h = if (bit(a, d) == 1) node(d, empty, h) else node(d, h, empty)
            upper.add(empty)
        }
        return Triple(h, listOf(lb) + upper, listOf(la) + upper)
    }

    private val made = twoRecords(recordKey(daler, configKey), realConfig, recordKey(daler, accountKey), fakeAccount)

    /**
     * The real header 50 blocks on, at the work limit, with the made-up state
     * root and a nonce found for it once (about 2^28 hashes): it has the work
     * the production chain asks of a header.
     */
    private val forgedHeader = realHeader.copyOf().also {
        u64le(height + 50).copyInto(it, 34)
        unhex("0000000" + "f".repeat(57)).copyInto(it, 82)
        made.first.copyInto(it, 178)
        unhex("90021208000000000000000000000000").copyInto(it, 278)
    }
    private val forged = taggedHash("block", forgedHeader).hex()

    private fun json(body: JsonElement, status: Int = 200) = HttpResponse(status, body.toString().encodeToByteArray(), mapOf("content-type" to "application/json"))

    private fun http(url: String, height: Long, tip: String, header: ByteArray, storage: (List<JsonObject>) -> JsonElement) = Http { r ->
        val path = r.url.removePrefix(url)
        when {
            path == "/v1/status" -> json(
                buildJsonObject {
                    put("chain_id", Networks.PRODUCTION)
                    put("height", height)
                    put("limits", JsonArray(emptyList()))
                    put("tip", tip)
                    // It claims far more work than the others.
                    put("work", if (tip == forged) "1000000000000" else "1")
                    put("mempool_count", 0)
                    put("name", url)
                },
            )
            path == "/v1/storage" -> json(storage(Json.parseToJsonElement(r.body!!.decodeToString()).jsonObject.getValue("reads").jsonArray.map { it.jsonObject }))
            path.startsWith("/v1/blocks/") && path.contains("/raw") ->
                if (path.removePrefix("/v1/blocks/").substringBefore('/') == tip) json(buildJsonObject { put("hex", header.hex()) })
                else json(buildJsonObject { put("error", "not found") }, 404)
            path.startsWith("/v1/blocks?") -> json(buildJsonObject { put("items", buildJsonArray { }) })
            else -> json(buildJsonObject { put("error", "not found") }, 404)
        }
    }

    private val evil = http("https://evil", height + 50, forged, forgedHeader) { asked ->
        fun proof(s: List<ByteArray>) = buildJsonObject {
            put("end", "present")
            put("siblings", JsonArray(s.map { JsonPrimitive(it.hex()) }))
        }
        buildJsonObject {
            put(
                "values",
                JsonArray(
                    asked.map { q ->
                        val isConfig = q.getValue("key").jsonPrimitive.content == configKey.hex()
                        buildJsonObject {
                            put("value_hex", (if (isConfig) realConfig else fakeAccount).hex())
                            put("proof", proof(if (isConfig) made.second else made.third))
                        }
                    },
                ),
            )
            put("others", JsonObject(emptyMap()))
            put("state_root", made.first.hex())
            put("tip", forged)
        }
    }

    private fun honest(url: String) = http(url, height, realTip, realHeader) { asked ->
        buildJsonObject {
            put("values", JsonArray(asked.map { q -> fixtureValues.first { it["contract"] == q["contract"] && it["key"] == q["key"] } }))
            put("others", fixture.getValue("others"))
            put("state_root", field("state_root"))
            put("tip", realTip)
        }
    }

    @Test
    fun `the made-up header has the work the production chain asks`() {
        assertNotNull(checkHeader(forged, forgedHeader, Networks.powLimitOf(Networks.PRODUCTION)))
    }

    @Test
    fun `one node with a made-up block above the others cannot pass off its records`() = runBlocking {
        var clock = Reader(realHeader.copyOfRange(74, 82)).u64() + 60
        val clients = mapOf("https://node1" to honest("https://node1"), "https://node2" to honest("https://node2"), "https://evil" to evil)
        val p = NodePool(listOf("https://evil", "https://node1", "https://node2"), make = { u -> NodeClient(u, clients.getValue(u)) }, now = { clock })
        // First in the list, highest and claiming the most work, it still does not rank first.
        assertEquals("https://node1", p.status().name)
        val reads = listOf(StorageRead(daler, configKey), StorageRead(daler, accountKey))
        assertEquals(listOf(realConfig.hex(), null), p.storageMany(reads).map { it?.hex() })
        assertNull(p.storageFixed(listOf(StorageRead(daler, accountKey)))[0])
        val states = { p.healthFlow.value.associate { it.url to it.state } }
        assertEquals(mapOf("https://evil" to NodeState.Ok, "https://node1" to NodeState.Ok, "https://node2" to NodeState.Ok), states())
        // Fifty blocks ahead of the newest block two nodes have: the user is told.
        assertEquals(PoolNotice.Split, p.notice.value)
        // Still ahead with blocks no other node has, long after: the other two outvote it.
        clock += AHEAD_SECONDS + 1
        assertEquals("https://node1", p.status().name)
        assertEquals(NodeState.Distrusted, states()["https://evil"])
        assertEquals(PoolNotice.Distrusted, p.notice.value)
        assertNull(p.storageMany(reads)[1])
    }
}
