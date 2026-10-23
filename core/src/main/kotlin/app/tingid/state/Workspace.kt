// What every screen needs at once: the chain, the followed coins, the active
// user with its identity and accounts, and the names this device knows.
package app.tingid.state

import app.tingid.chain.ChainApi
import app.tingid.chain.unhex
import app.tingid.text.Names
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A user's identity on the chain the app is on (null: none, or not on chain), and whether it is still being read. */
class UserIdentity(val identity: IdentityInfo?, val loading: Boolean)

/** The identity of `user`, from the cache and read again with each block; none for a key user. */
fun identityOf(chain: Chain, user: User?): Flow<UserIdentity> {
    if (user == null || user.kind != UserKind.Identity || user.registry == null || user.identityId == null) return flowOf(UserIdentity(null, false))
    return chain.identities(listOf(user)).map { m -> UserIdentity(m[user.id], !m.containsKey(user.id)) }
}

class WorkspaceState(
    /** What the app asks of the chain: the user's nodes, as one. */
    val node: ChainApi,
    val info: ChainInfo?,
    /** Followed coins that exist on this chain, in watchlist order. */
    val coins: List<CoinInfo>,
    val coinById: Map<String, CoinInfo>,
    val coinsLoading: Boolean,
    /** Followed coins the node does not have. */
    val missingCoins: List<String>,
    val user: User?,
    /** The active user is an identity of another chain than the nodes': it has no accounts here. */
    val otherChain: Boolean,
    val keys: List<KeyMeta>,
    val advanced: Boolean,
    val identity: IdentityInfo?,
    val identityLoading: Boolean,
    val accounts: List<UserAccount>,
    /** What the activity scan looks for: every account and the ID of the active user. */
    val scope: ScanScope?,
    val names: Names,
    /** Why no node can be used now, while none can. */
    val nodeError: String?,
)

/** An identity exists only on the chain it was made on: elsewhere it has no accounts to show or use. */
private fun otherChain(user: User?, info: ChainInfo?): Boolean =
    user != null && user.kind == UserKind.Identity && user.chainId != null && info != null && user.chainId != info.chainHex

/** What every screen needs at once, for the whole app. */
class Workspace(private val chain: Chain, coins: CoinData, scope: CoroutineScope) {
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<WorkspaceState> = run {
        val store = chain.store
        val user = store.data.map { it.activeUser }.distinctUntilChanged()
        val identity = combine(user, chain.info) { u, i -> u to otherChain(u, i) }.distinctUntilChanged()
            .flatMapLatest { (u, other) -> identityOf(chain, if (other) null else u) }
        val here = combine(store.data, chain.info, chain.caches.current) { d, i, cache -> Triple(d, i, cache?.chain) }
        combine(here, chain.error, coins.coins(), identity) { (d, info, cacheChain), error, c, id -> build(d, info, cacheChain, error, c, id) }
            .stateIn(scope, SharingStarted.Eagerly, build(store.data.value, chain.info.value, chain.caches.current.value?.chain, chain.error.value, null, null))
    }

    init {
        // An identity saved before it carried its chain gets it once found on chain: it exists there.
        scope.launch {
            state.collect { s ->
                val u = s.user
                val here = s.info?.chainHex
                if (u != null && u.kind == UserKind.Identity && u.chainId == null && s.identity != null && here != null) {
                    chain.store.updateUser(u.id) { it.copy(chainId = here) }
                }
            }
        }
    }

    /** Forgets what was found of the nodes and reads everything again. */
    fun refresh() = chain.refresh()

    /** The state from what it is made of; `coins` and `identity` null while not known. */
    private fun build(d: AppData, info: ChainInfo?, cacheChain: String?, error: String?, coins: Coins?, id: UserIdentity?): WorkspaceState {
        val user = d.activeUser
        val other = otherChain(user, info)
        val identity = id?.identity
        val c = info?.canonical
        val accounts = if (user != null && c != null && !other) userAccounts(user, c, d.keys, identity) else emptyList()
        val scope = if (info != null && user != null && accounts.isNotEmpty()) {
            ScanScope(
                chainId = info.status.chainId,
                registry = info.canonical.registry,
                accounts = accounts.map { it.account },
                ids = if (user.kind == UserKind.Identity && user.identityId != null) listOf(unhex(user.identityId)) else emptyList(),
            )
        } else {
            null
        }
        return WorkspaceState(
            node = chain.node,
            info = info,
            coins = coins?.coins ?: emptyList(),
            coinById = coins?.byId ?: emptyMap(),
            coinsLoading = coins?.loading ?: true,
            missingCoins = coins?.missing ?: emptyList(),
            user = user,
            otherChain = other,
            keys = d.keys,
            advanced = d.settings.advanced,
            identity = identity,
            identityLoading = id?.loading ?: (user?.kind == UserKind.Identity && !other),
            accounts = accounts,
            scope = scope,
            names = names(d.users, d.keys, d.contacts.filter { it.chain == null || it.chain == cacheChain }, c),
            nodeError = error,
        )
    }
}

/** Follows the chain's known coins the first time the app sees a chain, for as long as the job runs. */
fun followKnownCoins(chain: Chain, scope: CoroutineScope): Job = scope.launch {
    combine(chain.info.map { it?.chainHex }, chain.store.data.map { it.settings.coins.size }) { chainHex, watched -> chainHex to watched }
        .distinctUntilChanged()
        .collect { (chainHex, watched) ->
            if (chainHex == null || watched > 0) return@collect
            for (known in knownCoinsFor(chainHex)) chain.store.addCoin(known.contract)
        }
}

fun identitySummary(identity: IdentityInfo?): String {
    if (identity == null) return "not on chain yet"
    val c = identity.core
    return "${c.keyCount} keys · ${c.guardianCount} guardians · ${c.slotCount} accounts"
}
