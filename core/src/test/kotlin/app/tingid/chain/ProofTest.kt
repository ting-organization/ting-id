// Checking a node's answers: storage values against the state root by their
// Merkle proofs, and a header against its block id, on answers a node gave.
package app.tingid.chain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProofTest {
    private val fixture = Json.parseToJsonElement(ProofTest::class.java.getResource("/proof-fixture.json")!!.readText()).jsonObject
    private val root = unhex(fixture.getValue("state_root").jsonPrimitive.content)
    private val others = fixture.getValue("others").jsonObject.mapValues { unhex(it.value.jsonPrimitive.content) }

    private class Item(val key: ByteArray, val value: ByteArray?, val proof: StorageProof)

    private val items = fixture.getValue("values").jsonArray.map {
        val v = it.jsonObject
        val p = v.getValue("proof").jsonObject
        Item(
            key = recordKey(unhex(v.getValue("contract").jsonPrimitive.content), unhex(v.getValue("key").jsonPrimitive.content)),
            value = (v["value_hex"]?.takeIf { e -> e is kotlinx.serialization.json.JsonPrimitive && e.isString })?.jsonPrimitive?.content?.let(::unhex),
            proof = StorageProof(
                end = ProofEnd.valueOf(p.getValue("end").jsonPrimitive.content.replaceFirstChar { c -> c.uppercase() }),
                otherKey = p["other_key"]?.jsonPrimitive?.content?.let(::unhex),
                siblings = p.getValue("siblings").jsonArray.map { s -> unhex(s.jsonPrimitive.content) },
            ),
        )
    }
    private val present = items[0]
    private val absent = items[1]

    private fun with(p: StorageProof, end: ProofEnd = p.end, otherKey: ByteArray? = p.otherKey, siblings: List<ByteArray> = p.siblings) =
        StorageProof(end, otherKey, siblings)

    @Test
    fun `show present and absent records against the state root`() {
        assertEquals(listOf(ProofEnd.Present, ProofEnd.Other, ProofEnd.Empty, ProofEnd.Empty), items.map { it.proof.end })
        for (i in items) assertTrue(verifyRecord(root, i.key, i.value, i.proof, others))
        // The record key is the node's: the app derives the same one.
        val first = fixture.getValue("values").jsonArray[0].jsonObject.getValue("proof").jsonObject
        assertEquals(first.getValue("record_key").jsonPrimitive.content, present.key.hex())
    }

    @Test
    fun `refuse another value, another root, another record or a cut path`() {
        val changed = present.value!!.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFalse(verifyRecord(root, present.key, changed, present.proof, others))
        assertFalse(verifyRecord(root, present.key, null, present.proof, others))
        assertFalse(verifyRecord(root, absent.key, byteArrayOf(1), absent.proof, others))
        val otherRoot = root.copyOf().also { it[31] = (it[31].toInt() xor 1).toByte() }
        assertFalse(verifyRecord(otherRoot, present.key, present.value, present.proof, others))
        assertFalse(verifyRecord(root, absent.key, present.value, present.proof, others))
        assertFalse(verifyRecord(root, present.key, present.value, with(present.proof, siblings = present.proof.siblings.drop(1)), others))
    }

    @Test
    fun `refuse an absence whose other record is not as listed`() {
        val otherHex = absent.proof.otherKey!!.hex()
        assertFalse(verifyRecord(root, absent.key, null, absent.proof, others + (otherHex to (others.getValue(otherHex) + byteArrayOf(0)))))
        assertFalse(verifyRecord(root, absent.key, null, absent.proof, others - otherHex))
        // The key itself named as the other record.
        assertFalse(verifyRecord(root, absent.key, null, with(absent.proof, otherKey = absent.key), mapOf(absent.key.hex() to byteArrayOf(1))))
    }

    @Test
    fun `refuse a present record passed off as absent behind a neighbour`() {
        val neighbour = present.key.copyOf().also { it[31] = (it[31].toInt() xor 1).toByte() }
        val forged = StorageProof(ProofEnd.Other, neighbour, present.proof.siblings)
        assertFalse(verifyRecord(root, present.key, null, forged, mapOf(neighbour.hex() to present.value!!)))
    }

    private val tip = fixture.getValue("tip").jsonPrimitive.content
    private val header = unhex(fixture.getValue("header").jsonPrimitive.content)

    @Test
    fun `headers are the block's when they hash to its id, and give its state root`() {
        val h = checkHeader(tip, header)
        assertNotNull(h)
        assertEquals(fixture.getValue("state_root").jsonPrimitive.content, h!!.stateRoot.hex())
        assertEquals(fixture.getValue("height").jsonPrimitive.long, h.height)
        assertEquals(fixture.getValue("chain_id").jsonPrimitive.content, h.chainId.hex())
    }

    @Test
    fun `headers are refused when changed in any byte or cut short`() {
        val changed = header.copyOf().also { it[200] = (it[200].toInt() xor 1).toByte() }
        assertNull(checkHeader(tip, changed))
        assertNull(checkHeader(tip, header.copyOf(293)))
        assertNull(checkHeader("00".repeat(32), header))
    }

    @Test
    fun `headers need the proof of work their target asks, within the chain's limit`() {
        val limit = "0000000" + "f".repeat(57)
        assertNotNull(checkHeader(tip, header, limit))
        assertTrue(checkHeader(tip, header)!!.target.hex() <= limit)
        assertNull(checkHeader(tip, header, "00000000" + "f".repeat(56)))
        // A header whose id is above its own target has no work: so is one with target zero.
        for (fill in listOf(0x00, 0x01)) {
            val easy = header.copyOf()
            easy.fill(0, 82, 114)
            easy[113] = fill.toByte()
            assertNull(checkHeader(taggedHash("block", easy).hex(), easy))
        }
    }

}
