// Restoring from a recovery phrase: the identities whose current keys list
// the phrase's key are found from the registry's Created events.
package app.tingid.state

import app.tingid.chain.BlockEvents
import app.tingid.chain.EventPage
import app.tingid.chain.Id
import app.tingid.chain.NodeError
import app.tingid.chain.ReceiptEvent
import app.tingid.chain.concat
import app.tingid.chain.equal
import app.tingid.chain.filled
import app.tingid.chain.hex
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityScanTest {
    private val registry = filled(0x1d)
    private val other = filled(0x2a)
    private val policy = filled(0x3f)
    private val phraseKey = Id.Key(policy, filled(4, 65))
    private val otherKey = Id.Key(policy, filled(5, 65))
    private val identities = listOf(filled(0xa1), filled(0xb2), filled(0xc3))
    private fun created(id: ByteArray) = concat(Id.CREATED_TOPIC, id)

    private fun core(nextKeyId: Long, keyEpoch: Long = 0) = Id.Core(
        opNonce = 0,
        changeEpoch = 0,
        recoveryEpoch = 0,
        keyEpoch = keyEpoch,
        nextKeyId = nextKeyId,
        keyCount = nextKeyId,
        guardianCount = 0,
        slotCount = 2,
        threshold = 0,
        changeDelay = 0,
        recoveryDelay = 0,
        nextChange = 0,
    )

    /**
     * A chain of 250 blocks: the identities are created in blocks 7, 130 and 249,
     * and another program emits a look-alike event. The first lists another key,
     * the second the phrase's key as its second key, and the third listed it in
     * an old key epoch only, as after a recovery.
     */
    private inner class Node : FakeNode() {
        val at = sortedMapOf(
            7L to listOf(ReceiptEvent(registry, created(identities[0]), "01")),
            60L to listOf(ReceiptEvent(other, created(filled(0xee)), "02")),
            130L to listOf(ReceiptEvent(registry, created(identities[1]), "03")),
            249L to listOf(ReceiptEvent(registry, created(identities[2]), "04")),
        )

        init {
            height = 250
            record(registry, Id.coreKey(identities[0]), Id.encodeCore(core(1)))
            record(registry, Id.keyKey(identities[0], 0, 0), Id.encodeKeyRecord(otherKey))
            record(registry, Id.coreKey(identities[1]), Id.encodeCore(core(2)))
            record(registry, Id.keyKey(identities[1], 0, 0), Id.encodeKeyRecord(otherKey))
            record(registry, Id.keyKey(identities[1], 0, 1), Id.encodeKeyRecord(phraseKey))
            record(registry, Id.coreKey(identities[2]), Id.encodeCore(core(1, keyEpoch = 1)))
            record(registry, Id.keyKey(identities[2], 0, 0), Id.encodeKeyRecord(phraseKey))
            record(registry, Id.keyKey(identities[2], 1, 0), Id.encodeKeyRecord(otherKey))
        }

        // The node's filter: blocks with events of `contract` and `topic` from `from` on, one block per page here.
        override suspend fun contractEvents(contract: ByteArray, topic: ByteArray?, from: Long, limit: Int): EventPage {
            asked.add("${contract.hex().take(4)}:${topic?.hex()?.take(4) ?: "-"}:$from")
            val first = at.entries
                .filter { it.key >= from }
                .map { (h, events) -> h to events.filter { e -> equal(e.contract, contract) && (topic == null || equal(e.payload.copyOf(32), topic)) } }
                .firstOrNull { it.second.isNotEmpty() }
                ?: return EventPage(emptyList(), maxOf(height + 1, from), height)
            return EventPage(listOf(BlockEvents("b${first.first}", first.first, 0, first.second)), first.first + 1, height)
        }
    }

    @Test
    fun `finds the identities that list the key now, from the registry's Created events`() = runBlocking {
        val node = Node()
        val seen = ArrayList<Long>()
        val found = findIdentitiesWithKey(node, registry, phraseKey) { height, _ -> seen.add(height) }
        assertEquals(listOf(identities[1].hex()), found.map { it.hex() })
        // One page per block with a Created event, then the page that says there is no more.
        val t = Id.CREATED_TOPIC.hex().take(4)
        assertEquals(listOf("1d1d:$t:1", "1d1d:$t:8", "1d1d:$t:131", "1d1d:$t:250"), node.asked.filter { !it.startsWith("storage") })
        assertEquals(250L, seen.last())
    }

    @Test
    fun `take Created events of the registry only`() {
        val blocks = listOf(BlockEvents("x", 1, 0, listOf(ReceiptEvent(other, created(identities[0]), ""), ReceiptEvent(registry, created(identities[1]), ""))))
        assertEquals(listOf(identities[1].hex()), createdIds(blocks, registry).map { it.hex() })
    }

    @Test
    fun `read no keys of a core that counts more than any identity has`() = runBlocking {
        val node = Node()
        // The first identity's core claims a trillion keys.
        node.record(registry, Id.coreKey(identities[0]), Id.encodeCore(core(1L shl 40)))
        val found = identitiesWithKey(node, registry, phraseKey, identities)
        assertEquals(listOf(identities[1].hex()), found.map { it.hex() })
        // Three cores, then the keys of the other two: two and one.
        assertEquals(listOf("storage:3", "storage:3"), node.asked)
    }

    @Test
    fun `refuse an events page that does not move on`() = runBlocking {
        val node = object : FakeNode() {
            override suspend fun contractEvents(contract: ByteArray, topic: ByteArray?, from: Long, limit: Int) = EventPage(emptyList(), from, 250)
        }
        val e = runCatching { findIdentitiesWithKey(node, registry, phraseKey) }.exceptionOrNull()
        assertTrue(e is NodeError)
        assertEquals("the node gave an events page that does not move on", e!!.message)
    }
}
