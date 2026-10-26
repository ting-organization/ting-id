// The coins a user follows: loading them, their balances per account, and
// discovering standard coins the user's accounts already hold.
@file:UseSerializers(HexSerializer::class, BigIntegerSerializer::class)

package app.tingid.state

import app.tingid.chain.BigIntegerSerializer
import app.tingid.chain.ByContent
import app.tingid.chain.ChainApi
import app.tingid.chain.Coin
import app.tingid.chain.ContractName
import app.tingid.chain.Contracts
import app.tingid.chain.HexSerializer
import app.tingid.chain.NodeError
import app.tingid.chain.StorageRead
import app.tingid.chain.equal
import app.tingid.chain.hex
import app.tingid.chain.unhex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import java.math.BigInteger

@Serializable
class CoinInfo(val id: ByteArray, val hex: String, val config: Coin.Config, val state: Coin.State) : ByContent() {
    override fun toString() = "CoinInfo(id=${id.hex()}, hex=$hex, config=$config, state=$state)"
}

/**
 * Coins as they stand: each one's state, and its configuration, which never
 * changes and which the nodes must agree on, as amounts are signed by its
 * decimals. Null for a contract that is not an initialized coin.
 */
suspend fun fetchCoins(node: ChainApi, store: Store, coinHexes: List<String>): List<CoinInfo?> {
    if (coinHexes.isEmpty()) return emptyList()
    val ids = coinHexes.map { unhex(it) }
    val (states, configs) = coroutineScope {
        val states = async { node.storageMany(ids.map { StorageRead(it, Coin.STATE_KEY) }) }
        val configs = async { node.storageFixed(ids.map { StorageRead(it, Coin.CONFIG_KEY) }) }
        states.await() to configs.await()
    }
    return coinHexes.mapIndexed { i, h ->
        val bytes = configs[i]
        val config = bytes?.let { Coin.decodeConfig(it) }
        val state = states[i]?.let { Coin.decodeState(it) }
        if (bytes != null && config != null) trustConfig(store, h, bytes, config)
        if (config != null && state != null) CoinInfo(ids[i], h, config, state) else null
    }
}

/**
 * Refuses a configuration the app cannot believe: a listed coin's must give
 * the decimals and symbol it lists, as amounts are signed by its decimals,
 * and any coin's must be the one first read here, as it never changes.
 */
private fun trustConfig(store: Store, coinHex: String, bytes: ByteArray, config: Coin.Config) {
    val known = knownCoin(coinHex)
    if (known != null && ((known.decimals != null && known.decimals != config.decimals) || (known.symbol != null && known.symbol != config.symbol))) {
        throw NodeError("your nodes give ${known.name} other decimals or another symbol than it has: Ting ID does not use what they say")
    }
    val first = store.data.value.coinConfigs[coinHex]
    if (first != null && first != bytes.hex()) {
        throw NodeError("your nodes give ${config.name.ifEmpty { "a coin" }} another configuration than they did before: Ting ID does not use it")
    }
    if (first == null) store.pinCoinConfig(coinHex, bytes.hex())
}

suspend fun fetchCoin(node: ChainApi, store: Store, coinHex: String): CoinInfo? = fetchCoins(node, store, listOf(coinHex))[0]

/** Whether a contract runs the standard coin code and is initialized. */
suspend fun isStandardCoin(node: ChainApi, coinHex: String): Boolean =
    node.contract(unhex(coinHex))?.codeHash == Contracts.codeHash(ContractName.Coin)

class Coins(
    /** Followed coins that exist on this chain, in watchlist order. */
    val coins: List<CoinInfo>,
    val byId: Map<String, CoinInfo>,
    /** Followed coins the node does not have. */
    val missing: List<String>,
    val loading: Boolean,
)

class CoinInfos(val infos: Map<String, CoinInfo>, val loading: Boolean)

class AccountView(val account: UserAccount, val record: Coin.AccountRecord, val scheduled: List<ScheduledItem>)

/** What the chain holds of one account in one coin. */
@Serializable
internal class AccountState(val record: Coin.AccountRecord, val scheduled: List<ScheduledItem>)

/** Each coin's accounts (by coin hex), and whether some are not known yet. */
class AccountViews(val views: Map<String, List<AccountView>>, val loading: Boolean)

fun totalAvailable(views: List<AccountView>?): BigInteger = (views ?: emptyList()).fold(BigInteger.ZERO) { sum, v -> sum + v.record.available }

fun totalLocked(views: List<AccountView>?): BigInteger = (views ?: emptyList()).fold(BigInteger.ZERO) { sum, v -> sum + v.record.locked }

/** Whether an account has anything of a coin: a balance, a reserve or a scheduled action. */
fun holdsAny(view: AccountView): Boolean =
    view.record.available.signum() > 0 || view.record.locked.signum() > 0 || view.scheduled.isNotEmpty()

/** A followed coin with some of the user's accounts in it, and the total available across all of them. */
class CoinGroup(val coin: CoinInfo, val views: List<AccountView>, val total: BigInteger)

/**
 * The accounts coin by coin. The wallet lists under each coin the accounts
 * that hold some of it, or the first account (Spending) when none does, so a
 * followed coin always has one to open; `every` lists them all, to choose one
 * to send from. Empty while the balances load.
 */
fun coinGroups(coins: List<CoinInfo>, views: Map<String, List<AccountView>>, every: Boolean = false): List<CoinGroup> = coins.map { c ->
    val all = views[c.hex] ?: emptyList()
    val held = all.filter(::holdsAny)
    CoinGroup(c, if (every) all else if (held.isNotEmpty()) held else all.take(1), totalAvailable(all))
}

@Serializable
class Discovered(
    val info: CoinInfo,
    /** Available balance over the user's accounts. */
    val total: BigInteger,
)

private val DISCOVERED: KSerializer<List<Discovered>> = ListSerializer(Discovered.serializer())

fun sameCoin(a: CoinInfo?, b: CoinInfo?): Boolean = a != null && b != null && equal(a.id, b.id)

/**
 * The coins of the chain the app is on, from the cache and read again with
 * each block: those the user follows, any asked for, the user's balances in
 * them, and those the user's accounts hold without following them. `node` is
 * what reads go to: the user's nodes.
 */
class CoinData(
    private val chain: Chain,
    private val activities: Activities,
    /** Where reads run. */
    private val work: CoroutineScope,
    private val node: () -> ChainApi = { chain.node },
) {
    private val store: Store get() = chain.store

    private fun coinKey(coinHex: String) = "coin:$coinHex"

    /** Coins from the cache, read again with each block: null for one that is not a coin on this chain. */
    private fun coinRecords(coinHexes: List<String>): Flow<CachedValues<CoinInfo?>> =
        chain.watch(coinHexes.map(::coinKey), CoinInfo.serializer().nullable) { stale ->
            fetchCoins(node(), store, stale.map { it.removePrefix("coin:") })
        }

    /** The coins the user follows. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun coins(): Flow<Coins> = store.data.map { it.settings.coins }.distinctUntilChanged().flatMapLatest { watched ->
        coinRecords(watched).map { v ->
            val coins = ArrayList<CoinInfo>()
            val missing = ArrayList<String>()
            for (h in watched) {
                if (!v.values.containsKey(coinKey(h))) continue
                val c = v.values[coinKey(h)]
                if (c != null) coins.add(c) else missing.add(h)
            }
            Coins(coins, coins.associateBy { it.hex }, missing, v.missing)
        }
    }

    /** The coins `ids` from the cache, read again with each block; ones not on this chain are left out. */
    fun coinInfos(ids: List<String>): Flow<CoinInfos> = coinRecords(ids).map { v ->
        val infos = LinkedHashMap<String, CoinInfo>()
        for (id in ids) v.values[coinKey(id)]?.let { infos[id] = it }
        CoinInfos(infos, v.missing)
    }

    /**
     * Balances and scheduled actions of every account in every coin given, from
     * the cache, read again with each block. `loading`: some are not known yet.
     */
    fun accountViews(coins: List<CoinInfo>, accounts: List<UserAccount>): Flow<AccountViews> {
        class Wanted(val c: CoinInfo, val account: UserAccount, val key: String)
        val pairs = coins.flatMap { c -> accounts.map { a -> Wanted(c, a, "view:${c.hex}:${a.account.hex()}") } }
        return chain.watch(pairs.map { it.key }, AccountState.serializer()) { stale ->
            // Two reads in all: every balance, then every scheduled action below each nonce.
            val n = node()
            val want = stale.map { k -> pairs.first { it.key == k } }
            val records = fetchAccounts(n, want.map { it.c.id to it.account.account })
            val scheduled = fetchScheduledMany(
                n,
                want.mapIndexed { i, p ->
                    ScheduledQuery(p.c.id, p.account.account, records[i].nonce, records[i].locked, everything = equal(p.c.state.minter, p.account.account))
                },
            )
            want.indices.map { i -> AccountState(records[i], scheduled[i]) }
        }.map { v ->
            val out = LinkedHashMap<String, MutableList<AccountView>>()
            for (c in coins) out[c.hex] = ArrayList()
            for (p in pairs) v.values[p.key]?.let { out.getValue(p.c.hex).add(AccountView(p.account, it.record, it.scheduled)) }
            AccountViews(out, v.missing)
        }
    }

    /**
     * Standard coins the user's accounts hold without following them: contracts
     * whose events in the blocks the activity scan of `scope` has read touch one
     * of the accounts, and whose code is the standard coin. The scan is kept up
     * to the chain's height while collected.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun discovered(scope: ScanScope?, accounts: List<UserAccount>, excluded: List<String>): Flow<List<Discovered>> =
        activities.scan(scope).map { s ->
            (s?.activity ?: emptyList()).flatMap { item ->
                // A transfer a call took in, or a fee, is part of another item.
                (if (item.coin != null) listOf(item.contract) else emptyList()) + (item.paid + item.fees).map { it.contract }
            }.map { it.hex() }.distinct().filter { it !in excluded }.sorted()
        }.distinctUntilChanged().flatMapLatest { candidates ->
            val accountsKey = accounts.joinToString(",") { it.account.hex() }
            val key = if (accounts.isNotEmpty() && candidates.isNotEmpty()) "found:$accountsKey" else null
            if (key == null) {
                flowOf(emptyList())
            } else {
                // The candidates are part of the version: other candidates are read again.
                watchAt(listOf(key), DISCOVERED, { tip -> "$tip|${candidates.joinToString(",")}" }) {
                    val n = node()
                    val standard = candidates.filter { isStandardCoin(n, it) }
                    val infos = fetchCoins(n, store, standard).filterNotNull()
                    val records = fetchAccounts(n, infos.flatMap { info -> accounts.map { a -> info.id to a.account } })
                    listOf(
                        infos.mapIndexed { i, info ->
                            Discovered(info, records.subList(i * accounts.size, (i + 1) * accounts.size).fold(BigInteger.ZERO) { sum, r -> sum + r.available })
                        },
                    )
                }.map { v ->
                    // Until the read for these candidates is in, the last one stands, without coins followed or put away since.
                    (v.values[key] ?: emptyList()).filter { it.info.hex in candidates }
                }
            }
        }

    /**
     * As [Chain.watch], with the version each key is read at made by `version`
     * from the chain's tip: what else the read depends on goes into it, so a
     * value read for other inputs is read again, and shown until then.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> watchAt(keys: List<String>, serializer: KSerializer<T>, version: (tip: String) -> String, read: suspend (stale: List<String>) -> List<T>): Flow<CachedValues<T>> =
        chain.caches.current.flatMapLatest { cache ->
            if (cache == null) return@flatMapLatest flowOf(CachedValues(emptyMap(), keys.isNotEmpty()))
            combine(chain.info, cache.epoch, cache.revision) { i, _, _ -> i?.status?.tip?.let(version) }.map { at ->
                if (at != null) {
                    val stale = keys.filter { cache.stale(it, at) }
                    if (stale.isNotEmpty()) {
                        cache.begin(stale, at)
                        work.launch {
                            try {
                                val values = read(stale)
                                stale.forEachIndexed { k, key -> cache.put(key, values[k], serializer, at) }
                            } catch (e: Exception) {
                                for (key in stale) cache.fail(key, at)
                            }
                        }
                    }
                }
                val values = LinkedHashMap<String, T>()
                var missing = false
                for (key in keys) {
                    val c = cache.get(key, serializer)
                    if (c != null) values[key] = c.value else missing = true
                }
                CachedValues(values, missing)
            }
        }.distinctUntilChanged { a, b -> a.missing == b.missing && a.values.size == b.values.size && a.values.all { (k, v) -> b.values[k] === v } }
}
