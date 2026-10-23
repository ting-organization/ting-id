// Cancelling, running and queueing scheduled actions (state/Scheduled): a
// long cancel goes in several, each noted with the fee it pays, and one
// action runs at a time.
package app.tingid.state

import app.tingid.chain.Coin
import app.tingid.chain.Contracts
import app.tingid.chain.FeePayer
import app.tingid.chain.NodeStatus
import app.tingid.chain.Scheme
import app.tingid.chain.filled
import app.tingid.chain.hex
import app.tingid.chain.publicKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger

class ScheduledActionsTest {
    private val node = FakeRestNode(filled(0x27))
    private val app = TestApp(node)
    private val chainId = node.chainId
    private val c = Contracts.canonical(chainId)
    private val secret = filled(1)
    private val key = KeyMeta("k1", "Phone", Scheme.Secp256k1, publicKey(secret).hex(), 0)
    private val user = User("u1", "Bo", "#000", UserKind.Key, keyId = key.id)
    private val dlr = CoinInfo(
        filled(0x44),
        filled(0x44).hex(),
        Coin.Config("Daler", "DLR", 8, Coin.UNCAPPED, BigInteger.ZERO, 0, BigInteger.ZERO, 0),
        Coin.State(BigInteger.ZERO, BigInteger.ZERO, 0, Coin.ZERO_ACCOUNT),
    )
    private val account = userAccounts(user, c, listOf(key), null)[0]
    private val w = WorkspaceState(
        node = app.chain.node,
        info = ChainInfo(NodeStatus(chainId, 10, listOf(1L, 1, 1, 1), "00".repeat(32), BigInteger.ONE, 0, "test"), c),
        coins = listOf(dlr),
        coinById = mapOf(dlr.hex to dlr),
        coinsLoading = false,
        missingCoins = emptyList(),
        user = user,
        otherChain = false,
        keys = listOf(key),
        advanced = false,
        identity = null,
        identityLoading = false,
        accounts = listOf(account),
        scope = null,
        names = emptyMap(),
        nodeError = null,
    )

    init {
        app.secrets.saveKey(key.id, secret)
        app.store.addKey(key)
        app.store.addUser(user)
    }

    private val fee = object : FeeSource {
        override val summary = "Fee 0.0001 DLR from Account"
        override suspend fun resolve(): FeePayer? = null
    }

    private fun entries(n: Int) = (1..n).map {
        ScheduledAction(
            dlr,
            AccountView(account, Coin.EMPTY_ACCOUNT, emptyList()),
            it.toLong(),
            Coin.Scheduled(2_000_000_000, BigInteger.ONE, BigInteger.ZERO, Coin.Effect.Transfer(filled(0x7f), BigInteger.ONE)),
        )
    }

    @Test
    fun `a long cancel goes in several, each noted with its fee, and the last one opens`() = runBlocking {
        val opened = CompletableDeferred<String>()
        val actions = ScheduledActions(app.chain, testScope, { w }, { fee }, { opened.complete(it) }, io = Dispatchers.Default)
        actions.cancel(entries(20), "cancel-all")
        assertEquals("cancel-all", actions.busy.value)
        val last = withTimeout(5_000) { opened.await() }
        until { actions.busy.value == null }
        assertEquals(2, node.posted.size)
        // Newest first: the 4 left after a first cancel of 16.
        val notes = app.store.data.value.submitted
        assertEquals(
            listOf(
                "Cancel 4 scheduled actions from Account (DLR) · Fee 0.0001 DLR from Account",
                "Cancel 16 scheduled actions from Account (DLR) · Fee 0.0001 DLR from Account",
            ),
            notes.map { it.summary },
        )
        assertEquals(last, notes[0].id)
        assertNull(actions.error.value)
    }

    @Test
    fun `one action at a time, and what fails is told`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val failing = object : FeeSource {
            override val summary: String? = null
            override suspend fun resolve(): FeePayer? {
                gate.await()
                throw IllegalStateException("the fee needs more")
            }
        }
        val actions = ScheduledActions(app.chain, testScope, { w }, { failing }, { fail("nothing opens") }, io = Dispatchers.Default)
        val e = entries(1)
        actions.cancel(e, "cancel-${scheduledActionKey(e[0])}")
        // A second button pressed meanwhile does nothing.
        actions.run(e[0])
        assertEquals("cancel-${dlr.hex}-0-1", actions.busy.value)
        gate.complete(Unit)
        until { actions.busy.value == null }
        assertEquals("the fee needs more", actions.error.value)
        assertEquals(0, node.posted.size)
    }

    @Test
    fun `a run goes now, or to the node for when it is due`() = runBlocking {
        val opened = Channel<String>(2)
        val actions = ScheduledActions(app.chain, testScope, { w }, { fee }, { opened.trySend(it) }, io = Dispatchers.Default)
        val e = entries(1)[0]
        actions.run(e)
        val ran = withTimeout(5_000) { opened.receive() }
        until { actions.busy.value == null }
        actions.queue(e)
        val queued = withTimeout(5_000) { opened.receive() }
        assertEquals(listOf(null, 2_000_000_000L), node.posted.map { it.second })
        // The same run either way, so the node merges them.
        assertEquals(ran, queued)
        assertEquals("Run when due: Send 0.00000001 DLR to 7f7f7f7f…7f7f7f", app.store.data.value.submitted[0].summary)
    }
}
