// Following what this device submitted against a node: settled once a block
// deep enough has it, sent again with the same bytes when the node lost it,
// and failed only when the node refuses it.
package app.tingid.state

import app.tingid.chain.HttpFailure
import app.tingid.chain.filled
import app.tingid.chain.hex
import app.tingid.chain.transactionId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingTest {
    private val node = FakeRestNode(filled(0x26))
    private val app = TestApp(node)
    private val pending = Pending(app.chain, testScope, statusWords = { if (it == "Revert") "the program refused it" else "it failed ($it)" }, now = { app.clock }, pollMs = 20)
    private val store = app.store

    init {
        store.addUser(User("u", "Anna", "#000", UserKind.Key))
        app.at(110)
    }

    /** A transaction submitted five minutes ago, its bytes kept; its id. */
    private fun submitted(n: Int, userId: String = "u", state: TxState = TxState.Pending, chain: String? = null, keep: Boolean = true): String {
        val tx = byteArrayOf(n.toByte())
        val id = transactionId(node.chainId, tx).hex()
        store.addSubmitted(SubmittedTx(id, app.clock - 300, "Send $n DLR", userId, chain = chain, state = state, problem = if (state == TxState.Failed) "x" else null))
        if (keep) store.keepOutbox(id, OutboxEntry(tx.hex(), at = app.clock - 300))
        return id
    }

    private fun refuse(message: String) {
        node.accept = { jsonResponse(buildJsonObject { put("error", message) }, 400) }
    }

    private fun until(what: (List<PendingItem>) -> Boolean): List<PendingItem> = runBlocking {
        withTimeout(5_000) { pending.items().first(what) }
    }

    @Test
    fun `follows a submission until a block deep enough settles it`() {
        val id = submitted(1)
        node.lookups[id] = pendingLookup()
        assertEquals(Watch.Waiting, until { it.singleOrNull()?.watch == Watch.Waiting }.single().watch)
        node.lookups[id] = includedLookup(108)
        assertEquals(Watch.Confirming(108), until { it.singleOrNull()?.watch is Watch.Confirming }.single().watch)
        app.at(111)
        until { it.isEmpty() }
        val tx = store.data.value.submitted.single()
        assertEquals(TxState.Included, tx.state)
        assertEquals(108L, tx.height)
        assertFalse(id in store.data.value.outbox)
    }

    @Test
    fun `a submission that ran without succeeding fails, in words`() {
        val id = submitted(1)
        node.lookups[id] = includedLookup(100, status = 3)
        until { it.isEmpty() || it.single().tx.state == TxState.Failed }
        val tx = store.data.value.submitted.single()
        assertEquals(TxState.Failed, tx.state)
        assertEquals("it ran in block 100 without succeeding: the program refused it", tx.problem)
        assertEquals(Watch.Failed("it ran in block 100 without succeeding: the program refused it", 100), until { it.isNotEmpty() }.single().watch)
    }

    @Test
    fun `sends a lost one again, with the same bytes`() {
        val id = submitted(2)
        until { it.singleOrNull()?.watch == Watch.Resent }
        assertEquals(store.data.value.outbox.getValue(id).raw, node.posted.first().first)
        assertEquals(TxState.Pending, store.data.value.submitted.single().state)
    }

    @Test
    fun `a lost one the node refuses when sent again has failed`() {
        submitted(3)
        refuse("nonce too low")
        val item = until { it.singleOrNull()?.watch is Watch.Failed }.single()
        assertEquals(Watch.Failed("the node lost it and refused it when sent again: nonce too low", null), item.watch)
        assertTrue(item.canRetry)
    }

    @Test
    fun `a lost one whose answer is lost again is no refusal`() {
        submitted(4)
        node.accept = { throw HttpFailure("could not be reached") }
        until { it.singleOrNull()?.watch == Watch.Resent }
        assertEquals(TxState.Pending, store.data.value.submitted.single().state)
    }

    @Test
    fun `a paid operation settles when the operation alone goes in`() {
        val paid = submitted(5)
        val unpaid = transactionId(node.chainId, byteArrayOf(6)).hex()
        store.keepOutbox(unpaid, OutboxEntry("06", at = app.clock - 300))
        store.pairOutbox(paid, unpaid)
        node.lookups[paid] = includedLookup(100, status = 3)
        node.lookups[unpaid] = includedLookup(101)
        until { it.isEmpty() }
        assertEquals(TxState.Included, store.data.value.submitted.single().state)
        assertEquals(101L, store.data.value.submitted.single().height)
        assertTrue(store.data.value.outbox.isEmpty())
    }

    @Test
    fun `a failure the node refused may still have gone in`() {
        val id = submitted(7, state = TxState.Failed)
        node.lookups[id] = includedLookup(100)
        until { it.isEmpty() }
        assertEquals(TxState.Included, store.data.value.submitted.single().state)
        assertNull(store.data.value.submitted.single().problem)
    }

    @Test
    fun `shows only the active user's, sent to this chain`() {
        val mine = submitted(8)
        submitted(9, userId = "other")
        submitted(10, chain = "ff".repeat(32))
        val before = submitted(11, chain = null)
        store.updateSubmitted(before) { it.copy(chain = null) }
        node.lookups[mine] = pendingLookup()
        node.lookups[before] = pendingLookup()
        val items = until { list -> list.size == 2 && list.all { it.watch == Watch.Waiting } }
        assertEquals(setOf(mine, before), items.map { it.tx.id }.toSet())
    }

    @Test
    fun `retries with the same bytes, and says why the node refused`() = runBlocking {
        val id = submitted(12, state = TxState.Failed)
        assertNull(pending.retry(id))
        assertEquals(TxState.Pending, store.data.value.submitted.single().state)
        assertEquals(listOf(store.data.value.outbox.getValue(id).raw to null), node.posted.toList())
        refuse("nonce too low")
        assertEquals("nonce too low", pending.retry(id))
        val tx = store.data.value.submitted.single()
        assertEquals(TxState.Failed, tx.state)
        assertEquals("the node refused it: nonce too low", tx.problem)
        assertEquals("the app no longer has this transaction", pending.retry("aa".repeat(32)))
    }

    @Test
    fun `puts a submission away`() {
        val id = submitted(13)
        pending.dismiss(id)
        assertEquals(TxState.Dismissed, store.data.value.submitted.single().state)
        assertFalse(id in store.data.value.outbox)
        assertTrue(until { true }.isEmpty())
    }

    @Test
    fun `drops bytes nothing follows after a week`() {
        val followed = submitted(14)
        store.keepOutbox(followed, OutboxEntry("0e", at = app.clock - 8 * 86_400))
        store.keepOutbox("old", OutboxEntry("00", at = app.clock - 8 * 86_400))
        store.keepOutbox("young", OutboxEntry("01", at = app.clock - 86_400))
        node.lookups[followed] = pendingLookup()
        until { it.singleOrNull()?.watch == Watch.Waiting }
        assertEquals(setOf(followed, "young"), store.data.value.outbox.keys)
    }
}
