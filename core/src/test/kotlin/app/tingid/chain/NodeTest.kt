// Reading many storage records at once (NodeClient.storageMany): one request
// per 1,024 records, values in the order asked, and single reads for a node
// without the batch call.
package app.tingid.chain

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class NodeTest {
    private val contract = filled(1)

    private fun key(n: Int): ByteArray = ByteArray(32).also { k ->
        k[28] = (n ushr 24).toByte()
        k[29] = (n ushr 16).toByte()
        k[30] = (n ushr 8).toByte()
        k[31] = n.toByte()
    }

    /** A record's value in the fake node: its key's last two bytes, absent for odd numbers. */
    private fun valueOf(keyHex: String): String? = if (keyHex.takeLast(2).toInt(16) % 2 == 1) null else keyHex.takeLast(4)

    private fun json(body: Any, status: Int = 200) = HttpResponse(status, body.toString().encodeToByteArray(), mapOf("content-type" to "application/json"))

    private fun hexOrNull(v: String?) = if (v == null) JsonNull else JsonPrimitive(v)

    /** A node answering batch reads when `batch`, as `batchStatus` says otherwise; the calls it got, as `METHOD /v1/…`. */
    private fun fakeNode(batch: Boolean, batchStatus: Int = 404, values: (List<JsonObject>) -> List<JsonObject> = { it }): Pair<Http, MutableList<String>> {
        val calls = Collections.synchronizedList(ArrayList<String>())
        val http = Http { r ->
            calls.add("${r.method} ${r.url.substring(r.url.indexOf("/v1"))}")
            if (r.url.endsWith("/v1/storage")) {
                if (!batch) return@Http json(buildJsonObject { put("error", JsonPrimitive("not found")) }, batchStatus)
                val reads = kotlinx.serialization.json.Json.parseToJsonElement(r.body!!.decodeToString()).jsonObject.getValue("reads").jsonArray.map { it.jsonObject }
                val answered = values(
                    reads.map { read ->
                        JsonObject(read + ("value_hex" to hexOrNull(valueOf(read.getValue("key").jsonPrimitive.content))))
                    },
                )
                return@Http json(
                    buildJsonObject {
                        put("state_root", JsonPrimitive("00"))
                        put("tip", JsonPrimitive("00"))
                        put("values", buildJsonArray { answered.forEach { add(it) } })
                    },
                )
            }
            val keyHex = r.url.substringAfter("/storage/")
            json(buildJsonObject { put("value_hex", hexOrNull(valueOf(keyHex))) })
        }
        return http to calls
    }

    @Test
    fun `answers in the order asked, null where absent, 1,024 at a time`() = runBlocking {
        val (http, calls) = fakeNode(batch = true)
        val reads = (0 until 1500).map { StorageRead(contract, key(it)) }
        val values = NodeClient("http://node", http).storageMany(reads)
        assertEquals(1500, values.size)
        assertEquals("0000", values[0]?.hex())
        assertEquals(null, values[1])
        assertEquals(key(1498).hex().takeLast(4), values[1498]?.hex())
        assertEquals(listOf("POST /v1/storage", "POST /v1/storage"), calls)
    }

    @Test
    fun `reads one record at a time from a node without the batch call, and remembers it`() = runBlocking {
        val (http, calls) = fakeNode(batch = false)
        val node = NodeClient("http://node", http)
        val values = node.storageMany(listOf(0, 1, 2).map { StorageRead(contract, key(it)) })
        assertEquals(listOf("0000", null, "0002"), values.map { it?.hex() })
        assertEquals(1, calls.count { it.startsWith("POST") })
        node.storageMany(listOf(StorageRead(contract, key(4))))
        assertEquals(1, calls.count { it.startsWith("POST") })
    }

    @Test
    fun `takes a refused method for a node without the batch call`() = runBlocking {
        val (http, calls) = fakeNode(batch = false, batchStatus = 405)
        val values = NodeClient("http://node", http).storageMany(listOf(0, 1).map { StorageRead(contract, key(it)) })
        assertEquals(listOf("0000", null), values.map { it?.hex() })
        assertEquals("POST /v1/storage", calls[0])
        val reads = (0..1).map { "GET /v1/contracts/${contract.hex()}/storage/${key(it).hex()}" }
        assertEquals(reads, calls.drop(1).sorted())
    }

    @Test
    fun `a node failing the batch call is a failure, not a node without it`() = runBlocking {
        val (http, calls) = fakeNode(batch = false, batchStatus = 500)
        val e = runCatching { NodeClient("http://node", http).storageMany(listOf(StorageRead(contract, key(0)))) }.exceptionOrNull()
        assertTrue(e is NodeError && e.status == 500)
        assertEquals(listOf("POST /v1/storage"), calls)
    }

    @Test
    fun `answers by single reads when a batch answer has another number of values than asked`() = runBlocking {
        val (http, calls) = fakeNode(batch = true) { it.dropLast(1) }
        val values = NodeClient("http://node", http).storageMany(listOf(0, 2).map { StorageRead(contract, key(it)) })
        assertEquals(listOf("0000", "0002"), values.map { it?.hex() })
        assertEquals(1, calls.count { it.startsWith("POST") })
        assertEquals(2, calls.count { it.startsWith("GET") })
    }
}
