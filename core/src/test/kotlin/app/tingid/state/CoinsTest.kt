// The coins a user follows: what is believed of a coin's configuration, how
// accounts group under coins, and the coins, balances, discoveries and
// workspace that screens read from the cache.
package app.tingid.state

import app.tingid.chain.Coin
import app.tingid.chain.ContractName
import app.tingid.chain.Contracts
import app.tingid.chain.NodeError
import app.tingid.chain.NodeStatus
import app.tingid.chain.ReceiptEvent
import app.tingid.chain.Scheme
import app.tingid.chain.Writer
import app.tingid.chain.filled
import app.tingid.chain.hex
import app.tingid.chain.unhex
import app.tingid.chain.utf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class CoinsTest {
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gold = filled(0x60)
    private val me = filled(0x11)
    private val savings = filled(0x12)
    private fun big(n: Long): BigInteger = BigInteger.valueOf(n)

    private fun config(name: String, symbol: String, decimals: Int) = Writer().u8(1).bytes(utf8(name)).bytes(utf8(symbol)).u8(decimals)
        .u128(Coin.UNCAPPED).u128(BigInteger.ZERO).u64(0).u128(BigInteger.ZERO).u64(3).finish()
    private fun state(minter: ByteArray = Coin.ZERO_ACCOUNT) = Writer().u128(big(100)).u128(BigInteger.ZERO).u64(0).put(minter).finish()
    private fun account(balance: Long, locked: Long = 0, nonce: Long = 0) = Writer().u128(big(balance)).u128(big(locked)).u64(nonce).finish()

    /** A node with the coin Gold (GLD, 2 decimals), in which `me` holds 7 and `savings` 5. */
    private fun node(coin: ByteArray = gold) = FakeNode().apply {
        record(coin, Coin.CONFIG_KEY, config("Gold", "GLD", 2))
        record(coin, Coin.STATE_KEY, state())
        record(coin, me, account(7))
        record(coin, savings, account(5))
    }

    private fun store() = Store(MemoryStorage(), listOf("https://node.test"), scope = work).also { it.load() }

    private suspend fun <T> Flow<T>.firstThat(check: (T) -> Boolean): T = withTimeout(5_000) { first(check) }

    @Test
    fun `reads coins as they stand, and believes the configuration first read`() = runBlocking {
        val node = node()
        val store = store()
        val other = filled(0x61).hex()
        val (coin, none) = fetchCoins(node, store, listOf(gold.hex(), other))
        assertEquals(listOf("Gold", "GLD", "2"), coin!!.config.let { listOf(it.name, it.symbol, it.decimals.toString()) })
        assertNull(none)
        assertEquals(config("Gold", "GLD", 2).hex(), store.data.value.coinConfigs[gold.hex()])
        // A node giving another configuration later is not believed.
        node.record(gold, Coin.CONFIG_KEY, config("Gold", "GLD", 8))
        val e = runCatching { fetchCoin(node, store, gold.hex()) }.exceptionOrNull()
        assertTrue(e is NodeError)
        assertEquals("your nodes give Gold another configuration than they did before: Ting ID does not use it", e!!.message)
    }

    @Test
    fun `refuses a listed coin with other decimals than it has`() = runBlocking {
        val daler = unhex("305378d17f6af84a563d0f8a8455084948aff7330b62b4f2f98a44b82ba113c4")
        val node = FakeNode().apply {
            record(daler, Coin.CONFIG_KEY, config("Daler", "DLR", 6))
            record(daler, Coin.STATE_KEY, state())
        }
        val e = runCatching { fetchCoin(node, store(), daler.hex()) }.exceptionOrNull()
        assertEquals("your nodes give Daler other decimals or another symbol than it has: Ting ID does not use what they say", e!!.message)
    }

    @Test
    fun `lists under each coin the accounts that hold some, or the first`() {
        val coinA = CoinInfo(gold, gold.hex(), Coin.decodeConfig(config("Gold", "GLD", 2))!!, Coin.decodeState(state())!!)
        val coinB = CoinInfo(filled(0x61), filled(0x61).hex(), Coin.decodeConfig(config("Tin", "TIN", 2))!!, Coin.decodeState(state())!!)
        val spending = UserAccount(0, "Spending", me, 0)
        val saving = UserAccount(1, "Savings", savings, 604_800)
        fun view(a: UserAccount, balance: Long) = AccountView(a, Coin.decodeAccount(account(balance)), emptyList())
        val views = mapOf(coinA.hex to listOf(view(spending, 0), view(saving, 5)), coinB.hex to listOf(view(spending, 0), view(saving, 0)))
        val groups = coinGroups(listOf(coinA, coinB), views)
        assertEquals(listOf(listOf("Savings"), listOf("Spending")), groups.map { g -> g.views.map { it.account.label } })
        assertEquals(listOf(big(5), BigInteger.ZERO), groups.map { it.total })
        assertEquals(listOf(2, 2), coinGroups(listOf(coinA, coinB), views, every = true).map { it.views.size })
        assertEquals(big(5), totalAvailable(views[coinA.hex]))
        assertEquals(BigInteger.ZERO, totalLocked(views[coinA.hex]))
    }

    /** The app on chain `chainHex` at block 10, reading from `node`. */
    private inner class App(val node: FakeNode, chainHex: String = "aa".repeat(32)) {
        val store = store()
        val caches = Caches(MemoryStorage(), work)
        val chain = Chain(store, caches, KeyRing { error("no keys here") }, work)
        val heights = MutableStateFlow<Long?>(10)
        val activities = Activities({ node }, heights, MemoryStorage(), work)
        val coins = CoinData(chain, activities, work) { node }

        init {
            node.height = 10
            caches.select(chainHex).put(Chain.STATUS_KEY, NodeStatus(unhex(chainHex), 10, listOf(1, 1, 1, 1), "t10", BigInteger.ONE, 0, "test"), NodeStatus.serializer(), "")
        }
    }

    @Test
    fun `gives the coins followed, the balances in them, and those held without following them`() = runBlocking {
        val app = App(node())
        val missing = filled(0x61).hex()
        app.store.addCoin(gold.hex())
        app.store.addCoin(missing)
        val coins = app.coins.coins().firstThat { !it.loading }
        assertEquals(listOf(gold.hex()), coins.coins.map { it.hex })
        assertEquals(listOf(missing), coins.missing)
        assertNotNull(coins.byId[gold.hex()])
        val accounts = listOf(UserAccount(0, "Spending", me, 0), UserAccount(1, "Savings", savings, 0))
        val views = app.coins.accountViews(coins.coins, accounts).firstThat { !it.loading }
        assertEquals(listOf(big(7), big(5)), views.views[gold.hex()]!!.map { it.record.balance })
        assertEquals(listOf(gold.hex()), app.coins.coinInfos(listOf(gold.hex(), missing)).firstThat { !it.loading }.infos.keys.toList())

        // A coin of the standard code that paid one of the accounts is found in the activity.
        val tin = filled(0x62)
        app.node.code[tin.hex()] = Contracts.codeHash(ContractName.Coin)
        app.node.record(tin, Coin.CONFIG_KEY, config("Tin", "TIN", 0))
        app.node.record(tin, Coin.STATE_KEY, state())
        app.node.record(tin, savings, account(9))
        app.node.events[4] = listOf(ReceiptEvent(tin, Writer().put(Coin.TRANSFER_TOPIC).put(filled(9)).put(savings).u128(big(9)).finish(), "t4"))
        val scope = ScanScope(unhex("aa".repeat(32)), filled(0xee), accounts.map { it.account }, emptyList())
        val found = app.coins.discovered(scope, accounts, excluded = listOf(gold.hex())).firstThat { it.isNotEmpty() }
        assertEquals(listOf("Tin"), found.map { it.info.config.name })
        assertEquals(big(9), found[0].total)
        // Once followed, it is no longer found.
        assertEquals(emptyList<Discovered>(), app.coins.discovered(scope, accounts, excluded = listOf(gold.hex(), tin.hex())).first())
    }

    @Test
    fun `gives every screen the active user, its accounts and names, and follows the chain's listed coins`() = runBlocking {
        val dev = "8578787146be1ef0aacc797754001290272915e89cd9e5784fe1368e4c2131bc"
        val app = App(node(), dev)
        app.store.addKey(KeyMeta("k1", "Phone", Scheme.Secp256k1, "04" + "ab".repeat(64), 0))
        app.store.addUser(User("u1", "Anna", "#000", UserKind.Key, keyId = "k1"))
        val workspace = Workspace(app.chain, app.coins, work)
        val following = followKnownCoins(app.chain, work)
        val s = workspace.state.firstThat { it.accounts.isNotEmpty() && !it.coinsLoading }
        assertEquals("Anna", s.user!!.name)
        assertEquals("Anna", s.names[s.accounts[0].account.hex()])
        assertEquals(listOf(s.accounts[0].account.hex()), s.scope!!.accounts.map { it.hex() })
        until { app.store.data.value.settings.coins == listOf(knownCoinsFor(dev)[0].contract) }
        following.cancel()
        assertEquals("not on chain yet", identitySummary(null))
    }
}
