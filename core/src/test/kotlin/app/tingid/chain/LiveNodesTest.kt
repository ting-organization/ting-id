// The node client and pool against the production nodes: run with
// `./gradlew :core:test -Pting.live` (needs the network).
package app.tingid.chain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveNodesTest {
    private val nodes = listOf("https://node1.ting-node.com", "https://node2.ting-node.com")

    @Test
    fun `the production nodes answer, prove their reads and agree`() = runBlocking {
        assumeTrue(System.getProperty("ting.live") == "true")
        val pool = NodePool(nodes)
        val status = pool.status()
        assertEquals(Networks.PRODUCTION, status.chainId.hex())
        assertTrue(pool.healthFlow.value.all { it.state == NodeState.Ok })
        val c = Contracts.canonical(status.chainId)
        // The registry's Created events name identities; the first one's core record, proved.
        val page = pool.contractEvents(c.registry, Id.CREATED_TOPIC, 1, 5)
        val id = page.blocks.flatMap { it.events }.mapNotNull { Id.decodeEvent(it.payload) as? Id.Event.Created }.first().id
        val core = Id.decodeCore(pool.storage(c.registry, Id.coreKey(id))!!)!!
        assertTrue(core.slotCount >= 1)
        // A record that never changes, read from both nodes.
        val fixed = pool.storageFixed(listOf(StorageRead(c.registry, Id.coreKey(id))))
        assertTrue(fixed[0] != null)
        assertEquals(Contracts.codeHash(ContractName.IdRegistry), pool.contract(c.registry)!!.codeHash)
        println("height ${status.height}, identity ${id.hex()}, slots ${core.slotCount}, key epoch ${core.keyEpoch}")
    }
}
